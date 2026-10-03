package dev.codexops.client

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.codexops.core.*
import kotlinx.serialization.json.*

@Composable
internal fun GitMergeDialog(st: ScreenState, actions: ConversationActions) {
    val merge = st.merge
    if (!merge.visible || st.page != "chat") return
    val report = merge.report
    val status = report?.str("status")
    val cover = LocalAppWindowClass.current.coverScreen
    Dialog(onDismissRequest = actions::dismissMerge, properties = DialogProperties(usePlatformDefaultWidth = !cover)) {
        Surface(Modifier.fillMaxWidth().then(if (cover) Modifier.fillMaxHeight().systemBarsPadding() else Modifier.heightIn(max = 720.dp)),
            shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(20.dp).testTag("merge-dialog")) {
                Text("Merge into main", style = MaterialTheme.typography.titleLarge)
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (merge.working) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(if (merge.pending == null) "Checking repository and conflicts…" else "Checking or completing the merge…")
                    }
                    report?.let { value ->
                        Text(value.str("reason"), Modifier.testTag("merge-result"))
                        if (status == "ready") Text("This directly merges all listed commits into local main. It does not push or run tests.")
                        SelectionContainer {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                val snapshot = merge.pending?.map("snapshot") ?: value
                                if (snapshot.str("sourceHead").isNotEmpty()) {
                                    Text("Source: ${snapshot.str("sourceRef").ifBlank { "Detached HEAD" }} · ${snapshot.str("sourceHead").take(12)}")
                                    Text(snapshot.str("source"))
                                    Text("Destination: main · ${snapshot.str("targetHead").take(12)}")
                                    Text(snapshot.str("destination"))
                                }
                                if (value.str("result").isNotEmpty()) Text("Result: ${value.str("result")}")
                                for ((field, heading) in listOf("conflicts" to "Conflicting files", "commits" to "Commits (${value.str("count")})", "files" to "Changed files (${value.str("fileCount")})")) {
                                    val lines = (value[field] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()
                                    if (lines.isNotEmpty()) {
                                        Text(heading, style = MaterialTheme.typography.titleSmall)
                                        lines.forEach { Text(it) }
                                    }
                                }
                                if ((value["count"] as? JsonPrimitive)?.intOrNull?.let { it > 100 } == true) Text("Showing the first 100 commits; all commits are included.")
                                if ((value["fileCount"] as? JsonPrimitive)?.intOrNull?.let { it > 200 } == true) Text("Showing the first 200 changed files.")
                                if (merge.pending != null) {
                                    Text("Operation: ${merge.pending.str("operation")}")
                                    Text("Host receipt: ${snapshot.str("common")}/remote-codex-merges/${merge.pending.str("operation")}.json")
                                    if (value.str("temporary").isNotEmpty()) Text("Recovery worktree: ${value.str("temporary")}")
                                }
                            }
                        }
                    }
                    if (merge.pending != null && !merge.working) Text("This operation blocks further task submissions until its outcome is known.")
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(actions::dismissMerge) { Text("Close") }
                    if (merge.pending != null) {
                        TextButton(actions::reconcileMerge, enabled = st.ready && !merge.working,
                            modifier = Modifier.testTag("check-merge")) { Text("Check state") }
                    } else if (status != "succeeded") {
                        TextButton(actions::inspectMerge, enabled = st.canInspectMerge(),
                            modifier = Modifier.testTag("refresh-merge")) { Text("Refresh") }
                    }
                }
                if (merge.pending == null && status != "succeeded") {
                    Button(actions::mergeIntoMain, enabled = status == "ready" && st.canInspectMerge(),
                        modifier = Modifier.fillMaxWidth().testTag("confirm-merge")) { Text("Merge into main") }
                }
            }
        }
    }
}
