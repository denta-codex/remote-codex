package dev.codexops.core

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class TodoConnectionTest {
    @Test fun connectsWithoutStockInitializationAndCorrelatesStructuredResults() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val request = wire.parseToJsonElement(text).jsonObject
                    assertEquals("todo/list", request.str("method"))
                    webSocket.send(obj("jsonrpc" to s("2.0"), "id" to request["id"], "result" to obj("tasks" to JsonArray(emptyList()))).toString())
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, "") }
            }))
            server.start()
            val connection = TodoConnection(true)
            try {
                connection.connect("ws://127.0.0.1:${server.port}/codex/rpc", "fixture")
                assertTrue(connection.connected.value)
                assertEquals(JsonArray(emptyList()), connection.call("todo/list", obj())["tasks"])
                val request = server.takeRequest()
                assertEquals("/remote-codex/v1/todo", request.path)
                assertEquals("Bearer fixture", request.getHeader("Authorization"))
            } finally { connection.close() }
            assertFalse(connection.connected.value)
        }
    }

    @Test fun lostMutationReplyNeverReplaysAndReconnectOnlyReads() = runBlocking {
        MockWebServer().use { server ->
            val writes = AtomicInteger()
            val listener = object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val request = wire.parseToJsonElement(text).jsonObject
                    if (request.str("method") == "todo/create") {
                        writes.incrementAndGet(); webSocket.close(1000, "")
                    } else webSocket.send(obj("jsonrpc" to s("2.0"), "id" to request["id"], "result" to obj("tasks" to JsonArray(emptyList()))).toString())
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, "") }
            }
            repeat(2) { server.enqueue(MockResponse().withWebSocketUpgrade(listener)) }; server.start()
            val connection = TodoConnection(true)
            try {
                val endpoint = "ws://127.0.0.1:${server.port}/codex/rpc"
                connection.connect(endpoint, "fixture")
                assertTrue(runCatching { connection.call("todo/create", obj("title" to s("Idea"))) }.isFailure)
                connection.connect(endpoint, "fixture")
                connection.call("todo/list", obj())
                assertEquals(1, writes.get())
            } finally { connection.close() }
        }
    }

    @Test fun rejectedUpgradeAndMalformedReplyRemainFailures() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) { webSocket.send("{bad") }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, "") }
            }))
            server.start()
            val connection = TodoConnection(true)
            try {
                val endpoint = "ws://127.0.0.1:${server.port}/codex/rpc"
                assertTrue(runCatching { connection.connect(endpoint, "fixture") }.isFailure)
                assertFalse(connection.connected.value)
                connection.connect(endpoint, "fixture")
                assertTrue(runCatching { connection.call("todo/create", obj()) }.isFailure)
            } finally { connection.close() }
        }
    }
}
