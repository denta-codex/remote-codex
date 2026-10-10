package dev.codexops.client

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.res.painterResource
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp

@Composable
internal fun ChatCostBadge(cost: ChatCost, retry: () -> Unit) {
    when (cost.status) {
        ChatCostStatus.Calculating -> Box(Modifier.size(48.dp).testTag("chat-cost").semantics {
            contentDescription = "Calculating cost at opening"
        }, contentAlignment = androidx.compose.ui.Alignment.Center) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        }
        ChatCostStatus.Ready -> Text(cost.label + " at open",
            modifier = Modifier.padding(horizontal = 8.dp).testTag("chat-cost").semantics {
                contentDescription = "Estimated API-equivalent cost at opening ${cost.label}"
            }, maxLines = 1, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        ChatCostStatus.Unavailable -> IconButton(onClick = retry,
            modifier = Modifier.size(48.dp).testTag("chat-cost").semantics {
                contentDescription = "Cost unavailable. Retry calculation"
            }) {
            Icon(painterResource(R.drawable.ic_warning), contentDescription = null,
                modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
        }
    }
}
