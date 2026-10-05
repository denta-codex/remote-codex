package dev.codexops.client

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt

/** Physical left/right gestures, independent of text direction. No velocity-only dismissal. */
@Composable
internal fun TaskSwipeRow(
    id: String,
    archived: Boolean,
    unread: Boolean,
    archiveEnabled: Boolean,
    unreadEnabled: Boolean,
    pending: Boolean,
    onOpen: () -> Unit,
    onCopy: () -> Unit,
    onArchive: () -> Unit,
    onUnread: () -> Unit,
    content: @Composable () -> Unit,
) {
    var width by remember { mutableIntStateOf(0) }
    var drag by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    var armed by remember { mutableStateOf(false) }
    val maxThreshold = with(LocalDensity.current) { 112.dp.toPx() }
    val threshold = (width * 0.35f).coerceAtMost(maxThreshold).coerceAtLeast(1f)
    val haptics by rememberUpdatedState(LocalAppHaptics.current)
    val archiveLabel = if (archived) "Unarchive" else "Archive"
    val open by rememberUpdatedState(onOpen)
    val copy by rememberUpdatedState(onCopy)
    val archive by rememberUpdatedState(onArchive)
    val markUnread by rememberUpdatedState(onUnread)
    val offset by animateFloatAsState(
        drag,
        animationSpec = if (dragging) snap() else spring(),
        label = "Task swipe return",
    )
    // A reconnect, filter change, or mutation completion must never retain an armed gesture.
    LaunchedEffect(archiveEnabled, unreadEnabled, archived) {
        drag = 0f
        dragging = false
        armed = false
    }
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .onSizeChanged { width = it.width },
    ) {
        if (offset != 0f) {
            Row(
                Modifier.matchParentSize()
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .padding(horizontal = 18.dp),
                horizontalArrangement = if (offset < 0) Arrangement.End else Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Glyph(
                        if (offset > 0) R.drawable.ic_unread
                        else if (archived) R.drawable.ic_unarchive else R.drawable.ic_archive,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (offset < 0) archiveLabel else "Unread",
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        }
        Box(
            Modifier.absoluteOffset { IntOffset(offset.roundToInt(), 0) }
                .fillMaxWidth().background(MaterialTheme.colorScheme.surface)
                .testTag("task-row-$id")
                .semantics {
                    stateDescription = when {
                        pending -> "Updating task"
                        unread -> "Unread"
                        else -> "Read"
                    }
                    customActions = buildList {
                        if (archiveEnabled)
                            add(CustomAccessibilityAction(archiveLabel) { archive(); true })
                        if (unreadEnabled)
                            add(CustomAccessibilityAction("Mark unread") { markUnread(); true })
                        add(CustomAccessibilityAction("Copy deep link") { copy(); true })
                    }
                }
                .pointerInput(archiveEnabled, unreadEnabled, threshold) {
                    detectHorizontalDragGestures(
                        onDragStart = { dragging = true },
                        onDragCancel = { dragging = false; drag = 0f; armed = false },
                        onDragEnd = {
                            val action = if (abs(drag) >= threshold) {
                                if (drag < 0 && archiveEnabled) archive
                                else if (drag > 0 && unreadEnabled) markUnread else null
                            } else null
                            dragging = false
                            drag = 0f
                            armed = false
                            action?.invoke()
                        },
                    ) { change, delta ->
                        change.consume()
                        drag = (drag + delta).coerceIn(
                            if (archiveEnabled) -threshold * 1.45f else 0f,
                            if (unreadEnabled) threshold * 1.45f else 0f,
                        )
                        val nowArmed = abs(drag) >= threshold
                        if (nowArmed && !armed)
                            haptics.tick()
                        armed = nowArmed
                    }
                }
                .combinedClickable(
                    onClick = { open() },
                    onLongClickLabel = "Copy deep link",
                    onLongClick = { copy() },
                ),
        ) { content() }
    }
}
