package dev.codexops.client

import dev.codexops.core.Decision
import dev.codexops.core.Entry
import kotlinx.serialization.json.JsonObject

data class HostIdentity(
    val id: String,
    val displayName: String,
    val endpoint: String,
    val expectedCodexHome: String,
)

val GraceHost =
    HostIdentity(
        id = "grace",
        displayName = "Grace",
        endpoint = "wss://grace.taila198f.ts.net/codex/rpc",
        expectedCodexHome = "/home/agent/.codex",
    )

enum class ExecutionTarget {
    Projectless,
    CurrentWorkspace,
    NewWorktree,
}

data class ComposerAttachment(
    val id: String,
    val displayName: String,
    val localUri: String,
)

/** Shared new-task choices. Null values deliberately retain the server default. */
data class NewTaskOptions(
    val projectId: String? = null,
    val workingDirectory: String? = null,
    val executionTarget: ExecutionTarget = ExecutionTarget.Projectless,
    val model: String? = null,
    val reasoningEffort: String? = null,
    val approvalPolicy: String? = null,
    val collaborationMode: String? = null,
    val attachments: List<ComposerAttachment> = emptyList(),
) {
    fun hasExecutionDestination(): Boolean =
        projectId == null && executionTarget == ExecutionTarget.Projectless ||
            projectId != null &&
                !workingDirectory.isNullOrBlank() &&
                executionTarget != ExecutionTarget.Projectless
}

data class ScreenState(
    val page: String = "home",
    val host: HostIdentity = GraceHost,
    val connection: String = "Offline",
    val ready: Boolean = false,
    val configured: Boolean = false,
    val tasks: List<JsonObject> = emptyList(),
    val listCursor: String? = null,
    val query: String = "",
    val archived: Boolean = false,
    val thread: String? = null,
    val title: String = "New chat",
    val entries: List<Entry> = emptyList(),
    val historyCursor: String? = null,
    val draft: String = "",
    val newTaskOptions: NewTaskOptions = NewTaskOptions(),
    val activeTurn: String? = null,
    val decisions: List<Decision> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
    val journal: JsonObject? = null,
    val attention: Boolean = false,
)

interface AppNavigation {
    fun connect()

    fun settings()

    fun home()
}

interface HomeActions {
    fun query(value: String)

    fun archived(value: Boolean)

    fun moreTasks()

    fun newChat()

    fun openTask(id: String)
}

interface SettingsActions {
    fun saveCredential(value: String)
}

interface ConversationActions {
    fun updateNewTaskOptions(options: NewTaskOptions)

    fun older()

    fun draft(value: String)

    fun send()

    fun recoverPreparation()

    fun stop()

    fun unlockAfterReview()

    fun answer(decision: Decision, result: JsonObject)
}

interface ClientActions : AppNavigation, HomeActions, SettingsActions, ConversationActions
