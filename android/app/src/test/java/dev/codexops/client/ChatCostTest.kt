package dev.codexops.client

import org.junit.Assert.*
import org.junit.Test

class ChatCostTest {
    private fun counts(input: Long = 1_000, cached: Long = 200, writes: Long = 100, output: Long = 100) =
        """{"input_tokens":$input,"cached_input_tokens":$cached,"cache_write_input_tokens":$writes,"output_tokens":$output,"reasoning_output_tokens":50,"total_tokens":${input + output}}"""
    private fun event(total: String = counts(), last: String = counts()) =
        """{"type":"event_msg","payload":{"type":"token_count","info":{"total_token_usage":$total,"last_token_usage":$last}}}"""
    private val meta = """{"type":"session_meta","payload":{"id":"chat-a","model_provider":"openai"}}"""
    private fun context(model: String = "model-a", tier: String = "default") =
        """{"type":"turn_context","payload":{"model":"$model","service_tier":"$tier"}}"""
    private fun usage(vararg rows: String) = parseChatUsage((listOf(meta) + rows).joinToString("\n", postfix = "\n").toByteArray(), "chat-a")
    private fun model(id: String = "model-a", tier: String = "standard", price: Double = 4.0,
        aliases: List<String> = emptyList(), stale: Boolean = false, context: String = "short") =
        ModelEnrichment("openai", id, aliases, "a".repeat(64), ModelPricing("USD", "source", "2026-10-05T00:00:00Z", stale,
            listOf(ModelPriceRate(tier, context, price, 20.0, 0.4, 5.0))))

    @Test fun cachedWritesAndReasoningAreCountedOnceAndRepeatedTotalsDeduplicate() {
        val usage = usage(context(), event(), event())
        assertEquals(1, usage.requests.size)
        assertFalse(usage.incomplete)
        val cost = estimateChatCost(usage, listOf(model()))
        assertEquals(0.00538, cost.usd!!, 0.0000001)
        assertEquals("~<\$0.01", cost.label)
    }

    @Test fun historicalModelAndTierChangesKeepTheirOwnRatesAndExplicitAliases() {
        val usage = usage(context(), event(), context("dated-model-b", "priority"),
            event(counts(2_000, 400, 200, 200)))
        val cost = estimateChatCost(usage, listOf(model(), model("model-b", "fast", 8.0, listOf("dated-model-b"))))
        assertEquals(0.01356, cost.usd!!, 0.0000001)
        assertEquals(2, cost.requests)
        assertFalse(cost.partial)
        assertNull(estimateChatCost(usage, listOf(model().copy(provider = "modal"))).usd)
    }

    @Test fun zeroRatesAndUnknownPricesStayDistinctAndStaleProvenanceSurvives() {
        val data = usage(context(), event())
        val zero = model(price = 0.0).let { it.copy(pricing = it.pricing!!.copy(stale = true,
            rates = listOf(ModelPriceRate("standard", "short", 0.0, 0.0, 0.0, 0.0)))) }
        val cost = estimateChatCost(data, listOf(zero))
        assertEquals("~\$0.00", cost.label)
        assertTrue(cost.staleRates)
        assertEquals(listOf("source"), cost.sources)
        val unknown = estimateChatCost(data, emptyList())
        assertNull(unknown.usd)
        assertEquals("\$—", unknown.label)
        assertTrue(unknown.partial)
        assertEquals(1, unknown.unpricedRequests)
    }

    @Test fun malformedCountersGapsAndMissingContextsDoNotFabricateCompleteCosts() {
        val invalid = event().replace("\"cached_input_tokens\":200", "\"cached_input_tokens\":2000")
        val data = usage(context(), invalid, event(counts(3_000, 600, 300, 300)))
        assertTrue(data.incomplete)
        val missing = estimateChatCost(usage(event()), listOf(model()))
        assertNull(missing.usd)
        assertTrue(missing.partial)
        val partlyPriced = estimateChatCost(usage(context(), event(), context("unknown"),
            event(counts(2_000, 400, 200, 200))), listOf(model()))
        assertTrue(partlyPriced.partial)
        assertEquals(0.00538, partlyPriced.usd!!, 0.0000001)
    }

    @Test fun unfinishedAppendIsIgnoredAndChatIdentityIsVerified() {
        val text = listOf(meta, context(), event()).joinToString("\n", postfix = "\n") + "{\"type\":\"token_count"
        assertEquals(1, parseChatUsage(text.toByteArray(), "chat-a").requests.size)
        assertTrue(runCatching { parseChatUsage(text.toByteArray(), "chat-b") }.isFailure)
        assertTrue(runCatching { parseChatUsage("{}\n".toByteArray(), "chat-a") }.isFailure)
    }

    @Test fun longContextUsesPublishedVariantAndMissingOptionalRatesUseInputRate() {
        val data = usage(context(), event(counts(300_000), counts(300_000)))
        val short = model()
        val long = model(price = 8.0, context = "long")
        val metadata = short.copy(pricing = short.pricing!!.copy(rates = short.pricing.rates + long.pricing!!.rates))
        assertEquals(2.40018, estimateChatCost(data, listOf(metadata)).usd!!, 0.0000001)
        val plain = short.copy(pricing = short.pricing.copy(rates = short.pricing.rates.map {
            it.copy(cachedInputPerMillion = null, cacheWritePerMillion = null)
        }))
        assertEquals(0.006, estimateChatCost(usage(context(), event()), listOf(plain)).usd!!, 0.0000001)
    }
}
