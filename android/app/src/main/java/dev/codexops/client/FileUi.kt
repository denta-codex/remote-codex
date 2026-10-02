package dev.codexops.client

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownTable
import com.mikepenz.markdown.compose.elements.MarkdownTableHeader
import com.mikepenz.markdown.compose.elements.MarkdownTableRow
import dev.codexops.core.FileRef
import java.io.File
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun FileAwareMarkdown(text: String, actions: ConversationActions) {
    val external = LocalUriHandler.current
    val handler =
        remember(external, actions) {
            object : UriHandler {
                override fun openUri(uri: String) {
                    fileReference(uri)?.let(actions::inspectFile) ?: external.openUri(uri)
                }
            }
        }
    CompositionLocalProvider(LocalUriHandler provides handler) {
        Markdown(
            text,
            components = markdownComponents(
                table = { table ->
                    // The library defaults to one-line, ellipsized cells. Scrolling
                    // exposes more columns but cannot reveal that discarded text.
                    MarkdownTable(
                        table.content,
                        table.node,
                        style = table.typography.table,
                        headerBlock = { content, node, width, style ->
                            MarkdownTableHeader(
                                content, node, width, style,
                                verticalAlignment = Alignment.Top,
                                maxLines = Int.MAX_VALUE,
                                overflow = TextOverflow.Clip,
                            )
                        },
                        rowBlock = { content, node, width, style ->
                            MarkdownTableRow(
                                content, node, width, style,
                                verticalAlignment = Alignment.Top,
                                maxLines = Int.MAX_VALUE,
                                overflow = TextOverflow.Clip,
                            )
                        },
                    )
                }
            ),
        )
    }
}

internal fun fileReference(uri: String): FileRef? {
    if (uri.startsWith("#")) return null
    if (uri.startsWith("http://") || uri.startsWith("https://") || uri.startsWith("mailto:"))
        return null
    val raw =
        when {
            uri.startsWith("file:") -> runCatching { File(URI(uri)).path }.getOrNull()
            uri.startsWith("sandbox:") -> uri.removePrefix("sandbox:")
            uri.startsWith("/") || !uri.substringBefore('/').contains(':') -> uri
            else -> null
        } ?: return null
    val withoutLine = raw.replace(Regex(":\\d+(?::\\d+)?$"), "")
    val name = withoutLine.substringAfterLast('/').ifBlank { "File" }
    return FileRef("link/${uri.hashCode()}", name, withoutLine)
}

@Composable
internal fun FileReferenceList(files: List<FileRef>, actions: ConversationActions) {
    if (files.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        files.forEach { file ->
            OutlinedButton(
                onClick = { actions.inspectFile(file) },
                modifier = Modifier.fillMaxWidth().testTag("file-reference"),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Glyph(R.drawable.ic_file, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(file.displayName, Modifier.weight(1f), maxLines = 2)
            }
        }
    }
}

@Composable
internal fun FilePreviewDialog(
    preview: FilePreviewState,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
) {
    val context = LocalContext.current
    val cover = LocalAppWindowClass.current.coverScreen
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = !cover),
    ) {
        Surface(
            (if (cover) Modifier.fillMaxSize().systemBarsPadding()
                else Modifier.fillMaxWidth().heightIn(max = 720.dp))
                .testTag("file-preview"),
            shape = if (cover) androidx.compose.foundation.shape.RoundedCornerShape(0.dp)
                else MaterialTheme.shapes.large,
        ) {
            Column(
                Modifier.padding(if (cover) 12.dp else 16.dp),
                verticalArrangement = Arrangement.spacedBy(if (cover) 8.dp else 12.dp),
            ) {
                Text(preview.reference.displayName, style = MaterialTheme.typography.titleMedium)
                when {
                    preview.loading ->
                        Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    preview.error != null -> Text(preview.error, color = MaterialTheme.colorScheme.error)
                    preview.kind == FilePreviewKind.IMAGE && preview.localPath != null -> {
                        val bitmap by
                            produceState<android.graphics.Bitmap?>(null, preview.localPath) {
                                value =
                                    runCatching {
                                        val bytes =
                                            withContext(Dispatchers.IO) {
                                                File(preview.localPath).readBytes()
                                            }
                                        decodedBitmap(
                                            preview.reference.key,
                                            bytes,
                                            2048,
                                        )
                                    }.getOrNull()
                            }
                        if (bitmap != null)
                            Image(
                                bitmap!!.asImageBitmap(),
                                preview.reference.displayName,
                                Modifier.fillMaxWidth().heightIn(max = if (cover) 280.dp else 480.dp),
                                contentScale = ContentScale.Fit,
                            )
                    }
                    preview.kind == FilePreviewKind.TEXT ->
                        Column(Modifier.weight(1f, false).verticalScroll(rememberScrollState())) {
                            Text(
                                preview.text.orEmpty(),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                            )
                            if (preview.truncated)
                                Text(
                                    "Preview truncated after 65,536 characters.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 11.sp,
                                )
                        }
                    else ->
                        Text(
                            listOf(preview.mimeType, preview.byteSize?.let(::formatBytes))
                                .filterNotNull()
                                .joinToString(" · ")
                        )
                }
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.End,
                ) {
                    if (!preview.loading && preview.error == null && preview.contentUri != null) {
                        TextButton(
                            onClick = {
                                val uri = Uri.parse(preview.contentUri)
                                runCatching {
                                        context.startActivity(
                                            Intent(Intent.ACTION_VIEW)
                                                .setDataAndType(uri, preview.mimeType)
                                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        )
                                    }
                                    .onFailure {
                                        Toast.makeText(
                                                context,
                                                "No app can open this file.",
                                                Toast.LENGTH_SHORT,
                                            )
                                            .show()
                                    }
                            }
                        ) { Text("Open") }
                        TextButton(
                            onClick = {
                                val uri = Uri.parse(preview.contentUri)
                                runCatching {
                                        context.startActivity(
                                            Intent.createChooser(
                                                Intent(Intent.ACTION_SEND)
                                                    .setType(preview.mimeType)
                                                    .putExtra(Intent.EXTRA_STREAM, uri)
                                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                                                null,
                                            )
                                        )
                                    }
                                    .onFailure {
                                        Toast.makeText(
                                                context,
                                                "No app can share this file.",
                                                Toast.LENGTH_SHORT,
                                            )
                                            .show()
                                    }
                            }
                        ) { Text("Share") }
                        TextButton(onClick = onSave) { Text("Save") }
                    }
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
            }
        }
    }
}
