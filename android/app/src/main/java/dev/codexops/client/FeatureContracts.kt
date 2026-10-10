package dev.codexops.client

import android.net.Uri
import dev.codexops.core.Decision
import dev.codexops.core.Entry
import dev.codexops.core.FileApprovalContext
import dev.codexops.core.MediaRef
import dev.codexops.core.list
import dev.codexops.core.obj
import dev.codexops.core.s
import dev.codexops.core.str
import kotlinx.serialization.json.JsonObject

data class HostIdentity(
    val id: String,
    val displayName: String,
    val endpoint: String,
    val expectedCodexHome: String,
    val bugReportRepository: String = "/home/agent/workspaces/remote-codex",
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

/** A usable preset advertised by the stock collaborationMode/list contract. */
data class CollaborationModePreset(
    val mode: String,
    val name: String,
    val model: String? = null,
    val reasoningEffort: String? = null,
) {
    fun turnSetting(fallbackModel: String?): JsonObject? {
        val effectiveModel = model ?: fallbackModel ?: return null
        return obj(
            "mode" to s(mode),
            "settings" to
                obj(
                    "model" to s(effectiveModel),
                    "reasoning_effort" to reasoningEffort?.let(::s),
                ),
        )
    }

    companion object {
        fun parse(response: JsonObject): List<CollaborationModePreset> =
            response
                .list("data")
                .mapNotNull { row ->
                    val mode = row.str("mode")
                    val model = row.str("model")
                    if (mode !in setOf("default", "plan")) null
                    else
                        CollaborationModePreset(
                            mode = mode,
                            name =
                                row.str("name").ifBlank {
                                    mode.replaceFirstChar { it.uppercase() }
                                },
                            model = model.ifBlank { null },
                            reasoningEffort = row.str("reasoning_effort").ifBlank { null },
                        )
                }
                .distinctBy { it.mode }
    }
}

data class CodexProject(
    val id: String,
    val name: String,
    val roots: List<String>,
) {
    val primaryRoot: String?
        get() = roots.firstOrNull()
}

sealed interface TaskProjectFilter {
    data object All : TaskProjectFilter

    data object Projectless : TaskProjectFilter

    data class Project(val id: String) : TaskProjectFilter

    data class Selected(val ids: Set<String>, val includeProjectless: Boolean = false) : TaskProjectFilter

    fun contains(id: String?): Boolean = when (this) {
        All -> true
        Projectless -> id.isNullOrBlank()
        is Project -> id == this.id
        is Selected -> id in ids || (includeProjectless && id.isNullOrBlank())
    }

    fun toggle(id: String?): TaskProjectFilter {
        val ids = when (this) {
            is Project -> setOf(this.id)
            is Selected -> this.ids
            else -> emptySet()
        }.toMutableSet()
        var projectless = this == Projectless || (this is Selected && includeProjectless)
        if (id == null) projectless = !projectless
        else if (!ids.remove(id)) ids.add(id)
        return selection(ids, projectless)
    }

    companion object {
        fun selection(ids: Set<String>, projectless: Boolean): TaskProjectFilter = when {
            ids.isEmpty() -> if (projectless) Projectless else All
            ids.size == 1 && !projectless -> Project(ids.single())
            else -> Selected(ids.toSet(), projectless)
        }
    }
}

/** Shared new-task choices. Null values deliberately retain the server default. */
data class NewTaskOptions(
    val projectId: String? = null,
    val workingDirectory: String? = null,
    val executionTarget: ExecutionTarget = ExecutionTarget.Projectless,
    val model: String? = null,
    val reasoningEffort: String? = null,
    val approvalPolicy: String? = null,
    val collaborationMode: String? = null,
    // null inherits; default explicitly selects Standard for a new chat.
    val serviceTier: String? = null,
) {
    fun hasExecutionDestination(): Boolean =
        projectId == null && executionTarget == ExecutionTarget.Projectless ||
            projectId != null &&
                !workingDirectory.isNullOrBlank() &&
                executionTarget != ExecutionTarget.Projectless
}

enum class ChatSort(val label: String, val key: String, val direction: String) {
    Recent("Recent activity", "recency_at", "desc"),
    Newest("Newest created", "created_at", "desc"),
    Oldest("Oldest created", "created_at", "asc"),
}

/** Session-only snapshot; archive and inbox keep independent browsing positions. */
data class ChatListSnapshot(
    val query: String = "", val project: TaskProjectFilter = TaskProjectFilter.All,
    val sort: ChatSort = ChatSort.Recent, val tasks: List<JsonObject> = emptyList(),
    val cursor: String? = null, val initialized: Boolean = false,
    val failed: Boolean = false, val index: Int = 0, val offset: Int = 0,
)

data class ScreenState(
    val page: String = "home",
    val host: HostIdentity = GraceHost,
    val connection: String = "Offline",
    val ready: Boolean = false,
    val appForeground: Boolean = false,
    val hapticsLoaded: Boolean = false,
    val hapticsEnabled: Boolean = true,
    val liveAssistantText: LiveAssistantText? = null,
    val configured: Boolean = false,
    val weeklyUsage: WeeklyUsageState = WeeklyUsageState(),
    val projects: List<CodexProject> = emptyList(),
    val projectAddition: ProjectAdditionState = ProjectAdditionState(),
    val todo: TodoState = TodoState(),
    val projectFilter: TaskProjectFilter = TaskProjectFilter.All,
    val tasks: List<JsonObject> = emptyList(),
    val chatActivity: Map<String, ChatActivity> = emptyMap(),
    val listCursor: String? = null,
    val refreshingTasks: Boolean = false,
    val listLoading: Boolean = false,
    val listFailed: Boolean = false,
    val query: String = "",
    val archived: Boolean = false,
    val pendingTaskActions: Set<String> = emptySet(),
    val uncertainTaskActions: Set<String> = emptySet(),
    val pendingTaskRenames: Set<String> = emptySet(),
    val uncertainTaskRenames: Set<String> = emptySet(),
    val taskNotice: TaskNotice? = null,
    val snooze: SnoozeState = SnoozeState(),
    val chatSort: ChatSort = ChatSort.Recent,
    val listInitialized: Boolean = false,
    val listIndex: Int = 0,
    val listOffset: Int = 0,
    val thread: String? = null,
    val threadCwd: String? = null,
    val chatCost: ChatCost = ChatCost(),
    val worktreeChanges: WorktreeChanges = WorktreeChanges(),
    val title: String = "New chat",
    val entries: List<Entry> = emptyList(),
    val followUps: FollowUpState = FollowUpState(),
    val turnStatuses: Map<String, String> = emptyMap(),
    val historyCursor: String? = null,
    val historyLoading: Boolean = false,
    val historyError: Boolean = false,
    val draft: String = "",
    val attachments: List<DraftAttachment> = emptyList(),
    val newTaskOptions: NewTaskOptions = NewTaskOptions(),
    val collaborationModes: List<CollaborationModePreset> = emptyList(),
    val models: List<ServerModelOption> = emptyList(),
    val modelCatalogStatus: ModelCatalogStatus = ModelCatalogStatus.Unavailable,
    val modelCatalogMessage: String? = null,
    val inheritedSettings: InheritedSettings = InheritedSettings(),
    val threadMode: String? = null,
    val threadModel: String? = null,
    val threadReasoningEffort: String? = null,
    val threadServiceTier: String? = null,
    val threadServiceTierKnown: Boolean = false,
    val speedSaving: Boolean = false,
    val speedUncertain: Boolean = false,
    val speedError: String? = null,
    val fastModeAllowed: Boolean? = null,
    val activeTurn: String? = null,
    val queuedMessages: List<QueuedMessage> = emptyList(),
    val queueReady: Boolean = false,
    val queueError: String? = null,
    val decisions: List<Decision> = emptyList(),
    val fileApprovalContexts: Map<String, FileApprovalContext> = emptyMap(),
    val busy: Boolean = false,
    val error: String? = null,
    val journal: JsonObject? = null,
    val attention: Boolean = false,
    val update: UpdateState = UpdateState(),
    val filePreview: FilePreviewState? = null,
)

data class TaskNotice(
    val id: String,
    val message: String,
    val threadId: String? = null,
    val undoArchived: Boolean? = null,
    val changeSnooze: String? = null,
)

fun ScreenState.collaborationModel(): String? =
    newTaskOptions.model ?: if (thread != null) threadModel else inheritedSettings.takeIf { it.cwd == settingsCwd() }?.model

interface AppNavigation {
    fun connect()

    fun settings()

    fun back()

    fun home()
}

interface HomeActions {
    fun refreshTasks()

    fun visibleChats(ids: Set<String>) {}
    fun applyListOptions(project: TaskProjectFilter, sort: ChatSort)
    fun retryList()
    fun listPosition(index: Int, offset: Int)

    fun query(value: String)

    fun moreTasks()

    fun newChat()

    fun openTask(id: String)

    fun archiveTask(id: String, archived: Boolean)

    fun toggleTaskUnread(id: String)

    fun undoTaskAction(noticeId: String)

    fun dismissTaskNotice(noticeId: String)

    fun snoozeTask(id: String, until: java.time.Instant? = null) {}
    fun editSnooze(id: String) {}
    fun dismissSnoozeEditor() {}
    fun returnSnoozedTask(id: String) {}
    fun refreshSnoozes() {}
}

interface SettingsActions {
    fun refreshWeeklyUsage() {}

    fun hapticFeedback(enabled: Boolean)

    fun openArchives()
    fun openSnoozed() {}

    fun saveCredential(value: String)

    fun checkForUpdates()

    fun downloadAndInstallUpdate()

    fun cancelUpdateDownload()

    fun updateInstallPermissionRequired()
}

interface ConversationActions {
    suspend fun loadToolDetails(entry: Entry, offset: Int = 0): ToolDetailsPage = error("Complete details unavailable")
    fun sendFollowUp(text: String) {}
    fun refreshWorktreeChanges() {}
    fun assistantTextRendered(update: LiveAssistantText, visible: Boolean): Float? = null
    fun cancelStreamingHaptics() {}

    fun openAddProject()
    fun dismissAddProject()
    fun projectPath(value: String)
    fun browseProjectFolder(path: String)
    fun useProjectFolder()
    fun projectName(value: String)
    fun addProject()
    fun checkProjectRegistration()
    fun chooseMatchingProject(id: String)
    fun viewedReply(thread: String, signature: String) {}
    fun refreshModels() {}
    fun refreshChatCost() {}
    fun selectSpeed(fast: Boolean) {}
    fun updateNewTaskOptions(options: NewTaskOptions)


    fun older()

    fun draft(value: String)

    fun addAttachments(uris: List<Uri>)

    fun addFiles(uris: List<Uri>)

    fun prepareCamera(): Uri?

    fun finishCamera(success: Boolean)

    fun removeAttachment(id: String)

    suspend fun loadMedia(media: MediaRef): ByteArray

    suspend fun loadVisualization(reference: VisualizationRef): String

    suspend fun visualizationState(key: String): String

    suspend fun saveVisualizationState(key: String, value: String)

    fun stageVisualizationFollowUp(prompt: String)

    fun inspectFile(file: dev.codexops.core.FileRef)

    fun dismissFile()

    fun saveFile(destination: Uri)

    fun send()

    fun sendQueuedNow(id: String)

    fun removeQueued(id: String)

    fun refreshQueue()

    fun implementPlan(planKey: String)

    fun recoverPreparation()

    fun stop()

    fun unlockAfterReview()

    fun answer(decision: Decision, result: JsonObject)

    fun refreshApprovalContext(decision: Decision) {}
}

interface ClientActions : AppNavigation, HomeActions, SettingsActions, ConversationActions, TodoActions
