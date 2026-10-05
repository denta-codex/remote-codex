package dev.codexops.core

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class ServerRequestDispatchTest {
    private val interactive = listOf(
        "item/commandExecution/requestApproval", "item/fileChange/requestApproval",
        "item/permissions/requestApproval", "item/tool/requestUserInput", "mcpServer/elicitation/request",
    )
    private val unsupported = listOf(
        "applyPatchApproval", "execCommandApproval",
        "account/chatgptAuthTokens/refresh", "attestation/generate", "future/request",
    )

    private fun request(id: JsonElement, method: String) =
        obj("id" to id, "method" to s(method), "params" to obj())

    @Test
    fun everyStockRequestHasAnInteractiveRouteOrAnImmediateReply() = runBlocking {
        Fixture { peer, message ->
            if (message.str("method") == "initialize") {
                // This ID collides with the outstanding initialize call in the opposite direction.
                peer.send(request(message.getValue("id"), unsupported.first()).toString())
                (unsupported.drop(1) + "item/tool/call" + "currentTime/read" + interactive)
                    .forEach { peer.send(request(s(it), it).toString()) }
                peer.send(obj("method" to s("future/notification"), "params" to obj()).toString())
            }
        }.use { fixture ->
            fixture.connect()
            repeat(unsupported.size + 2) {
                val reply = fixture.reply()
                when (reply["id"]) {
                    s("item/tool/call") -> {
                        assertEquals("false", reply.map("result").str("success"))
                        assertEquals("inputText", reply.map("result").list("contentItems").single().str("type"))
                    }
                    s("currentTime/read") -> assertTrue(reply.map("result")["currentTimeAt"]!!.jsonPrimitive.long > 0)
                    else -> {
                        assertEquals(setOf("id", "error"), reply.keys)
                        assertEquals("-32601", reply.map("error").str("code"))
                    }
                }
            }
            interactive.forEach {
                val event = withTimeout(5000) { fixture.rpc.events.receive() }
                assertEquals(it, event.str("method"))
                assertEquals(fixture.rpc.generation.toString(), event.str("_epoch"))
            }
            assertEquals("future/notification", withTimeout(5000) { fixture.rpc.events.receive() }.str("method"))
            assertEquals(unsupported.size + 2, fixture.replyCount())
        }
    }

    @Test
    fun currentTimeUsesWholeWallClockSeconds() {
        listOf(2_200_000_000_999L to 2_200_000_000L, 1000L to 1L, 999L to 0L, -1L to -1L)
            .forEach { (millis, seconds) ->
                val route = ServerRequests.route("currentTime/read") { millis } as ServerRequestRoute.Result
                assertEquals(obj("currentTimeAt" to JsonPrimitive(seconds)), route.result)
                assertFalse(route.result.getValue("currentTimeAt").jsonPrimitive.isString)
            }
    }

    @Test
    fun currentTimeRepliesOncePerTypedIdAndSamplesEachNewRequest() = runBlocking {
        var now = 2_200_000_000_999L
        val samples = AtomicInteger()
        Fixture(clockMillis = { samples.incrementAndGet(); now }).use { fixture ->
            fixture.connect()
            listOf(JsonPrimitive(7), s("7")).forEachIndexed { index, id ->
                now += index * 1000L
                val request = obj("id" to id, "method" to s("currentTime/read"),
                    "params" to obj("threadId" to s("unselected-thread")))
                fixture.send(request)
                fixture.send(request)
                val reply = fixture.reply()
                assertEquals(id, reply["id"])
                assertEquals(obj("currentTimeAt" to JsonPrimitive(Math.floorDiv(now, 1000L))), reply["result"])
                fixture.send(request)
            }
            assertEquals(2, fixture.replyCount())
            assertEquals(2, samples.get())
            assertTrue(fixture.rpc.events.tryReceive().isFailure)
        }
    }

    @Test
    fun currentTimeDoesNotReplayAfterDisconnectAndUsesFreshClockOnNewDelivery() = runBlocking {
        var now = 1000L
        var closeAfterReply = true
        Fixture(clockMillis = { now }, onMessage = { peer, message ->
            if (message.str("method").isEmpty() && closeAfterReply) {
                closeAfterReply = false
                peer.close(1000, "fixture disconnect after receipt")
            }
        }).use { fixture ->
            fixture.connect()
            val epoch = fixture.rpc.generation
            val id = s("time")
            val request = obj("id" to id, "method" to s("currentTime/read"),
                "params" to obj("threadId" to s("thread")))
            fixture.send(request)
            assertEquals(obj("currentTimeAt" to JsonPrimitive(1L)), fixture.reply()["result"])
            assertEquals("connection/lost", withTimeout(5000) { fixture.rpc.events.receive() }.str("method"))
            now = 2999L
            fixture.connect()
            assertThrows(ConnectionLost::class.java) { fixture.rpc.respond(id, obj(), epoch) }
            assertEquals(1, fixture.replyCount())
            fixture.send(request)
            assertEquals(obj("currentTimeAt" to JsonPrimitive(2L)), fixture.reply()["result"])
            assertEquals(2, fixture.replyCount())
            assertTrue(fixture.rpc.events.tryReceive().isFailure)
        }
    }

    @Test
    fun duplicateRequestsAndConcurrentRepliesAreConsumedOnceWithTypedIds() = runBlocking {
        Fixture().use { fixture ->
            fixture.connect()
            val numeric = JsonPrimitive(7)
            val string = s("7")
            listOf(numeric, string).forEach { id ->
                val request = request(id, if (id == numeric) "item/tool/requestUserInput" else McpElicitation.METHOD)
                fixture.send(request)
                fixture.send(request)
                assertEquals(id, withTimeout(5000) { fixture.rpc.events.receive() }["id"])
                coroutineScope {
                    val result = if (id == numeric) obj("answers" to obj()) else McpElicitation.response("cancel")
                    repeat(8) { launch(Dispatchers.Default) { fixture.rpc.respond(id, result, fixture.rpc.generation) } }
                }
                assertEquals(id, fixture.reply()["id"])
                fixture.send(request)
            }
            assertEquals(2, fixture.replyCount())
            // A server round trip ensures all earlier server messages were dispatched.
            assertTrue(fixture.rpc.events.tryReceive().isFailure)
        }
    }

    @Test
    fun resolvedRequestsCannotBeAnsweredOrReopened() = runBlocking {
        Fixture().use { fixture ->
            fixture.connect()
            val id = s("resolved")
            fixture.send(request(id, "item/commandExecution/requestApproval"))
            withTimeout(5000) { fixture.rpc.events.receive() }
            fixture.send(obj("method" to s("serverRequest/resolved"), "params" to obj("requestId" to id)))
            assertEquals("serverRequest/resolved", withTimeout(5000) { fixture.rpc.events.receive() }.str("method"))
            fixture.rpc.respond(id, obj("decision" to s("accept")), fixture.rpc.generation)
            fixture.send(request(id, "item/commandExecution/requestApproval"))
            assertEquals(0, fixture.replyCount())
            assertTrue(fixture.rpc.events.tryReceive().isFailure)

            val beforeRequest = s("resolved-first")
            fixture.send(obj("method" to s("serverRequest/resolved"), "params" to obj("requestId" to beforeRequest)))
            withTimeout(5000) { fixture.rpc.events.receive() }
            fixture.send(request(beforeRequest, "item/tool/requestUserInput"))
            assertEquals(0, fixture.replyCount())
            assertTrue(fixture.rpc.events.tryReceive().isFailure)
        }
    }

    @Test
    fun disconnectAfterReplyDoesNotReplayAndNewGenerationCanReuseId() = runBlocking {
        var closeAfterReply = true
        Fixture { peer, message ->
            if (message.str("method").isEmpty() && closeAfterReply) {
                closeAfterReply = false
                peer.close(1000, "fixture disconnect after receipt")
            }
        }.use { fixture ->
            fixture.connect()
            val epoch = fixture.rpc.generation
            val id = s("same-id")
            fixture.send(request(id, McpElicitation.METHOD))
            withTimeout(5000) { fixture.rpc.events.receive() }
            fixture.rpc.respond(id, McpElicitation.response("accept", obj()), epoch)
            assertEquals(id, fixture.reply()["id"])
            assertEquals("connection/lost", withTimeout(5000) { fixture.rpc.events.receive() }.str("method"))
            fixture.connect()
            assertThrows(ConnectionLost::class.java) { fixture.rpc.respond(id, obj(), epoch) }
            assertEquals(1, fixture.replyCount())
            fixture.send(request(id, McpElicitation.METHOD))
            withTimeout(5000) { fixture.rpc.events.receive() }
            fixture.rpc.respond(id, McpElicitation.response("cancel"), fixture.rpc.generation)
            assertEquals(id, fixture.reply()["id"])
            assertEquals(2, fixture.replyCount())
        }
    }

    @Test
    fun nativeTaskReadsCanIssueNestedRpcWithoutConsumingTheTriggerReply() = runBlocking {
        val stockReads = AtomicInteger()
        Fixture { peer, message ->
            when (message.str("method")) {
                "trigger-native" -> {
                    for (id in listOf(message.getValue("id"), s(message.str("id")))) {
                        val tool = obj("id" to id, "method" to s("item/tool/call"),
                            "params" to obj("namespace" to s("codex_app"), "tool" to s("list_threads"), "arguments" to obj()))
                        peer.send(tool.toString())
                        peer.send(tool.toString())
                    }
                    peer.send(obj("id" to message["id"], "result" to obj("trigger" to s("accepted"))).toString())
                }
                "thread/list" -> {
                    stockReads.incrementAndGet()
                    peer.send(obj("id" to message["id"], "result" to obj("data" to JsonArray(emptyList()))).toString())
                }
            }
        }.use { fixture ->
            fixture.connect()
            val tools = TaskToolRequests(this, { fixture.rpc.generation }, { "grace" },
                { method, params, epoch -> fixture.rpc.call(method, params, expectedGeneration = epoch) },
                fixture.rpc::respond)
            val collector = launch {
                for (event in fixture.rpc.events) tools.handle(event)
            }
            try {
                assertEquals("accepted", fixture.rpc.call("trigger-native").str("trigger"))
                val replies = listOf(fixture.reply(), fixture.reply())
                assertEquals(setOf(false, true), replies.map { it.getValue("id").jsonPrimitive.isString }.toSet())
                replies.forEach { assertEquals(JsonPrimitive(true), it.map("result")["success"]) }
                assertEquals(2, stockReads.get())
                assertEquals(2, fixture.replyCount())
            } finally { collector.cancelAndJoin(); tools.cancelAll() }
        }
    }

    @Test
    fun nativeTaskRequestsUseTheSharedTypedIdAndResolutionGuard() = runBlocking {
        Fixture().use { fixture ->
            fixture.connect()
            val epoch = fixture.rpc.generation
            for (id in listOf(JsonPrimitive(7), s("7"))) {
                val tool = obj("id" to id, "method" to s("item/tool/call"),
                    "params" to obj("namespace" to s("codex_app"), "tool" to s("list_threads"), "arguments" to obj()))
                fixture.send(tool)
                fixture.send(tool)
                assertEquals(id, withTimeout(5000) { fixture.rpc.events.receive() }["id"])
                fixture.rpc.respond(id, obj("success" to JsonPrimitive(true)), epoch)
                assertEquals(id, fixture.reply()["id"])
                fixture.send(tool)
            }
            assertEquals(2, fixture.replyCount())
            assertTrue(fixture.rpc.events.tryReceive().isFailure)
            val resolved = s("resolved-tool")
            fixture.send(obj("id" to resolved, "method" to s("item/tool/call"),
                "params" to obj("namespace" to s("codex_app"), "tool" to s("read_thread"))))
            withTimeout(5000) { fixture.rpc.events.receive() }
            fixture.send(obj("method" to s("serverRequest/resolved"), "params" to obj("requestId" to resolved)))
            withTimeout(5000) { fixture.rpc.events.receive() }
            fixture.rpc.respond(resolved, obj("success" to JsonPrimitive(true)), epoch)
            assertEquals(2, fixture.replyCount())
            fixture.connect()
            try {
                fixture.rpc.call("thread/read", obj("threadId" to s("task")), expectedGeneration = epoch)
                fail("A stale read reached the new connection")
            } catch (_: ConnectionLost) { }
            assertEquals(2, fixture.replyCount())
        }
    }

    private class Fixture(
        clockMillis: () -> Long = System::currentTimeMillis,
        private val onMessage: (WebSocket, JsonObject) -> Unit = { _, _ -> },
    ) : AutoCloseable {
        val rpc = Rpc(true, clockMillis)
        private val server = MockWebServer()
        private val replies = Channel<JsonObject>(Channel.UNLIMITED)
        private val count = AtomicInteger()
        @Volatile private var peer: WebSocket? = null

        init {
            repeat(2) {
                server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) { peer = webSocket }
                    override fun onMessage(ws: WebSocket, text: String) {
                        val message = wire.parseToJsonElement(text).jsonObject
                        if (message.str("method").isEmpty()) {
                            count.incrementAndGet()
                            replies.trySend(message)
                        }
                        onMessage(ws, message)
                        when (message.str("method")) {
                            "initialize" -> ws.send(obj("id" to message["id"], "result" to obj()).toString())
                            "barrier" -> ws.send(obj("id" to message["id"], "result" to obj("count" to JsonPrimitive(count.get()))).toString())
                        }
                    }
                }))
            }
            server.start()
        }

        suspend fun connect() { rpc.connect("ws://127.0.0.1:${server.port}", "fixture") }
        fun send(message: JsonObject) { check(peer!!.send(message.toString())) }
        suspend fun reply(): JsonObject = withTimeout(5000) { replies.receive() }
        suspend fun replyCount(): Int = rpc.call("barrier").str("count").toInt()
        override fun close() { rpc.dispose(); replies.close(); server.close() }
    }
}
