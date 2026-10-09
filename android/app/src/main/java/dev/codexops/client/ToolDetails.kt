package dev.codexops.client

import android.content.Context
import dev.codexops.core.*
import java.io.File
import java.io.FilterOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.*

data class ToolDetailsPage(val text: String, val nextOffset: Int?)

/** Complete details are fetched only on request, cached privately, and displayed in small pages. */
internal class ToolDetailsStore(context: Context) {
    private val root = File(context.cacheDir, "tool-details").apply { mkdirs() }
    @OptIn(ExperimentalSerializationApi::class)
    suspend fun read(key: String, offset: Int, fetch: suspend () -> JsonObject): ToolDetailsPage {
        require(offset >= 0)
        val name = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        val target = File(root, name)
        // An explicit request for the first page refreshes the server snapshot.
        if (offset == 0) withContext(Dispatchers.IO) { target.delete() }
        if (!target.isFile) {
            val item = fetch()
            withContext(Dispatchers.IO) {
                val part = File.createTempFile(".details-", ".part", root)
                try {
                    part.outputStream().use { stream ->
                        val bounded = object : FilterOutputStream(stream) {
                            var bytes = 0L
                            override fun write(value: Int) { check(++bytes <= RPC_INBOUND_MAX_BYTES); out.write(value) }
                            override fun write(value: ByteArray, start: Int, length: Int) {
                                bytes += length; check(bytes <= RPC_INBOUND_MAX_BYTES); out.write(value, start, length)
                            }
                        }
                        Json { prettyPrint = true }.encodeToStream(JsonObject.serializer(), item, bounded)
                    }
                    check(part.renameTo(target)) { "Complete details could not be cached" }
                    var total = root.listFiles().orEmpty().sumOf(File::length)
                    root.listFiles().orEmpty().filter { it != target && !it.name.startsWith('.') }.sortedBy(File::lastModified).forEach {
                        val bytes = it.length()
                        if (total > 64L * 1024 * 1024 && it.delete()) total -= bytes
                    }
                } finally { part.delete() }
            }
        }
        return withContext(Dispatchers.IO) {
            target.setLastModified(System.currentTimeMillis())
            target.reader().buffered().use { reader ->
                var skipped = 0L
                while (skipped < offset) {
                    val count = reader.skip(offset - skipped)
                    if (count == 0L) error("Details page is no longer available")
                    skipped += count
                }
                val chars = CharArray(TOOL_PREVIEW_CHARS)
                var size = 0
                while (size < chars.size) {
                    val count = reader.read(chars, size, chars.size - size)
                    if (count < 0) break
                    size += count
                }
                val splitSurrogate = size == chars.size && chars[size - 1].isHighSurrogate()
                if (splitSurrogate) size--
                ToolDetailsPage(String(chars, 0, size), if (!splitSurrogate && reader.read() < 0) null else offset + size)
            }
        }
    }
}
