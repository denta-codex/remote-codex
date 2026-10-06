package dev.codexops.core

import java.net.URI
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.java_websocket.client.WebSocketClient
import org.java_websocket.drafts.Draft_6455
import org.java_websocket.extensions.IExtension
import org.java_websocket.handshake.ServerHandshake

fun interface TodoRpc {
    suspend fun call(method: String, params: JsonObject): JsonObject
}
class TodoRpcRejected(val code: Int, val kind: String) : Exception("Todo request rejected")
class TodoConnectionFailure : Exception("Todo connection unavailable; check the service and refresh")

/** Application protocol, independent of stock initialization. Never replays requests. */
class TodoConnection(private val allowLoopbackTest: Boolean = false) : TodoRpc {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
    private val sequence = AtomicLong()
    private val connectLock = Mutex()
    private val available = MutableStateFlow(false)
    val connected = available.asStateFlow()
    @Volatile private var socket: WebSocketClient? = null
    @Volatile private var epoch = 0L

    suspend fun connect(endpoint: String, token: String) = connectLock.withLock {
        if (available.value && socket?.isOpen == true) return@withLock
        close()
        val uri = URI(endpoint)
        require(uri.scheme == "wss" || allowLoopbackTest && uri.scheme == "ws" && uri.host == "127.0.0.1")
        require(uri.userInfo == null && uri.query == null && uri.fragment == null && token.isNotEmpty())
        val url = URI(uri.scheme, null, uri.host, uri.port, "/remote-codex/v1/todo", null, null)
        val ready = CompletableDeferred<Unit>()
        val generation = epoch
        val client = object : WebSocketClient(url, Draft_6455(emptyList<IExtension>(), LIMIT), mapOf("Authorization" to "Bearer $token"), 10000) {
            override fun onOpen(handshake: ServerHandshake) {
                if (epoch == generation) { available.value = true; ready.complete(Unit) } else close()
            }
            override fun onMessage(message: String) {
                if (epoch != generation) return
                try {
                    require(message.toByteArray(Charsets.UTF_8).size <= LIMIT)
                    val value = wire.parseToJsonElement(message).jsonObject
                    require(value["jsonrpc"] == s("2.0"))
                    val id = value["id"] as? JsonPrimitive
                    require(id?.isString == true)
                    require(value.containsKey("result") != value.containsKey("error"))
                    if (value.containsKey("error")) {
                        val error = value.getValue("error").jsonObject
                        val code = error.getValue("code").jsonPrimitive.int
                        val kind = error.getValue("data").jsonObject.getValue("code").jsonPrimitive.content
                        pending.remove(id!!.content)?.completeExceptionally(TodoRpcRejected(code, kind))
                    } else pending.remove(id!!.content)?.complete(value.getValue("result").jsonObject)
                } catch (_: Exception) { fail(generation); close() }
            }
            override fun onMessage(bytes: ByteBuffer) { fail(generation); close() }
            override fun onClose(code: Int, reason: String, remote: Boolean) {
                if (epoch != generation) return
                ready.completeExceptionally(TodoConnectionFailure()); fail(generation)
            }
            override fun onError(error: Exception) {
                if (epoch != generation) return
                ready.completeExceptionally(TodoConnectionFailure()); fail(generation)
                closeConnection(1000, "")
            }
        }
        socket = client
        try { client.connect(); withTimeout(12000) { ready.await() } }
        catch (error: Exception) { close(); throw error }
    }

    override suspend fun call(method: String, params: JsonObject): JsonObject {
        val generation = epoch
        val current = socket ?: throw TodoConnectionFailure()
        if (!current.isOpen) throw TodoConnectionFailure()
        val id = sequence.incrementAndGet().toString()
        val encoded = obj("jsonrpc" to s("2.0"), "id" to s(id), "method" to s(method), "params" to params).toString()
        require(encoded.toByteArray(Charsets.UTF_8).size <= LIMIT)
        val waiter = CompletableDeferred<JsonObject>()
        pending[id] = waiter
        try {
            if (epoch != generation) throw TodoConnectionFailure()
            current.send(encoded)
            val result = withTimeout(15000) { waiter.await() }
            if (epoch != generation) throw TodoConnectionFailure()
            return result
        } finally { pending.remove(id) }
    }

    private fun fail(generation: Long) {
        if (epoch != generation) return
        available.value = false
        pending.values.forEach { it.completeExceptionally(TodoConnectionFailure()) }
        pending.clear()
    }
    fun close() {
        fail(epoch)
        epoch++
        socket?.closeConnection(1000, "")
        socket = null
    }
    private companion object { const val LIMIT = 1024 * 1024 }
}
