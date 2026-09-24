package dev.codexops.client

import dev.codexops.core.Rpc
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

interface RemoteSession {
    val events: ReceiveChannel<JsonObject>
    val generation: Long

    suspend fun connect(url: String, token: String): JsonObject

    suspend fun call(method: String, params: JsonObject): JsonObject

    fun respond(id: JsonElement, result: JsonObject, epoch: Long)

    fun close()

    fun dispose()
}

class StockRemoteSession(allowLoopbackTest: Boolean = false) : RemoteSession {
    private val rpc = Rpc(allowLoopbackTest)

    override val events: ReceiveChannel<JsonObject>
        get() = rpc.events

    override val generation: Long
        get() = rpc.generation

    override suspend fun connect(url: String, token: String) = rpc.connect(url, token)

    override suspend fun call(method: String, params: JsonObject) = rpc.call(method, params)

    override fun respond(id: JsonElement, result: JsonObject, epoch: Long) =
        rpc.respond(id, result, epoch)

    override fun close() = rpc.close()

    override fun dispose() = rpc.dispose()
}

interface ClientStore {
    suspend fun get(id: String): String

    suspend fun put(id: String, value: String)

    suspend fun remove(id: String)

    suspend fun token(): String

    suspend fun saveToken(value: String)
}
