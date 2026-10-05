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
        "item/permissions/requestApproval", "item/tool/requestUserInput",
    )
    private val unsupported = listOf(
        "currentTime/read", "applyPatchApproval", "execCommandApproval",
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
                (unsupported.drop(1) + "item/tool/call" + "mcpServer/elicitation/request" + interactive)
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
                    s("mcpServer/elicitation/request") -> assertEquals(obj("action" to s("cancel")), reply["result"])
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
    fun duplicateRequestsAndConcurrentRepliesAreConsumedOnceWithTypedIds() = runBlocking {
        Fixture().use { fixture ->
            fixture.connect()
            val numeric = JsonPrimitive(7)
            val string = s("7")
            listOf(numeric, string).forEach { id ->
                val request = request(id, "item/tool/requestUserInput")
                fixture.send(request)
                fixture.send(request)
                assertEquals(id, withTimeout(5000) { fixture.rpc.events.receive() }["id"])
                coroutineScope {
                    repeat(8) { launch(Dispatchers.Default) { fixture.rpc.respond(id, obj("answers" to obj()), fixture.rpc.generation) } }
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
            fixture.send(request(id, "item/tool/requestUserInput"))
            withTimeout(5000) { fixture.rpc.events.receive() }
            fixture.rpc.respond(id, obj("answers" to obj()), epoch)
            assertEquals(id, fixture.reply()["id"])
            assertEquals("connection/lost", withTimeout(5000) { fixture.rpc.events.receive() }.str("method"))
            fixture.connect()
            assertThrows(ConnectionLost::class.java) { fixture.rpc.respond(id, obj(), epoch) }
            assertEquals(1, fixture.replyCount())
            fixture.send(request(id, "item/tool/requestUserInput"))
            withTimeout(5000) { fixture.rpc.events.receive() }
            fixture.rpc.respond(id, obj("answers" to obj()), fixture.rpc.generation)
            assertEquals(id, fixture.reply()["id"])
            assertEquals(2, fixture.replyCount())
        }
    }

    private class Fixture(private val onMessage: (WebSocket, JsonObject) -> Unit = { _, _ -> }) : AutoCloseable {
        val rpc = Rpc(true)
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
