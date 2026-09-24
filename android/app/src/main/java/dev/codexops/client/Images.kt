package dev.codexops.client

import android.content.ContentResolver
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.core.content.FileProvider
import dev.codexops.core.*
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

data class DraftAttachment(
    val id: String,
    val localPath: String,
    val displayName: String,
    val mimeType: String,
    val byteSize: Long,
) {
    fun json() =
        obj(
            "id" to s(id),
            "localPath" to s(localPath),
            "displayName" to s(displayName),
            "mimeType" to s(mimeType),
            "byteSize" to JsonPrimitive(byteSize),
        )

    companion object {
        fun from(value: JsonObject): DraftAttachment? {
            val size = (value["byteSize"] as? JsonPrimitive)?.longOrNull ?: return null
            return DraftAttachment(
                value.str("id"),
                value.str("localPath"),
                value.str("displayName"),
                value.str("mimeType"),
                size,
            ).takeIf { it.id.isNotBlank() && it.localPath.isNotBlank() }
        }
    }
}

class AttachmentStore(private val context: Context) {
    private val root = File(context.filesDir, "draft-images").apply { mkdirs() }
    private var pendingCamera: File? = null

    fun prepareCamera(): Uri {
        val file = File(root, ".camera-${UUID.randomUUID()}.jpg")
        check(file.createNewFile()) { "Could not prepare camera capture." }
        pendingCamera = file
        return FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }

    suspend fun finishCamera(success: Boolean, combinedBytes: Long): DraftAttachment? {
        val file = pendingCamera.also { pendingCamera = null } ?: return null
        if (!success) {
            file.delete()
            return null
        }
        return try {
            import(Uri.fromFile(file), combinedBytes, "Camera photo.jpg")
        } finally {
            file.delete()
        }
    }

    suspend fun import(
        uri: Uri,
        combinedBytes: Long,
        fallbackName: String = "Photo",
    ): DraftAttachment =
        withContext(Dispatchers.IO) {
            require(combinedBytes < MAX_ATTACHMENT_BYTES) {
                "Images in one message must total 50 MiB or less."
            }
            val id = UUID.randomUUID().toString()
            val part = File(root, ".$id.part")
            try {
                val input =
                    if (uri.scheme == ContentResolver.SCHEME_FILE)
                        FileInputStream(requireNotNull(uri.path))
                    else context.contentResolver.openInputStream(uri)
                requireNotNull(input) { "The selected image could not be opened." }
                input.use { source ->
                    part.outputStream().buffered().use { target ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var total = 0L
                        while (true) {
                            val read = source.read(buffer)
                            if (read < 0) break
                            total += read
                            require(total <= MAX_IMAGE_BYTES) {
                                "Images must be 20 MiB or smaller."
                            }
                            require(combinedBytes + total <= MAX_ATTACHMENT_BYTES) {
                                "Images in one message must total 50 MiB or less."
                            }
                            target.write(buffer, 0, read)
                        }
                    }
                }
                val format = ImagePolicy.inspect(part)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(part.path, bounds)
                require(bounds.outWidth > 0 && bounds.outHeight > 0) {
                    "The selected image is corrupt or unsupported."
                }
                val destination = File(root, "$id.${format.extension}")
                check(part.renameTo(destination)) { "The selected image could not be saved." }
                DraftAttachment(
                    id,
                    destination.canonicalPath,
                    displayName(uri).ifBlank { fallbackName },
                    format.mimeType,
                    destination.length(),
                )
            } catch (e: Exception) {
                part.delete()
                throw e
            }
        }

    fun restore(serialized: String): List<DraftAttachment> {
        val canonicalRoot = root.canonicalFile
        val values =
            runCatching { wire.parseToJsonElement(serialized) as? JsonArray }
                .getOrNull()
                .orEmpty()
        return values.mapNotNull { (it as? JsonObject)?.let(DraftAttachment::from) }.filter {
            val file = File(it.localPath)
            runCatching {
                    file.exists() &&
                        file.canonicalFile.parentFile == canonicalRoot &&
                        file.length() == it.byteSize &&
                        ImagePolicy.inspect(file).mimeType == it.mimeType
                }
                .getOrDefault(false)
        }.also { ImagePolicy.validateCombined(it.map(DraftAttachment::byteSize)) }
    }

    fun serialize(values: List<DraftAttachment>) =
        JsonArray(values.map { it.json() }).toString()

    fun delete(value: DraftAttachment) {
        runCatching {
            val file = File(value.localPath)
            if (file.canonicalFile.parentFile == root.canonicalFile) file.delete()
        }
    }

    private fun displayName(uri: Uri): String {
        if (uri.scheme == ContentResolver.SCHEME_FILE)
            return uri.lastPathSegment.orEmpty().ifBlank { "Photo" }
        return runCatching {
                context.contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            }
            .getOrNull()
            .orEmpty()
            .replace('\n', ' ')
            .replace('\r', ' ')
            .take(120)
    }
}

class MediaRepository(private val context: Context) {
    private val root = File(context.cacheDir, "remote-images").apply { mkdirs() }
    private val maxCacheBytes = 64L * 1024 * 1024

    suspend fun load(media: MediaRef, hostReader: suspend (String) -> ByteArray): ByteArray =
        withContext(Dispatchers.IO) {
            when (media.location) {
                MediaLocation.HOST_PATH -> {
                    val target = File(root, digest(media.value))
                    if (target.exists()) return@withContext target.readBytes()
                    val bytes = hostReader(media.value)
                    require(bytes.size <= MAX_IMAGE_BYTES) { "Image is too large to display." }
                    val part = File(root, ".${target.name}.part")
                    part.writeBytes(bytes)
                    if (!part.renameTo(target)) part.delete()
                    trim()
                    bytes
                }
                MediaLocation.DATA_URL -> decodeDataUrl(media.value)
                MediaLocation.BASE64 -> Base64.decode(media.value, Base64.DEFAULT)
                MediaLocation.EXTERNAL_URL -> error("External images require explicit opening.")
            }
        }

    private fun decodeDataUrl(value: String): ByteArray {
        val comma = value.indexOf(',')
        require(comma > 0 && value.substring(0, comma).contains(";base64")) {
            "Unsupported image data URL."
        }
        return Base64.decode(value.substring(comma + 1), Base64.DEFAULT)
    }

    private fun digest(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun trim() {
        val files = root.listFiles()?.filter { !it.name.startsWith('.') }?.sortedBy { it.lastModified() }
            ?: return
        var total = files.sumOf(File::length)
        for (file in files) {
            if (total <= maxCacheBytes) break
            val size = file.length()
            if (file.delete()) total -= size
        }
    }
}
