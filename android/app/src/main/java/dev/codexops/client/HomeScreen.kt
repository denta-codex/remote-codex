package dev.codexops.client

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.codexops.core.map
import dev.codexops.core.str

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeScreen(st: ScreenState, actions: HomeActions) {
    val cover = LocalAppWindowClass.current.coverScreen
    val horizontalPadding = if (cover) 12.dp else 20.dp
    Column(Modifier.fillMaxSize().padding(horizontal = horizontalPadding)) {
        Row(
            Modifier.fillMaxWidth().padding(
                top = if (cover) 8.dp else 20.dp,
                bottom = if (cover) 8.dp else 20.dp,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Your tasks",
                    fontSize = if (cover) 22.sp else 28.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                if (!cover)
                    Text(
                        "Pick up where you left off",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
            }
            FilledTonalIconButton(
                onClick = actions::newChat,
                modifier = Modifier.size(if (cover) 40.dp else 48.dp),
            ) {
                Glyph(R.drawable.ic_compose, "New chat")
            }
        }
        OutlinedTextField(
            st.query,
            actions::query,
            Modifier.fillMaxWidth().padding(bottom = if (cover) 8.dp else 16.dp),
            placeholder = { Text("Search tasks") },
            leadingIcon = { Glyph(R.drawable.ic_search) },
            shape = RoundedCornerShape(16.dp),
            singleLine = true,
            colors =
                OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                ),
        )
        if (!cover)
            Text(
                "Projects",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
        LazyRow(
            Modifier.fillMaxWidth().padding(
                top = if (cover) 2.dp else 8.dp,
                bottom = if (cover) 4.dp else 12.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                FilterChip(
                    selected = st.projectFilter == TaskProjectFilter.All,
                    onClick = { actions.projectFilter(TaskProjectFilter.All) },
                    label = { Text("All") },
                )
            }
            item {
                FilterChip(
                    selected = st.projectFilter == TaskProjectFilter.Projectless,
                    onClick = { actions.projectFilter(TaskProjectFilter.Projectless) },
                    label = { Text("Chats") },
                    leadingIcon = { Glyph(R.drawable.ic_chat, modifier = Modifier.size(16.dp)) },
                )
            }
            items(st.projects, key = { it.id }) { project ->
                FilterChip(
                    selected = st.projectFilter == TaskProjectFilter.Project(project.id),
                    onClick = { actions.projectFilter(TaskProjectFilter.Project(project.id)) },
                    label = { Text(project.name, maxLines = 1) },
                    leadingIcon = { Glyph(R.drawable.ic_folder, modifier = Modifier.size(16.dp)) },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when (val filter = st.projectFilter) {
                    TaskProjectFilter.All -> if (st.archived) "Archived" else "Recent"
                    TaskProjectFilter.Projectless -> "Chats"
                    is TaskProjectFilter.Project ->
                        st.projects.firstOrNull { it.id == filter.id }?.name ?: "Project"
                },
                Modifier.weight(1f),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            FilterChip(
                st.archived,
                { actions.archived(!st.archived) },
                label = { Text("Archived") },
                leadingIcon = {
                    Glyph(
                        if (st.archived) R.drawable.ic_check else R.drawable.ic_archive,
                        modifier = Modifier.size(16.dp),
                    )
                },
                border =
                    FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = st.archived,
                        borderColor = MaterialTheme.colorScheme.outlineVariant,
                    ),
            )
        }
        PullToRefreshBox(
            isRefreshing = st.refreshingTasks,
            onRefresh = actions::refreshTasks,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (st.tasks.isEmpty())
                    item {
                        Column(
                            Modifier.fillMaxWidth().padding(vertical = 40.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Glyph(R.drawable.ic_chat, modifier = Modifier.size(32.dp))
                            Text(
                                if (st.ready) "No tasks found" else "Your tasks will appear here",
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                if (st.ready) "Start a new chat to get going."
                                else "Connect to ${st.host.displayName} in Settings.",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                items(st.tasks, key = { it.str("id") }) { task ->
                    val status = task.map("status").str("type")
                    val projectName =
                        task.str("projectId").takeIf(String::isNotBlank)?.let { projectId ->
                            st.projects.firstOrNull { it.id == projectId }?.name ?: "Project"
                        } ?: "Chats"
                    val statusLabel =
                        when (status) {
                            "active" -> "Working"
                            "idle" -> "Ready"
                            "notLoaded" -> "Saved"
                            "systemError" -> "Needs attention"
                            else -> status.replaceFirstChar { it.uppercase() }.ifBlank { "Task" }
                        }
                    Surface(
                        onClick = { actions.openTask(task.str("id")) },
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface,
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(
                                vertical = if (cover) 10.dp else 16.dp,
                                horizontal = 4.dp,
                            ),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                            ) {
                                Box(
                                    Modifier.size(if (cover) 36.dp else 40.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Glyph(R.drawable.ic_chat)
                                }
                            }
                            Column(
                                Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(if (cover) 3.dp else 6.dp),
                            ) {
                                Text(
                                    task.str("name").ifBlank {
                                        task.str("preview").take(100).ifBlank { "Untitled task" }
                                    },
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    fontWeight = FontWeight.Medium,
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                                ) {
                                    Glyph(R.drawable.ic_folder, modifier = Modifier.size(13.dp))
                                    Text(
                                        "$projectName · $statusLabel",
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Glyph(R.drawable.ic_chevron, modifier = Modifier.size(16.dp))
                        }
                    }
                    HorizontalDivider(
                        Modifier.padding(start = 58.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
                if (st.listCursor != null)
                    item {
                        TextButton(onClick = actions::moreTasks, modifier = Modifier.fillMaxWidth()) {
                            Text("Load more tasks")
                            Spacer(Modifier.width(8.dp))
                            Glyph(R.drawable.ic_down)
                        }
                    }
                item { Spacer(Modifier.height(20.dp)) }
            }
        }
    }
}
