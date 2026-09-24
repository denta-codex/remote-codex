package dev.codexops.client

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun ConversationComposer(
    state: ScreenState,
    actions: ConversationActions,
    onSend: () -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    var projectMenu by remember { mutableStateOf(false) }
    val selectedProject =
        state.newTaskOptions.projectId?.let { id -> state.projects.firstOrNull { it.id == id } }
    val projectAvailable =
        state.newTaskOptions.projectId == null || selectedProject?.primaryRoot != null
    Surface(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(8.dp)) {
            if (state.thread == null) {
                Box(Modifier.padding(start = 8.dp, top = 2.dp)) {
                    AssistChip(
                        onClick = { projectMenu = true },
                        label = {
                            Text(
                                selectedProject?.name
                                    ?: if (state.newTaskOptions.projectId == null) "No project"
                                    else "Project unavailable"
                            )
                        },
                        leadingIcon = {
                            Glyph(
                                if (state.newTaskOptions.projectId == null) R.drawable.ic_chat
                                else R.drawable.ic_folder,
                                modifier = Modifier.size(16.dp),
                            )
                        },
                        modifier = Modifier.testTag("project-selector"),
                    )
                    DropdownMenu(projectMenu, { projectMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("No project") },
                            onClick = {
                                projectMenu = false
                                actions.updateNewTaskOptions(
                                    state.newTaskOptions.copy(
                                        projectId = null,
                                        workingDirectory = null,
                                        executionTarget = ExecutionTarget.Projectless,
                                    )
                                )
                            },
                            leadingIcon = { Glyph(R.drawable.ic_chat) },
                        )
                        state.projects.forEach { project ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(project.name)
                                        Text(
                                            project.primaryRoot ?: "No workspace root",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                                onClick = {
                                    projectMenu = false
                                    actions.updateNewTaskOptions(
                                        state.newTaskOptions.copy(
                                            projectId = project.id,
                                            workingDirectory = project.primaryRoot,
                                            executionTarget = ExecutionTarget.CurrentWorkspace,
                                        )
                                    )
                                },
                                enabled = project.primaryRoot != null,
                                leadingIcon = { Glyph(R.drawable.ic_folder) },
                            )
                        }
                    }
                }
            }
            TextField(
                state.draft,
                actions::draft,
                Modifier.fillMaxWidth().testTag("composer"),
                placeholder = { Text("Message ${state.host.displayName}…") },
                minLines = 1,
                maxLines = 6,
                enabled = !state.busy,
                colors =
                    TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                    ),
            )
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    when {
                        state.activeTurn != null -> "Follow-up guides the active turn"
                        state.thread == null && !projectAvailable ->
                            "Choose an available project or No project"
                        state.thread == null && selectedProject != null ->
                            selectedProject.primaryRoot.orEmpty()
                        else -> "${state.host.displayName} defaults"
                    },
                    Modifier.weight(1f),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.activeTurn != null)
                    IconButton(actions::stop, enabled = state.ready) {
                        Glyph(R.drawable.ic_stop, "Stop")
                    }
                FilledIconButton(
                    {
                        onSend()
                        keyboard?.hide()
                        actions.send()
                    },
                    modifier = Modifier.testTag("send").size(48.dp),
                    enabled =
                        state.ready &&
                            !state.busy &&
                            state.draft.isNotBlank() &&
                            state.journal == null &&
                            projectAvailable,
                ) {
                    Glyph(
                        R.drawable.ic_send,
                        if (state.activeTurn != null) "Follow up" else "Send",
                    )
                }
            }
        }
    }
}
