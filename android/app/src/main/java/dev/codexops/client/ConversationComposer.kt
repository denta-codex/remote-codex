package dev.codexops.client

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
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
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (state.activeTurn != null) "Follow-up guides the active turn"
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
                            state.journal == null,
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
