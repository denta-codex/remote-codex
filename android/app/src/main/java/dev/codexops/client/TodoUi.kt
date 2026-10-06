package dev.codexops.client

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TodoScreen(screen: ScreenState, actions: TodoActions) {
    val state = screen.todo
    val enabled = state.ready && state.loaded && !state.busy && state.pending == null
    val cover = LocalAppWindowClass.current.coverScreen
    Column(Modifier.fillMaxSize().testTag("todo-screen")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = if (cover) 8.dp else 16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(if (state.editor == null) "Your tasks" else if (state.editor.original == null) "New task" else "Edit task",
                Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            IconButton(actions::refreshTodo, enabled = !state.busy) {
                Glyph(R.drawable.ic_refresh, "Refresh Todo")
            }
            if (state.editor == null) TextButton(actions::newTodo, enabled = enabled) { Text("Add") }
            else TextButton(actions::saveTodo, enabled = enabled && state.editor.title.isNotBlank() &&
                state.editor.title.none(Char::isISOControl) && state.editor.dirty,
                modifier = Modifier.testTag("todo-save")) { Text("Save") }
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (!state.ready) {
            Text("Connect to Grace to use Todo.", Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            state.error?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
        }
        // Put notices in the scrollable content so recovery controls remain reachable on the cover.
        val editor = state.editor
        if (editor != null) {
            LazyColumn(Modifier.fillMaxSize().testTag("todo-editor"), contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { TodoNotices(state, state.ready, actions) }
                item {
                    OutlinedTextField(editor.title, actions::todoTitle, label = { Text("Title") }, singleLine = true,
                        isError = editor.title.any(Char::isISOControl),
                        supportingText = { if (editor.title.any(Char::isISOControl)) Text("Use a single-line title.") },
                        enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("todo-title"))
                }
                editor.original?.let { original ->
                    item {
                        var expanded by remember { mutableStateOf(false) }
                        Box {
                            OutlinedButton({ expanded = true }, enabled = enabled && !editor.dirty,
                                modifier = Modifier.testTag("todo-status")) { Text(original.status) }
                            DropdownMenu(expanded, { expanded = false }) {
                                todoStatuses.forEach { status -> DropdownMenuItem(text = { Text(status) }, onClick = {
                                    expanded = false; actions.moveTodo(status)
                                }) }
                            }
                        }
                        if (editor.dirty) Text("Save text edits before changing status.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                item {
                    OutlinedTextField(editor.description, actions::todoDescription,
                        label = { Text("Description") }, supportingText = { Text("Markdown and checklists supported") },
                        enabled = enabled, minLines = if (cover) 3 else 5,
                        modifier = Modifier.fillMaxWidth().testTag("todo-description"))
                }
                if (editor.original?.notes?.isNotEmpty() == true) item {
                    var expanded by remember(editor.original.id) { mutableStateOf(false) }
                    TextButton({ expanded = !expanded }) { Text("Work notes (${editor.original.notes.size})") }
                    if (expanded) editor.original.notes.forEach { note ->
                        Text(note, Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                item { TextButton(actions::closeTodoEditor, enabled = !state.busy) { Text("Close") } }
            }
        } else if (state.ready) {
            val pager = rememberPagerState(initialPage = todoStatuses.indexOf(state.status)) { todoStatuses.size }
            LaunchedEffect(state.status) { pager.animateScrollToPage(todoStatuses.indexOf(state.status)) }
            LaunchedEffect(pager) {
                snapshotFlow { pager.settledPage }.distinctUntilChanged().collect { actions.selectTodoStatus(todoStatuses[it]) }
            }
            TabRow(selectedTabIndex = pager.currentPage) {
                todoStatuses.forEachIndexed { index, status ->
                    Tab(selected = pager.currentPage == index, onClick = { actions.selectTodoStatus(status) },
                        modifier = Modifier.testTag("todo-tab-$index"), text = {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(status, style = MaterialTheme.typography.labelMedium)
                                Text(state.items.count { it.status == status }.toString(), style = MaterialTheme.typography.labelSmall)
                            }
                        })
                }
            }
            Text("Swipe a task to move it; hold and drag to reorder. Order is saved on this phone.",
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall)
            HorizontalPager(pager, Modifier.weight(1f).testTag("todo-columns"), userScrollEnabled = false) { page ->
                PullToRefreshBox(isRefreshing = state.busy, onRefresh = actions::refreshTodo, modifier = Modifier.fillMaxSize()) {
                    val tasks = state.items.filter { it.status == todoStatuses[page] }
                    TodoTaskList(tasks, page, enabled, !state.busy && state.loaded, actions, cover,
                        notices = { TodoNotices(state, state.ready, actions) },
                        empty = {
                            if (state.loaded) Text("No tasks ${when (page) { 0 -> "to do"; 1 -> "in progress"; else -> "done yet" }}.",
                                Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        })
                }
            }
        }
    }
    if (state.confirmDiscard) AlertDialog(onDismissRequest = actions::keepTodoEditor,
        title = { Text("Discard unsaved edits?") },
        text = { Text("Your unsaved text will be discarded. This does not change any task on Grace.") },
        confirmButton = { TextButton(actions::discardTodoEditor) { Text("Discard") } },
        dismissButton = { TextButton(actions::keepTodoEditor) { Text("Keep editing") } })
}

@Composable
private fun TodoNotices(state: TodoState, ready: Boolean, actions: TodoActions) {
    state.error?.let { Text(it, Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.error) }
    state.pending?.takeIf { !state.busy }?.let { pending ->
        Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Text("Check the previous save", fontWeight = FontWeight.SemiBold)
                Text(pending, Modifier.padding(vertical = 8.dp))
                Text("Refresh, then inspect the task. Nothing will be sent again automatically.", style = MaterialTheme.typography.bodySmall)
                TextButton(actions::acknowledgeTodoOutcome, enabled = ready && !state.busy && state.reviewed,
                    modifier = Modifier.testTag("todo-acknowledge")) { Text("I've checked · unlock edits") }
            }
        }
    }
}
