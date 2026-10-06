package dev.codexops.client

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.roundToInt

/** Physical left/right gestures, independent of text direction. No velocity-only dismissal. */
@Composable
internal fun TaskSwipeRow(
    id: String,
    archived: Boolean,
    unread: Boolean,
    archiveEnabled: Boolean,
    unreadEnabled: Boolean,
    snoozeEnabled: Boolean,
    pending: Boolean,
    revealed: Boolean,
    onReveal: (Boolean) -> Unit,
    onOpen: () -> Unit,
    onCopy: () -> Unit,
    onArchive: () -> Unit,
    onToggleUnread: () -> Unit,
    onSnooze: () -> Unit,
    content: @Composable () -> Unit,
) {
    var width by remember { mutableIntStateOf(0) }
    var drag by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    var armed by remember { mutableStateOf(false) }
    var closingTray by remember { mutableStateOf(false) }
    val actionWidth = 88.dp
    val trayWidthDp = actionWidth * 2
    val trayWidth = with(LocalDensity.current) { trayWidthDp.toPx() }
    val revealThreshold = trayWidth / 2
    val maxThreshold = with(LocalDensity.current) { 112.dp.toPx() }
    val threshold = (width * 0.35f).coerceAtMost(maxThreshold).coerceAtLeast(1f)
    val haptics by rememberUpdatedState(LocalAppHaptics.current)
    val unreadLabel = if (unread) "Mark read" else "Mark unread"
    val archiveLabel = if (archived) "Unarchive" else "Archive"
    val open by rememberUpdatedState(onOpen)
    val copy by rememberUpdatedState(onCopy)
    val archive by rememberUpdatedState(onArchive)
    val toggleUnread by rememberUpdatedState(onToggleUnread)
    val snooze by rememberUpdatedState(onSnooze)
    val reveal by rememberUpdatedState(onReveal)
    val isRevealed by rememberUpdatedState(revealed)
    val offset by animateFloatAsState(
        drag,
        animationSpec = if (dragging) snap() else spring(),
        label = "Task swipe return",
    )
    // A reconnect, filter change, or mutation completion must never retain an armed gesture.
    LaunchedEffect(revealed, archiveEnabled, unreadEnabled, archived, unread, pending) {
        drag = if (revealed && unreadEnabled && !pending) trayWidth else 0f
        dragging = false
        armed = false
    }
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .onSizeChanged { width = it.width },
    ) {
        if (offset > 0f) {
            Box(
                Modifier.matchParentSize(),
                contentAlignment = AbsoluteAlignment.CenterLeft,
            ) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Row(Modifier.width(trayWidthDp).fillMaxHeight()) {
                        Column(
                            Modifier.width(actionWidth).fillMaxHeight()
                                .background(MaterialTheme.colorScheme.secondaryContainer)
                                .testTag("task-unread-action-$id")
                                .clickable(enabled = revealed && unreadEnabled && !pending,
                                    role = Role.Button, onClickLabel = unreadLabel) {
                                    reveal(false)
                                    toggleUnread()
                                }
                                .padding(vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Glyph(R.drawable.ic_unread)
                            Spacer(Modifier.height(6.dp))
                            Text(if (unread) "Read" else "Unread",
                                color = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                        Column(
                            Modifier.width(actionWidth).fillMaxHeight()
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .testTag("task-snooze-action-$id")
                                .clickable(enabled = revealed && snoozeEnabled && !pending,
                                    role = Role.Button, onClickLabel = "Snooze for one hour") {
                                    reveal(false)
                                    snooze()
                                }.padding(vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Glyph(R.drawable.ic_snooze)
                            Spacer(Modifier.height(6.dp))
                            Text("Snooze", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        if (offset < 0f) {
            Row(
                Modifier.matchParentSize()
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .padding(horizontal = 18.dp),
                horizontalArrangement = Arrangement.Absolute.Right,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Glyph(
                        if (archived) R.drawable.ic_unarchive else R.drawable.ic_archive,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        archiveLabel,
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
                            add(CustomAccessibilityAction(unreadLabel) { reveal(false); toggleUnread(); true })
                        if (snoozeEnabled)
                            add(CustomAccessibilityAction("Snooze for one hour") { reveal(false); snooze(); true })
                        add(CustomAccessibilityAction("Copy deep link") { copy(); true })
                    }
                }
                .pointerInput(archiveEnabled, unreadEnabled, threshold, revealThreshold) {
                    detectHorizontalDragGestures(
                        onDragStart = {
                            dragging = true
                            closingTray = drag > 0f && isRevealed
                            armed = closingTray
                        },
                        onDragCancel = {
                            dragging = false
                            drag = if (isRevealed) trayWidth else 0f
                            armed = false
                        },
                        onDragEnd = {
                            val shouldArchive = !closingTray && drag <= -threshold && archiveEnabled
                            val shouldReveal = drag >= revealThreshold && unreadEnabled
                            dragging = false
                            drag = if (shouldReveal) trayWidth else 0f
                            armed = false
                            reveal(shouldReveal)
                            if (shouldArchive) archive()
                        },
                    ) { change, delta ->
                        change.consume()
                        drag = (drag + delta).coerceIn(
                            if (archiveEnabled && !closingTray) -threshold * 1.45f else 0f,
                            if (unreadEnabled) trayWidth else 0f,
                        )
                        val nowArmed = drag <= -threshold || drag >= revealThreshold
                        if (nowArmed && !armed)
                            haptics.tick()
                        armed = nowArmed
                    }
                }
                .combinedClickable(
                    onClick = { if (revealed) reveal(false) else open() },
                    onLongClickLabel = "Copy deep link",
                    onLongClick = { copy() },
                ),
        ) { content() }
    }
}
