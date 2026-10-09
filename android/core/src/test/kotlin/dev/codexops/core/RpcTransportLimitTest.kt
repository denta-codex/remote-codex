package dev.codexops.core

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.java_websocket.WebSocket
import org.java_websocket.enums.Opcode
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.junit.Assert.*
import org.junit.Test

class RpcTransportLimitTest {
    @Test fun productionLimitSurvivesDraftCopiesAndFileBase64Fits() {
        val draft = StatusDraft({})
        assertEquals(32 * 1024 * 1024, draft.maxFrameSize)
        assertEquals(draft.maxFrameSize, (draft.copyInstance() as StatusDraft).maxFrameSize)
        assertEquals(100 * 1024 * 1024, RPC_OUTBOUND_MAX_BYTES)
        assertTrue((20L * 1024 * 1024 + 2) / 3 * 4 + 1024 < RPC_INBOUND_MAX_BYTES)
    }

    @Test fun declaredOversizeIsRejectedFromHeaderBeforeAllocatingPayload() {
        val draft = StatusDraft({})
        val header = ByteBuffer.allocate(10).put(0x81.toByte()).put(127.toByte()).putLong(RPC_INBOUND_MAX_BYTES + 1L)
        header.flip()
        try { draft.translateFrame(header); fail("Accepted oversized frame header") }
        catch (_: org.java_websocket.exceptions.LimitExceededException) { }
    }

    @Test fun boundariesFragmentedMessagesPendingRequestsAndReconnect() = runBlocking {
        val started = CountDownLatch(1)
        val deliveries = AtomicInteger()
        val server = object : WebSocketServer(InetSocketAddress("127.0.0.1", 0), 1) {
            override fun onStart() { started.countDown() }
            override fun onOpen(socket: WebSocket, handshake: ClientHandshake) {}
            override fun onClose(socket: WebSocket, code: Int, reason: String, remote: Boolean) {}
            override fun onError(socket: WebSocket?, error: Exception) {}
            override fun onMessage(socket: WebSocket, text: String) {
                val request = wire.parseToJsonElement(text).jsonObject
                val id = request["id"] ?: return
                when (request.str("method")) {
                    "hold" -> Unit
                    "oversized", "fragmented" -> {
                        deliveries.incrementAndGet()
                        if (request.str("method") == "oversized") socket.send("x".repeat(4097))
                        else {
                            socket.sendFragmentedFrame(Opcode.TEXT, ByteBuffer.wrap(ByteArray(4096) { 120 }), false)
                            socket.sendFragmentedFrame(Opcode.TEXT, ByteBuffer.wrap(byteArrayOf(120)), false)
                        }
                    }
                    "boundary" -> {
                        val envelope = obj("id" to id, "result" to obj("text" to s(""))).toString()
                        val result = obj("id" to id, "result" to obj("text" to s("é" + "x".repeat(4096 - envelope.length - 2))))
                        assertEquals(4096, result.toString().toByteArray().size)
                        socket.send(result.toString())
                    }
                    else -> socket.send(obj("id" to id, "result" to obj()).toString())
                }
            }
        }
        server.start()
        assertTrue(started.await(5, TimeUnit.SECONDS))
        val rpc = Rpc(allowLoopbackTest = true, inboundMaxBytes = 4096)
        val url = "ws://127.0.0.1:${server.port}/codex/rpc"
        try {
            for (method in listOf("oversized", "fragmented")) {
                rpc.connect(url, "fixture")
                assertTrue(rpc.call("boundary").str("text").startsWith("é"))
                supervisorScope {
                    val held = async { runCatching { rpc.call("hold", timeoutMillis = 5000) }.exceptionOrNull() }
                    yield()
                    val failure = runCatching { rpc.call(method, timeoutMillis = 5000) }.exceptionOrNull()
                    assertTrue("$method: $failure", failure is RpcMessageTooLarge)
                    assertTrue(withTimeout(5000) { held.await() } is RpcMessageTooLarge)
                }
                val event = withTimeout(5000) { rpc.events.receive() }
                assertEquals("messageTooLarge", event.str("reason"))
            }
            rpc.connect(url, "fixture")
            assertEquals(obj(), rpc.call("healthy"))
            assertEquals(2, deliveries.get())
        } finally { rpc.dispose(); server.stop(1000) }
    }
}
