package dev.codexops.client

import dev.codexops.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ChatCostTest {
    private val tokens = CostTokens(1_000, 200, 100, 100, 1_100)
    private fun usage(vararg requests: CostRequest) = ChatUsage(requests.toList(), false)
    private fun request(model: String = "model-a", tier: String = "default", provider: String = "openai") =
        CostRequest(provider, model, tier, tokens)
    private fun model(id: String = "model-a", tier: String = "standard", price: Double = 4.0,
        aliases: List<String> = emptyList(), context: String = "short") =
        ModelEnrichment("openai", id, aliases, "a".repeat(64), ModelPricing("USD", "source", "2026-10-05T00:00:00Z", false,
            listOf(ModelPriceRate(tier, context, price, 20.0, 0.4, 5.0))))

    @Test fun cacheWritesAndReasoningAreCountedOnce() {
        val cost = estimateChatCost(usage(request()), listOf(model()))
        assertEquals(0.00538, cost.usd!!, 0.0000001)
        assertEquals("~<\$0.01", cost.label)
        assertEquals(ChatCostStatus.Ready, cost.status)
    }

    @Test fun mixedHistoricalModelsTiersAndProvidersAreNotPricedUsingCurrentSettings() {
        val data = usage(request(), request("dated-model-b", "priority"))
        val catalog = listOf(model(), model("model-b", "fast", 8.0, listOf("dated-model-b")))
        val cost = estimateChatCost(data, catalog)
        assertEquals(0.01356, cost.usd!!, 0.0000001)
        assertEquals(2, cost.requests)
        assertNull(estimateChatCost(usage(request(provider = "other")), catalog).usd)
    }

    @Test fun aggregationRetainsPerRequestContextBandAndRequestCount() {
        val grouped = CostRequest("openai", "model-a", "default", CostTokens(300_000, 60_000, 30_000, 30_000, 330_000), "short", 300)
        val short = model()
        val long = model(price = 100.0, context = "long")
        val catalog = listOf(short.copy(pricing = short.pricing!!.copy(rates = short.pricing.rates + long.pricing!!.rates)))
        val cost = estimateChatCost(usage(grouped), catalog)
        assertEquals(300, cost.requests)
        assertEquals(0.00538 * 300, cost.usd!!, 0.0000001)
        val large = CostRequest("openai", "model-a", "default", CostTokens(300_000, 200, 100, 100, 300_100))
        assertEquals(29.97258, estimateChatCost(usage(large), catalog).usd!!, 0.0000001)
    }

    @Test fun missingIncompleteAndStalePricesShowUnavailableInsteadOfPartialAmounts() {
        val data = usage(request())
        val fresh = model()
        val stale = fresh.copy(pricing = fresh.pricing!!.copy(stale = true))
        val cases = listOf(estimateChatCost(data, emptyList()), estimateChatCost(data, listOf(stale)),
            estimateChatCost(data, listOf(fresh.copy(cached = true))),
            estimateChatCost(data.copy(incomplete = true), listOf(fresh)),
            estimateChatCost(usage(request(), request("unknown")), listOf(fresh)))
        for (cost in cases) {
            assertNull(cost.usd)
            assertEquals(ChatCostStatus.Unavailable, cost.status)
        }
        val zero = fresh.copy(pricing = fresh.pricing!!.copy(rates = listOf(ModelPriceRate("standard", "short", 0.0, 0.0, 0.0, 0.0))))
        assertEquals("~\$0.00", estimateChatCost(data, listOf(zero)).label)
        assertEquals("~\$0.00", estimateChatCost(usage(), emptyList()).label)
    }

    private inner class Fixture : ChatAccounting, AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var epoch = 1L
        var screen = ScreenState(page = "chat", thread = "chat-a", ready = true)
        var calls = 0
        var fail = false
        var gate: CompletableDeferred<Unit>? = null
        var ignoreCancellation = false
        var data = usage(request())
        var catalog = listOf(model())
        val controller = ChatCostController(scope, this, { epoch }, { screen }, { catalog },
            { screen = screen.copy(chatCost = it) })
        override suspend fun snapshot(thread: String, path: String, generation: Long): ChatUsage {
            calls++
            val captured = data
            val waiting = gate
            if (ignoreCancellation) withContext(NonCancellable) { waiting?.await() } else waiting?.await()
            check(!fail)
            return captured
        }
        override fun close() { scope.cancel() }
    }

    @Test fun snapshotStaysFixedUntilReopeningAndCatalogRepricingDoesNotReadUsage() = Fixture().use { f ->
        f.controller.opened("/rollout")
        val original = f.screen.chatCost
        f.data = usage(request(), request())
        f.screen = f.screen.copy(threadModel = "changed", activeTurn = "turn-new")
        f.controller.reprice()
        assertEquals(original, f.screen.chatCost)
        assertEquals(1, f.calls)
        f.catalog = emptyList()
        f.controller.reprice()
        assertEquals(ChatCostStatus.Unavailable, f.screen.chatCost.status)
        assertEquals(1, f.calls)
        f.catalog = listOf(model())
        f.controller.opened("/rollout")
        assertEquals(2, f.screen.chatCost.requests)
        assertEquals(2, f.calls)
    }

    @Test fun failuresStopTheSpinnerWithoutRetriesAndExplicitRetryWorks() = Fixture().use { f ->
        f.gate = CompletableDeferred()
        f.fail = true
        f.controller.opened("/rollout")
        assertEquals(ChatCostStatus.Calculating, f.screen.chatCost.status)
        f.gate!!.complete(Unit)
        assertEquals(ChatCostStatus.Unavailable, f.screen.chatCost.status)
        assertEquals(1, f.calls)
        f.fail = false
        f.controller.opened("/rollout")
        assertEquals(ChatCostStatus.Ready, f.screen.chatCost.status)
        assertEquals(2, f.calls)
    }

    @Test fun lateSnapshotsCannotOverwriteAnotherThreadOrConnection() = Fixture().use { f ->
        val gate = CompletableDeferred<Unit>()
        f.gate = gate
        f.ignoreCancellation = true
        f.controller.opened("/rollout-a")
        f.screen = f.screen.copy(thread = "chat-b")
        f.gate = null
        f.data = usage(request(), request())
        f.controller.opened("/rollout-b")
        assertEquals(2, f.screen.chatCost.requests)
        gate.complete(Unit)
        assertEquals(2, f.screen.chatCost.requests)
        f.gate = CompletableDeferred()
        f.controller.opened("/rollout-b")
        f.epoch++
        f.screen = f.screen.copy(ready = false)
        f.controller.cancel()
        f.gate!!.complete(Unit)
        assertEquals(ChatCostStatus.Unavailable, f.screen.chatCost.status)
    }

    private class Session(val responses: MutableList<JsonObject>) : RemoteSession {
        override val events = Channel<JsonObject>(Channel.UNLIMITED)
        override var generation = 1L
        val calls = mutableListOf<JsonObject>()
        override suspend fun connect(url: String, token: String) = obj()
        override suspend fun call(method: String, params: JsonObject): JsonObject {
            assertEquals("command/exec", method)
            calls += params
            check(responses.isNotEmpty())
            return responses.removeAt(0)
        }
        override fun respond(id: JsonElement, result: JsonObject, epoch: Long) {}
        override fun close() {}
        override fun dispose() {}
    }
    private fun page(status: String = "caughtUp", offset: Long = 200, boundary: Long = 200, buckets: JsonArray = JsonArray(emptyList())) =
        obj("version" to JsonPrimitive(1), "status" to s(status), "thread" to s("chat-a"), "offset" to JsonPrimitive(offset),
            "boundary" to JsonPrimitive(boundary), "buckets" to buckets, "continuation" to obj("checkpoint" to JsonPrimitive(offset)))
    private fun result(page: JsonObject) = obj("exitCode" to JsonPrimitive(0), "stdout" to s(page.toString()))
    private fun bucket() = wire.parseToJsonElement("""{"provider":"openai","model":"model-a","tier":"default","context":"short","requests":1,"tokens":{"input_tokens":1000,"cached_input_tokens":200,"cache_write_input_tokens":100,"output_tokens":100,"total_tokens":1100}}""").jsonObject

    @Test fun stockAdapterUsesBoundedReadOnlyArgvBatchesAndAccumulatesCheckpointsOnce() = runBlocking {
        val rows = JsonArray(listOf(bucket()))
        val session = Session(mutableListOf(result(page("more", 100, buckets = rows)), result(page(buckets = rows))))
        val usage = StockChatAccounting(session).snapshot("chat-a", "/rollout", 1)
        assertEquals(1, usage.requests.size)
        assertEquals(2, usage.requests.single().count)
        assertEquals(2000L, usage.requests.single().tokens.input)
        for (params in session.calls) {
            val command = params["command"]!!.jsonArray.map { it.jsonPrimitive.content }
            assertEquals(listOf(StockChatAccounting.EXECUTABLE, "accounting"), command.take(2))
            assertEquals("readOnly", params.map("sandboxPolicy").str("type"))
            assertEquals(JsonPrimitive(false), params.map("sandboxPolicy")["networkAccess"])
            assertEquals(JsonPrimitive(2000), params["timeoutMs"])
            assertEquals(JsonPrimitive(65536), params["outputBytesCap"])
        }
        val input = wire.parseToJsonElement(session.calls[1]["command"]!!.jsonArray[2].jsonPrimitive.content).jsonObject
        assertEquals(JsonPrimitive(100), input.map("continuation")["checkpoint"])
    }

    @Test fun malformedOrNonprogressingAccountingAndGenerationChangesAreRejectedWithoutRetry() = runBlocking {
        val bad = listOf(page("unavailable"), page("more", 0),
            JsonObject(page() + ("thread" to s("wrong"))),
            page(buckets = JsonArray(listOf(JsonObject(bucket() + ("tokens" to obj()))))))
        for (response in bad) {
            val session = Session(mutableListOf(result(response)))
            assertTrue(runCatching { StockChatAccounting(session).snapshot("chat-a", "/rollout", 1) }.isFailure)
            assertEquals(1, session.calls.size)
        }
        val session = Session(mutableListOf(result(page())))
        session.generation = 2
        assertTrue(runCatching { StockChatAccounting(session).snapshot("chat-a", "/rollout", 1) }.isFailure)
        assertTrue(session.calls.isEmpty())
    }
}
