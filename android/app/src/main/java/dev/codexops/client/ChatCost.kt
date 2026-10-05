package dev.codexops.client

import dev.codexops.core.*
import java.util.Locale
import kotlinx.serialization.json.*

/** Request usage only: conversation text is neither retained nor cached here. */
internal data class CostTokens(val input: Long, val cached: Long, val writes: Long, val output: Long, val total: Long)
internal data class CostRequest(val provider: String, val model: String, val tier: String, val tokens: CostTokens)
internal data class ChatUsage(val requests: List<CostRequest>, val incomplete: Boolean)

data class ChatCost(
    val usd: Double? = null,
    val partial: Boolean = false,
    val staleRates: Boolean = false,
    val staleUsage: Boolean = false,
    val requests: Int = 0,
    val unpricedRequests: Int = 0,
    val sources: List<String> = emptyList(),
    val fetchedAt: List<String> = emptyList(),
) {
    val label: String get() = usd?.let {
        if (it > 0 && it < 0.01) "≈<\$0.01" else String.format(Locale.US, "≈\$%.2f", it)
    } ?: "\$—"
}

private fun tokens(value: JsonObject): CostTokens? {
    fun count(key: String, optional: Boolean = false): Long? {
        if (optional && value[key] == null) return 0
        val number = value[key] as? JsonPrimitive ?: return null
        if (number.isString) return null
        return number.longOrNull?.takeIf { it >= 0 }
    }
    val input = count("input_tokens") ?: return null
    val cached = count("cached_input_tokens") ?: return null
    val writes = count("cache_write_input_tokens", true) ?: return null
    val output = count("output_tokens") ?: return null
    val total = count("total_tokens") ?: return null
    // Cached reads and writes are subsets of input; reasoning is part of output.
    if (cached > input || writes > input - cached || output > Long.MAX_VALUE - input || total != input + output) return null
    val reasoning = count("reasoning_output_tokens", true) ?: return null
    if (reasoning > output) return null
    return CostTokens(input, cached, writes, output, total)
}

internal fun parseChatUsage(bytes: ByteArray, thread: String): ChatUsage {
    require(bytes.size <= 16 * 1024 * 1024) { "Usage history too large" }
    val requests = mutableListOf<CostRequest>()
    var provider = ""
    var model = ""
    var tier = ""
    var incomplete = false
    var identitySeen = false
    var previous: CostTokens? = null
    // A live append may end halfway through its last event. Wait for its newline.
    val text = bytes.toString(Charsets.UTF_8)
    val committed = text.substringBeforeLast('\n', "")
    for (line in committed.lineSequence()) {
        if (line.isBlank()) continue
        // Decode only accounting envelopes, without constructing message objects.
        if (!line.contains("session_meta") && !line.contains("turn_context") &&
            !line.contains("thread_settings_applied") && !line.contains("token_count")) continue
        val row = try { wire.parseToJsonElement(line).jsonObject }
            catch (_: Exception) { incomplete = true; model = ""; tier = ""; continue }
        val payload = row.map("payload")
        when (row.str("type")) {
            "session_meta" -> {
                val id = payload.str("id").ifBlank { payload.str("session_id") }
                require(id == thread) { "Usage belongs to another chat" }
                identitySeen = true
                provider = payload.str("model_provider")
            }
            "turn_context" -> {
                model = payload.str("model")
                tier = payload.str("service_tier")
                payload.str("model_provider").takeIf { it.isNotBlank() }?.let { provider = it }
            }
            "event_msg" -> when (payload.str("type")) {
                "thread_settings_applied" -> {
                    val settings = payload.map("thread_settings")
                    model = settings.str("model")
                    tier = settings.str("service_tier")
                    settings.str("model_provider").takeIf { it.isNotBlank() }?.let { provider = it }
                }
                "token_count" -> {
                    val info = payload.map("info")
                    if (info.isEmpty()) continue
                    val total = tokens(info.map("total_token_usage"))
                    val last = tokens(info.map("last_token_usage"))
                    if (total == null || last == null) { incomplete = true; continue }
                    if (total == previous) continue
                    val prior = previous
                    previous = total
                    if (last.total == 0L) continue
                    // A delta gap or reset cannot be reconstructed by pricing the
                    // current model against a cumulative total. Keep it partial.
                    if (prior == null) {
                        if (total != last) incomplete = true
                    } else if (total.input - prior.input != last.input || total.cached - prior.cached != last.cached ||
                        total.writes - prior.writes != last.writes || total.output - prior.output != last.output) incomplete = true
                    requests += CostRequest(provider, model, tier, last)
                }
            }
        }
    }
    require(identitySeen) { "Missing chat identity" }
    return ChatUsage(requests, incomplete)
}

internal fun estimateChatCost(usage: ChatUsage, catalog: List<ModelEnrichment>): ChatCost {
    var amount = 0.0
    var priced = 0
    var stale = false
    val sources = linkedSetOf<String>()
    val dates = linkedSetOf<String>()
    for (request in usage.requests) {
        val metadata = catalog.singleOrNull { it.provider == request.provider &&
            (it.model == request.model || request.model in it.aliases) }
        val pricing = metadata?.pricing ?: continue
        val tier = when (request.tier) { "", "default", "auto" -> "standard"; "priority" -> "fast"; else -> request.tier }
        val variants = pricing.rates.filter { it.tier == tier }
        // Mirrors the server's published short/long context pricing convention.
        val context = if (request.tokens.input > 272_000) "long" else "short"
        val rate = variants.singleOrNull { it.context == context }
            ?: variants.singleOrNull { context == "long" && it.context == "short" } ?: continue
        val t = request.tokens
        val estimate = ((t.input - t.cached - t.writes) * rate.inputPerMillion +
            t.cached * (rate.cachedInputPerMillion ?: rate.inputPerMillion) +
            t.writes * (rate.cacheWritePerMillion ?: rate.inputPerMillion) + t.output * rate.outputPerMillion) / 1_000_000
        if (!estimate.isFinite() || !(amount + estimate).isFinite()) continue
        amount += estimate
        priced++
        stale = stale || pricing.stale || metadata.cached
        sources += pricing.sourceUrl
        dates += pricing.fetchedAt
    }
    val unpriced = usage.requests.size - priced
    return ChatCost(amount.takeIf { priced > 0 }, usage.incomplete || unpriced > 0, stale,
        requests = usage.requests.size, unpricedRequests = unpriced, sources = sources.toList(), fetchedAt = dates.toList())
}
