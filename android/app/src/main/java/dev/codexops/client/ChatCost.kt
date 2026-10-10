package dev.codexops.client

import java.util.Locale
import kotlinx.serialization.json.*

/** Accounting only: these buckets never contain conversation text. */
internal data class CostTokens(val input: Long, val cached: Long, val writes: Long, val output: Long, val total: Long)
internal data class CostRequest(val provider: String, val model: String, val tier: String, val tokens: CostTokens,
    val context: String = if (tokens.input > 272_000) "long" else "short", val count: Int = 1)
internal data class ChatUsage(val requests: List<CostRequest>, val incomplete: Boolean)

enum class ChatCostStatus { Calculating, Ready, Unavailable }

data class ChatCost(
    val usd: Double? = null,
    val status: ChatCostStatus = ChatCostStatus.Unavailable,
    val requests: Int = 0,
    val unpricedRequests: Int = 0,
) {
    val label: String get() = usd?.let {
        if (it > 0 && it < 0.01) "~<\$0.01" else String.format(Locale.US, "~\$%.2f", it)
    } ?: "\$—"
}

internal fun costTokens(value: JsonObject): CostTokens? {
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
    if (cached > input || writes > input - cached || output > Long.MAX_VALUE - input || total != input + output) return null
    if ((count("reasoning_output_tokens", true) ?: return null) > output) return null
    return CostTokens(input, cached, writes, output, total)
}

internal fun estimateChatCost(usage: ChatUsage, catalog: List<ModelEnrichment>): ChatCost {
    var amount = 0.0
    var priced = 0
    var requested = 0
    for (request in usage.requests) {
        requested = Math.addExact(requested, request.count)
        val metadata = catalog.singleOrNull { it.provider == request.provider &&
            (it.model == request.model || request.model in it.aliases) }
        val pricing = metadata?.pricing?.takeUnless { it.stale || metadata.cached } ?: continue
        val tier = when (request.tier) { "", "default", "auto" -> "standard"; "priority" -> "fast"; else -> request.tier }
        val variants = pricing.rates.filter { it.tier == tier }
        val rate = variants.singleOrNull { it.context == request.context }
            ?: variants.singleOrNull { request.context == "long" && it.context == "short" } ?: continue
        val t = request.tokens
        val estimate = ((t.input - t.cached - t.writes) * rate.inputPerMillion +
            t.cached * (rate.cachedInputPerMillion ?: rate.inputPerMillion) +
            t.writes * (rate.cacheWritePerMillion ?: rate.inputPerMillion) + t.output * rate.outputPerMillion) / 1_000_000
        if (!estimate.isFinite() || !(amount + estimate).isFinite()) continue
        amount += estimate
        priced = Math.addExact(priced, request.count)
    }
    val unpriced = requested - priced
    val complete = !usage.incomplete && unpriced == 0
    return ChatCost(amount.takeIf { complete }, if (complete) ChatCostStatus.Ready else ChatCostStatus.Unavailable,
        requests = requested, unpricedRequests = unpriced)
}
