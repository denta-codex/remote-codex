package dev.codexops.client

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.codexops.core.str

private fun fileCount(count: Int) = "$count ${if (count == 1) "file" else "files"} changed"

@Composable
internal fun TurnChangesRow(changes: ConversationRow.Changes, open: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClickLabel = "View changed files", onClick = open)
            .testTag("turn-changes-${changes.turn}").padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Glyph(R.drawable.ic_file)
        Column(Modifier.weight(1f)) {
            Text(fileCount(changes.files.size), style = MaterialTheme.typography.bodyMedium)
            Text("This reply", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Glyph(R.drawable.ic_chevron, "View changed files", Modifier.size(16.dp))
    }
}

@Composable
internal fun TurnChangesViewer(changes: ConversationRow.Changes, onDismiss: () -> Unit) {
    var selectedPath by rememberSaveable(changes.turn) { mutableStateOf<String?>(null) }
    val file = changes.files.firstOrNull { it.path == selectedPath }
    val back = { if (file == null) onDismiss() else selectedPath = null }
    Dialog(onDismissRequest = back, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().testTag("turn-changes-fullscreen"), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(back, Modifier.testTag("changes-back")) { Glyph(R.drawable.ic_back, "Back") }
                    Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                        Text(if (file == null) "Changed files" else file.path.substringAfterLast('/'),
                            style = MaterialTheme.typography.titleMedium)
                        Text("This reply", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                HorizontalDivider()
                if (file == null) {
                    LazyColumn(Modifier.fillMaxSize().testTag("changed-files"), contentPadding = PaddingValues(12.dp)) {
                        items(changes.files, key = { it.path }) { changed ->
                            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                                .clickable(role = Role.Button, onClickLabel = "View recorded changes") { selectedPath = changed.path }
                                .testTag("changed-file-${changed.path}").padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Glyph(R.drawable.ic_file)
                                Text(changed.path, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                Glyph(R.drawable.ic_chevron, modifier = Modifier.size(16.dp))
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                } else key(file.path) {
                    LazyColumn(Modifier.fillMaxSize().testTag("recorded-diff"), contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        item { SelectionContainer { Text(file.path, style = MaterialTheme.typography.bodySmall) } }
                        itemsIndexed(file.patches) { index, patch ->
                            Column {
                                if (file.patches.size > 1) Text("Edit ${index + 1}", style = MaterialTheme.typography.labelMedium)
                                val movePath = patch["kind"]?.let { (it as? kotlinx.serialization.json.JsonObject)?.str("movePath") }.orEmpty()
                                if (movePath.isNotBlank()) Text("Renamed to $movePath", style = MaterialTheme.typography.bodySmall)
                                val diff = patch.str("diff")
                                if (diff.isBlank()) Text("No text diff recorded", style = MaterialTheme.typography.bodyMedium)
                                else RecordedPatch(diff)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordedPatch(diff: String) {
    val foreground = MaterialTheme.colorScheme.onSurface
    val added = MaterialTheme.colorScheme.onSecondaryContainer
    val addedBackground = MaterialTheme.colorScheme.secondaryContainer
    val removed = MaterialTheme.colorScheme.onErrorContainer
    val removedBackground = MaterialTheme.colorScheme.errorContainer
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val text = remember(diff, foreground, added, addedBackground, removed, removedBackground, muted) {
        buildAnnotatedString {
            diff.lineSequence().forEachIndexed { index, line ->
                if (index > 0) append('\n')
                val style = when {
                    line.startsWith("+++") || line.startsWith("---") || line.startsWith("@@") -> SpanStyle(color = muted)
                    line.startsWith('+') -> SpanStyle(color = added, background = addedBackground)
                    line.startsWith('-') -> SpanStyle(color = removed, background = removedBackground)
                    else -> SpanStyle(color = foreground, background = Color.Unspecified)
                }
                withStyle(style) { append(line) }
            }
        }
    }
    SelectionContainer {
        Text(text, Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 8.dp),
            fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 20.sp, softWrap = false)
    }
}
