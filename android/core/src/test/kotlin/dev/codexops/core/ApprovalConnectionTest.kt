package dev.codexops.core

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class ApprovalConnectionTest {
    @Test fun rejectsInsecureProductionTransport() = runBlocking {
        val connection = ApprovalConnection()
        try { connection.connect("ws://127.0.0.1:1234/codex/rpc", "fixture"); fail("accepted insecure transport") }
        catch (_: IllegalArgumentException) { }
        finally { connection.close() }
    }

    @Test fun droppedMutationIsNotReplayedOnReconnect() = runBlocking {
        MockWebServer().use { server ->
            val releases = AtomicInteger()
            val listener = object : WebSocketListener() {
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, "") }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val request = wire.parseToJsonElement(text).jsonObject
                    if (request.str("method") == "release") {
                        releases.incrementAndGet(); webSocket.close(1000, ""); return
                    }
                    webSocket.send(obj("version" to JsonPrimitive(1), "id" to request["id"], "requests" to JsonArray(emptyList())).toString())
                }
            }
            repeat(2) { server.enqueue(MockResponse().withWebSocketUpgrade(listener)) }; server.start()
            val connection = ApprovalConnection(true)
            try {
                val endpoint = "ws://127.0.0.1:${server.port}/codex/rpc"
                connection.connect(endpoint, "fixture")
                try { connection.call("release", "test-id", "FAKE"); fail("lost response reported success") }
                catch (_: ApprovalFailure) { }
                connection.connect(endpoint, "fixture")
                connection.call("get", "test-id")
                assertEquals(1, releases.get())
                assertEquals("/remote-codex/v1/credentials", server.takeRequest().path)
            } finally { connection.close() }
        }
    }
}
