package dev.codexops.client

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConversationComposer(state: ScreenState, actions: ConversationActions, onSend: () -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) {
        actions.addAttachments(it)
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
        actions.addFiles(it)
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { actions.finishCamera(it) }
    var tray by rememberSaveable(state.thread) { mutableStateOf(false) }
    val window = LocalAppWindowClass.current
    val compact = window.coverScreen || window.compactHeight
    val density = LocalDensity.current
    val ime = WindowInsets.ime
    val typing by remember(ime, density) { derivedStateOf { ime.getBottom(density) > 0 } }
    val settings = state.composerSettings()
    val project = state.projects.firstOrNull { it.id == state.newTaskOptions.projectId }
    val projectAvailable = state.newTaskOptions.projectId == null || project?.primaryRoot != null
    val bottomActions: @Composable (Boolean) -> Unit = { cameraDock ->
        ComposerActions(state, actions, settings,
            onSettings = { focus.clearFocus(); keyboard?.hide(); tray = !tray },
            onPhotos = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            onFiles = { filePicker.launch(arrayOf("*/*")) },
            onCamera = { actions.prepareCamera()?.let(camera::launch) },
            onSend = { tray = false; onSend(); keyboard?.hide(); actions.send() },
            projectAvailable = projectAvailable, cameraDock = cameraDock)
    }
    Surface(
        Modifier.padding(horizontal = if (compact) 8.dp else 12.dp, vertical = if (compact) 4.dp else 8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(if (compact) 6.dp else 8.dp)) {
            if (!typing || !compact) MessageQueue(state, actions, compact || typing)
            if (state.attachments.isNotEmpty()) LazyRow(
                Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.attachments, key = { it.id }) { attachment ->
                    DraftAttachmentPreview(attachment) { actions.removeAttachment(attachment.id) }
                }
            }
            if (!tray && !window.coverScreen && !state.willQueueMessage() && !window.compactHeight && !typing)
                ProjectControl(state, actions, expanded = false)
            FullscreenEditorSync(state.draft) {
                TextField(state.draft, actions::draft, Modifier.fillMaxWidth().testTag("composer"),
                    placeholder = { Text("Message ${state.host.displayName}…") },
                    trailingIcon = when {
                        window.coverScreen && !tray && typing -> {
                            { Box(Modifier.width(if (state.activeTurn != null) 208.dp else 156.dp)) { bottomActions(false) } }
                        }
                        window.coverScreen && !tray && state.activeTurn != null -> {
                            { IconButton(actions::stop, enabled = state.ready) { Glyph(R.drawable.ic_stop, "Stop") } }
                        }
                        else -> null
                    },
                    minLines = if (compact || typing || state.willQueueMessage()) 1 else 2,
                    maxLines = if (window.coverScreen && typing) 1 else if (window.compactHeight && !window.coverScreen) 2 else if (compact) 3 else 6, enabled = !state.busy,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent))
            }
            if (state.waitingToSendMode() || state.willQueueMessage() || !projectAvailable)
                Text(when {
                    state.waitingToSendMode() -> "${settings.mode} selected · send when the task is idle"
                    state.willQueueMessage() -> "Sends after this turn · uses task settings"
                    else -> "Choose an available project or No project"
                }, Modifier.padding(horizontal = 8.dp).testTag("composer-status"), style = MaterialTheme.typography.bodySmall)
            if (compact && typing && state.queuedMessages.isNotEmpty())
                TextButton({ focus.clearFocus(); keyboard?.hide() }, Modifier.testTag("show-queue")) {
                    Text("Queued (${state.queuedMessages.size})")
                }
            if (!tray && !(window.coverScreen && typing)) bottomActions(window.coverScreen)
        }
    }
    ProjectAdditionSheet(state, actions)
    if (tray && !state.projectAddition.visible) ModalBottomSheet(
        onDismissRequest = { tray = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.85f).dp)
            .padding(horizontal = 16.dp).testTag("conversation-tray")) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Conversation", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                if (window.coverScreen && state.activeTurn != null)
                    IconButton(actions::stop, enabled = state.ready) { Glyph(R.drawable.ic_stop, "Stop") }
                IconButton({ tray = false }) { Glyph(R.drawable.ic_close, "Close conversation settings") }
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ProjectControl(state, actions, expanded = true)
                ModelControls(state, actions)
                SpeedControl(state, actions)
                ModeControls(state, actions)
                TextButton(actions::refreshModels, enabled = state.ready && !state.busy && state.modelCatalogStatus != ModelCatalogStatus.Loading) {
                    Glyph(R.drawable.ic_refresh); Spacer(Modifier.width(8.dp)); Text("Refresh models and settings")
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            bottomActions(window.coverScreen)
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ComposerActions(
    state: ScreenState, actions: ConversationActions, settings: ComposerSettings,
    onSettings: () -> Unit, onPhotos: () -> Unit, onFiles: () -> Unit, onCamera: () -> Unit,
    onSend: () -> Unit, projectAvailable: Boolean, cameraDock: Boolean,
) {
    var attachments by remember { mutableStateOf(false) }
    val shortWindow = LocalAppWindowClass.current.let { it.compactHeight && !it.coverScreen }
    val attachmentControl: @Composable () -> Unit = {
        Box {
            OutlinedIconButton({ attachments = true }, Modifier.size(48.dp).testTag("add-menu"),
                enabled = !state.busy && state.journal == null) { Glyph(R.drawable.ic_attach, "Add attachment") }
            DropdownMenu(attachments, { attachments = false }) {
                listOf(Triple("Photos", R.drawable.ic_photo, onPhotos), Triple("Files", R.drawable.ic_file, onFiles),
                    Triple("Camera", R.drawable.ic_camera, onCamera)).forEach { (label, icon, action) ->
                    DropdownMenuItem(text = { Text(label) }, leadingIcon = { Glyph(icon) },
                        onClick = { attachments = false; action() }, modifier = Modifier.testTag("add-${label.lowercase()}"))
                }
            }
        }
    }
    val settingsDescription = listOfNotNull("Conversation settings", settings.model, settings.effort.takeIf { it.isNotBlank() }, settings.mode, if (state.composerSpeed().fast) "Fast mode" else null).joinToString(", ")
    val settingsControl: @Composable (Modifier) -> Unit = { modifier ->
        OutlinedButton(onSettings, modifier.heightIn(min = 48.dp).testTag("conversation-settings")
            .semantics {
                contentDescription = settingsDescription
            },
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = if (shortWindow) 4.dp else 8.dp), shape = RoundedCornerShape(28.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSecondaryContainer)) {
            Glyph(R.drawable.ic_sliders, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(listOf(settings.model, settings.effort).filter { it.isNotBlank() }.joinToString(" · "), Modifier.weight(1f, fill = false), style = MaterialTheme.typography.labelLarge)
                    if (state.composerSpeed().fast) Glyph(R.drawable.ic_fast, modifier =
                        Modifier.size(16.dp).testTag("fast-mode-icon"))
                }

                settings.mode?.let { mode ->
                    Text(mode, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    val sendControls: @Composable () -> Unit = {
        if (state.activeTurn != null && !cameraDock) IconButton(actions::stop, Modifier.size(48.dp), enabled = state.ready) {
            Glyph(R.drawable.ic_stop, "Stop")
        }
        FilledIconButton(onSend, Modifier.testTag("send").size(48.dp), shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary),
            enabled = state.ready && !state.busy && !state.speedSaving && !state.speedUncertain && !state.merge.blocksTask && !state.waitingToSendMode() &&
                (state.thread != null || !isFastTier(state.newTaskOptions.serviceTier) || state.canSelectFast()) &&
                (state.draft.isNotBlank() || state.attachments.isNotEmpty()) && state.journal == null &&
                (state.newTaskOptions.collaborationMode == null || state.collaborationModes.any {
                    it.mode == state.newTaskOptions.collaborationMode && it.turnSetting(state.collaborationModel()) != null
                }) &&
                (state.thread == null || state.queueReady) &&
                (state.thread != null || projectAvailable && state.newTaskOptions.hasExecutionDestination()) &&
                (state.willQueueMessage() || (state.newTaskOptions.model == null && state.newTaskOptions.reasoningEffort == null) ||
                    state.modelCatalogStatus == ModelCatalogStatus.Ready)) {
            Glyph(if (state.willQueueMessage()) R.drawable.ic_queue else R.drawable.ic_send,
                if (state.willQueueMessage()) "Queue message" else "Send")
        }    }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = if (shortWindow) 2.dp else 6.dp).testTag("composer-actions")) {
        val stacked = maxWidth < 340.dp || LocalDensity.current.fontScale > 1.3f
        if (LocalAppWindowClass.current.coverScreen) {
            // The Razr's lenses and flash occupy the lower right. Keep the text field
            // above that band and all three 48dp touch targets in its left-hand pocket.
            // Only the IME lifts this row above the hardware; the settings sheet
            // keeps the same camera clearance as the resting composer.
            Row(Modifier.fillMaxWidth().heightIn(min = if (cameraDock) 80.dp else 48.dp)
                .testTag(if (cameraDock) "cover-camera-dock" else "cover-composer-toolbar"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                sendControls()
                attachmentControl()
                OutlinedIconButton(onSettings, Modifier.size(48.dp).testTag("conversation-settings")
                    .semantics { contentDescription = settingsDescription }) {
                    Glyph(R.drawable.ic_sliders)
                }
            }
        } else if (stacked) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            settingsControl(Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                attachmentControl()
                Spacer(Modifier.weight(1f))
                sendControls()
            }
        } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            attachmentControl()
            settingsControl(Modifier.weight(1f))
            sendControls()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProjectControl(state: ScreenState, actions: ConversationActions, expanded: Boolean) {
    var menu by remember { mutableStateOf(false) }
    val options = state.newTaskOptions
    val project = state.projects.firstOrNull { it.id == options.projectId }
    val label = if (state.thread != null) state.threadCwd?.let { File(it).name } ?: "This chat"
        else project?.name ?: if (options.projectId == null) "No project" else "Project unavailable"
    val enabled = state.thread == null && !state.busy && state.journal == null
    Column {
        Box {
            if (expanded) TrayRow("Project", label, if (state.thread != null) "This chat" else null,
                R.drawable.ic_folder, "project-selector", enabled, { menu = true })
            else AssistChip(onClick = { menu = true }, label = { Text(label) }, enabled = enabled,
                leadingIcon = { Glyph(R.drawable.ic_folder, modifier = Modifier.size(18.dp)) },
                modifier = Modifier.padding(start = 8.dp).heightIn(min = 48.dp).testTag("project-selector"))
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text("Add project") },
                    onClick = { menu = false; actions.openAddProject() },
                    modifier = Modifier.testTag("add-project"))
                DropdownMenuItem(text = { Text("No project") }, leadingIcon = { Glyph(R.drawable.ic_folder) },
                    onClick = { menu = false; actions.updateNewTaskOptions(options.copy(projectId = null,
                        workingDirectory = null, executionTarget = ExecutionTarget.Projectless)) })
                state.projects.forEach { candidate ->
                    DropdownMenuItem(text = { Text(candidate.name) }, leadingIcon = { Glyph(R.drawable.ic_folder) },
                        enabled = candidate.primaryRoot != null,
                        onClick = { menu = false; actions.updateNewTaskOptions(options.copy(projectId = candidate.id,
                            workingDirectory = candidate.primaryRoot, executionTarget = ExecutionTarget.CurrentWorkspace)) })
                }
            }
        }
        if (expanded && state.thread == null && options.projectId != null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(ExecutionTarget.CurrentWorkspace to "Current", ExecutionTarget.NewWorktree to "New worktree").forEach { (target, title) ->
                    FilterChip(options.executionTarget == target, { actions.updateNewTaskOptions(options.copy(executionTarget = target)) },
                        label = { Text(title) }, enabled = enabled,
                        modifier = Modifier.testTag(if (target == ExecutionTarget.CurrentWorkspace) "workspace-current" else "workspace-new-worktree"))
                }
            }
            Text(if (options.executionTarget == ExecutionTarget.NewWorktree) "Settings resolve after the isolated workspace is created."
                else options.workingDirectory.orEmpty(), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun TrayRow(title: String, value: String, source: String?, icon: Int, tag: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag(tag),
        shape = RoundedCornerShape(16.dp), color = Color.Transparent,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.padding(16.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Glyph(icon)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.labelLarge)
                Text(value, style = MaterialTheme.typography.bodyLarge)
                source?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (enabled) Glyph(R.drawable.ic_chevron)
        }
    }
}

@Composable
private fun ModelControls(state: ScreenState, actions: ConversationActions) {
    var modelMenu by remember { mutableStateOf(false) }
    var effortMenu by remember { mutableStateOf(false) }
    val settings = state.composerSettings()
    val preset = state.collaborationModes.firstOrNull { it.mode == state.newTaskOptions.collaborationMode }
    val enabled = state.ready && !state.busy && state.journal == null && !state.willQueueMessage() &&
        state.modelCatalogStatus == ModelCatalogStatus.Ready
    val efforts = state.models.firstOrNull { it.id == settings.modelId }?.supportedReasoningEfforts.orEmpty()
    Box {
        TrayRow("Model", settings.model, settings.modelSource, R.drawable.ic_model, "model-selector",
            enabled && preset?.model == null, { modelMenu = true })
        DropdownMenu(modelMenu, { modelMenu = false }) {
            DropdownMenuItem(text = { Text("Automatic (inherit)") }, modifier = Modifier.testTag("model-automatic"),
                onClick = { modelMenu = false; actions.updateNewTaskOptions(state.newTaskOptions.copy(model = null, reasoningEffort = null)) })
            state.models.forEach { model ->
                DropdownMenuItem(text = { Text(model.displayName ?: model.id) }, onClick = {
                    modelMenu = false; actions.updateNewTaskOptions(state.newTaskOptions.copy(model = model.id, reasoningEffort = null)) })
            }
        }
    }
    Box {
        TrayRow("Reasoning", settings.effort, settings.effortSource, R.drawable.ic_reasoning, "reasoning-selector",
            enabled && preset == null && efforts.isNotEmpty(), { effortMenu = true })
        DropdownMenu(effortMenu, { effortMenu = false }) {
            DropdownMenuItem(text = { Text("Automatic (inherit)") }, modifier = Modifier.testTag("reasoning-automatic"),
                onClick = { effortMenu = false; actions.updateNewTaskOptions(state.newTaskOptions.copy(reasoningEffort = null)) })
            efforts.forEach { effort ->
                DropdownMenuItem(text = { Text(effort.id.replaceFirstChar { it.uppercase() }) }, onClick = {
                    effortMenu = false; actions.updateNewTaskOptions(state.newTaskOptions.copy(reasoningEffort = effort.id)) })
            }
        }
    }
    state.modelCatalogMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}

@Composable
private fun ModeControls(state: ScreenState, actions: ConversationActions) {
    val modes = state.collaborationModes.filter { it.turnSetting(state.collaborationModel()) != null }
    if (modes.isEmpty() && state.newTaskOptions.collaborationMode == null) return
    val activeMode = state.newTaskOptions.collaborationMode ?: state.threadMode ?: if (state.thread == null) "default" else null
    val enabled = state.ready && !state.busy && state.journal == null
    Surface(shape = RoundedCornerShape(16.dp), color = Color.Transparent,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Glyph(R.drawable.ic_code)
                Text("Mode", style = MaterialTheme.typography.labelLarge)
            }
            if (modes.isEmpty()) Text("This mode needs resolved workspace settings. Choose Automatic to inherit.",
                style = MaterialTheme.typography.bodySmall)
            BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                if (maxWidth < 280.dp || LocalDensity.current.fontScale > 1.3f) {
                    Column {
                        modes.forEach { mode ->
                            FilterChip(selected = activeMode == mode.mode,
                                onClick = { actions.updateNewTaskOptions(state.newTaskOptions.copy(collaborationMode = mode.mode)) },
                                enabled = enabled, label = { Text(mode.name) },
                                leadingIcon = { Glyph(if (mode.mode == "plan") R.drawable.ic_plan else R.drawable.ic_code) },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("mode-${mode.mode}"))
                        }
                    }
                } else SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    modes.forEachIndexed { index, mode ->
                        SegmentedButton(selected = activeMode == mode.mode,
                            onClick = { actions.updateNewTaskOptions(state.newTaskOptions.copy(collaborationMode = mode.mode)) },
                            enabled = enabled, shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                            icon = { Glyph(if (mode.mode == "plan") R.drawable.ic_plan else R.drawable.ic_code) },
                            modifier = Modifier.heightIn(min = 48.dp).testTag("mode-${mode.mode}")) { Text(mode.name) }
                    }
                }
            }
            TextButton({ actions.updateNewTaskOptions(state.newTaskOptions.copy(collaborationMode = null)) },
                enabled = enabled, modifier = Modifier.testTag("mode-server-default")) { Text("Automatic (inherit)") }
        }
    }
}

@Composable
private fun MessageQueue(state: ScreenState, actions: ConversationActions, cover: Boolean) {
    if (state.queuedMessages.isEmpty() && state.queueError == null) return
    val enabled = state.ready && state.queueReady && !state.busy && state.journal == null && !state.merge.blocksTask
    Column(Modifier.fillMaxWidth().testTag("message-queue")) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Queued (${state.queuedMessages.size})",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
            )
            if (state.queueError != null)
                TextButton(actions::refreshQueue, enabled = state.ready && !state.busy) {
                    Text("Refresh")
                }
        }
        if (state.queueError != null)
            Text(state.queueError, Modifier.padding(horizontal = 12.dp), fontSize = 11.sp)
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = if (cover) 112.dp else 176.dp)) {
            items(state.queuedMessages, key = { it.id }) { message ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp).testTag("queued-${message.id}")) {
                    Text(
                        message.preview,
                        Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            { actions.removeQueued(message.id) },
                            enabled = enabled,
                            modifier = Modifier.testTag("remove-queued-${message.id}"),
                        ) { Text("Remove") }
                        Spacer(Modifier.weight(1f))
                        TextButton(
                            { actions.sendQueuedNow(message.id) },
                            enabled = enabled,
                            modifier = Modifier.testTag("send-queued-${message.id}"),
                        ) { Text(if (state.activeTurn != null) "Steer now" else "Send now") }
                    }
                }
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun SpeedControl(state: ScreenState, actions: ConversationActions) {
    var menu by remember(state.thread) { mutableStateOf(false) }
    val speed = state.composerSpeed()
    val enabled = state.ready && !state.busy && state.journal == null && !state.speedSaving &&
        !state.speedUncertain && (state.thread == null || speed.known)
    val explanation = when {
        state.speedSaving -> "Saving…"
        state.speedUncertain -> "Awaiting server confirmation"
        !state.ready -> "Offline"
        state.thread == null && state.newTaskOptions.serviceTier == null -> "Inherited for this new chat"
        state.activeTurn != null -> "Applies to subsequent turns"
        else -> "This chat"
    }
    Box {
        TrayRow("Speed", speed.label, explanation, R.drawable.ic_fast, "speed-selector", enabled) { menu = true }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(text = { Text("Standard") }, modifier = Modifier.testTag("speed-standard"),
                trailingIcon = { if (speed.known && speed.label == "Standard") Text("✓") },
                onClick = { menu = false; actions.selectSpeed(false) })
            DropdownMenuItem(text = { Column {
                Text("Fast")
                Text(if (state.canSelectFast()) "Faster responses, increased usage" else "Unavailable for this model or host",
                    style = MaterialTheme.typography.bodySmall)
            } }, enabled = state.canSelectFast(), modifier = Modifier.testTag("speed-fast"),
                trailingIcon = { if (speed.fast) Text("✓") },
                onClick = { menu = false; actions.selectSpeed(true) })
        }
    }
    state.speedError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
}
