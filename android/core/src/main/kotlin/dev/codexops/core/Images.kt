package dev.codexops.core

import java.io.File
import java.io.InputStream
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

const val MAX_IMAGE_BYTES = 20L * 1024 * 1024
const val MAX_ATTACHMENT_BYTES = 50L * 1024 * 1024

enum class AttachmentKind {
    IMAGE,
    FILE,
}

data class TurnAttachment(
    val kind: AttachmentKind,
    val displayName: String,
    val path: String,
)

data class ImageFormat(val mimeType: String, val extension: String)

object ImagePolicy {
    fun validateSize(size: Long) {
        require(size in 1..MAX_IMAGE_BYTES) { "Images must be 20 MiB or smaller." }
    }

    fun validateCombined(sizes: Iterable<Long>) {
        var total = 0L
        sizes.forEach {
            validateSize(it)
            total = Math.addExact(total, it)
        }
        require(total <= MAX_ATTACHMENT_BYTES) {
            "Images in one message must total 50 MiB or less."
        }
    }

    fun inspect(file: File): ImageFormat {
        validateSize(file.length())
        file.inputStream().buffered().use { input ->
            val header = ByteArray(12)
            require(input.read(header) >= 12) { "The selected file is not a supported image." }
            return when {
                header[0].u() == 0xFF && header[1].u() == 0xD8 && header[2].u() == 0xFF ->
                    ImageFormat("image/jpeg", "jpg")
                header.copyOfRange(0, 8).contentEquals(
                    byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
                ) -> ImageFormat("image/png", "png")
                String(header, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                    String(header, 8, 4, Charsets.US_ASCII) == "WEBP" ->
                    ImageFormat("image/webp", "webp")
                String(header, 0, 6, Charsets.US_ASCII) in setOf("GIF87a", "GIF89a") -> {
                    require(!hasMultipleGifFrames(file)) { "Animated GIF images are not supported." }
                    ImageFormat("image/gif", "gif")
                }
                else -> error("Only JPEG, PNG, WebP, and non-animated GIF images are supported.")
            }
        }
    }

    private fun hasMultipleGifFrames(file: File): Boolean {
        return file.inputStream().buffered().use { input ->
            val header = ByteArray(13)
            if (input.read(header) != header.size) return@use false
            val packed = header[10].u()
            if (packed and 0x80 != 0) skip(input, 3L * (1 shl ((packed and 7) + 1)))
            var frames = 0
            while (true) {
                when (input.read()) {
                    -1, 0x3B -> return@use false
                    0x21 -> {
                        if (input.read() == -1) return@use false
                        skipSubBlocks(input)
                    }
                    0x2C -> {
                        frames++
                        if (frames > 1) return@use true
                        val descriptor = ByteArray(9)
                        if (input.read(descriptor) != descriptor.size) return@use false
                        val imagePacked = descriptor[8].u()
                        if (imagePacked and 0x80 != 0)
                            skip(input, 3L * (1 shl ((imagePacked and 7) + 1)))
                        if (input.read() == -1) return@use false
                        skipSubBlocks(input)
                    }
                    else -> return@use false
                }
            }
            @Suppress("UNREACHABLE_CODE")
            false
        }
    }

    private fun skipSubBlocks(input: InputStream) {
        while (true) {
            val size = input.read()
            if (size <= 0) return
            skip(input, size.toLong())
        }
    }

    private fun skip(input: InputStream, count: Long) {
        var remaining = count
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) remaining -= skipped
            else if (input.read() == -1) return else remaining--
        }
    }

    private fun Byte.u() = toInt() and 0xFF
}

object AttachmentPolicy {
    fun validateSize(kind: AttachmentKind, size: Long) {
        if (kind == AttachmentKind.IMAGE) ImagePolicy.validateSize(size)
        else require(size in 0..MAX_IMAGE_BYTES) { "Files must be 20 MiB or smaller." }
    }

    fun validateCombined(attachments: Iterable<Pair<AttachmentKind, Long>>) {
        var total = 0L
        attachments.forEach { (kind, size) ->
            validateSize(kind, size)
            total = Math.addExact(total, size)
        }
        require(total <= MAX_ATTACHMENT_BYTES) {
            "Attachments in one message must total 50 MiB or less."
        }
    }
}

fun safeAttachmentName(
    index: Int,
    displayName: String,
    kind: AttachmentKind,
    imageFormat: ImageFormat? = null,
): String {
    val cleanName = displayName.replace('\r', ' ').replace('\n', ' ')
    val originalExtension =
        cleanName.substringAfterLast('.', "").replace(Regex("[^A-Za-z0-9]+"), "").take(12)
    val extension =
        if (kind == AttachmentKind.IMAGE) requireNotNull(imageFormat).extension
        else originalExtension
    val rawStem = if (extension.isBlank()) cleanName else cleanName.substringBeforeLast('.')
    val stem =
        rawStem
            .replace(Regex("[^A-Za-z0-9._-]+"), "-")
            .trim('-', '.', '_')
            .take(64)
            .ifBlank { if (kind == AttachmentKind.IMAGE) "image" else "file" }
    return "$index-$stem${extension.takeIf(String::isNotBlank)?.let { ".$it" }.orEmpty()}"
}

fun attachmentContext(text: String, attachments: List<TurnAttachment>): String {
    if (attachments.isEmpty()) return text
    val sections = mutableListOf("# Files mentioned by the user:")
    sections +=
        attachments.map {
            "## ${it.displayName.replace('\r', ' ').replace('\n', ' ')}: ${it.path}"
        }
    sections += "## My request for Codex:"
    text.trim().takeIf(String::isNotEmpty)?.let(sections::add)
    return sections.joinToString("\n\n")
}

fun turnInput(text: String, attachments: List<TurnAttachment>): JsonArray {
    val values = mutableListOf<JsonElement>()
    val contextualText = attachmentContext(text, attachments)
    if (contextualText.isNotBlank())
        values += obj("type" to s("text"), "text" to s(contextualText))
    values +=
        attachments.filter { it.kind == AttachmentKind.IMAGE }.map {
            obj("type" to s("localImage"), "path" to s(it.path))
        }
    return JsonArray(values)
}
