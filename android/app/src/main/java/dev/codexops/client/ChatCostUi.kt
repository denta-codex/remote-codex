package dev.codexops.client

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp

@Composable
internal fun ChatCostBadge(cost: ChatCost) {
    var details by remember { mutableStateOf(false) }
    TextButton(onClick = { details = true }, modifier = Modifier.heightIn(min = 48.dp).testTag("chat-cost").semantics {
        contentDescription = "Estimated chat cost ${cost.label}" +
            (if (cost.partial) ", partial" else "") +
            (if (cost.staleRates || cost.staleUsage) ", stale" else "")
    }, contentPadding = PaddingValues(horizontal = 8.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)) {
        Text(cost.label + if (cost.partial || cost.staleRates || cost.staleUsage) "*" else "",
            maxLines = 1, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.width(6.dp))
        Glyph(R.drawable.ic_chevron, modifier = Modifier.size(14.dp))
    }
    if (details) AlertDialog(
        onDismissRequest = { details = false },
        title = { Text("Estimated chat cost") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(cost.usd?.let { cost.label } ?: "Estimate unavailable")
            Text("API-equivalent cost of recorded model requests in this chat, including earlier turns and inherited history. Subscription usage is not an extra charge.")
            if (cost.usd == null) Text("Recorded usage or matching model prices are unavailable.")
            if (cost.partial) Text("Partial estimate: some recorded usage is missing or cannot be priced. The amount includes only requests with known prices.")
            if (cost.staleRates) Text("Uses retained catalog prices marked stale. Refresh models to read the server’s published catalog.")
            if (cost.staleUsage) Text("Usage could not be refreshed. Showing the last recorded estimate.")
            if (cost.requests > 0) Text("${cost.requests - cost.unpricedRequests} of ${cost.requests} recorded requests priced.")
            Text("Separate subagent chats, provider hosting charges, and tool costs are excluded. Explicit zero token rates appear as \$0.00.")
            if (cost.fetchedAt.isNotEmpty()) Text("Price data fetched: ${cost.fetchedAt.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
            if (cost.sources.isNotEmpty()) Text("Sources: ${cost.sources.joinToString("\n")}", style = MaterialTheme.typography.bodySmall)
        } },
        confirmButton = { TextButton(onClick = { details = false }) { Text("Done") } },
    )
}
