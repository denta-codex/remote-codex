package dev.codexops.client

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.codexops.core.str
import dev.codexops.core.map
import kotlinx.coroutines.flow.distinctUntilChanged
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun HomeScreen(st: ScreenState, actions: HomeActions) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(st.taskNotice?.id) {
        val notice = st.taskNotice ?: return@LaunchedEffect
        try {
            val result = snackbar.showSnackbar(notice.message,
                actionLabel = if (notice.changeSnooze != null) "Change time" else if (notice.undoArchived != null) "Undo" else null,
                withDismissAction = true, duration = SnackbarDuration.Long)
            if (result == SnackbarResult.ActionPerformed) {
                if (notice.changeSnooze != null) actions.editSnooze(notice.changeSnooze)
                else actions.undoTaskAction(notice.id)
            }
        } finally {
            // Leaving the list cancels showSnackbar. Retire that notice too so
            // returning from a conversation cannot replay an old archive result.
            actions.dismissTaskNotice(notice.id)
        }
    }
    Box(Modifier.fillMaxSize()) {
        key(st.page, st.archived) {
            if (st.page == "snoozed") SnoozedChats(st, actions) else ChatBrowser(st, actions)
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(12.dp))
    }
    st.snooze.editor?.let { id ->
        st.snooze.tasks[id]?.let { SnoozeTimeSheet(it, st.snooze, actions) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatBrowser(st: ScreenState, actions: HomeActions) {
    val context = LocalContext.current
    var sheet by remember { mutableStateOf(false) }
    val cover = LocalAppWindowClass.current.coverScreen
    val list = rememberLazyListState(st.listIndex, st.listOffset)
    var revealedTask by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(st.query, st.projectFilter, st.chatSort, st.ready, st.listLoading, st.pendingTaskActions) {
        revealedTask = null
    }
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress }.distinctUntilChanged().collect {
            if (it) revealedTask = null
        }
    }
    val scope = projectLabel(st.projectFilter, st.projects)
    LaunchedEffect(st.query, st.projectFilter, st.chatSort) {
        list.scrollToItem(st.listIndex, st.listOffset)
    }
    LaunchedEffect(list) {
        snapshotFlow { list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset }
            .distinctUntilChanged().collect { (index, offset) -> actions.listPosition(index, offset) }
    }
    LaunchedEffect(list, st.listCursor, st.listLoading, st.listFailed, st.ready) {
        if (st.ready && st.listCursor != null && !st.listLoading && !st.listFailed) {
            snapshotFlow {
                val layout = list.layoutInfo
                layout.totalItemsCount > 0 &&
                    (layout.visibleItemsInfo.lastOrNull()?.index ?: -1) >= layout.totalItemsCount - 6
            }.distinctUntilChanged().collect { nearEnd -> if (nearEnd) actions.moreTasks() }
        }
    }
    DisposableEffect(Unit) { onDispose { actions.visibleChats(emptySet()) } }
    LaunchedEffect(list, st.tasks) {
        snapshotFlow { list.layoutInfo.visibleItemsInfo.mapNotNull {
            (it.key as? String)?.takeIf { key -> key.startsWith("chat:") }?.removePrefix("chat:")
        }.toSet() }.distinctUntilChanged().collect(actions::visibleChats)
    }
    PullToRefreshBox(
        isRefreshing = st.refreshingTasks,
        onRefresh = actions::refreshTasks,
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize().testTag("chat-list")
                .pointerInput(revealedTask) {
                    val id = revealedTask ?: return@pointerInput
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.type == PointerEventType.Press) {
                                val row = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "chat:$id" }
                                val y = event.changes.firstOrNull()?.position?.y
                                if (row == null || y == null || y < row.offset || y >= row.offset + row.size) {
                                    revealedTask = null
                                }
                            }
                        }
                    }
                },
            contentPadding = PaddingValues(horizontal = if (cover) 12.dp else 20.dp, vertical = 8.dp),
        ) {
            item(key = "search") {
                OutlinedTextField(
                    value = st.query, onValueChange = actions::query,
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    label = { Text("Search chats") }, leadingIcon = { Glyph(R.drawable.ic_search) },
                    trailingIcon = {
                        if (st.query.isNotEmpty()) IconButton(onClick = { actions.query("") }) {
                            Glyph(R.drawable.ic_close, "Clear search")
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant),
                )
            }
            item(key = "controls") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { sheet = true }, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("project-control")) {
                        Text(scope, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Glyph(R.drawable.ic_down)
                    }
                    TextButton(onClick = { sheet = true }, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("sort-control")) {
                        Text(st.chatSort.label, maxLines = 2)
                        Glyph(R.drawable.ic_down)
                    }
                }
                if (st.projectFilter != TaskProjectFilter.All) TextButton(
                    onClick = { actions.applyListOptions(TaskProjectFilter.All, st.chatSort) }) {
                    Text("Clear project filter")
                }
            }
            items(st.tasks, key = { "chat:${it.str("id")}" }) { task ->
                val id = task.str("id")
                val project = task.str("projectId").takeIf { it.isNotBlank() }?.let { projectId ->
                    st.projects.firstOrNull { it.id == projectId }?.name ?: "Project"
                } ?: "No project"
                val date = chatTimestamp(task.str(if (st.chatSort == ChatSort.Recent) "recencyAt" else "createdAt"))
                val activity = st.chatActivity[id] ?: ChatActivity(runtimeIndicator(task.map("status")))
                val pending = id in st.pendingTaskActions
                Column(Modifier.fillMaxWidth()) {
                    TaskSwipeRow(
                        id = id, archived = st.archived, unread = activity.unread, pending = pending,
                        revealed = revealedTask == id,
                        onReveal = { if (it) revealedTask = id else if (revealedTask == id) revealedTask = null },
                        archiveEnabled = st.ready && !st.listLoading && !pending && id !in st.uncertainTaskActions,
                        unreadEnabled = !st.listLoading && !pending,
                        snoozeEnabled = st.ready && st.snooze.available && st.snooze.loaded && !pending &&
                            id !in st.snooze.uncertain && id !in st.uncertainTaskActions &&
                            (!st.archived || st.snooze.tasks[id]?.scheduled == true),
                        onOpen = { if (!pending && !st.listLoading) actions.openTask(id) },
                        onCopy = { copyThreadDeeplink(context, id) },
                        onArchive = { actions.archiveTask(id, !st.archived) },
                        onToggleUnread = { actions.toggleTaskUnread(id) },
                        onSnooze = { if (st.archived) actions.editSnooze(id) else actions.snoozeTask(id) },
                    ) {
                    Column(Modifier.fillMaxWidth()
                        .heightIn(min = 64.dp).padding(vertical = 14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(task.str("name").ifBlank { task.str("preview").take(100).ifBlank { "Untitled chat" } },
                                modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (pending) CircularProgressIndicator(Modifier.padding(start = 12.dp).size(18.dp), strokeWidth = 2.dp)
                            else ChatStatusIndicator(if (st.ready) activity.indicator
                                else if (activity.unread) ChatIndicator.Unread else ChatIndicator.None)
                        }
                        Spacer(Modifier.height(5.dp))
                        Text(listOf(project, date).filter { it.isNotBlank() }.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        st.snooze.tasks[id]?.takeIf { it.waiting }?.let {
                            Text("Snoozes after it finishes", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    }
                    if (id in st.uncertainTaskActions) TextButton(onClick = actions::retryList,
                        enabled = st.ready && !st.listLoading) {
                        Text("Check status")
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
            item(key = "footer") {
                Column(Modifier.fillMaxWidth().padding(vertical = 20.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    when {
                        !st.ready -> Text("Connect in Settings to load chats.")
                        st.listLoading && !st.refreshingTasks -> {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.height(8.dp))
                            Text(if (st.query.isNotBlank() && st.projectFilter != TaskProjectFilter.All)
                                "Searching this project…" else if (st.tasks.isEmpty()) "Loading chats…" else "Loading more chats…")
                        }
                        st.listFailed -> {
                            Text("Could not load chats.")
                            TextButton(onClick = actions::retryList) { Text("Retry") }
                        }
                        st.listInitialized && st.listCursor == null -> Text(
                            if (st.tasks.isNotEmpty()) "All matching chats shown"
                            else if (st.query.isNotBlank() || st.projectFilter != TaskProjectFilter.All) "No matching chats"
                            else if (st.archived) "No archived chats" else "Start a new chat to get going.")
                    }
                }
                st.snooze.error?.takeIf { st.snooze.loaded || st.snooze.uncertain.isNotEmpty() }?.let { error ->
                    Text(error, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = actions::refreshSnoozes, enabled = st.ready) { Text("Check snooze status") }
                }
            }
        }
    }
    if (sheet) {
        var project by remember { mutableStateOf(st.projectFilter) }
        var sort by remember { mutableStateOf(st.chatSort) }
        val accent = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) Color(0xFFB8E6C9) else Color(0xFF125441)
        ModalBottomSheet(onDismissRequest = { sheet = false },
            containerColor = MaterialTheme.colorScheme.surface,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Project & sort", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = { project = TaskProjectFilter.All; sort = ChatSort.Recent }) { Text("Reset") }
                }
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).testTag("project-sort-options")) {
                    Text("Projects · select one or more", style = MaterialTheme.typography.labelLarge)
                    OptionRow("All projects", project == TaskProjectFilter.All) { project = TaskProjectFilter.All }
                    ProjectOptionRow("No project", project != TaskProjectFilter.All && project.contains(null)) { project = project.toggle(null) }
                    st.projects.forEach { item ->
                        ProjectOptionRow(item.name, project != TaskProjectFilter.All && project.contains(item.id)) { project = project.toggle(item.id) }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 12.dp))
                    Text("Sort by", style = MaterialTheme.typography.labelLarge)
                    ChatSort.entries.forEach { item -> OptionRow(item.label, sort == item) { sort = item } }
                }
                Button(onClick = { sheet = false; actions.applyListOptions(project, sort) },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = accent,
                        contentColor = if (accent.luminance() > 0.5f) Color(0xFF153C2F) else Color.White),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp).heightIn(min = 48.dp)) { Text("Apply") }
            }
        }
    }
}

@Composable
private fun ProjectOptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
        .selectable(selected, role = Role.Checkbox, onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Checkbox(selected, onCheckedChange = null)
        Spacer(Modifier.width(12.dp))
        Text(label, Modifier.weight(1f))
    }
}

@Composable
private fun OptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
        .selectable(selected, role = Role.RadioButton, onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick = null, colors = RadioButtonDefaults.colors(
            selectedColor = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) Color(0xFFB8E6C9) else Color(0xFF125441)))
        Spacer(Modifier.width(12.dp))
        Text(label, Modifier.weight(1f))
    }
}

private fun projectLabel(filter: TaskProjectFilter, projects: List<CodexProject>) = when (filter) {
    TaskProjectFilter.All -> "All projects"
    TaskProjectFilter.Projectless -> "No project"
    is TaskProjectFilter.Selected -> "${filter.ids.size + if (filter.includeProjectless) 1 else 0} projects"
    is TaskProjectFilter.Project -> projects.firstOrNull { it.id == filter.id }?.name ?: "Project"
}

internal fun chatTimestamp(value: String): String = runCatching {
    val instant = value.toLongOrNull()?.let { Instant.ofEpochSecond(it) } ?: Instant.parse(value)
    val now = Instant.now()
    val minutes = java.time.Duration.between(instant, now).toMinutes().coerceAtLeast(0)
    when {
        minutes < 1 -> "Just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 1440 -> "${minutes / 60} hr ago"
        else -> DateTimeFormatter.ofPattern("MMM d, yyyy").withZone(ZoneId.systemDefault()).format(instant)
    }
}.getOrDefault("")
