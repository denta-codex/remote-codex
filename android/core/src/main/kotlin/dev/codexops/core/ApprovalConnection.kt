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

/** A prepared mutation holds transient transport text and can be consumed once. */
class ApprovalSubmission internal constructor(internal val id: String, private var payload: String?) {
    @Synchronized internal fun take(): String = payload?.also { payload = null } ?: throw ApprovalFailure("already_submitted")
    @Synchronized fun discard() { payload = null }
}

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

    fun prepare(method: String, requestId: String = "", value: String? = null, values: JsonArray? = null): ApprovalSubmission {
        val id = sequence.incrementAndGet().toString()
        return ApprovalSubmission(id, encode(id, method, requestId, value, values))
    }

    fun releaseFits(requestId: String, value: String? = null, values: JsonArray? = null): Boolean = try {
        encode((sequence.get() + 1).toString(), if (values == null) "release" else "release_batch", requestId, value, values)
        true
    } catch (_: ApprovalFailure) { false }

    private fun encode(id: String, method: String, requestId: String, value: String?, values: JsonArray?): String {
        if (value != null && !approvalValueFits(value)) throw ApprovalFailure("invalid_value")
        if (values != null && values.any { entry ->
                val field = entry as? JsonObject
                val selected = field?.get("value") as? JsonPrimitive
                selected?.isString != true || !approvalValueFits(selected.content)
            }) throw ApprovalFailure("invalid_value")
        val encoded = obj("version" to JsonPrimitive(1), "id" to s(id), "method" to s(method),
            "request_id" to requestId.takeIf { it.isNotEmpty() }?.let(::s), "value" to value?.let(::s), "values" to values,
            "capabilities" to if (method == "list") JsonArray(listOf(s(APPROVAL_BATCH_CAPABILITY))) else null).toString()
        if (encoded.toByteArray(Charsets.UTF_8).size > APPROVAL_WIRE_LIMIT) throw ApprovalFailure("request_limit")
        return encoded
    }

    suspend fun call(method: String, requestId: String = "", value: String? = null): JsonObject = send(prepare(method, requestId, value))

    suspend fun send(submission: ApprovalSubmission): JsonObject {
        // An unavailable connection discards the payload too: a submitted
        // mutation never becomes an automatic retry buffer.
        val current = socket ?: run { submission.discard(); throw ApprovalFailure("disconnected") }
        if (!current.isOpen) { submission.discard(); throw ApprovalFailure("disconnected") }
        val encoded = submission.take()
        val id = submission.id
        val waiter = CompletableDeferred<JsonObject>()
        pending[id] = waiter
        try {
            current.send(encoded)
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
