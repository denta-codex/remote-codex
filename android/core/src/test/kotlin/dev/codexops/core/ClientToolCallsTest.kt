package dev.codexops.core

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class ClientToolCallsTest {
    private val failure = obj(
        "success" to JsonPrimitive(false),
        "contentItems" to JsonArray(listOf(obj(
            "type" to s("inputText"),
            "text" to s("This client does not support client-executed tools. The tool was not executed."),
        ))),
    )

    private fun request(id: JsonElement, params: JsonElement) =
        obj("id" to id, "method" to s("item/tool/call"), "params" to params)

    @Test
    fun toolsFailBeforeEventConsumptionWithoutInspectingArguments() = runBlocking {
        MockWebServer().use { server ->
            val replies = Channel<JsonObject>(Channel.UNLIMITED)
            val count = AtomicInteger()
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(ws: WebSocket, text: String) {
                    val m = wire.parseToJsonElement(text).jsonObject
                    when (m.str("method")) {
                        "initialize" -> {
                            // A server request may collide with an outstanding client request ID.
                            val first = request(m.getValue("id"), obj("namespace" to s("other_app"), "tool" to s("read_thread"),
                                "arguments" to obj("threadId" to s("task-fixture"), "hostId" to s("local"),
                                    "turnLimit" to JsonPrimitive(20), "maxOutputCharsPerItem" to JsonPrimitive(32000))))
                            ws.send(first.toString())
                            ws.send(first.toString())
                            ws.send(request(s("arbitrary"), obj("tool" to s("anything"), "arguments" to JsonPrimitive(42))).toString())
                            ws.send(request(s("no-parameters"), JsonNull).toString())
                            ws.send(obj("id" to m["id"], "result" to obj("codexHome" to s("/test"))).toString())
                        }
                        "barrier" -> ws.send(obj("id" to m["id"], "result" to obj("count" to JsonPrimitive(count.get()))).toString())
                        "" -> { count.incrementAndGet(); replies.trySend(m) }
                    }
                }
            }))
            server.start()
            val rpc = Rpc(true)
            try {
                assertEquals("/test", rpc.connect("ws://127.0.0.1:${server.port}", "fixture").str("codexHome"))
                val known = withTimeout(5000) { replies.receive() }
                assertTrue((known["id"] as JsonPrimitive).isString.not())
                assertEquals(failure, known.map("result"))
                for (id in listOf("arbitrary", "no-parameters")) {
                    val reply = withTimeout(5000) { replies.receive() }
                    assertEquals(s(id), reply["id"])
                    assertEquals(failure, reply.map("result"))
                }
                assertEquals("3", rpc.call("barrier").str("count"))
                assertTrue(rpc.events.tryReceive().isFailure)
            } finally { rpc.dispose(); replies.close() }
        }
    }

    @Test
    fun onlyExactReadOnlyTaskToolsHaveAnAsyncRoute() {
        val params = obj("namespace" to s("codex_app"), "tool" to s("read_thread"), "arguments" to JsonPrimitive(42))
        assertEquals(ServerRequestRoute.TaskTool, ServerRequests.route("item/tool/call", params))
        assertEquals(ServerRequestRoute.TaskTool, ServerRequests.route("item/tool/call", obj("namespace" to s("codex_app"), "tool" to s("list_threads"))))
        val unsupportedParams = listOf(
            JsonNull, JsonArray(emptyList()), JsonPrimitive(42), obj(),
            obj("namespace" to s("other_app"), "tool" to s("read_thread")),
            obj("namespace" to s("codex_app"), "tool" to s("archive_thread")),
            obj("namespace" to s("codex_app"), "tool" to s("READ_THREAD")),
            obj("namespace" to JsonPrimitive(42), "tool" to s("read_thread")),
            obj("namespace" to s("codex_app"), "tool" to JsonArray(listOf(s("read_thread")))),
        )
        unsupportedParams.forEach { assertEquals(ServerRequestRoute.Result(failure), ServerRequests.route("item/tool/call", it)) }
    }

    @Test
    fun disconnectDoesNotReplayAndNewConnectionsCanReuseRequestIds() = runBlocking {
        MockWebServer().use { server ->
            val replies = Channel<JsonObject>(Channel.UNLIMITED)
            val firstCount = AtomicInteger()
            val secondCount = AtomicInteger()
            repeat(2) { connection ->
                server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onMessage(ws: WebSocket, text: String) {
                        val m = wire.parseToJsonElement(text).jsonObject
                        when (m.str("method")) {
                            "initialize" -> ws.send(obj("id" to m["id"], "result" to obj()).toString())
                            "request-tool" -> {
                                ws.send(request(s("same-id"), obj()).toString())
                                ws.send(obj("id" to m["id"], "result" to obj()).toString())
                            }
                            "barrier" -> ws.send(obj("id" to m["id"], "result" to obj("count" to JsonPrimitive(secondCount.get()))).toString())
                            "" -> {
                                (if (connection == 0) firstCount else secondCount).incrementAndGet()
                                replies.trySend(m)
                                if (connection == 0) ws.close(1000, "fixture disconnect after receipt")
                            }
                        }
                    }
                }))
            }
            server.start()
            val rpc = Rpc(true)
            try {
                val endpoint = "ws://127.0.0.1:${server.port}"
                rpc.connect(endpoint, "fixture")
                val oldEpoch = rpc.generation
                rpc.call("request-tool")
                assertEquals(failure, withTimeout(5000) { replies.receive() }.map("result"))
                assertEquals("connection/lost", withTimeout(5000) { rpc.events.receive() }.str("method"))
                rpc.connect(endpoint, "fixture")
                assertThrows(ConnectionLost::class.java) { rpc.respond(s("same-id"), failure, oldEpoch) }
                assertEquals("0", rpc.call("barrier").str("count"))
                rpc.call("request-tool")
                assertEquals(failure, withTimeout(5000) { replies.receive() }.map("result"))
                assertEquals("1", rpc.call("barrier").str("count"))
                assertEquals(1, firstCount.get())
                assertTrue(rpc.events.tryReceive().isFailure)
            } finally { rpc.dispose(); replies.close() }
        }
    }
}
