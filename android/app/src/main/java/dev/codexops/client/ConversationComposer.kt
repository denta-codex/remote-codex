package dev.codexops.client

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
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

@Composable
internal fun ConversationComposer(
    state: ScreenState,
    actions: ConversationActions,
    onSend: () -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    Surface(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(8.dp)) {
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
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (state.activeTurn != null) "Follow-up guides the active turn"
                    else if (
                        state.newTaskOptions.model != null ||
                            state.newTaskOptions.reasoningEffort != null
                    )
                        "Explicit overrides apply to the next turn"
                    else "${state.host.displayName} defaults",
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
