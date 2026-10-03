package dev.codexops.core

import java.net.URI
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.java_websocket.client.WebSocketClient
import org.java_websocket.drafts.Draft_6455
import org.java_websocket.extensions.IExtension
import org.java_websocket.handshake.ServerHandshake

class ApprovalFailure(val kind: String) : Exception("Credential approval connection failed")

/** Separate protocol: never initializes a stock Codex session and never retries a mutation. */
class ApprovalConnection(private val allowLoopbackTest: Boolean = false) {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
    private val sequence = AtomicLong()
    @Volatile private var socket: WebSocketClient? = null
    @Volatile private var epoch = 0L
    val connected: Boolean get() = socket?.isOpen == true

    suspend fun connect(endpoint: String, token: String) {
        close()
        val uri = URI(endpoint)
        require(uri.scheme == "wss" || allowLoopbackTest && uri.scheme == "ws" && uri.host == "127.0.0.1")
        require(uri.userInfo == null && uri.query == null && uri.fragment == null && token.isNotEmpty())
        val url = URI(uri.scheme, null, uri.host, uri.port, "/remote-codex/v1/credentials", null, null)
        val ready = CompletableDeferred<Unit>()
        val generation = epoch
        val client = object : WebSocketClient(url, Draft_6455(emptyList<IExtension>(), 1024 * 1024), mapOf("Authorization" to "Bearer $token"), 10000) {
            override fun onOpen(handshake: ServerHandshake) { if (epoch == generation) ready.complete(Unit) else close() }
            override fun onMessage(message: String) {
                if (epoch != generation) return
                try {
                    if (message.toByteArray().size > 1024 * 1024) throw ApprovalFailure("invalid_response")
                    val value = wire.parseToJsonElement(message).jsonObject
                    if (value.str("version") != "1") throw ApprovalFailure("invalid_response")
                    pending.remove(value.str("id"))?.complete(value)
                } catch (_: Exception) { fail(generation); close() }
            }
            override fun onMessage(bytes: ByteBuffer) { fail(generation); close() }
            override fun onClose(code: Int, reason: String, remote: Boolean) {
                if (epoch != generation) return
                val kind = if (reason.contains("503")) "no_session" else if (reason.contains("401")) "unauthorized" else "disconnected"
                ready.completeExceptionally(ApprovalFailure(kind)); fail(generation)
            }
            override fun onError(error: Exception) {
                if (epoch != generation) return
                ready.completeExceptionally(ApprovalFailure("disconnected")); fail(generation)
            }
        }
        socket = client
        try { client.connect(); withTimeout(12000) { ready.await() } }
        catch (error: Exception) { close(); throw error }
    }

    suspend fun call(method: String, requestId: String = "", value: String? = null): JsonObject {
        val current = socket ?: throw ApprovalFailure("disconnected")
        if (!current.isOpen) throw ApprovalFailure("disconnected")
        val id = sequence.incrementAndGet().toString()
        val waiter = CompletableDeferred<JsonObject>()
        pending[id] = waiter
        try {
            current.send(obj("version" to JsonPrimitive(1), "id" to s(id), "method" to s(method),
                "request_id" to requestId.takeIf { it.isNotEmpty() }?.let(::s), "value" to value?.let(::s)).toString())
            return withTimeout(10000) { waiter.await() }
        } finally { pending.remove(id) }
    }

    private fun fail(generation: Long) {
        if (epoch != generation) return
        pending.values.forEach { it.completeExceptionally(ApprovalFailure("disconnected")) }
        pending.clear()
    }
    fun close() {
        fail(epoch)
        epoch++
        socket?.close()
        socket = null
    }
}
