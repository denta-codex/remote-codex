package dev.codexops.core

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ReadOnlyTaskToolsTest {
    private val metadata = obj("id" to s("task"), "name" to s("Fixture task"), "preview" to s("Preview"),
        "status" to obj("type" to s("active"), "activeFlags" to JsonArray(listOf(s("waitingOnApproval")))),
        "cwd" to s("/fixture"), "createdAt" to JsonPrimitive(100), "updatedAt" to JsonPrimitive(200))
    private fun params(tool: String, args: JsonElement = obj()) =
        obj("namespace" to s("codex_app"), "tool" to s(tool), "arguments" to args)
    private fun item(type: String, vararg fields: Pair<String, JsonElement?>) =
        obj("id" to s(type), "type" to s(type), *fields)
    private fun turn(items: List<JsonObject> = emptyList(), view: String = "full") =
        obj("id" to s("turn"), "status" to s("completed"), "itemsView" to s(view),
            "startedAt" to JsonPrimitive(123), "completedAt" to JsonPrimitive(125),
            "durationMs" to JsonPrimitive(2000), "items" to JsonArray(items))

    @Test
    fun listingThenReadingUsesOnlyStockReadApisAndPreservesCursorStatusAndTime() = runBlocking {
        val calls = mutableListOf<Pair<String, JsonObject>>()
        val tools = ReadOnlyTaskTools("grace") { method, args ->
            calls.add(method to args)
            when (method) {
                "thread/list" -> obj("data" to JsonArray(listOf(metadata)), "nextCursor" to s("more-tasks"))
                "thread/read" -> obj("thread" to metadata)
                "thread/turns/list" -> obj("data" to JsonArray(listOf(turn(listOf(
                    item("agentMessage", "text" to s("Final"), "phase" to s("final")),
                )))), "nextCursor" to if (args.str("cursor").isEmpty()) s("opaque/older") else JsonNull)
                else -> error("Unexpected mutation: $method")
            }
        }
        val listed = tools.execute(params("list_threads"))
        val task = listed.list("threads").single()
        assertEquals("grace", task.str("hostId"))
        assertEquals("task", task.str("threadId"))
        assertEquals("active", task.str("status"))
        assertEquals("10", calls.first().second.str("limit"))
        assertEquals(JsonPrimitive(false), calls.first().second["archived"])
        assertEquals("updated_at", calls.first().second.str("sortKey"))
        assertFalse(calls.first().second.containsKey("sourceKinds"))
        val args = obj("threadId" to task["threadId"], "hostId" to task["hostId"])
        val read = tools.execute(params("read_thread", args))
        assertEquals(metadata["status"], read.map("thread")["status"])
        assertEquals(metadata["createdAt"], read.map("thread")["createdAt"])
        assertEquals("2000", read.list("turns").single().str("durationMs"))
        assertEquals("final", read.list("turns").single().list("items").single().str("phase"))
        assertEquals("opaque/older", read.map("page").str("nextCursor"))
        assertEquals(JsonPrimitive(true), read.map("page")["hasMore"])
        assertEquals(JsonPrimitive(false), calls[1].second["includeTurns"])
        assertEquals("full", calls[2].second.str("itemsView"))
        assertEquals("desc", calls[2].second.str("sortDirection"))
        assertEquals("1", calls[2].second.str("limit"))
        val older = tools.execute(params("read_thread", JsonObject(args + ("cursor" to read.map("page")["nextCursor"]!!))))
        assertEquals(JsonPrimitive(false), older.map("page")["hasMore"])
        assertEquals(listOf("thread/list", "thread/read", "thread/turns/list", "thread/read", "thread/turns/list"), calls.map { it.first })
        assertEquals("opaque/older", calls.last().second.str("cursor"))
    }

    @Test
    fun validationRejectsUnsupportedArgumentsAndForeignHostsBeforeAnyRpc() = runBlocking {
        val tools = ReadOnlyTaskTools("grace") { _, _ -> error("Invalid arguments must not reach stock") }
        val bad = listOf(
            params("list_threads", obj("query" to s("find me"))),
            params("list_threads", obj("cursor" to s("not-supported"))),
            params("list_threads", obj("hostId" to s("local"))),
            params("list_threads", obj("hostId" to s("other-host"))),
            params("list_threads", obj("limit" to JsonPrimitive(51))),
            params("list_threads", obj("limit" to s("10"))),
            params("read_thread", JsonNull), params("read_thread"),
            params("read_thread", obj("threadId" to s(" "))),
            params("read_thread", obj("threadId" to s("task"), "turnLimit" to JsonPrimitive(0))),
            params("read_thread", obj("threadId" to s("task"), "turnLimit" to JsonPrimitive(11))),
            params("read_thread", obj("threadId" to s("task"), "includeOutputs" to s("true"))),
            params("read_thread", obj("threadId" to s("task"), "maxOutputCharsPerItem" to JsonPrimitive(20001))),
            params("read_thread", obj("threadId" to s("task"), "hostId" to JsonNull)),
            params("archive_thread", obj("threadId" to s("task"))),
        )
        bad.forEach { request ->
            try { tools.execute(request); fail("Accepted $request") } catch (_: TaskToolFailure) { }
        }
    }

    @Test
    fun outputsAreOptInAndMcpDynamicAndFunctionPayloadsStayOmitted() = runBlocking {
        val items = listOf(
            item("userMessage", "content" to JsonArray(listOf(obj("type" to s("text"), "text" to s("Question"))))),
            item("agentMessage", "text" to s("Answer")), item("plan", "text" to s("Plan")),
            item("reasoning", "summary" to JsonArray(listOf(s("Summary"))), "content" to JsonArray(listOf(s("123456")))),
            item("commandExecution", "aggregatedOutput" to s("123456"), "status" to s("completed")),
            item("fileChange", "changes" to JsonArray(listOf(obj("path" to s("file"), "kind" to obj("type" to s("update")), "diff" to s("123456"))))),
            item("mcpToolCall", "tool" to s("fixture"), "result" to obj("payload" to s("MCP payload"))),
            item("dynamicToolCall", "tool" to s("fixture"), "contentItems" to JsonArray(listOf(obj("text" to s("Dynamic payload"))))),
            item("functionCallOutput", "output" to s("Function payload")),
        )
        val tools = ReadOnlyTaskTools("grace") { method, _ -> if (method == "thread/read") obj("thread" to metadata)
            else obj("data" to JsonArray(listOf(turn(items)))) }
        val args = obj("threadId" to s("task"))
        val without = tools.execute(params("read_thread", args)).list("turns").single().list("items")
        assertEquals("Answer", without[1].str("text"))
        assertFalse(without[3].containsKey("content"))
        assertFalse(without[4].containsKey("output"))
        assertFalse(without[5].list("changes").single().containsKey("diff"))
        val read = tools.execute(params("read_thread", JsonObject(args + obj("includeOutputs" to JsonPrimitive(true),
            "maxOutputCharsPerItem" to JsonPrimitive(3)))))
        val with = read.list("turns").single().list("items")
        assertEquals(obj("text" to s("123"), "truncated" to JsonPrimitive(true), "originalChars" to JsonPrimitive(6)), with[4]["output"])
        assertEquals("123", with[5].list("changes").single().map("diff").str("text"))
        assertFalse(read.toString().contains("MCP payload"))
        assertFalse(read.toString().contains("Dynamic payload"))
        assertFalse(read.toString().contains("Function payload"))
        val zero = tools.execute(params("read_thread", JsonObject(args + obj("includeOutputs" to JsonPrimitive(true), "maxOutputCharsPerItem" to JsonPrimitive(0)))))
        assertEquals("", zero.list("turns").single().list("items")[4].map("output").str("text"))
    }

    @Test
    fun outputBudgetsCoverWholeItemsAndHandleUnicodeWithoutBreakingSurrogates() = runBlocking {
        val items = listOf(
            item("reasoning", "content" to JsonArray(listOf(s("123456"), s("abcdef")))),
            item("commandExecution", "aggregatedOutput" to s("🙂🙂")),
            item("commandExecution", "aggregatedOutput" to s("x".repeat(21000))),
            item("commandExecution", "aggregatedOutput" to s("last")),
        )
        val tools = ReadOnlyTaskTools("grace") { method, _ -> if (method == "thread/read") obj("thread" to metadata)
            else obj("data" to JsonArray(listOf(turn(items)))) }
        val small = tools.execute(params("read_thread", obj("threadId" to s("task"), "includeOutputs" to JsonPrimitive(true),
            "maxOutputCharsPerItem" to JsonPrimitive(3)))).list("turns").single().list("items")
        assertEquals("123", small[0].list("content")[0].str("text"))
        assertEquals("", small[0].list("content")[1].str("text"))
        assertEquals("🙂", small[1].map("output").str("text"))
        val full = tools.execute(params("read_thread", obj("threadId" to s("task"), "includeOutputs" to JsonPrimitive(true),
            "maxOutputCharsPerItem" to JsonPrimitive(20000)))).list("turns").single().list("items")
        assertEquals(19984, full[2].map("output").str("text").length)
        assertEquals("", full[3].map("output").str("text"))
        assertEquals(JsonPrimitive(true), full[3].map("output")["truncated"])
    }

    @Test
    fun emptyPagesAndIncompleteOrRepeatedHistoryAreDistinguished() = runBlocking {
        var response = obj("data" to JsonArray(emptyList()))
        val tools = ReadOnlyTaskTools("grace") { method, _ -> if (method == "thread/read") obj("thread" to metadata) else response }
        assertTrue(tools.execute(params("list_threads", obj("limit" to JsonPrimitive(50)))).list("threads").isEmpty())
        val args = obj("threadId" to s("task"), "turnLimit" to JsonPrimitive(10), "cursor" to s("cursor"))
        val empty = tools.execute(params("read_thread", args))
        assertTrue(empty.list("turns").isEmpty())
        assertEquals(JsonPrimitive(false), empty.map("page")["hasMore"])
        val incompletePages = listOf(
            obj(), obj("data" to JsonArray(listOf(turn(view = "summary")))),
            obj("data" to JsonArray(emptyList()), "nextCursor" to s("cursor")),
        )
        for (bad in incompletePages) {
            response = bad
            try { tools.execute(params("read_thread", args)); fail("Accepted incomplete history") } catch (_: TaskToolFailure) { }
        }
    }

    private fun event(id: JsonElement = s("request"), epoch: Long = 1, args: JsonElement = obj()) =
        obj("id" to id, "method" to s("item/tool/call"), "_epoch" to JsonPrimitive(epoch), "params" to params("list_threads", args))
    private fun data(result: JsonObject) = wire.parseToJsonElement(result.list("contentItems").last().str("text")).jsonObject

    @Test
    fun asyncFailuresAreBoundedAndDoNotExposeServerErrorPayloads() = runBlocking {
        val replies = Channel<JsonObject>(Channel.UNLIMITED)
        val tools = TaskToolRequests(this, { 1 }, { "grace" }, { _, _, _ -> throw RpcRejected(-32601, "private error payload") },
            { _, result, _ -> replies.trySend(result) })
        tools.handle(event())
        val rejected = withTimeout(1000) { replies.receive() }
        assertEquals(JsonPrimitive(false), rejected["success"])
        assertTrue(rejected.toString().contains("-32601"))
        assertFalse(rejected.toString().contains("private error payload"))
        val oversized = TaskToolRequests(this, { 1 }, { "grace" }, { _, _, _ -> obj("data" to JsonArray(listOf(
            JsonObject(metadata + ("preview" to s("x".repeat(1000)))),
        ))) }, { _, result, _ -> replies.trySend(result) }, maxMessageBytes = 512)
        oversized.handle(event())
        val bounded = withTimeout(1000) { replies.receive() }
        assertEquals(JsonPrimitive(false), bounded["success"])
        assertTrue(bounded.toString().contains("transport limit"))
        val timeout = TaskToolRequests(this, { 1 }, { "grace" }, { _, _, _ -> awaitCancellation() },
            { _, result, _ -> replies.trySend(result) }, timeoutMillis = 20)
        timeout.handle(event())
        assertTrue(withTimeout(1000) { replies.receive() }.toString().contains("timed out"))
    }

    @Test
    fun resolutionAndDisconnectCancelReadsAndNewGenerationsCanReuseIds() = runBlocking {
        var epoch = 1L
        var host: String? = "grace"
        val started = Channel<Long>(Channel.UNLIMITED)
        val cancelled = Channel<Long>(Channel.UNLIMITED)
        val replies = Channel<JsonObject>(Channel.UNLIMITED)
        var hold = true
        val tools = TaskToolRequests(this, { epoch }, { host }, { _, _, generation ->
            if (hold) {
                started.send(generation)
                try { awaitCancellation() } finally { cancelled.trySend(generation) }
            } else obj("data" to JsonArray(emptyList()))
        }, { _, result, _ -> replies.trySend(result) })
        tools.handle(event())
        assertEquals(1L, withTimeout(1000) { started.receive() })
        tools.resolved(s("request"))
        assertEquals(1L, withTimeout(1000) { cancelled.receive() })
        assertTrue(replies.tryReceive().isFailure)
        tools.handle(event())
        withTimeout(1000) { started.receive() }
        epoch = 2
        host = "other-host"
        tools.cancelAll()
        withTimeout(1000) { cancelled.receive() }
        assertTrue(replies.tryReceive().isFailure)
        hold = false
        tools.handle(event(epoch = 1))
        assertTrue(replies.tryReceive().isFailure)
        tools.handle(event(epoch = 2))
        assertEquals(1, data(withTimeout(1000) { replies.receive() })["schemaVersion"]?.jsonPrimitive?.int)
        host = null
        tools.handle(event(s("unverified"), epoch = 2))
        assertTrue(withTimeout(1000) { replies.receive() }.toString().contains("verified active connection"))
    }
}
