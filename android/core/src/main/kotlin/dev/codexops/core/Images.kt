package dev.codexops.core

import java.io.File
import java.io.InputStream
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

const val MAX_IMAGE_BYTES = 20L * 1024 * 1024
const val MAX_ATTACHMENT_BYTES = 50L * 1024 * 1024

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

fun safeAttachmentName(index: Int, displayName: String, format: ImageFormat): String {
    val rawStem = displayName.substringBeforeLast('.', displayName)
    val stem =
        rawStem
            .replace(Regex("[^A-Za-z0-9._-]+"), "-")
            .trim('-', '.', '_')
            .take(64)
            .ifBlank { "image" }
    return "$index-$stem.${format.extension}"
}

fun turnInput(text: String, localImages: List<String>): JsonArray {
    val values = mutableListOf<JsonElement>()
    if (text.isNotBlank()) values += obj("type" to s("text"), "text" to s(text))
    values += localImages.map { obj("type" to s("localImage"), "path" to s(it)) }
    return JsonArray(values)
}
