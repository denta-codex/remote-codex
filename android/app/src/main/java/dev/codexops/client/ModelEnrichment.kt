package dev.codexops.client

import dev.codexops.core.*
import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

data class ModelPriceRate(
    val tier: String,
    val context: String,
    val inputPerMillion: Double,
    val outputPerMillion: Double,
    val cachedInputPerMillion: Double?,
    val cacheWritePerMillion: Double?,
)

data class ModelPricing(
    val currency: String,
    val sourceUrl: String,
    val fetchedAt: String,
    val stale: Boolean,
    val rates: List<ModelPriceRate>,
)

data class ModelEnrichment(
    val provider: String,
    val model: String,
    val aliases: List<String>,
    val revision: String,
    val pricing: ModelPricing?,
    val cached: Boolean = false,
)

internal data class EnrichedCatalog(val revision: String, val models: List<ModelEnrichment>) {
    fun enrich(runtime: List<ServerModelOption>, provider: String, cached: Boolean) = runtime.map { option ->
        val matches = models.filter { it.provider == provider && (it.model == option.id || option.id in it.aliases) }
        option.copy(enrichment = matches.singleOrNull()?.let {
            it.copy(cached = cached, pricing = it.pricing?.let { price -> price.copy(stale = price.stale || cached) })
        })
    }
}

internal fun parseEnrichedCatalog(bytes: ByteArray): EnrichedCatalog {
    require(bytes.size <= 8 * 1024 * 1024)
    val metadata = wire.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject.map("remote_codex")
    require((metadata["schema_version"] as? JsonPrimitive)?.intOrNull == 1)
    val revision = metadata.str("revision").also { require(it.matches(Regex("[a-f0-9]{64}"))) }
    val seen = mutableSetOf<Pair<String, String>>()
    val identities = mutableSetOf<Pair<String, String>>()
    val records = metadata["models"] as? JsonArray ?: error("Missing enrichment models")
    val models = records.map { value ->
        val row = value.jsonObject
        val provider = row.str("provider").also { require(it.isNotBlank()) }
        val model = row.str("model").also { require(it.isNotBlank()) }
        require(seen.add(provider to model))
        require(row["aliases"] == null || row["aliases"] is JsonArray)
        val aliases = (row["aliases"] as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList()
        require(aliases.all { it.isNotBlank() } && aliases.distinct().size == aliases.size)
        (listOf(model) + aliases).forEach { require(identities.add(provider to it)) }
        require(row["pricing"] == null || row["pricing"] == JsonNull || row["pricing"] is JsonObject)
        val price = (row["pricing"] as? JsonObject)?.let { p ->
            require(p.str("currency") == "USD")
            require(p.str("status") in setOf("fresh", "stale"))
            require(p.str("source_url").isNotBlank())
            Instant.parse(p.str("fetched_at"))
            val rateKeys = mutableSetOf<Pair<String, String>>()
            val rates = (p["rates"] as? JsonArray ?: error("Missing model rates")).map { value ->
                val r = value.jsonObject
                fun rate(name: String, optional: Boolean = false): Double? {
                    if (optional && (r[name] == null || r[name] == JsonNull)) return null
                    require((r[name] as? JsonPrimitive)?.isString == false)
                    return (r[name] as? JsonPrimitive)?.doubleOrNull.also {
                        require(it != null && it.isFinite() && it >= 0)
                    }
                }
                val tier = r.str("tier").also { require(it.isNotBlank()) }
                require(r.str("model").let { it.isBlank() || it == model })
                val context = r.str("context").also { require(it in setOf("short", "long")) }
                require(rateKeys.add(tier to context))
                ModelPriceRate(tier, context, rate("input_per_million")!!, rate("output_per_million")!!,
                    rate("cached_input_per_million", true), rate("cache_write_per_million", true))
            }
            require(rates.isNotEmpty())
            ModelPricing("USD", p.str("source_url"), p.str("fetched_at"), p.str("status") == "stale", rates)
        }
        ModelEnrichment(provider, model, aliases, revision, price)
    }
    return EnrichedCatalog(revision, models)
}

internal class ModelEnrichmentRepository(private val rpc: RemoteSession, private val store: ClientStore) {
    private val cacheWrites = Mutex()
    internal var catalogModels: List<ModelEnrichment> = emptyList()
        private set
    // Enrich models in the client deliberately. Stock Codex drops custom
    // catalog fields before returning model/list. Enriching that response
    // on the server would require the forwarder to parse and rewrite RPC.
    // Read the canonical catalog through stock fs/readFile instead, keeping
    // the forwarder a transparent transport.
    suspend fun load(host: String, runtime: List<ServerModelOption>, current: () -> Boolean): List<ServerModelOption> {
        val generation = rpc.generation
        fun valid() = current() && rpc.generation == generation
        val config = try {
            withTimeout(5_000) { rpc.call("config/read", obj("includeLayers" to JsonPrimitive(false))).map("config") }
        } catch (e: Exception) {
            if (e is CancellationException && e !is TimeoutCancellationException) throw e
            if (valid()) catalogModels = emptyList()
            return runtime
        }
        if (!valid()) return runtime
        val path = config.str("model_catalog_json")
        if (!path.startsWith('/') || path.contains('\u0000')) {
            catalogModels = emptyList()
            return runtime
        }
        val provider = config.str("model_provider").ifBlank { "openai" }
        val scope = "models/enrichment/" + MessageDigest.getInstance("SHA-256")
            .digest("$host\u0000$path".toByteArray()).joinToString("") { "%02x".format(it) }
        val cached = try {
            val revision = store.get("$scope/current")
            revision.takeIf { it.matches(Regex("[a-f0-9]{64}")) }?.let {
                parseEnrichedCatalog(store.get("$scope/$it").toByteArray()).takeIf { catalog -> catalog.revision == it }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }
        val bytes: ByteArray
        val catalog = try {
            bytes = withTimeout(5_000) { rpc.readFile(path) }
            parseEnrichedCatalog(bytes)
        } catch (e: Exception) {
            if (e is CancellationException && e !is TimeoutCancellationException) throw e
            if (valid()) catalogModels = cached?.models?.map {
                it.copy(cached = true, pricing = it.pricing?.copy(stale = true))
            } ?: emptyList()
            return if (valid()) cached?.enrich(runtime, provider, true) ?: runtime else runtime
        }
        if (!valid()) return runtime
        try {
            cacheWrites.withLock {
                if (valid()) {
                    store.put("$scope/${catalog.revision}", bytes.toString(Charsets.UTF_8))
                    if (valid()) {
                        val previousRevision = store.get("$scope/current")
                        store.put("$scope/current", catalog.revision)
                        if (previousRevision.matches(Regex("[a-f0-9]{64}")) && previousRevision != catalog.revision)
                            store.remove("$scope/$previousRevision")
                    } else {
                        store.remove("$scope/${catalog.revision}")
                    }
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // Cache failure must not affect the server-owned model picker.
        }
        if (valid()) catalogModels = catalog.models
        return if (valid()) catalog.enrich(runtime, provider, false) else runtime
    }
}
