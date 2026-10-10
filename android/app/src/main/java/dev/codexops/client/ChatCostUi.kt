package dev.codexops.client

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp

@Composable
internal fun ChatCostBadge(cost: ChatCost) {
    if (cost.status == ChatCostStatus.Ready) {
        Text(cost.label,
            modifier = Modifier.padding(horizontal = 10.dp).testTag("chat-cost").semantics {
                contentDescription = "Estimated API-equivalent cost at opening ${cost.label}"
            }, maxLines = 1, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
