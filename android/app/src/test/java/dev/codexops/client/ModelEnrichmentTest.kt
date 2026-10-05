package dev.codexops.client

import dev.codexops.core.*
import java.util.Base64
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ModelEnrichmentTest {
    private val runtime = parseModelCatalog(obj("data" to JsonArray(listOf(
        obj("model" to s("sample"), "isDefault" to JsonPrimitive(true)),
        obj("model" to s("unpriced")),
    ))))

    private fun document(provider: String = "openai", id: String = "sample", input: Double = 4.0,
        aliases: List<String> = emptyList(), revision: Char = 'a', status: String = "fresh") =
        obj("models" to JsonArray(emptyList()), "remote_codex" to obj(
            "schema_version" to JsonPrimitive(1), "revision" to s(revision.toString().repeat(64)),
            "models" to JsonArray(listOf(obj("provider" to s(provider), "model" to s(id),
                "aliases" to JsonArray(aliases.map(::s)), "pricing" to obj(
                    "currency" to s("USD"), "source_url" to s("https://models.dev/api.json"),
                    "fetched_at" to s("2026-10-05T00:00:00Z"), "status" to s(status),
                    "rates" to JsonArray(listOf(obj("tier" to s("standard"), "context" to s("short"),
                        "input_per_million" to JsonPrimitive(input), "output_per_million" to JsonPrimitive(0),
                        "cached_input_per_million" to JsonPrimitive(0))))))))))

    private class Store : ClientStore {
        val records = mutableMapOf<String, String>()
        var rejectWrites = false
        override suspend fun get(id: String) = records[id].orEmpty()
        override suspend fun put(id: String, value: String) { check(!rejectWrites); records[id] = value }
        override suspend fun remove(id: String) { records.remove(id) }
        override suspend fun token() = ""
        override suspend fun saveToken(value: String) {}
    }
    private class Session(var document: JsonObject) : RemoteSession {
        override val events = Channel<JsonObject>(Channel.UNLIMITED)
        override var generation = 1L
        var path = "/catalog/models.json"
        var provider = "openai"
        var unavailable = false
        var gate: CompletableDeferred<Unit>? = null
        val calls = mutableListOf<String>()
        override suspend fun connect(url: String, token: String) = obj()
        override suspend fun call(method: String, params: JsonObject): JsonObject {
            calls += method
            return when (method) {
                "config/read" -> obj("config" to obj("model_catalog_json" to s(path), "model_provider" to s(provider)))
                "fs/readFile" -> {
                    assertEquals(path, params.str("path"))
                    val captured = document
                    gate?.await()
                    check(!unavailable)
                    obj("dataBase64" to s(Base64.getEncoder().encodeToString(captured.toString().toByteArray())))
                }
                else -> error("Unexpected RPC $method")
            }
        }
        override fun respond(id: JsonElement, result: JsonObject, epoch: Long) {}
        override fun close() {}
        override fun dispose() {}
    }

    @Test fun enrichmentPreservesRuntimeInventoryAndExplicitZeroRates() = runBlocking {
        val session = Session(document(input = 0.0))
        val got = ModelEnrichmentRepository(session, Store()).load("host", runtime) { true }
        assertEquals(runtime, got.map { it.copy(enrichment = null) })
        assertEquals(0.0, got[0].enrichment!!.pricing!!.rates[0].inputPerMillion, 0.0)
        assertNull(got[1].enrichment)
        assertEquals(listOf("config/read", "fs/readFile"), session.calls)
    }

    @Test fun aliasesAreExplicitAndProviderScoped() {
        val catalog = parseEnrichedCatalog(document("modal", "provider-id", aliases = listOf("sample")).toString().toByteArray())
        assertNull(catalog.enrich(runtime, "openai", false)[0].enrichment)
        assertEquals("provider-id", catalog.enrich(runtime, "modal", false)[0].enrichment!!.model)
        val noAlias = parseEnrichedCatalog(document("modal", "sample-latest").toString().toByteArray())
        assertNull(noAlias.enrich(runtime, "modal", false)[0].enrichment)
    }

    @Test fun staleCacheSurvivesRecreationButNeverCrossesHostsOrPaths() = runBlocking {
        val session = Session(document())
        val store = Store()
        ModelEnrichmentRepository(session, store).load("host-a", runtime) { true }
        session.unavailable = true
        val restored = ModelEnrichmentRepository(session, store).load("host-a", runtime) { true }
        assertTrue(restored[0].enrichment!!.cached)
        assertTrue(restored[0].enrichment!!.pricing!!.stale)
        assertNull(ModelEnrichmentRepository(session, store).load("host-b", runtime) { true }[0].enrichment)
        session.path = "/different/models.json"
        assertNull(ModelEnrichmentRepository(session, store).load("host-a", runtime) { true }[0].enrichment)
    }

    @Test fun invalidFreshDocumentRetainsLastValidatedRevisionAndCacheIsBounded() = runBlocking {
        val session = Session(document())
        val store = Store()
        val repository = ModelEnrichmentRepository(session, store)
        repository.load("host", runtime) { true }
        session.document = document(input = -1.0, revision = 'b')
        assertEquals("a".repeat(64), repository.load("host", runtime) { true }[0].enrichment!!.revision)
        session.document = document(revision = 'b')
        assertEquals("b".repeat(64), repository.load("host", runtime) { true }[0].enrichment!!.revision)
        assertEquals(2, store.records.size)
    }

    @Test fun oldRefreshCannotOverwriteNewRevisionOrCrossConnectionGeneration() = runBlocking {
        val session = Session(document())
        val store = Store()
        val repository = ModelEnrichmentRepository(session, store)
        val gate = CompletableDeferred<Unit>()
        session.gate = gate
        var selected = 1
        val old = async(start = CoroutineStart.UNDISPATCHED) { repository.load("host", runtime) { selected == 1 } }
        selected = 2
        session.gate = null
        session.document = document(revision = 'b')
        assertEquals("b".repeat(64), repository.load("host", runtime) { selected == 2 }[0].enrichment!!.revision)
        gate.complete(Unit)
        assertNull(old.await()[0].enrichment)
        session.gate = CompletableDeferred()
        val disconnected = async(start = CoroutineStart.UNDISPATCHED) { repository.load("host", runtime) { true } }
        session.generation++
        session.gate!!.complete(Unit)
        assertNull(disconnected.await()[0].enrichment)
        assertTrue(store.records.values.any { it == "b".repeat(64) })
    }

    @Test fun missingEnrichmentAndCacheWriteFailureDoNotBreakStockModels() = runBlocking {
        val session = Session(obj("models" to JsonArray(emptyList())))
        val store = Store()
        val repository = ModelEnrichmentRepository(session, store)
        assertEquals(runtime, repository.load("host", runtime) { true })
        session.document = document(status = "stale")
        store.rejectWrites = true
        assertTrue(repository.load("host", runtime) { true }[0].enrichment!!.pricing!!.stale)
        session.path = ""
        assertEquals(runtime, repository.load("host", runtime) { true })
    }

    @Test fun unsupportedSchemaAndInvalidRatesAreRejected() {
        val bad = listOf(
            document(input = -0.1).toString(),
            document().toString().replace("\"USD\"", "\"EUR\""),
            document().toString().replace("\"schema_version\":1", "\"schema_version\":2"),
            document().toString().replace("2026-10-05T00:00:00Z", "invalid"),
            document().toString().replace("\"input_per_million\":4.0", "\"input_per_million\":\"4.0\""),
            document(aliases = listOf("sample")).toString(),
            document().toString().replace("\"pricing\":{", "\"pricing\":{\"invalid\":null,").replace("\"rates\":[", "\"rates\":[null,"),
        )
        for (value in bad) assertTrue(runCatching { parseEnrichedCatalog(value.toByteArray()) }.isFailure)
    }
}
