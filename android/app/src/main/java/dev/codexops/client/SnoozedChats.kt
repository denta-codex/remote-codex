package dev.codexops.client

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun SnoozedChats(st: ScreenState, actions: HomeActions) {
    val snooze = st.snooze
    LazyColumn(Modifier.fillMaxSize().testTag("snoozed-list"),
        contentPadding = PaddingValues(if (LocalAppWindowClass.current.coverScreen) 12.dp else 20.dp)) {
        item {
            Text("Chats return at their scheduled time without restarting.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = actions::refreshSnoozes, enabled = st.ready && !snooze.loading) { Text("Refresh") }
            if (snooze.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            snooze.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (!st.ready) Text("Reconnect to manage snoozed chats.")
            else if (snooze.loaded && snooze.tasks.isEmpty() && snooze.error == null) Text("No snoozed chats")
        }
        items(snooze.tasks.values.sortedBy { it.deadline }, key = { it.id }) { task ->
            val working = task.id in snooze.pending
            val enabled = st.ready && snooze.available && task.id !in snooze.uncertain && !working
            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp).testTag("snoozed-task-${task.id}")) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(task.title, Modifier.weight(1f), fontWeight = FontWeight.Medium)
                    if (working) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                }
                Spacer(Modifier.height(6.dp))
                if (task.waiting) Text("Snoozes after it finishes", style = MaterialTheme.typography.bodySmall)
                task.deadline?.let { Text("Returns ${snoozeTime(it)}", style = MaterialTheme.typography.bodySmall) }
                if (!task.scheduled) Text(when (task.status) {
                    "expired" -> "Snooze expired before the chat finished."
                    "cancelled" -> "Snooze was cancelled."
                    else -> "Snooze needs attention. Check status before returning it."
                }, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Row {
                    if (task.scheduled) TextButton(onClick = { actions.editSnooze(task.id) }, enabled = enabled,
                        modifier = Modifier.testTag("change-snooze-${task.id}")) { Text("Change time") }
                    TextButton(onClick = { actions.returnSnoozedTask(task.id) }, enabled = enabled,
                        modifier = Modifier.testTag("return-snooze-${task.id}")) { Text("Return now") }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SnoozeTimeSheet(task: SnoozedTask, snooze: SnoozeState, actions: HomeActions) {
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    var chosen by remember(task.id, task.deadline) {
        mutableStateOf(LocalDateTime.ofInstant(task.deadline ?: Instant.now().plusSeconds(3600), zone).withSecond(0).withNano(0))
    }
    var dateDialog by remember { mutableStateOf<DatePickerDialog?>(null) }
    var timeDialog by remember { mutableStateOf<TimePickerDialog?>(null) }
    DisposableEffect(Unit) { onDispose { dateDialog?.dismiss(); timeDialog?.dismiss() } }
    val pending = task.id in snooze.pending
    val enabled = snooze.available && !pending && task.id !in snooze.uncertain
    val deadline = snoozeDeadline(chosen, zone)
    val future = deadline?.isAfter(Instant.now()) == true
    ModalBottomSheet(onDismissRequest = actions::dismissSnoozeEditor,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)
            .testTag("snooze-time-sheet"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Change snooze time", style = MaterialTheme.typography.titleLarge)
            Text(task.title, style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = {
                dateDialog = DatePickerDialog(context, { _, year, month, day ->
                    chosen = chosen.withDayOfMonth(1).withYear(year).withMonth(month + 1).withDayOfMonth(day)
                }, chosen.year, chosen.monthValue - 1, chosen.dayOfMonth).also { it.show() }
            }, enabled = enabled && task.scheduled, modifier = Modifier.fillMaxWidth().testTag("snooze-date")) {
                Text(chosen.format(DateTimeFormatter.ofPattern("EEE, MMM d, yyyy")))
            }
            OutlinedButton(onClick = {
                timeDialog = TimePickerDialog(context, { _, hour, minute ->
                    chosen = chosen.withHour(hour).withMinute(minute)
                }, chosen.hour, chosen.minute, android.text.format.DateFormat.is24HourFormat(context)).also { it.show() }
            }, enabled = enabled && task.scheduled, modifier = Modifier.fillMaxWidth().testTag("snooze-time")) {
                Text(chosen.format(DateTimeFormatter.ofPattern("h:mm a")))
            }
            Text("Times shown in ${zone.id} (${chosen.atZone(zone).offset})", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (task.waiting) Text("This chat will hide after it finishes.", style = MaterialTheme.typography.bodySmall)
            if (!future) Text(if (deadline == null) "This time does not exist because the clocks change. Choose another time."
                else "Choose a future time.", color = MaterialTheme.colorScheme.error)
            snooze.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = { deadline?.let { actions.snoozeTask(task.id, it) } },
                enabled = enabled && task.scheduled && future,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("save-snooze-time")) {
                Text(if (pending) "Updating…" else "Save time")
            }
            TextButton(onClick = { actions.returnSnoozedTask(task.id) }, enabled = enabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("snooze-return-now")) { Text("Return now") }
            Spacer(Modifier.height(12.dp))
        }
    }
}
