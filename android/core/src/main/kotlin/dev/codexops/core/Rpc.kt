package dev.codexops.core

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import okhttp3.*

val wire = Json { ignoreUnknownKeys = true }

fun obj(vararg entries: Pair<String, JsonElement?>) =
    JsonObject(entries.filter { it.second != null }.associate { it.first to it.second!! })

fun s(value: String) = JsonPrimitive(value)

fun JsonObject.str(key: String): String = (get(key) as? JsonPrimitive)?.contentOrNull ?: ""

fun JsonObject.map(key: String): JsonObject = get(key) as? JsonObject ?: obj()

fun JsonObject.list(key: String): List<JsonObject> =
    (get(key) as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()

fun JsonObject.cursor(): String? = str("nextCursor").ifEmpty { null }

class RpcRejected(val code: Int, message: String) : Exception(message)

class ConnectionLost : Exception("Connection lost; a submitted operation may have been accepted")

class Rpc(private val allowLoopbackTest: Boolean = false) {
    val events = Channel<JsonObject>(1024)
    private val client =
        OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .build()
    private val next = AtomicLong()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
    @Volatile private var socket: WebSocket? = null
    private var opened = CompletableDeferred<Unit>()
    private val guard = Any()
    @Volatile
    var generation: Long = 0
        private set

    suspend fun connect(url: String, token: String): JsonObject {
        require(
            url.startsWith("wss://") || allowLoopbackTest && url.startsWith("ws://127.0.0.1:")
        ) {
            "A secure WSS endpoint is required"
        }
        close()
        val epoch = generation
        opened = CompletableDeferred()
        val ready = opened
        val request = Request.Builder().url(url).header("Authorization", "Bearer $token").build()
        socket =
            client.newWebSocket(
                request,
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        if (generation == epoch) ready.complete(Unit) else webSocket.cancel()
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        if (generation != epoch) return
                        try {
                            val message = wire.parseToJsonElement(text).jsonObject
                            // Request IDs are independent in the two directions.
                            if (message.str("method").isNotEmpty()) {
                                if (
                                    !events
                                        .trySend(
                                            JsonObject(message + ("_epoch" to JsonPrimitive(epoch)))
                                        )
                                        .isSuccess
                                )
                                    failed(epoch)
                            } else {
                                val waiter = pending.remove(message["id"].toString()) ?: return
                                val error = message["error"] as? JsonObject
                                if (error != null)
                                    waiter.completeExceptionally(
                                        RpcRejected(
                                            error.str("code").toIntOrNull() ?: -1,
                                            error.str("message"),
                                        )
                                    )
                                else waiter.complete(message["result"] as? JsonObject ?: obj())
                            }
                        } catch (_: Exception) {
                            failed(epoch)
                        }
                    }

                    override fun onFailure(
                        webSocket: WebSocket,
                        t: Throwable,
                        response: Response?,
                    ) {
                        failed(epoch)
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        failed(epoch)
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(code, null)
                        failed(epoch)
                    }
                },
            )
        try {
            withTimeout(15000) { ready.await() }
            val result =
                call(
                    "initialize",
                    obj(
                        "clientInfo" to obj("name" to s("remote-codex"), "version" to s("0.1.0")),
                        "capabilities" to obj("experimentalApi" to JsonPrimitive(true)),
                    ),
                )
            send(obj("method" to s("initialized"), "params" to obj()))
            return result
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    suspend fun call(method: String, params: JsonObject = obj()): JsonObject {
        val id = next.incrementAndGet()
        val waiter = CompletableDeferred<JsonObject>()
        synchronized(guard) {
            pending[id.toString()] = waiter
            try {
                send(obj("id" to JsonPrimitive(id), "method" to s(method), "params" to params))
            } catch (e: Exception) {
                pending.remove(id.toString())
                throw e
            }
        }
        return try {
            withTimeout(30000) { waiter.await() }
        } finally {
            pending.remove(id.toString())
        }
    }

    fun respond(id: JsonElement, result: JsonObject, epoch: Long) {
        synchronized(guard) {
            if (generation != epoch) throw ConnectionLost()
            send(obj("id" to id, "result" to result))
        }
    }

    private fun send(message: JsonObject) {
        if (socket?.send(message.toString()) != true) throw ConnectionLost()
    }

    private fun failed(epoch: Long) {
        synchronized(guard) {
            if (generation != epoch) return
            close()
            events.trySend(obj("method" to s("connection/lost")))
        }
    }

    fun close() {
        synchronized(guard) {
            generation++
            socket?.cancel()
            socket = null
            opened.completeExceptionally(ConnectionLost())
            pending.values.forEach { it.completeExceptionally(ConnectionLost()) }
            pending.clear()
        }
    }

    fun dispose() {
        close()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        events.close()
    }
}
