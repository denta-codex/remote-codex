package dev.codexops.client

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import dev.codexops.core.AttachmentPolicy
import dev.codexops.core.FileRef
import java.io.File
import java.net.URI
import java.net.URLConnection
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Paths
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

enum class FilePreviewKind {
    IMAGE,
    TEXT,
    GENERIC,
}

data class FilePreviewState(
    val reference: FileRef,
    val loading: Boolean = true,
    val error: String? = null,
    val localPath: String? = null,
    val contentUri: String? = null,
    val mimeType: String = "application/octet-stream",
    val byteSize: Long? = null,
    val kind: FilePreviewKind = FilePreviewKind.GENERIC,
    val text: String? = null,
    val truncated: Boolean = false,
)

class RemoteFileRepository(private val context: Context) {
    private val root = File(context.cacheDir, "remote-files").apply { mkdirs() }
    private val maxCacheBytes = 64L * 1024 * 1024
    private val maxAgeMillis = 7L * 24 * 60 * 60 * 1000

    fun resolve(reference: FileRef, cwd: String?): FileRef {
        val raw = reference.path
        val path =
            when {
                raw.startsWith("file:") -> File(URI(raw)).path
                File(raw).isAbsolute -> raw
                cwd.isNullOrBlank() -> error("The task working directory is unavailable.")
                else -> Paths.get(cwd).resolve(raw).normalize().toString()
            }
        return reference.copy(path = path)
    }

    suspend fun load(
        reference: FileRef,
        metadataReader: suspend (String) -> JsonObject,
        hostReader: suspend (String) -> ByteArray,
    ): FilePreviewState =
        withContext(Dispatchers.IO) {
            val metadata = metadataReader(reference.path)
            val details = (metadata["metadata"] as? JsonObject) ?: metadata
            val type = details.string("type").ifBlank { details.string("kind") }
            val isDirectory =
                type.equals("directory", true) ||
                    (details["isDirectory"] as? JsonPrimitive)?.booleanOrNull == true
            require(!isDirectory) { "Directories cannot be opened as files." }
            val reportedSize =
                sequenceOf("size", "byteSize", "length")
                    .mapNotNull { (details[it] as? JsonPrimitive)?.longOrNull }
                    .firstOrNull()
            if (reportedSize != null)
                AttachmentPolicy.validateSize(dev.codexops.core.AttachmentKind.FILE, reportedSize)

            val extension =
                reference.displayName.substringAfterLast('.', "")
                    .replace(Regex("[^A-Za-z0-9]+"), "")
                    .take(12)
            val bytes = hostReader(reference.path)
            AttachmentPolicy.validateSize(
                dev.codexops.core.AttachmentKind.FILE,
                bytes.size.toLong(),
            )
            val target =
                File(
                    root,
                    digest(reference.path, bytes) +
                        extension.takeIf { it.isNotBlank() }?.let { ".$it" }.orEmpty(),
                )
            if (!target.exists()) {
                val part = File.createTempFile(".remote-file-", ".part", root)
                try {
                    part.writeBytes(bytes)
                    check(part.renameTo(target) || target.exists()) {
                        "The remote file could not be cached."
                    }
                } finally {
                    part.delete()
                }
            }
            target.setLastModified(System.currentTimeMillis())
            trim(target)

            val mime =
                details.string("mimeType").ifBlank {
                    URLConnection.guessContentTypeFromName(reference.displayName)
                        ?: URLConnection.guessContentTypeFromStream(bytes.inputStream())
                        ?: "application/octet-stream"
                }
            val imageBounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, imageBounds)
            val isImage =
                imageBounds.outWidth in 1..8192 && imageBounds.outHeight in 1..8192
            val decodedText = decodeText(bytes)
            val kind =
                when {
                    isImage -> FilePreviewKind.IMAGE
                    decodedText != null -> FilePreviewKind.TEXT
                    else -> FilePreviewKind.GENERIC
                }
            FilePreviewState(
                reference = reference,
                loading = false,
                localPath = target.canonicalPath,
                contentUri =
                    FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.files",
                            target,
                        )
                        .toString(),
                mimeType = mime,
                byteSize = bytes.size.toLong(),
                kind = kind,
                text = decodedText?.take(65_536),
                truncated = decodedText?.length?.let { it > 65_536 } == true,
            )
        }

    suspend fun save(preview: FilePreviewState, destination: Uri) =
        withContext(Dispatchers.IO) {
            val source = File(requireNotNull(preview.localPath))
            require(source.canonicalFile.parentFile == root.canonicalFile)
            val output = context.contentResolver.openOutputStream(destination)
            requireNotNull(output) { "The selected destination could not be opened." }
            source.inputStream().use { input -> output.use(input::copyTo) }
        }

    private fun decodeText(bytes: ByteArray): String? {
        if (bytes.any { it == 0.toByte() }) return null
        return runCatching {
                StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            }
            .getOrNull()
    }

    private fun trim(retained: File) {
        val now = System.currentTimeMillis()
        root.listFiles()
            ?.filter { it.isFile && it != retained && !it.name.startsWith('.') }
            ?.filter { now - it.lastModified() > maxAgeMillis }
            ?.forEach(File::delete)
        val files =
            root.listFiles()?.filter { it.isFile && !it.name.startsWith('.') }
                ?.sortedBy { it.lastModified() }
                ?: return
        var total = files.sumOf(File::length)
        for (file in files) {
            if (total <= maxCacheBytes) break
            if (file == retained) continue
            val size = file.length()
            if (file.delete()) total -= size
        }
    }

    private fun digest(path: String, bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(path.toByteArray(Charsets.UTF_8))
        digest.update(0)
        digest.update(bytes)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun JsonObject.string(key: String) =
        (this[key] as? JsonPrimitive)?.content.orEmpty()
}
