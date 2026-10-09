package dev.codexops.client

import android.animation.ValueAnimator
import androidx.compose.animation.core.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.codexops.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonPrimitive

@Composable
internal fun ToolActivityRow(group: ConversationRow.Activity, scope: String,
    actions: ConversationActions, foreground: Boolean,
    animationsEnabled: Boolean = ValueAnimator.areAnimatorsEnabled()) {
    var expanded by rememberSaveable(scope, group.key) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().testTag("tool-activity-${group.key}")) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .clickable(role = Role.Button, onClickLabel = if (expanded) "Collapse activity" else "Expand activity") {
                    expanded = !expanded
                }
                .semantics {
                    stateDescription = (if (group.working) "In progress · " else "") +
                        if (expanded) "Expanded" else "Collapsed"
                }
                .testTag("tool-activity-toggle-${group.key}")
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ActivitySummary(group.summary, group.working && foreground && animationsEnabled, Modifier.weight(1f))
            Glyph(if (expanded) R.drawable.ic_up else R.drawable.ic_down, modifier = Modifier.size(16.dp))
        }
        if (!expanded) {
            group.progressMessage?.let { message ->
                Text(message, Modifier.testTag("tool-progress-preview-${group.key}"),
                    fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            group.previewSummary?.let { summary ->
                Text(summary, Modifier.testTag("progress-summary-preview-${group.key}"),
                    fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (expanded) Column(Modifier.padding(start = 12.dp).testTag("tool-activity-details-${group.key}")) {
            val calls = group.calls.associateBy { it.entry.key }
            group.entries.forEach { entry -> key(entry.key) {
                if (entry.kind == "reasoning") {
                    val summaries = entry.summaries.filter(String::isNotBlank)
                    if (summaries.isNotEmpty()) Column(
                        Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("progress-summary-${entry.key}"),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Progress summary", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        summaries.forEach { summary -> SelectionContainer {
                            Text(summary, fontSize = 13.sp, lineHeight = 18.sp)
                        } }
                    }
                } else calls[entry.key]?.let { ToolCallDetails(it, scope, actions) }
            } }
            if (group.calls.isEmpty() && group.entries.none { it.summaries.any(String::isNotBlank) })
                Text("Details unavailable", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Animate the text's brush, rather than its layout or opacity, to keep reading stable. */
@Composable
private fun ActivitySummary(text: String, shimmer: Boolean, modifier: Modifier) {
    val base = MaterialTheme.colorScheme.onSurfaceVariant
    val style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp)
    if (shimmer) {
        var width by remember { mutableFloatStateOf(1f) }
        val transition = rememberInfiniteTransition(label = "Tool activity")
        val progress by transition.animateFloat(-0.5f, 1.5f,
            infiniteRepeatable(tween(2000, easing = LinearEasing)), label = "Text shimmer")
        val center = width * progress
        val brush = Brush.linearGradient(
            listOf(base, MaterialTheme.colorScheme.onSurface, base),
            start = Offset(center - width * .3f, 0f), end = Offset(center + width * .3f, 0f),
        )
        Text(text, modifier.onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }.testTag("tool-activity-shimmer"),
            style = style.copy(brush = brush), maxLines = 2, overflow = TextOverflow.Ellipsis)
    } else Text(text, modifier, color = base, style = style, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun ToolCallDetails(call: ToolCall, scope: String, actions: ConversationActions) {
    var expanded by rememberSaveable(scope, call.entry.key) { mutableStateOf(false) }
    var technical by rememberSaveable(scope, call.entry.key) { mutableStateOf(false) }
    var complete by remember(scope, call.entry.key) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .clickable(role = Role.Button, onClickLabel = if (expanded) "Collapse tool details" else "Expand tool details") {
                    expanded = !expanded
                }
                .semantics { stateDescription = "${call.state.label} · ${if (expanded) "Expanded" else "Collapsed"}" }
                .testTag("tool-call-toggle-${call.entry.key}")
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(call.title, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(call.state.label, fontSize = 12.sp,
                    color = if (call.state == ToolState.Failed) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant)
                if (call.entry.progressMessage.isNotBlank())
                    Text(call.entry.progressMessage, Modifier.testTag("tool-progress-${call.entry.key}"),
                        fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Glyph(if (expanded) R.drawable.ic_up else R.drawable.ic_down, modifier = Modifier.size(16.dp))
        }
        if (expanded) Column(
            Modifier.padding(start = 8.dp, bottom = 12.dp).testTag("tool-call-details-${call.entry.key}"),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            MediaGallery(call.entry.media, actions)
            FileReferenceList(call.entry.files, actions)
            if (call.details.isEmpty()) Text("Details unavailable", fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (call.entry.raw["_detailsOmitted"] == JsonPrimitive(true)) {
                Text("Showing a preview. Complete details remain on the server.", fontSize = 12.sp)
                TextButton(onClick = { complete = true }, modifier = Modifier.testTag("load-complete-details-${call.entry.key}")) {
                    Text("Load complete details")
                }
            }
            call.details.forEach { detail ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(detail.label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SelectionContainer {
                        Text(detail.value.take(60000), fontSize = 12.sp, lineHeight = 18.sp,
                            fontFamily = if (detail.code) FontFamily.Monospace else FontFamily.Default)
                    }
                }
            }
            TextButton(onClick = { technical = !technical },
                modifier = Modifier.testTag("tool-technical-toggle-${call.entry.key}").semantics {
                    stateDescription = if (technical) "Expanded" else "Collapsed"
                }) {
                Text("Technical details", fontSize = 12.sp)
                Spacer(Modifier.width(8.dp))
                Glyph(if (technical) R.drawable.ic_up else R.drawable.ic_down, modifier = Modifier.size(16.dp))
            }
            if (technical) SelectionContainer {
                Text(call.technicalDetails.take(60000), fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp, lineHeight = 18.sp,
                    modifier = Modifier.testTag("tool-technical-details-${call.entry.key}"))
            }
        }
    }
    if (complete) CompleteToolDetails(call.entry, actions) { complete = false }
}

@Composable
private fun CompleteToolDetails(entry: Entry, actions: ConversationActions, close: () -> Unit) {
    var offset by remember { mutableIntStateOf(0) }
    var previous by remember { mutableStateOf(emptyList<Int>()) }
    var retry by remember { mutableIntStateOf(0) }
    val page by produceState<Result<ToolDetailsPage>?>(null, entry.key, offset, retry) {
        value = null
        value = try { Result.success(actions.loadToolDetails(entry, offset)) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { Result.failure(IllegalStateException("Complete details unavailable. Check the connection and try again.")) }
    }
    AlertDialog(onDismissRequest = close,
        title = { Text("Complete tool details") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                when {
                    page == null -> CircularProgressIndicator()
                    page!!.isFailure -> Text("Complete details unavailable. Check the connection and try again.")
                    else -> SelectionContainer {
                        Text(page!!.getOrThrow().text, Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                            fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    }
                }
                Row {
                    if (previous.isNotEmpty()) TextButton(onClick = { offset = previous.last(); previous = previous.dropLast(1) }) { Text("Previous") }
                    page?.getOrNull()?.nextOffset?.let { next ->
                        TextButton(onClick = { previous = previous + offset; offset = next }) { Text("Next") }
                    }
                    if (page?.isFailure == true) TextButton(onClick = { retry++ }) { Text("Retry") }
                }
            }
        },
        confirmButton = { TextButton(onClick = close) { Text("Close") } })
}
