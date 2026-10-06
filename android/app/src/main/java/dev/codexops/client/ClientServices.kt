package dev.codexops.client

import dev.codexops.core.*
import java.util.Base64
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

interface RemoteSession {
    val events: ReceiveChannel<JsonObject>
    val generation: Long

    suspend fun connect(url: String, token: String): JsonObject

    suspend fun call(method: String, params: JsonObject): JsonObject

    suspend fun callWithTimeout(method: String, params: JsonObject, timeoutMillis: Long): JsonObject =
        call(method, params)

    suspend fun callForGeneration(method: String, params: JsonObject, epoch: Long): JsonObject {
        if (generation != epoch) throw ConnectionLost()
        return call(method, params)
    }

    suspend fun callForGenerationWithTimeout(method: String, params: JsonObject, epoch: Long, timeoutMillis: Long): JsonObject {
        if (generation != epoch) throw ConnectionLost()
        val result = callWithTimeout(method, params, timeoutMillis)
        if (generation != epoch) throw ConnectionLost()
        return result
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
            obj("path" to s(path), "dataBase64" to s(Base64.getEncoder().encodeToString(bytes))),
        )
    }

    suspend fun readFile(path: String): ByteArray =
        Base64.getDecoder().decode(call("fs/readFile", obj("path" to s(path))).str("dataBase64"))

    suspend fun getMetadata(path: String): JsonObject =
        call("fs/getMetadata", obj("path" to s(path)))

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

    override suspend fun callWithTimeout(method: String, params: JsonObject, timeoutMillis: Long) =
        rpc.call(method, params, timeoutMillis)

    override suspend fun callForGeneration(method: String, params: JsonObject, epoch: Long) =
        rpc.call(method, params, expectedGeneration = epoch)

    override suspend fun callForGenerationWithTimeout(method: String, params: JsonObject, epoch: Long, timeoutMillis: Long) =
        rpc.call(method, params, timeoutMillis, expectedGeneration = epoch)

    override suspend fun createDirectory(path: String) = rpc.createDirectory(path)

    override suspend fun writeFile(path: String, bytes: ByteArray) = rpc.writeFile(path, bytes)

    override suspend fun readFile(path: String) = rpc.readFile(path)

    override suspend fun getMetadata(path: String) =
        rpc.call("fs/getMetadata", obj("path" to s(path)))

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
