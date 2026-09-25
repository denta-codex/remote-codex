package dev.codexops.client

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

@Composable
internal fun ConversationComposer(
    state: ScreenState,
    actions: ConversationActions,
    onSend: () -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) {
            actions.addAttachments(it)
        }
    val camera =
        rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) {
            actions.finishCamera(it)
        }
    var projectMenu by remember { mutableStateOf(false) }
    var modeMenu by remember { mutableStateOf(false) }
    val selectedProject =
        state.newTaskOptions.projectId?.let { id -> state.projects.firstOrNull { it.id == id } }
    val projectAvailable =
        state.newTaskOptions.projectId == null || selectedProject?.primaryRoot != null
    val modes =
        state.collaborationModes.filter {
            it.turnSetting(state.collaborationModel()) != null
        }
    val selectedMode = modes.firstOrNull { it.mode == state.newTaskOptions.collaborationMode }
    val composerStatus =
        when {
            state.activeTurn != null -> "Follow-up guides the active turn"
            state.thread == null && !projectAvailable ->
                "Choose an available project or No project"
            state.newTaskOptions.model != null ||
                state.newTaskOptions.reasoningEffort != null ->
                "Explicit overrides apply to the next turn"
            state.thread == null && selectedProject != null ->
                selectedProject.primaryRoot.orEmpty()
            else -> "${state.host.displayName} defaults"
        }
    Surface(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(8.dp)) {
            if (state.attachments.isNotEmpty())
                LazyRow(
                    Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.attachments, key = { it.id }) { attachment ->
                        DraftAttachmentPreview(attachment) {
                            actions.removeAttachment(attachment.id)
                        }
                    }
                }
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
            if (state.thread == null && state.newTaskOptions.projectId != null) {
                val options = state.newTaskOptions
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "Workspace",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = options.executionTarget == ExecutionTarget.CurrentWorkspace,
                            onClick = {
                                actions.updateNewTaskOptions(
                                    options.copy(executionTarget = ExecutionTarget.CurrentWorkspace)
                                )
                            },
                            label = { Text("Current") },
                            modifier = Modifier.testTag("workspace-current"),
                            enabled = !state.busy && state.journal == null,
                        )
                        FilterChip(
                            selected = options.executionTarget == ExecutionTarget.NewWorktree,
                            onClick = {
                                actions.updateNewTaskOptions(
                                    options.copy(executionTarget = ExecutionTarget.NewWorktree)
                                )
                            },
                            label = { Text("New worktree") },
                            modifier = Modifier.testTag("workspace-new-worktree"),
                            enabled = !state.busy && state.journal == null,
                        )
                    }
                    Text(
                        when (options.executionTarget) {
                            ExecutionTarget.NewWorktree ->
                                "Starts from origin/HEAD in an isolated detached worktree."
                            else ->
                                options.workingDirectory?.let { "Uses ${File(it).name.ifBlank { it }} as-is." }
                                    ?: "Choose a project workspace."
                        },
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
            ModelControls(state, actions)
            Text(
                composerStatus,
                Modifier.fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 2.dp)
                    .testTag("composer-status"),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp).testTag("composer-actions"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = {
                        picker.launch(
                            PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly
                            )
                        )
                    },
                    enabled = !state.busy && state.journal == null,
                    modifier = Modifier.testTag("add-photos"),
                ) {
                    Text("Photos")
                }
                TextButton(
                    onClick = { actions.prepareCamera()?.let(camera::launch) },
                    enabled = !state.busy && state.journal == null,
                    modifier = Modifier.testTag("add-camera"),
                ) {
                    Text("Camera")
                }
                Spacer(Modifier.weight(1f))
                if (modes.isNotEmpty())
                    Box {
                        TextButton(
                            onClick = { modeMenu = true },
                            modifier = Modifier.testTag("mode-selector"),
                            enabled =
                                state.ready &&
                                    !state.busy &&
                                    state.activeTurn == null &&
                                    state.journal == null,
                        ) {
                            Text(selectedMode?.name ?: "Server default", fontSize = 12.sp)
                        }
                        DropdownMenu(
                            expanded = modeMenu,
                            onDismissRequest = { modeMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Server default") },
                                onClick = {
                                    modeMenu = false
                                    actions.updateNewTaskOptions(
                                        state.newTaskOptions.copy(collaborationMode = null)
                                    )
                                },
                                modifier = Modifier.testTag("mode-server-default"),
                            )
                            modes.forEach { preset ->
                                DropdownMenuItem(
                                    text = { Text(preset.name) },
                                    onClick = {
                                        modeMenu = false
                                        actions.updateNewTaskOptions(
                                            state.newTaskOptions.copy(
                                                collaborationMode = preset.mode
                                            )
                                        )
                                    },
                                    modifier = Modifier.testTag("mode-${preset.mode}"),
                                )
                            }
                        }
                    }
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
                            (state.draft.isNotBlank() || state.attachments.isNotEmpty()) &&
                            state.journal == null &&
                            (state.thread != null ||
                                projectAvailable &&
                                    state.newTaskOptions.hasExecutionDestination()) &&
                            (state.activeTurn != null ||
                                (state.newTaskOptions.model == null &&
                                    state.newTaskOptions.reasoningEffort == null) ||
                                state.modelCatalogStatus == ModelCatalogStatus.Ready),
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

@Composable
private fun ModelControls(state: ScreenState, actions: ConversationActions) {
    var modelExpanded by remember { mutableStateOf(false) }
    var effortExpanded by remember { mutableStateOf(false) }
    val controlsEnabled =
        state.ready &&
            !state.busy &&
            state.activeTurn == null &&
            state.modelCatalogStatus == ModelCatalogStatus.Ready
    val selectedModel =
        state.newTaskOptions.model?.let { id -> state.models.firstOrNull { it.id == id } }
    val effectiveModel =
        effectiveModel(state.newTaskOptions, state.models, state.threadModel)
    val efforts = effectiveModel?.supportedReasoningEfforts.orEmpty()
    val defaultEffort =
        if (state.newTaskOptions.model == null && state.thread != null)
            state.threadReasoningEffort
        else effectiveModel?.defaultReasoningEffort
    val modelLabel =
        when (state.modelCatalogStatus) {
            ModelCatalogStatus.Loading -> "Loading models…"
            ModelCatalogStatus.Error -> "Models unavailable"
            ModelCatalogStatus.Unavailable -> "Models offline"
            ModelCatalogStatus.Ready ->
                selectedModel?.displayName ?: selectedModel?.id ?: "Server default"
        }

    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.weight(1f)) {
            OutlinedButton(
                onClick = { modelExpanded = true },
                modifier = Modifier.fillMaxWidth().testTag("model-selector"),
                enabled = controlsEnabled,
                contentPadding = PaddingValues(horizontal = 12.dp),
            ) {
                Text(modelLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            DropdownMenu(
                expanded = modelExpanded,
                onDismissRequest = { modelExpanded = false },
            ) {
                DropdownMenuItem(
                    text = {
                        Column {
                            Text("Server default")
                            effectiveModel
                                ?.takeIf { state.newTaskOptions.model == null }
                                ?.let { Text(it.displayName ?: it.id, fontSize = 12.sp) }
                        }
                    },
                    onClick = {
                        modelExpanded = false
                        actions.updateNewTaskOptions(
                            state.newTaskOptions.copy(model = null, reasoningEffort = null)
                        )
                    },
                )
                state.models.forEach { model ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(model.displayName ?: model.id)
                                model.description?.let { Text(it, fontSize = 12.sp) }
                            }
                        },
                        onClick = {
                            modelExpanded = false
                            actions.updateNewTaskOptions(
                                state.newTaskOptions.copy(
                                    model = model.id,
                                    reasoningEffort = null,
                                )
                            )
                        },
                    )
                }
            }
        }
        if (efforts.isNotEmpty() && state.modelCatalogStatus == ModelCatalogStatus.Ready) {
            Box(Modifier.weight(1f)) {
                OutlinedButton(
                    onClick = { effortExpanded = true },
                    modifier = Modifier.fillMaxWidth().testTag("reasoning-selector"),
                    enabled = controlsEnabled,
                    contentPadding = PaddingValues(horizontal = 12.dp),
                ) {
                    Text(
                        state.newTaskOptions.reasoningEffort ?: "Default reasoning",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                DropdownMenu(
                    expanded = effortExpanded,
                    onDismissRequest = { effortExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text("Default reasoning")
                                defaultEffort?.let {
                                    Text("Server default: $it", fontSize = 12.sp)
                                }
                            }
                        },
                        onClick = {
                            effortExpanded = false
                            actions.updateNewTaskOptions(
                                state.newTaskOptions.copy(reasoningEffort = null)
                            )
                        },
                    )
                    efforts.forEach { effort ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(effort.id)
                                    effort.description?.let { Text(it, fontSize = 12.sp) }
                                }
                            },
                            onClick = {
                                effortExpanded = false
                                actions.updateNewTaskOptions(
                                    state.newTaskOptions.copy(reasoningEffort = effort.id)
                                )
                            },
                        )
                    }
                }
            }
        }
        if (state.modelCatalogStatus == ModelCatalogStatus.Loading) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        } else {
            IconButton(
                onClick = actions::refreshModels,
                enabled = state.ready && !state.busy,
                modifier = Modifier.size(48.dp),
            ) {
                Glyph(R.drawable.ic_refresh, "Refresh models")
            }
        }
    }
    state.modelCatalogMessage?.let {
        Text(
            it,
            Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
