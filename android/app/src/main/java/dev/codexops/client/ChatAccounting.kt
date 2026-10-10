package dev.codexops.client

import dev.codexops.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

internal interface ChatAccounting {
    suspend fun snapshot(thread: String, path: String, generation: Long): ChatUsage
}

/** Stock command/exec carries bounded accounting, never a rollout download. */
internal class StockChatAccounting(private val rpc: RemoteSession) : ChatAccounting {
    companion object {
        const val EXECUTABLE = "/home/agent/.local/libexec/remote-codex-forwarder"
        const val OUTPUT_CAP = 65_536
    }

    override suspend fun snapshot(thread: String, path: String, generation: Long): ChatUsage = withTimeout(30_000) {
        require(path.startsWith('/') && !path.contains('\u0000'))
        var continuation: JsonObject? = null
        var boundary: Long? = null
        var offset = 0L
        val buckets = linkedMapOf<List<String>, CostRequest>()
        var totalRequests = 0
        while (true) {
            ensureActive()
            val input = obj("version" to JsonPrimitive(1), "thread" to s(thread), "path" to s(path),
                "continuation" to continuation).toString()
            require(input.toByteArray(Charsets.UTF_8).size <= OUTPUT_CAP)
            val result = rpc.callForGenerationWithTimeout("command/exec", obj(
                "command" to JsonArray(listOf(EXECUTABLE, "accounting", input).map(::s)),
                "cwd" to s("/home/agent"),
                "sandboxPolicy" to obj("type" to s("readOnly"), "networkAccess" to JsonPrimitive(false)),
                "timeoutMs" to JsonPrimitive(2_000), "outputBytesCap" to JsonPrimitive(OUTPUT_CAP),
            ), generation, 2_500)
            check(result.str("exitCode") == "0")
            val output = result.str("stdout")
            require(output.toByteArray(Charsets.UTF_8).size <= OUTPUT_CAP)
            val page = wire.parseToJsonElement(output).jsonObject
            require(page["version"] == JsonPrimitive(1) && page.str("thread") == thread)
            val status = page.str("status")
            require(status in setOf("more", "caughtUp"))
            fun count(name: String): Long = (page[name] as? JsonPrimitive)?.takeUnless { it.isString }
                ?.longOrNull?.takeIf { it >= 0 } ?: error("Invalid accounting checkpoint")
            val nextBoundary = count("boundary")
            val nextOffset = count("offset")
            require((boundary == null || boundary == nextBoundary) && nextOffset in offset..nextBoundary)
            if (status == "more") require(nextOffset > offset)
            boundary = nextBoundary
            offset = nextOffset
            val rows = page["buckets"] as? JsonArray ?: error("Missing accounting buckets")
            require(rows.size <= 128)
            for (element in rows) {
                val row = element.jsonObject
                val provider = row.str("provider")
                val model = row.str("model")
                val tier = row.str("tier")
                val context = row.str("context")
                require(listOf(provider, model, tier).all { it.length <= 256 && it.none(Char::isISOControl) } &&
                    provider.isNotBlank() && model.isNotBlank() && context in setOf("short", "long"))
                val requests = (row["requests"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
                    ?.takeIf { it > 0 } ?: error("Invalid accounting request count")
                val tokens = costTokens(row.map("tokens")) ?: error("Invalid accounting counters")
                totalRequests = Math.addExact(totalRequests, requests)
                val key = listOf(provider, model, tier, context)
                val prior = buckets[key]
                val summed = if (prior == null) tokens else CostTokens(
                    Math.addExact(prior.tokens.input, tokens.input), Math.addExact(prior.tokens.cached, tokens.cached),
                    Math.addExact(prior.tokens.writes, tokens.writes), Math.addExact(prior.tokens.output, tokens.output),
                    Math.addExact(prior.tokens.total, tokens.total))
                buckets[key] = CostRequest(provider, model, tier, summed, context,
                    Math.addExact(prior?.count ?: 0, requests))
                require(buckets.size <= 128)
            }
            if (status == "caughtUp") return@withTimeout ChatUsage(buckets.values.toList(), false)
            continuation = page["continuation"] as? JsonObject ?: error("Missing accounting continuation")
        }
        @Suppress("UNREACHABLE_CODE")
        error("Accounting did not finish")
    }
}

internal class ChatCostController(
    private val scope: CoroutineScope,
    private val accounting: ChatAccounting,
    private val generation: () -> Long,
    private val state: () -> ScreenState,
    private val catalog: () -> List<ModelEnrichment>,
    private val update: (ChatCost) -> Unit,
) {
    private var revision = 0L
    private var job: Job? = null
    private var usage: ChatUsage? = null
    private var owner: Pair<String, Long>? = null

    fun cancel() {
        revision++
        job?.cancel()
        job = null
        usage = null
        owner = null
        update(ChatCost())
    }

    fun opened(path: String?) {
        cancel()
        val st = state()
        val thread = st.thread ?: return
        if (st.page != "chat" || !st.ready || path == null) return
        val epoch = generation()
        val selected = revision
        owner = thread to epoch
        fun current() = revision == selected && generation() == epoch &&
            state().let { it.page == "chat" && it.thread == thread && it.ready }
        update(ChatCost(status = ChatCostStatus.Calculating))
        job = scope.launch {
            try {
                val snapshot = withTimeout(30_000) { accounting.snapshot(thread, path, epoch) }
                if (current()) {
                    usage = snapshot
                    update(estimateChatCost(snapshot, catalog()))
                }
            } catch (e: Exception) {
                if (e is CancellationException && e !is TimeoutCancellationException) throw e
                if (current()) update(ChatCost())
            }
        }
    }

    fun reprice() {
        val snapshot = usage ?: return
        val st = state()
        if (st.page == "chat" && st.ready && owner == st.thread?.let { it to generation() }) {
            update(estimateChatCost(snapshot, catalog()))
        }
    }
}
