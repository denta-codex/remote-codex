package dev.codexops.client

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.codexops.core.*
import kotlinx.serialization.json.*

data class GitChangesState(
    val thread: String? = null,
    val loading: Boolean = false,
    val report: JsonObject? = null,
    val error: Boolean = false,
)

internal object GitChanges {
    val script: String by lazy {
        requireNotNull(GitChanges::class.java.getResourceAsStream("/git-changes.sh"))
            .bufferedReader().use { it.readText() }
    }

    suspend fun read(rpc: RemoteSession, cwd: String): JsonObject {
        val response = rpc.callWithTimeout("command/exec", obj(
            "command" to JsonArray(listOf("bash", "-c", script, "remote-codex-changes", cwd).map(::s)),
            "cwd" to s(cwd),
            "sandboxPolicy" to obj("type" to s("readOnly")),
            "timeoutMs" to JsonPrimitive(15_000),
            "outputBytesCap" to JsonPrimitive(4096),
        ), 20_000)
        require(response.str("exitCode") == "0")
        return wire.parseToJsonElement(response.str("stdout")).jsonObject.also {
            require(it.str("status") in setOf("ready", "notRepository"))
        }
    }
}

@Composable
internal fun GitChangesBar(state: GitChangesState, enabled: Boolean, refresh: () -> Unit) {
    val report = state.report
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp).testTag("git-changes"),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(when {
                state.loading -> "Checking changes…"
                state.error -> "Could not load changes"
                report?.str("status") == "notRepository" -> "No Git repository"
                report != null -> "${report.str("files")} files · +${report.str("added")} / −${report.str("removed")} lines"
                else -> "Git changes"
            }, style = MaterialTheme.typography.bodySmall)
            if (!state.loading && !state.error && report?.str("status") == "ready")
                Text("Since ${if (report.str("baseline") == "main") "branch point with main" else "HEAD"}" +
                    if (report.str("binary") != "0") " · ${report.str("binary")} binary files" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(refresh, enabled = enabled && !state.loading, modifier = Modifier.testTag("refresh-git-changes")) {
            Glyph(R.drawable.ic_refresh, "Refresh changes")
        }
    }
}
