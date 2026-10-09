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

open class ConnectionLost(message: String = "Connection lost; a submitted operation may have been accepted") : Exception(message)

class RpcMessageTooLarge : ConnectionLost("The incoming message exceeded the 32 MiB limit. Loaded content and drafts are retained. A submitted operation may have been accepted; it was not replayed.")

/** Only transport metadata; never include headers, response bodies, or exception messages. */
class ConnectionFailure(val httpStatus: Int?, val transport: String) :
    Exception("Connection failed: ${httpStatus ?: transport}")

private const val CONNECT_TIMEOUT_MS = 10_000
const val RPC_INBOUND_MAX_BYTES = 32 * 1024 * 1024
const val RPC_OUTBOUND_MAX_BYTES = 100 * 1024 * 1024

/** Count UTF-8 bytes without allocating a second copy of a large RPC frame. */
internal fun exceedsUtf8Limit(text: String, limit: Int): Boolean {
    var bytes = 0L
    var index = 0
    while (index < text.length) {
        val ch = text[index++]
        bytes += when {
            ch.code < 0x80 -> 1
            ch.code < 0x800 -> 2
            ch.isHighSurrogate() && index < text.length && text[index].isLowSurrogate() -> {
                index++
                4
            }
            ch.isSurrogate() -> 1 // JVM UTF-8 encoder replacement for malformed input.
            else -> 3
        }
        if (bytes > limit) return true
    }
    return false
}
private const val OUTBOUND_FRAGMENT_BYTES = 256 * 1024
private val rejectedStatus =
    Regex("^Invalid status code received: ([0-9]{3}) Status line: HTTP/1\\.[01] ([0-9]{3})(?: .*)?$")

private fun rejectedHttpStatus(message: String?): Int? {
    val match = message?.let(rejectedStatus::matchEntire) ?: return null
    val reported = match.groupValues[1].toIntOrNull() ?: return null
    val statusLine = match.groupValues[2].toIntOrNull() ?: return null
    return reported.takeIf { it == statusLine && it in 100..599 }
}

internal class StatusDraft(private val status: (Int) -> Unit, private val limit: Int = RPC_INBOUND_MAX_BYTES) :
    Draft_6455(emptyList<IExtension>(), limit) {
    private var messageBytes = 0L

    override fun processFrame(connection: org.java_websocket.WebSocketImpl, frame: org.java_websocket.framing.Framedata) {
        // Draft_6455 checks an accumulated message at FIN. Enforce the ceiling on
        // every continuation, including a peer that never sends a final frame.
        val data = frame.opcode in setOf(Opcode.TEXT, Opcode.BINARY, Opcode.CONTINUOUS)
        if (data) {
            if (frame.opcode != Opcode.CONTINUOUS) messageBytes = 0
            messageBytes += frame.payloadData.remaining()
            if (messageBytes > limit) throw org.java_websocket.exceptions.LimitExceededException(limit)
        }
        super.processFrame(connection, frame)
        if (data && frame.isFin) messageBytes = 0
    }

    override fun reset() { super.reset(); messageBytes = 0 }
    override fun acceptHandshakeAsClient(
        request: ClientHandshake,
        response: ServerHandshake,
    ): HandshakeState {
        status(response.httpStatus.toInt())
        return super.acceptHandshakeAsClient(request, response)
    }

    override fun copyInstance(): Draft = StatusDraft(status, limit)
}

class Rpc(
    private val allowLoopbackTest: Boolean = false,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val inboundMaxBytes: Int = RPC_INBOUND_MAX_BYTES,
) {
    init { require(inboundMaxBytes == RPC_INBOUND_MAX_BYTES || allowLoopbackTest) }
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
                    StatusDraft({ handshakeStatus = it }, inboundMaxBytes),
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
                    if (generation != epoch) return
                    if (exceedsUtf8Limit(text, inboundMaxBytes))
                        return failed(epoch, RpcMessageTooLarge())
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

                override fun onClose(code: Int, reason: String?, remote: Boolean) {
                    if (code == CloseFrame.TOOBIG) return failed(epoch, RpcMessageTooLarge())
                    handshakeStatus = handshakeStatus ?: rejectedHttpStatus(reason)
                    if (!ready.isCompleted)
                        ready.completeExceptionally(ConnectionFailure(handshakeStatus, "Closed"))
                    failed(epoch)
                }

                override fun onClosing(code: Int, reason: String?, remote: Boolean) {
                    // Preserve a local size rejection before the peer's close acknowledgement.
                    if (code == CloseFrame.TOOBIG) failed(epoch, RpcMessageTooLarge())
                }

                override fun onError(error: Exception) {
                    if (generation != epoch) return
                    if (error is org.java_websocket.exceptions.LimitExceededException)
                        return failed(epoch, RpcMessageTooLarge())
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
        expectedGeneration: Long? = null,
    ): JsonObject {
        val id = next.incrementAndGet()
        val waiter = CompletableDeferred<JsonObject>()
        val context = currentCoroutineContext()
        synchronized(guard) {
            context.ensureActive()
            if (expectedGeneration != null && generation != expectedGeneration) throw ConnectionLost()
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
                when (val route = ServerRequests.route(message.str("method"), message["params"], clockMillis)) {
                    ServerRequestRoute.Interactive, ServerRequestRoute.TaskTool -> Unit
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
        if (bytes.size > RPC_OUTBOUND_MAX_BYTES) throw IllegalArgumentException("RPC message too large")
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

    private fun failed(epoch: Long, cause: ConnectionLost = ConnectionLost()) {
        synchronized(guard) {
            if (generation != epoch) return
            close(cause)
            events.trySend(obj("method" to s("connection/lost"), "_epoch" to JsonPrimitive(generation),
                "reason" to s(if (cause is RpcMessageTooLarge) "messageTooLarge" else "connectionLost")))
        }
    }

    fun close(cause: ConnectionLost = ConnectionLost()) {
        synchronized(guard) {
            generation++
            socket?.closeConnection(CloseFrame.NORMAL, "")
            socket = null
            opened.completeExceptionally(cause)
            pending.values.forEach { it.completeExceptionally(cause) }
            pending.clear()
            serverRequests.clear()
        }
    }

    fun dispose() {
        close()
        events.close()
    }
}
