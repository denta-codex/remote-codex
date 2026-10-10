package dev.codexops.client

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.codexops.core.obj
import dev.codexops.core.s

/** Composer-anchored chrome reserves its own space, so it never covers a message. */
@Composable
internal fun ConversationSummary(st: ScreenState, actions: ConversationActions) {
    var openChanges by rememberSaveable(st.thread, st.threadCwd) { mutableStateOf(false) }
    val changes = st.worktreeChanges
    val cover = LocalAppWindowClass.current.coverScreen
    val stale = changes.stale || !st.ready
    val showChanges = st.threadCwd != null && changes.cwd == st.threadCwd &&
        changes.status == WorktreeStatus.Ready && changes.files.isNotEmpty()
    val showCost = st.ready && st.chatCost.status == ChatCostStatus.Ready
    if (showChanges || showCost) Box(Modifier.fillMaxWidth().padding(horizontal = if (cover) 8.dp else 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shadowElevation = 3.dp,
            modifier = Modifier.testTag("conversation-summary")) {
            Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (showChanges) {
                    TextButton(onClick = { openChanges = true },
                        modifier = Modifier.weight(1f, fill = false).heightIn(min = 48.dp).testTag("git-changes").semantics {
                            contentDescription = when (changes.status) {
                                WorktreeStatus.Ready -> "Git worktree: ${changes.files.size} files, ${changes.added} lines added, ${changes.removed} lines removed" +
                                    if (stale) ", stale" else ""
                                WorktreeStatus.Loading -> "Loading Git worktree changes"
                                else -> "Git worktree changes unavailable"
                            }
                        }, contentPadding = PaddingValues(horizontal = if (cover) 6.dp else 10.dp)) {
                        Glyph(R.drawable.ic_branch, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        if (changes.status == WorktreeStatus.Ready) {
                            Text("${changes.files.size} ${if (changes.files.size == 1) "file" else "files"}",
                                modifier = Modifier.weight(1f, fill = false),
                                style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.width(8.dp))
                            Text("+${changes.added}", color = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f)
                                Color(0xFF8CCD9D) else Color(0xFF2E7147), style = MaterialTheme.typography.labelMedium)
                            Spacer(Modifier.width(6.dp))
                            Text("−${changes.removed}${if (stale) "*" else ""}",
                                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                        } else Text(if (changes.status == WorktreeStatus.Loading) "Changes…" else "Changes unavailable",
                            style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (showChanges && showCost) VerticalDivider(Modifier.height(18.dp).testTag("conversation-summary-divider"),
                    color = MaterialTheme.colorScheme.outlineVariant)
                if (showCost) ChatCostBadge(st.chatCost)
            }
        }
    }
    if (openChanges) {
        if (changes.status == WorktreeStatus.Ready) {
            val recorded = ConversationRow.Changes("worktree-${changes.root}", changes.files.map {
                RecordedFileChanges(it.path, listOf(obj("diff" to s(it.diff))))
            })
            TurnChangesViewer(recorded, onDismiss = { openChanges = false },
                subtitle = "Worktree · staged, unstaged, untracked${if (stale) " · stale" else ""}",
                tagPrefix = "worktree", refresh = actions::refreshWorktreeChanges)
        } else AlertDialog(onDismissRequest = { openChanges = false },
            title = { Text("Worktree changes") },
            text = { Text(if (changes.status == WorktreeStatus.Loading) "Reading current Git changes…"
                else "Current Git changes could not be read. Refresh to try again.") },
            confirmButton = { TextButton(onClick = actions::refreshWorktreeChanges, enabled = st.ready) { Text("Refresh") } },
            dismissButton = { TextButton(onClick = { openChanges = false }) { Text("Done") } })
    }
}
