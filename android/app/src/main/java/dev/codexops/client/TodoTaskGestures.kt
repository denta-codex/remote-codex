package dev.codexops.client

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

/** The list owns long-press drags so scrolling the source row offscreen cannot cancel a drag. */
@Composable
internal fun TodoTaskList(tasks: List<TodoItem>, page: Int, enabled: Boolean, openEnabled: Boolean,
    actions: TodoActions, cover: Boolean, notices: @Composable () -> Unit, empty: @Composable () -> Unit) {
    val list = rememberLazyListState()
    var dragging by remember { mutableStateOf<TodoItem?>(null) }
    var top by remember { mutableFloatStateOf(0f) }
    var height by remember { mutableIntStateOf(0) }
    val edge = with(LocalDensity.current) { 48.dp.toPx() }
    val haptics by rememberUpdatedState(LocalAppHaptics.current)
    val currentActions by rememberUpdatedState(actions)
    fun target() = list.layoutInfo.visibleItemsInfo.filter { info -> tasks.any { it.id == info.key } }
        .minByOrNull { abs(it.offset + it.size / 2f - (top + height / 2f + list.layoutInfo.viewportStartOffset)) }
    LaunchedEffect(dragging != null) {
        while (dragging != null) {
            val center = top + height / 2f
            val scroll = when {
                center < edge -> -edge / 4
                center > list.layoutInfo.viewportEndOffset - list.layoutInfo.viewportStartOffset - edge -> edge / 4
                else -> 0f
            }
            if (scroll != 0f) list.scroll { scrollBy(scroll) }
            delay(16)
        }
    }
    LaunchedEffect(enabled) { if (!enabled) dragging = null }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().testTag("todo-list-$page")
            .pointerInput(enabled, tasks) {
                if (enabled) awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val held = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                    val position = held.position.y + list.layoutInfo.viewportStartOffset
                    val row = list.layoutInfo.visibleItemsInfo.firstOrNull {
                        position >= it.offset && position <= it.offset + it.size
                    }
                    val source = tasks.firstOrNull { it.id == row?.key } ?: return@awaitEachGesture
                    if (row == null) return@awaitEachGesture
                    dragging = source
                    top = row.offset.toFloat() - list.layoutInfo.viewportStartOffset
                    height = row.size
                    haptics.longPress()
                    try {
                        while (true) {
                            // Claim held drags before the child row can start a horizontal swipe.
                            val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == held.id }
                                ?: break
                            if (!change.pressed) {
                                change.consume()
                                val destination = target()
                                val id = destination?.key as? Long
                                if (destination != null && id != null && id != source.id)
                                    currentActions.reorderTodo(source.id, id,
                                        top + height / 2f + list.layoutInfo.viewportStartOffset > destination.offset + destination.size / 2f)
                                break
                            }
                            top += change.positionChange().y
                            change.consume()
                        }
                    } finally { dragging = null }
                }
            }, state = list,
            contentPadding = PaddingValues(if (cover) 12.dp else 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { notices() }
            if (tasks.isEmpty()) item { empty() }
            items(tasks, key = { it.id }) { task ->
                Box(Modifier.alpha(if (dragging?.id == task.id) 0.25f else 1f)) {
                    TodoTaskCard(task, tasks, enabled && dragging == null, openEnabled && dragging == null, actions)
                }
            }
        }
        dragging?.let { task ->
            OutlinedCard(Modifier.offset { IntOffset(0, top.roundToInt()) }
                .padding(horizontal = if (cover) 12.dp else 20.dp).fillMaxWidth()
                .graphicsLayer { shadowElevation = 8.dp.toPx() }) {
                Text(task.title, Modifier.fillMaxWidth().padding(16.dp), fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun TodoTaskCard(task: TodoItem, tasks: List<TodoItem>,
    enabled: Boolean, openEnabled: Boolean, actions: TodoActions) {
    var x by remember { mutableFloatStateOf(0f) }
    val threshold = with(LocalDensity.current) { 96.dp.toPx() }
    val currentActions by rememberUpdatedState(actions)
    val page = todoStatuses.indexOf(task.status)
    LaunchedEffect(enabled, task.status) { x = 0f }
    Box {
        if (x != 0f) Text("Move to ${todoStatuses[(page + if (x > 0) 1 else -1).coerceIn(0, 2)]}",
            Modifier.padding(16.dp), style = MaterialTheme.typography.labelMedium)
        OutlinedCard(onClick = { actions.openTodoTask(task.id) }, enabled = openEnabled,
            modifier = Modifier.fillMaxWidth().testTag("todo-task-${task.id}")
                .graphicsLayer { translationX = x }
                .semantics {
                    customActions = buildList {
                        if (enabled) {
                            if (page > 0) add(CustomAccessibilityAction("Move to ${todoStatuses[page - 1]}") { actions.moveTodoTask(task.id, todoStatuses[page - 1]); true })
                            if (page < 2) add(CustomAccessibilityAction("Move to ${todoStatuses[page + 1]}") { actions.moveTodoTask(task.id, todoStatuses[page + 1]); true })
                            val index = tasks.indexOfFirst { it.id == task.id }
                            if (index > 0) add(CustomAccessibilityAction("Move up") { actions.reorderTodo(task.id, tasks[index - 1].id, false); true })
                            if (index < tasks.lastIndex) add(CustomAccessibilityAction("Move down") { actions.reorderTodo(task.id, tasks[index + 1].id, true); true })
                        }
                    }
                }
                .pointerInput(enabled, task.status) {
                    if (enabled) detectHorizontalDragGestures(
                        onDragCancel = { x = 0f },
                        onDragEnd = {
                            val destination = page + if (x > 0) 1 else -1
                            if (abs(x) >= threshold && destination in todoStatuses.indices)
                                currentActions.moveTodoTask(task.id, todoStatuses[destination])
                            x = 0f
                        },
                    ) { change, delta ->
                        change.consume()
                        x = (x + delta).coerceIn(if (page > 0) -threshold * 1.5f else 0f,
                            if (page < 2) threshold * 1.5f else 0f)
                    }
                }) {
            Text(task.title, Modifier.fillMaxWidth().padding(16.dp), fontWeight = FontWeight.Medium)
        }
    }
}
