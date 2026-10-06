package dev.codexops.client

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
internal fun WeeklyUsageCard(usage: WeeklyUsageState, connected: Boolean, refresh: () -> Unit) {
    Card(Modifier.fillMaxWidth().testTag("weekly-usage")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Weekly usage", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = refresh, enabled = connected && !usage.loading,
                    modifier = Modifier.testTag("refresh-weekly-usage")) { Text("Refresh") }
            }
            when {
                !connected -> Text("Connect to see your weekly usage.")
                usage.loading -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Loading weekly usage…")
                }
                usage.remainingPercent != null -> {
                    Text("${usage.remainingPercent}% remaining", style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.testTag("weekly-usage-remaining"))
                    LinearProgressIndicator(progress = { usage.remainingPercent / 100f }, modifier = Modifier.fillMaxWidth())
                    usage.resetsAt?.let { seconds ->
                        val reset = runCatching {
                            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
                                .format(Instant.ofEpochSecond(seconds).atZone(ZoneId.systemDefault()))
                        }.getOrNull()
                        if (reset != null) Text("Resets $reset", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                else -> Text(usage.message ?: "Weekly usage is unavailable for this account.")
            }
        }
    }
}
