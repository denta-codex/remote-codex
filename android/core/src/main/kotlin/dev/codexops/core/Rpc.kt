package dev.codexops.core

import java.net.URI
import java.nio.ByteBuffer
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import org.java_websocket.WebSocket
import org.java_websocket.client.WebSocketClient
import org.java_websocket.drafts.Draft
import org.java_websocket.drafts.Draft_6455
import org.java_websocket.enums.HandshakeState
import org.java_websocket.enums.Opcode
import org.java_websocket.extensions.IExtension
import org.java_websocket.framing.CloseFrame
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.handshake.ServerHandshake

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

/** Only transport metadata; never include headers, response bodies, or exception messages. */
class ConnectionFailure(val httpStatus: Int?, val transport: String) :
    Exception("Connection failed: ${httpStatus ?: transport}")

private const val CONNECT_TIMEOUT_MS = 10_000
private const val RPC_MESSAGE_MAX_BYTES = 100 * 1024 * 1024
private const val OUTBOUND_FRAGMENT_BYTES = 256 * 1024
private val rejectedStatus =
    Regex("^Invalid status code received: ([0-9]{3}) Status line: HTTP/1\\.[01] ([0-9]{3})(?: .*)?$")

private fun rejectedHttpStatus(message: String?): Int? {
    val match = message?.let(rejectedStatus::matchEntire) ?: return null
    val reported = match.groupValues[1].toIntOrNull() ?: return null
    val statusLine = match.groupValues[2].toIntOrNull() ?: return null
    return reported.takeIf { it == statusLine && it in 100..599 }
}

private class StatusDraft(private val status: (Int) -> Unit) :
    Draft_6455(emptyList<IExtension>(), RPC_MESSAGE_MAX_BYTES) {
    override fun acceptHandshakeAsClient(
        request: ClientHandshake,
        response: ServerHandshake,
    ): HandshakeState {
        status(response.httpStatus.toInt())
        return super.acceptHandshakeAsClient(request, response)
    }

    override fun copyInstance(): Draft = StatusDraft(status)
}

class Rpc(
    private val allowLoopbackTest: Boolean = false,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    val events = Channel<JsonObject>(1024)
    private val next = AtomicLong()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
    private enum class ServerRequestState { Pending, Terminal }
    // IDs are retained until disconnect, including resolved and attempted replies.
    private val serverRequests = mutableMapOf<JsonElement, ServerRequestState>()
    @Volatile private var socket: WebSocketClient? = null
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
        var handshakeStatus: Int? = null
        val webSocket =
            object :
                WebSocketClient(
                    URI(url),
                    StatusDraft { handshakeStatus = it },
                    mapOf("Authorization" to "Bearer $token"),
                    CONNECT_TIMEOUT_MS,
                ) {
                override fun onWebsocketHandshakeReceivedAsClient(
                    connection: WebSocket,
                    request: ClientHandshake,
                    response: ServerHandshake,
                ) {
                    handshakeStatus = response.httpStatus.toInt()
                    super.onWebsocketHandshakeReceivedAsClient(connection, request, response)
                }

                override fun onOpen(handshake: ServerHandshake) {
                    if (generation == epoch) ready.complete(Unit)
                    else closeConnection(CloseFrame.NORMAL, "")
                }

                override fun onMessage(text: String) {
                    if (generation != epoch || text.toByteArray(Charsets.UTF_8).size > RPC_MESSAGE_MAX_BYTES)
                        return failed(epoch)
                    try {
                        val message = wire.parseToJsonElement(text).jsonObject
                        // Request IDs are independent in the two directions.
                        if (message.str("method").isNotEmpty()) {
                            dispatch(message, epoch)
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

                override fun onMessage(bytes: ByteBuffer) {
                    failed(epoch)
                }

                override fun onClose(code: Int, reason: String, remote: Boolean) {
                    handshakeStatus = handshakeStatus ?: rejectedHttpStatus(reason)
                    if (!ready.isCompleted)
                        ready.completeExceptionally(ConnectionFailure(handshakeStatus, "Closed"))
                    failed(epoch)
                }

                override fun onError(error: Exception) {
                    if (generation != epoch) return
                    handshakeStatus = handshakeStatus ?: rejectedHttpStatus(error.message)
                    ready.completeExceptionally(
                        ConnectionFailure(handshakeStatus, error.javaClass.simpleName)
                    )
                    failed(epoch)
                }
            }
        webSocket.connectionLostTimeout = 20
        socket = webSocket
        webSocket.connect()
        try {
            withTimeout(15000) { ready.await() }
            val result =
                call(
                    "initialize",
                    obj(
                        "clientInfo" to obj("name" to s("remote-codex"), "version" to s("0.1.8")),
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

    suspend fun call(
        method: String,
        params: JsonObject = obj(),
        timeoutMillis: Long = 30000,
    ): JsonObject {
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
            withTimeout(timeoutMillis) { waiter.await() }
        } finally {
            pending.remove(id.toString())
        }
    }

    suspend fun createDirectory(path: String) {
        call(
            "fs/createDirectory",
            obj("path" to s(path), "recursive" to JsonPrimitive(true)),
        )
    }

    suspend fun writeFile(path: String, bytes: ByteArray) {
        call(
            "fs/writeFile",
            obj(
                "path" to s(path),
                "dataBase64" to s(Base64.getEncoder().encodeToString(bytes)),
            ),
            120000,
        )
    }

    suspend fun readFile(path: String): ByteArray {
        val result = call("fs/readFile", obj("path" to s(path)), 60000)
        return Base64.getDecoder().decode(result.str("dataBase64"))
    }

    fun respond(id: JsonElement, result: JsonObject, epoch: Long) {
        respondOnce(id, obj("id" to id, "result" to result), epoch)
    }

    fun respondError(id: JsonElement, code: Int, message: String, epoch: Long) {
        respondOnce(id, obj("id" to id, "error" to obj(
            "code" to JsonPrimitive(code), "message" to s(message),
        )), epoch)
    }

    private fun dispatch(message: JsonObject, epoch: Long) {
        synchronized(guard) {
            if (generation != epoch) return
            if (message.containsKey("id")) {
                val id = message.getValue("id")
                require(id is JsonPrimitive && (id.isString || id.longOrNull != null))
                if (serverRequests.containsKey(id)) return
                serverRequests[id] = ServerRequestState.Pending
                when (val route = ServerRequests.route(message.str("method"), clockMillis)) {
                    ServerRequestRoute.Interactive -> Unit
                    is ServerRequestRoute.Result -> {
                        respond(id, route.result, epoch)
                        return
                    }
                    is ServerRequestRoute.Error -> {
                        respondError(id, route.code, route.message, epoch)
                        return
                    }
                }
            } else if (message.str("method") == "serverRequest/resolved") {
                message.map("params")["requestId"]?.let {
                    serverRequests[it] = ServerRequestState.Terminal
                }
            }
            if (!events.trySend(JsonObject(message + ("_epoch" to JsonPrimitive(epoch)))).isSuccess)
                failed(epoch)
        }
    }

    private fun respondOnce(id: JsonElement, message: JsonObject, epoch: Long) {
        synchronized(guard) {
            if (generation != epoch) throw ConnectionLost()
            if (serverRequests[id] != ServerRequestState.Pending) return
            // Reserve before sending, including fragmented writes with uncertain delivery.
            serverRequests[id] = ServerRequestState.Terminal
            try {
                send(message)
            } catch (e: Exception) {
                failed(epoch)
                throw e
            }
        }
    }

    private fun send(message: JsonObject) {
        val active = socket
        if (active?.isOpen != true) throw ConnectionLost()
        val bytes = message.toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > RPC_MESSAGE_MAX_BYTES) throw IllegalArgumentException("RPC message too large")
        try {
            var start = 0
            while (start < bytes.size) {
                var end = minOf(start + OUTBOUND_FRAGMENT_BYTES, bytes.size)
                if (end < bytes.size) {
                    while (end > start && bytes[end].toInt() and 0xC0 == 0x80) end--
                }
                check(end > start)
                active.sendFragmentedFrame(
                    Opcode.TEXT,
                    ByteBuffer.wrap(bytes, start, end - start),
                    end == bytes.size,
                )
                start = end
            }
        } catch (_: Exception) {
            throw ConnectionLost()
        }
    }

    private fun failed(epoch: Long) {
        synchronized(guard) {
            if (generation != epoch) return
            close()
            events.trySend(obj("method" to s("connection/lost"), "_epoch" to JsonPrimitive(generation)))
        }
    }

    fun close() {
        synchronized(guard) {
            generation++
            socket?.closeConnection(CloseFrame.NORMAL, "")
            socket = null
            opened.completeExceptionally(ConnectionLost())
            pending.values.forEach { it.completeExceptionally(ConnectionLost()) }
            pending.clear()
            serverRequests.clear()
        }
    }

    fun dispose() {
        close()
        events.close()
    }
}
