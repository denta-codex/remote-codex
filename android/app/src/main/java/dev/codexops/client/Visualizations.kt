package dev.codexops.client

import dev.codexops.core.str
import dev.codexops.core.wire
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

internal const val MAX_VISUALIZATION_BYTES = 1_000_000
internal const val MAX_WIDGET_STATE_BYTES = 16 * 1024

data class VisualizationRef(val path: String, val title: String = "Visualization", val wide: Boolean = false)

internal sealed interface VisualizationPart {
    data class Markdown(val text: String) : VisualizationPart
    data class Visual(val reference: VisualizationRef) : VisualizationPart
}

/** Only standalone references execute. Examples in fenced/indented/inline code remain text. */
internal fun visualizationParts(text: String): List<VisualizationPart> {
    val parts = mutableListOf<VisualizationPart>()
    val markdown = StringBuilder()
    var fence: Char? = null
    var fenceLength = 0
    text.splitToSequence('\n').forEach { line ->
        val trimmed = line.trimStart()
        val indent = line.length - trimmed.length
        val marker = trimmed.firstOrNull()
        val count = trimmed.takeWhile { it == marker }.length
        if (indent <= 3 && marker in listOf('`', '~') && count >= 3) {
            if (fence == null) {
                fence = marker
                fenceLength = count
            } else if (marker == fence && count >= fenceLength && trimmed.drop(count).isBlank()) {
                fence = null
            }
            markdown.append(line).append('\n')
        } else {
            val candidate = line.trim()
            val reference = if (fence == null && indent <= 3 && '\t' !in line.take(indent) &&
                candidate.startsWith("visualize") && candidate.endsWith(""))
                runCatching {
                    val value = wire.parseToJsonElement(candidate.removePrefix("visualize").removeSuffix("")) as JsonObject
                    val path = value.str("path")
                    require(validVisualizationPath(path))
                    VisualizationRef(path, value.str("title").take(250).ifBlank { "Visualization" }, value.str("mode") == "wide")
                }.getOrNull()
            else null
            if (reference != null) {
                if (markdown.isNotEmpty()) parts += VisualizationPart.Markdown(markdown.toString().trimEnd('\n'))
                markdown.clear()
                parts += VisualizationPart.Visual(reference)
            } else markdown.append(line).append('\n')
        }
    }
    if (markdown.isNotEmpty()) parts += VisualizationPart.Markdown(markdown.toString().removeSuffix("\n"))
    return parts
}

internal fun validVisualizationPath(path: String): Boolean =
    path.startsWith('/') && path.length <= 4096 &&
        path.none { it.code < 32 } && path.substringAfterLast('.').lowercase() in setOf("html", "htm")

internal suspend fun readVisualization(
    reference: VisualizationRef,
    metadataReader: suspend (String) -> JsonObject,
    reader: suspend (String) -> ByteArray,
): String {
    require(validVisualizationPath(reference.path)) { "Invalid visualization path." }
    val response = metadataReader(reference.path)
    val metadata = response["metadata"] as? JsonObject ?: response
    require(metadata.str("type") != "directory" && metadata.str("isDirectory") != "true") {
        "The visualization is not a file."
    }
    val size = sequenceOf("size", "byteSize", "length")
        .mapNotNull { (metadata[it] as? JsonPrimitive)?.longOrNull }.firstOrNull()
    require(size == null || size in 1..MAX_VISUALIZATION_BYTES) { "Visualization must be at most 1 MB." }
    val bytes = reader(reference.path)
    require(bytes.size in 1..MAX_VISUALIZATION_BYTES) { "Visualization must be at most 1 MB." }
    return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
}

internal fun validWidgetState(value: String): Boolean =
    value.toByteArray(Charsets.UTF_8).size <= MAX_WIDGET_STATE_BYTES && runCatching {
        wire.parseToJsonElement(value) is JsonObject
    }.getOrDefault(false)

internal object VisualizationPolicy {
    const val ORIGIN = "https://visualization.invalid"
    val cdnHosts = setOf("cdnjs.cloudflare.com", "esm.sh", "cdn.jsdelivr.net", "unpkg.com",
        "fonts.googleapis.com", "fonts.gstatic.com", "fonts.bunny.net")
    fun allowsResource(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme == "https" && uri.host in cdnHosts && uri.rawUserInfo == null && uri.port in setOf(-1, 443)
    }.getOrDefault(false)
    val frameCsp = "default-src 'none'; script-src 'unsafe-inline' 'unsafe-eval' " +
        cdnHosts.joinToString(" ") { "https://$it" } + "; style-src 'unsafe-inline' " +
        cdnHosts.joinToString(" ") { "https://$it" } + "; img-src data: blob: " +
        cdnHosts.joinToString(" ") { "https://$it" } + "; font-src data: " +
        cdnHosts.joinToString(" ") { "https://$it" } +
        "; media-src data: blob:; connect-src 'none'; worker-src blob:; frame-src 'none'; " +
        "object-src 'none'; base-uri 'none'; form-action 'none'"
}
