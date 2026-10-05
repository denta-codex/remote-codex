package dev.codexops.client

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.codexops.core.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

class ClientModel
@JvmOverloads
constructor(
    app: Application,
    private val endpoint: String = GraceHost.endpoint,
    private val expectedHome: String = GraceHost.expectedCodexHome,
    allowLoopbackTest: Boolean = false,
    bugReportRepository: String = GraceHost.bugReportRepository,
) : AndroidViewModel(app), ClientActions {
    private val host =
        GraceHost.copy(endpoint = endpoint, expectedCodexHome = expectedHome, bugReportRepository = bugReportRepository)
    private val local: ClientStore = LocalStore(app)
    private val rpc: RemoteSession = StockRemoteSession(allowLoopbackTest)
    private val updater = AppUpdater(app, endpoint, allowLoopbackTest)
    private val workspaces = StockWorkspaceAdapter(rpc)
    private val projectRepository = ProjectRepository(rpc)
    private val timeline = Timeline()
    private val attachmentStore = AttachmentStore(app)
    private val mediaRepository = MediaRepository(app)
    private val remoteFileRepository = RemoteFileRepository(app)
    private val _state = MutableStateFlow(ScreenState(host = host))
    val state = _state.asStateFlow()
    private val todoController = TodoController(viewModelScope, local, StockTodoOperations(rpc),
        { _state.value }, { todo -> _state.update { it.copy(todo = todo) } })
    override fun openTodo() {
        cancelList(); saveList()
        _state.update { it.copy(page = "todo", error = null) }
    }
    override fun refreshTodo() = todoController.refresh()
    override fun selectTodoStatus(status: String) = todoController.select(status)
    override fun newTodo() = todoController.new()
    override fun openTodoTask(id: Long) = todoController.open(id)
    override fun todoTitle(value: String) = todoController.title(value)
    override fun todoDescription(value: String) = todoController.description(value)
    override fun saveTodo() = todoController.save()
    override fun moveTodoTask(id: Long, status: String) = todoController.moveTask(id, status)
    override fun reorderTodo(id: Long, target: Long, after: Boolean) = todoController.reorder(id, target, after)
    override fun moveTodo(status: String) = todoController.move(status)
    override fun closeTodoEditor() = todoController.close()
    override fun discardTodoEditor() = todoController.discard()
    override fun keepTodoEditor() = todoController.keep()
    override fun acknowledgeTodoOutcome() = todoController.acknowledge()
    private val projectAddition = ProjectAdditionController(viewModelScope, local, rpc,
        { _state.value }, { addition -> _state.update { it.copy(projectAddition = addition) } },
        { projects, project, root, current ->
            val options = _state.value.newTaskOptions.copy(projectId = project.id,
                workingDirectory = root, executionTarget = ExecutionTarget.CurrentWorkspace)
            local.put("options/new", newTaskOptionsJson(options).toString())
            if (current()) {
                _state.update { it.copy(projects = projects, newTaskOptions = options) }
                true
            } else false
        })
    override fun openAddProject() = projectAddition.open()
    override fun dismissAddProject() = projectAddition.dismiss()
    override fun projectPath(value: String) = projectAddition.editPath(value)
    override fun browseProjectFolder(path: String) = projectAddition.browse(path)
    override fun useProjectFolder() = projectAddition.confirmFolder()
    override fun projectName(value: String) = projectAddition.name(value)
    override fun addProject() = projectAddition.add()
    override fun checkProjectRegistration() = projectAddition.checkAgain()
    override fun chooseMatchingProject(id: String) = projectAddition.choose(id)
    internal val reports = BugReportController(app, viewModelScope, local, rpc, host, { _state.value })
    private var connectionJob: Job? = null
    private var updateJob: Job? = null
    private var foreground = false
    private val visibleChatIds = MutableStateFlow(emptySet<String>())
    private val activityMonitor = ChatActivityMonitor(viewModelScope, local, rpc, endpoint + "/" + expectedHome) { id, activity ->
        _state.update { it.copy(chatActivity = it.chatActivity + (id to activity)) }
    }

    override fun visibleChats(ids: Set<String>) { visibleChatIds.value = ids }
    override fun viewedReply(thread: String, signature: String) {
        val st = _state.value
        if (foreground && st.page == "chat" && st.thread == thread && !st.busy)
            activityMonitor.read(thread, signature)
    }
    private var selection = 0
    private var speedRevision = 0
    private val modelDefaultsMutex = Mutex()
    private var uncertainModelDefaultsGeneration: Long? = null
    private var listSelection = 0
    private var listJob: Job? = null
    private val listCursors = mutableMapOf<Boolean, MutableSet<String>>()
    private val listSnapshots = mutableMapOf<Boolean, ChatListSnapshot>()
    private var chatOrigin = "home"
    private var settingsOrigin = "home"

    private fun cancelList() {
        listSelection++
        listJob?.cancel()
        listJob = null
        _state.update { it.copy(listLoading = false, refreshingTasks = false) }
    }

    private fun saveList() {
        val st = _state.value
        listSnapshots[st.archived] = ChatListSnapshot(st.query, st.projectFilter, st.chatSort,
            st.tasks, st.listCursor, st.listInitialized, st.listFailed, st.listIndex, st.listOffset)
    }

    private fun invalidateArchiveSnapshots() {
        // Preserve each tab's search and sort choices while forcing fresh server membership.
        listSnapshots.replaceAll { _, saved ->
            saved.copy(tasks = emptyList(), cursor = null, initialized = false, failed = false,
                index = 0, offset = 0)
        }
    }

    private fun showList(archive: Boolean) {
        cancelList()
        saveList()
        val saved = listSnapshots[archive] ?: ChatListSnapshot()
        _state.update { it.copy(page = if (archive) "archives" else "home", archived = archive,
            query = saved.query, projectFilter = saved.project, chatSort = saved.sort,
            tasks = saved.tasks, listCursor = saved.cursor, listInitialized = saved.initialized,
            listFailed = saved.failed, listIndex = saved.index, listOffset = saved.offset, error = null) }
        if (!saved.initialized && !saved.failed) launchList()
    }

    private fun launchList(more: Boolean = false, debounce: Boolean = false, refreshing: Boolean = false) {
        if (!_state.value.ready || listJob?.isActive == true || _state.value.listLoading) return
        _state.update { it.copy(listLoading = true, listFailed = false, refreshingTasks = refreshing) }
        listJob = viewModelScope.launch {
            if (debounce) delay(300)
            refreshList(more)
        }
    }
    private var modelCatalogSelection = 0
    private var queueSelection = 0
    private var hydrating = false
    private var codexHome = expectedHome
    private val buffered = mutableListOf<JsonObject>()
    private val requests = linkedMapOf<String, Decision>()
    private val network = app.getSystemService(ConnectivityManager::class.java)
    private val callback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                viewModelScope.launch { if (foreground && !_state.value.ready) connect() }
            }
        }

    init {
        viewModelScope.launch {
            state.map { Triple(it.page == "todo", it.ready, it.appForeground) }.distinctUntilChanged()
                .collect { (visible, ready, foreground) ->
                    if (!ready) todoController.disconnected()
                    else if (visible && foreground) todoController.refresh()
                }
        }
        viewModelScope.launch {
            state.map { Triple(it.ready, it.page, it.thread) }.distinctUntilChanged().collect { (ready, page, thread) ->
                if (!ready) projectAddition.disconnected()
                if (page != "chat" || thread != null) projectAddition.dismiss()
            }
        }
        viewModelScope.launch {
            state.map { Triple(it.ready && it.page == "chat", it.thread, it.settingsCwd()) }
                .distinctUntilChanged().collectLatest { (active, _, cwd) ->
                    _state.update { it.copy(inheritedSettings = InheritedSettings(cwd,
                        if (active && cwd != null) ModelCatalogStatus.Loading else ModelCatalogStatus.Unavailable)) }
                    if (active && cwd != null) readComposerConfig(cwd)
                }
        }

        viewModelScope.launch {
            combine(state.map { Triple(it.page, it.ready && it.appForeground, it.thread) }.distinctUntilChanged(), visibleChatIds) { state, ids ->
                val (page, active, thread) = state
                (if (page in listOf("home", "archives")) ids else if (page == "chat" && thread != null) setOf(thread) else emptySet()) to active
            }.distinctUntilChanged().collect { (ids, active) -> activityMonitor.watch(ids, active) }
        }
        network.registerDefaultNetworkCallback(callback)
        viewModelScope.launch {
            state.map { listOf(it.page, it.connection, it.thread, it.activeTurn, it.journal?.str("stage"),
                it.queueReady.toString(), it.queuedMessages.size.toString(), it.error?.let { "error" }) }
                .distinctUntilChanged().collect { signals ->
                    reports.actions.add("appState", signals[2], signals.filterNotNull().joinToString(" / "))
                }
        }
        viewModelScope.launch {
            _state.update {
                it.copy(configured = runCatching { local.token().isNotEmpty() }.getOrDefault(false))
            }
            for (event in rpc.events) handle(event)
        }
        viewModelScope.launch {
            UpdateInstallResults.events.collect { message -> applyInstallResult(message) }
        }
    }

    fun foreground(value: Boolean) {
        foreground = value
        _state.update { it.copy(appForeground = value) }
        if (value) UpdateInstallResults.consume(getApplication())?.let(::applyInstallResult)
        if (value && !_state.value.ready) connect()
    }

    override fun connect() {
        reports.actions.add("connect")
        if (connectionJob?.isActive == true || _state.value.busy) return
        connectionJob =
            viewModelScope.launch {
                var attempt = 0
                do {
                    val token =
                        runCatching { local.token() }
                            .getOrElse {
                                _state.update {
                                    it.copy(
                                        error =
                                            "Credential unavailable. Enter it again in settings."
                                    )
                                }
                                ""
                            }
                    if (token.isEmpty()) {
                        _state.update { it.copy(page = "settings", configured = false) }
                        return@launch
                    }
                    _state.update {
                        it.copy(
                            connection = "Connecting…",
                            ready = false,
                            queueReady = false,
                            collaborationModes = emptyList(),
                            error = null,
                            configured = true,
                            modelCatalogStatus = ModelCatalogStatus.Loading,
                            modelCatalogMessage = null,
                        )
                    }
                    requests.clear()
                    while (rpc.events.tryReceive().isSuccess) {}
                    publish()
                    try {
                        val init = rpc.connect(host.endpoint, token)
                        require(init.str("codexHome") == host.expectedCodexHome) {
                            "Unexpected Codex account"
                        }
                        codexHome = init.str("codexHome")
                        val modes =
                            try {
                                CollaborationModePreset.parse(
                                    rpc.call("collaborationMode/list", obj())
                                )
                            } catch (_: RpcRejected) {
                                // This experimental method is the capability check. A server that
                                // rejects it gets no mode UI or client-simulated fallback.
                                emptyList()
                            }
                        _state.update {
                            it.copy(
                                connection = "Connected to ${host.displayName}",
                                ready = true,
                                collaborationModes = modes,
                                newTaskOptions = if (modes.any { mode -> mode.mode == it.newTaskOptions.collaborationMode })
                                    it.newTaskOptions else it.newTaskOptions.copy(collaborationMode = null),
                            )
                        }
                        refreshProjects()
                        viewModelScope.launch { refreshModelCatalog() }
                        cancelList()
                        launchList()
                        val id = _state.value.thread
                        if (id != null && _state.value.page == "chat") loadTask(id)
                        else if (_state.value.page == "chat") recoverNew()
                        return@launch
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        // Deliberately omit throwable messages and stack traces: these may
                        // contain server data. Log only transport status/type for diagnosis.
                        val reason = when (e) {
                            is ConnectionFailure -> "http=${e.httpStatus} transport=${e.transport}"
                            is RpcRejected -> "rpc=${e.code}"
                            else -> e.javaClass.simpleName
                        }
                        Log.w("RemoteCodexConnection", reason)
                        rpc.close()
                        requests.clear()
                        _state.update {
                            it.copy(
                                ready = false,
                                collaborationModes = emptyList(),
                                connection = "Disconnected",
                                speedUncertain = it.speedUncertain || it.speedSaving,
                                speedError = if (it.speedSaving) SPEED_OUTCOME_UNKNOWN else it.speedError,
                                speedSaving = false,
                                modelCatalogStatus = ModelCatalogStatus.Unavailable,
                                error = when {
                                    e is ConnectionFailure && e.httpStatus == 401 ->
                                        "${host.displayName} rejected the connection credential. Scan the setup QR again in Settings."
                                    e is ConnectionFailure && e.httpStatus != null ->
                                        "${host.displayName} rejected the WebSocket connection (HTTP ${e.httpStatus})."
                                    e is ConnectionFailure ->
                                        "Cannot reach ${host.displayName} (${e.transport}). Check Tailscale and reconnect."
                                    e is RpcRejected ->
                                        "${host.displayName} rejected connection setup (RPC ${e.code})."
                                    else ->
                                        "Cannot connect to ${host.displayName} (${e.javaClass.simpleName})."
                                },
                            )
                        }
                        publish()
                    }
                    attempt++
                    delay((1000L shl attempt.coerceAtMost(4)))
                } while (foreground)
            }
    }

    override fun saveCredential(value: String) {
        viewModelScope.launch {
            try {
                require(value.trim().length >= 43)
                local.saveToken(value.trim())
                connectionJob?.cancel()
                connectionJob = null
                rpc.close()
                _state.update { it.copy(configured = true, page = "home", ready = false) }
                showList(false)
                connect()
            } catch (_: Exception) {
                _state.update {
                    it.copy(error = "Could not save credential. Check its value and try again.")
                }
            }
        }
    }

    override fun checkForUpdates() {
        if (updateJob?.isActive == true) return
        updateJob =
            viewModelScope.launch {
                _state.update { it.copy(update = UpdateState(stage = UpdateStage.Checking)) }
                try {
                    val token = local.token()
                    if (token.isEmpty()) throw UpdateFailure("Enter the connection credential before checking for updates.")
                    val manifest = updater.check(token)
                    _state.update {
                        it.copy(
                            update =
                                if (isUpdateAvailable(manifest, BuildConfig.VERSION_CODE.toLong()))
                                    UpdateState(UpdateStage.Available, manifest)
                                else
                                    UpdateState(
                                        UpdateStage.Current,
                                        message = "Remote Codex is up to date.",
                                    )
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: UpdateFailure) {
                    _state.update { it.copy(update = UpdateState(UpdateStage.Error, message = e.message)) }
                } catch (_: Exception) {
                    _state.update {
                        it.copy(update = UpdateState(UpdateStage.Error, message = "Could not reach the private update service. Check Tailscale and try again."))
                    }
                }
            }
    }

    override fun downloadAndInstallUpdate() {
        if (updateJob?.isActive == true) return
        val manifest = _state.value.update.manifest ?: return
        updateJob =
            viewModelScope.launch {
                _state.update { it.copy(update = UpdateState(UpdateStage.Downloading, manifest)) }
                var file: java.io.File? = null
                try {
                    val token = local.token()
                    if (token.isEmpty()) throw UpdateFailure("The connection credential is unavailable.")
                    file =
                        updater.downloadAndVerify(manifest, token) { progress ->
                            _state.update {
                                it.copy(update = UpdateState(UpdateStage.Downloading, manifest, progress))
                            }
                        }
                    _state.update {
                        it.copy(
                            update =
                                UpdateState(
                                    UpdateStage.Installing,
                                    manifest,
                                    100,
                                    "Confirm the update in Android's installer.",
                                )
                        )
                    }
                    updater.install(file, manifest)
                    file.delete()
                } catch (e: CancellationException) {
                    file?.delete()
                    _state.update {
                        it.copy(
                            update =
                                UpdateState(
                                    UpdateStage.Available,
                                    manifest,
                                    message = "Download canceled.",
                                )
                        )
                    }
                } catch (e: UpdateFailure) {
                    file?.delete()
                    _state.update {
                        it.copy(update = UpdateState(UpdateStage.Error, manifest, message = e.message))
                    }
                } catch (_: Exception) {
                    file?.delete()
                    _state.update {
                        it.copy(
                            update =
                                UpdateState(
                                    UpdateStage.Error,
                                    manifest,
                                    message = "The installation request failed or its outcome is uncertain. Inspect Android's installer before trying again.",
                                )
                        )
                    }
                }
            }
    }

    override fun cancelUpdateDownload() {
        if (_state.value.update.stage == UpdateStage.Downloading) updateJob?.cancel()
    }

    override fun updateInstallPermissionRequired() {
        _state.update {
            it.copy(
                update =
                    it.update.copy(
                        message = "Allow Remote Codex to install apps, then return and tap Download and install again."
                    )
            )
        }
    }

    private fun applyInstallResult(message: String?) {
        _state.update {
            it.copy(
                update =
                    if (message == null || message == "Update installed.")
                        UpdateState(UpdateStage.Current, message = "Update installed.")
                    else UpdateState(UpdateStage.Error, it.update.manifest, message = message)
            )
        }
    }

    override fun settings() {
        reports.actions.add("settings")
        settingsOrigin = _state.value.page
        cancelList()
        saveList()
        _state.update { it.copy(page = "settings", error = null) }
    }

    override fun back() {
        if (_state.value.busy) return
        when (_state.value.page) {
            "todo" -> if (_state.value.todo.editor != null) todoController.close() else home()
            "archives" -> {
                cancelList(); saveList()
                _state.update { it.copy(page = "settings", error = null) }
            }
            "settings" -> if (settingsOrigin in setOf("chat", "todo")) _state.update { it.copy(page = settingsOrigin) } else home()
            "chat" -> if (chatOrigin == "archives") showList(true) else home()
            else -> home()
        }
    }

    override fun home() {
        reports.actions.add("home")
        if (_state.value.busy) return
        selection++
        showList(false)
    }

    override fun openArchives() { showList(true) }

    override fun query(value: String) {
        invalidateList()
        _state.update { it.copy(query = value) }
        launchList(debounce = true)
    }

    override fun applyListOptions(project: TaskProjectFilter, sort: ChatSort) {
        if (project == _state.value.projectFilter && sort == _state.value.chatSort) return
        invalidateList()
        _state.update { it.copy(projectFilter = project, chatSort = sort) }
        launchList()
    }

    override fun refreshTasks() {
        launchList(refreshing = true)
    }

    override fun moreTasks() {
        val st = _state.value
        if (st.listLoading || st.listFailed || st.listCursor == null) return
        launchList(more = true)
    }

    override fun retryList() {
        if (_state.value.uncertainTaskActions.isNotEmpty()) {
            cancelList()
            launchList()
        } else launchList(more = _state.value.listInitialized && _state.value.listCursor != null)
    }

    override fun listPosition(index: Int, offset: Int) {
        _state.update { it.copy(listIndex = index, listOffset = offset) }
    }

    private fun invalidateList() {
        cancelList()
        _state.update { it.copy(tasks = emptyList(), listCursor = null, listInitialized = false,
            listFailed = false, listIndex = 0, listOffset = 0) }
    }

    override fun toggleTaskUnread(id: String) {
        if (_state.value.tasks.none { it.str("id") == id }) return
        viewModelScope.launch {
            try {
                val unread = activityMonitor.toggleUnread(id)
                _state.update { it.copy(taskNotice = TaskNotice(UUID.randomUUID().toString(), if (unread) "Marked unread" else "Marked read")) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.update { it.copy(error = "Could not save the read status.") }
            }
        }
    }

    override fun dismissTaskNotice(noticeId: String) {
        _state.update { if (it.taskNotice?.id == noticeId) it.copy(taskNotice = null) else it }
    }

    override fun undoTaskAction(noticeId: String) {
        val notice = _state.value.taskNotice?.takeIf { it.id == noticeId } ?: return
        val id = notice.threadId ?: return
        val archived = notice.undoArchived ?: return
        dismissTaskNotice(noticeId)
        changeTaskArchive(id, archived)
    }

    override fun archiveTask(id: String, archived: Boolean) {
        val before = _state.value
        if (before.listLoading || before.archived == archived || before.tasks.none { it.str("id") == id }) return
        changeTaskArchive(id, archived)
    }

    internal fun archiveCurrentTask() {
        val before = _state.value
        val id = before.thread ?: return
        if (before.page != "chat" || before.busy) return
        changeTaskArchive(id, !before.archived)
    }

    private fun changeTaskArchive(id: String, archived: Boolean) {
        val before = _state.value
        if (id in before.pendingTaskActions || id in before.uncertainTaskActions) return
        if (!before.ready) {
            _state.update { it.copy(error = "Reconnect before changing archived tasks.") }
            return
        }
        // Reserve synchronously so repeated releases/accessibility actions cannot double-send.
        _state.update { it.copy(pendingTaskActions = it.pendingTaskActions + id, error = null) }
        viewModelScope.launch {
            try {
                rpc.call(if (archived) "thread/archive" else "thread/unarchive", obj("threadId" to s(id)))
            } catch (e: Exception) {
                if (e is CancellationException && e !is TimeoutCancellationException) throw e
                // Reads started before the failed request cannot reconcile its outcome.
                cancelList()
                invalidateArchiveSnapshots()
                _state.update {
                    it.copy(
                        listLoading = false,
                        listInitialized = false,
                        uncertainTaskActions = if (e is RpcRejected) it.uncertainTaskActions else it.uncertainTaskActions + id,
                        error = if (e is RpcRejected) "The server rejected the archive change."
                            else "Archive outcome unknown. Reconnect or refresh the task list before trying again. No retry was sent.",
                    )
                }
                return@launch
            } finally {
                _state.update { it.copy(pendingTaskActions = it.pendingTaskActions - id) }
            }
            // Only an acknowledged mutation removes a row or offers its inverse as Undo.
            cancelList()
            invalidateArchiveSnapshots()
            _state.update {
                it.copy(
                    tasks = if (it.archived != archived) it.tasks.filterNot { row -> row.str("id") == id } else it.tasks,
                    listInitialized = false,
                    taskNotice = TaskNotice(UUID.randomUUID().toString(), if (archived) "Task archived" else "Task unarchived", id, !archived),
                )
            }
            if (_state.value.page == "chat" && _state.value.thread == id) back()
            else if (_state.value.page in listOf("home", "archives")) launchList()
        }
    }

    private suspend fun refreshProjects() {
        if (!_state.value.ready) return
        val values = projectRepository.list()
        _state.update { before ->
            val filter = before.projectFilter
            val availableFilter =
                if (filter is TaskProjectFilter.Project && values.none { it.id == filter.id })
                    TaskProjectFilter.All
                else filter
            val selected = before.newTaskOptions.projectId?.let { id -> values.find { it.id == id } }
            val options =
                if (before.thread == null && selected?.primaryRoot != null)
                    before.newTaskOptions.copy(
                        workingDirectory = before.newTaskOptions.workingDirectory?.takeIf { it in selected.roots } ?: selected.primaryRoot,
                        executionTarget = ExecutionTarget.CurrentWorkspace,
                    )
                else before.newTaskOptions
            before.copy(projects = values, projectFilter = availableFilter, newTaskOptions = options)
        }
        if (_state.value.page == "chat" && _state.value.thread == null) {
            local.put("options/new", newTaskOptionsJson(_state.value.newTaskOptions).toString())
        }
    }

    private suspend fun refreshList(more: Boolean = false) {
        if (!_state.value.ready) { _state.update { it.copy(listLoading = false, refreshingTasks = false) }; return }
        val before = _state.value
        val n = ++listSelection
        val scopedSearch = before.query.isNotBlank() && before.projectFilter != TaskProjectFilter.All
        var cursor = if (more) before.listCursor else null
        val seen = if (more) listCursors.getOrPut(before.archived) { mutableSetOf() }
            else mutableSetOf<String>().also { listCursors[before.archived] = it }
        cursor?.let(seen::add)
        val rows = (if (more) before.tasks else emptyList()).associateByTo(linkedMapOf()) { it.str("id") }
        val target = rows.size + 30
        _state.update { it.copy(listLoading = true, listFailed = false) }
        try {
            do {
                val params = obj(
                    "limit" to JsonPrimitive(30), "archived" to JsonPrimitive(before.archived),
                    "modelProviders" to if (before.query.isBlank()) JsonArray(emptyList()) else null,
                    "sortKey" to s(before.chatSort.key), "sortDirection" to s(before.chatSort.direction),
                    // Keep the server default: internal reviewer threads are not user chats.
                    "cursor" to cursor?.let(::s),
                    "searchTerm" to before.query.takeIf { it.isNotBlank() }?.let(::s),
                    "projectId" to if (before.query.isNotBlank()) null else when (val filter = before.projectFilter) {
                        TaskProjectFilter.All -> null
                        TaskProjectFilter.Projectless -> JsonNull
                        is TaskProjectFilter.Project -> s(filter.id)
                    },
                )
                val response = rpc.call(if (before.query.isBlank()) "thread/list" else "thread/search", params)
                if (n != listSelection) return
                val next = response.cursor()
                check(next == null || seen.add(next)) { "The server repeated a page. Retry to continue." }
                response.list("data").map { if (before.query.isBlank()) it else it.map("thread") }
                    .filter { row -> when (val filter = before.projectFilter) {
                        TaskProjectFilter.All -> true
                        TaskProjectFilter.Projectless -> row["projectId"] == null || row["projectId"] is JsonNull
                        is TaskProjectFilter.Project -> row.str("projectId") == filter.id
                    } }.forEach { row -> if (row.str("id").isNotBlank()) rows[row.str("id")] = row }
                cursor = next
                val confirmedIds = response.list("data").map {
                    (if (before.query.isBlank()) it else it.map("thread")).str("id")
                }.toSet()
                _state.update { it.copy(tasks = rows.values.toList(), listCursor = cursor, listInitialized = true,
                    uncertainTaskActions = it.uncertainTaskActions - confirmedIds) }
            } while (scopedSearch && rows.size < target && cursor != null)
        } catch (e: CancellationException) { throw e
        } catch (_: Exception) {
            if (n == listSelection) _state.update { it.copy(listFailed = true) }
        } finally {
            if (n == listSelection) _state.update { it.copy(listLoading = false, refreshingTasks = false) }
        }
    }

    override fun newChat() = openNewChat()

    internal fun receiveShare(share: IncomingShare) = openNewChat(share)

    private fun openNewChat(share: IncomingShare? = null) {
        reports.actions.add("newChat")
        if (_state.value.busy) {
            if (share != null) _state.update { it.copy(error = "Please wait for the current operation, then share again.") }
            return
        }
        if (share != null) _state.update { it.copy(busy = true) }
        chatOrigin = "home"
        cancelList(); saveList()
        listSnapshots.remove(false)
        _state.update { it.copy(listInitialized = false) }
        viewModelScope.launch {
            try {
                selection++
                timeline.clear()
                buffered.clear()
                hydrating = false
                var draft = local.get("draft/new")
                var attachments = restoreAttachments("new")
                val journal = parse(local.get("journal/new"))
                var shareError: String? = null
                if (share != null) {
                    if (journal != null) {
                        shareError = "Resolve the pending send in this draft, then share again."
                    } else {
                        if (share.text.isNotBlank()) {
                            draft = listOf(draft, share.text).filter { it.isNotBlank() }.joinToString("\n\n")
                            local.put("draft/new", draft)
                        }
                        for (uri in share.streams) {
                            try {
                                require(uri.scheme == "content" &&
                                    uri.authority?.substringAfter('@') != "${getApplication<Application>().packageName}.files")
                                val added = attachmentStore.importDocument(uri, attachments.sumOf { it.byteSize })
                                try {
                                    persistAttachments("new", attachments + added)
                                } catch (e: Exception) {
                                    attachmentStore.delete(added)
                                    throw e
                                }
                                attachments += added
                            } catch (e: CancellationException) { throw e
                            } catch (_: Exception) {
                                shareError = "Some shared files could not be added. Check access and attachment size limits, then share those files again."
                            }
                        }
                        if (share.text.isBlank() && share.streams.isEmpty())
                            shareError = "This share contains no text or files."
                    }
                }
                val savedOptions = parseNewTaskOptions(local.get("options/new"))
                val options = journal?.let { optionsFromJournal(it, savedOptions) } ?: savedOptions
                _state.update {
                    val reconciled = if (it.modelCatalogStatus == ModelCatalogStatus.Ready)
                        reconcileModelOptions(options, it.models, null).options else options
                    val restored = if (it.ready && it.collaborationModes.none { mode -> mode.mode == reconciled.collaborationMode })
                        reconciled.copy(collaborationMode = null) else reconciled
                    it.copy(
                        page = "chat",
                        thread = null,
                        threadCwd = null,
                        title = "New chat",
                        entries = emptyList(),
                        turnStatuses = emptyMap(),
                        activeTurn = null,
                        queuedMessages = emptyList(),
                        queueReady = false,
                        queueError = null,
                        decisions = emptyList(),
                        historyCursor = null,
                        draft = draft,
                        attachments = attachments,
                        newTaskOptions = restored,
                        threadModel = null,
                        threadMode = null,
                        threadReasoningEffort = null,
                        threadServiceTier = null,
                        threadServiceTierKnown = false,
                        speedSaving = false,
                        speedUncertain = false,
                        speedError = null,
                        journal = journal,
                        error = shareError,
                        attention = false,
                        filePreview = null,
                    )
                }
                if (share == null && _state.value.ready) guarded { recoverNew() }
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) {
                _state.update { it.copy(error = "The draft could not be opened. Please try again.") }
            } finally {
                if (share != null) {
                    _state.update { it.copy(busy = false) }
                    if (foreground && !_state.value.ready) connect()
                }
            }
        }
    }

    override fun openTask(id: String) {
        reports.actions.add("openTask", id)
        if (_state.value.busy) return
        chatOrigin = if (_state.value.archived) "archives" else "home"
        cancelList(); saveList()
        viewModelScope.launch { guarded { loadTask(id) } }
    }

    private suspend fun readEventually(method: String, params: JsonObject): JsonObject {
        repeat(5) { attempt ->
            try {
                return rpc.call(method, params)
            } catch (e: RpcRejected) {
                if (
                    attempt == 4 ||
                        !(e.message.orEmpty().contains("list_turns is not supported yet") ||
                            e.message.orEmpty().contains("no rollout found"))
                )
                    throw e
                delay(250L shl attempt)
            }
        }
        error("History is not available yet")
    }

    private suspend fun loadTask(id: String) {
        val n = ++selection
        timeline.clear()
        buffered.clear()
        hydrating = true
        _state.update {
            it.copy(
                page = "chat",
                thread = id,
                threadCwd = null,
                title = "Conversation",
                entries = emptyList(),
                turnStatuses = emptyMap(),
                activeTurn = null,
                queuedMessages = emptyList(),
                queueReady = false,
                queueError = null,
                historyCursor = null,
                attachments = emptyList(),
                newTaskOptions = NewTaskOptions(),
                threadModel = null,
                threadMode = null,
                threadReasoningEffort = null,
                threadServiceTier = null,
                threadServiceTierKnown = false,
                speedSaving = false,
                speedUncertain = false,
                speedError = null,
                error = null,
                busy = true,
                attention = false,
                filePreview = null,
            )
        }
        var draft = local.get("draft/$id")
        var attachments = restoreAttachments(id)
        var journal = parse(local.get("journal/$id"))
        if (journal?.str("stage") == "accepted") {
            if (journal.str("clearDraft") != "false" && draft == journal.str("text")) {
                local.remove("draft/$id")
                draft = ""
                attachments.forEach(attachmentStore::delete)
                local.remove("attachments/$id")
                attachments = emptyList()
            }
            local.remove("journal/$id")
            journal = null
        }
        if (n != selection) return
        _state.update { it.copy(draft = draft, attachments = attachments, journal = journal) }
        try {
            val response =
                readEventually(
                    "thread/resume",
                    obj("threadId" to s(id), "excludeTurns" to JsonPrimitive(true)),
                )
            val thread = response.map("thread")
            val history =
                readEventually(
                    "thread/turns/list",
                    obj(
                        "threadId" to s(id),
                        "limit" to JsonPrimitive(20),
                        "itemsView" to s("full"),
                        "sortDirection" to s("desc"),
                    ),
                )
            if (n != selection) return
            timeline.hydrate(
                history.list("data").reversed(),
                buffered.filter { it.map("params").str("threadId") == id },
            )
            buffered.removeAll {
                !it.containsKey("id") &&
                    it.str("method") != "serverRequest/resolved" &&
                    it.map("params").str("threadId") == id
            }
            _state.update {
                val threadModel = response.str("model").takeIf(String::isNotBlank)
                val options =
                    reconcileModelOptions(
                        it.newTaskOptions,
                        it.models,
                        threadModel,
                    )
                it.copy(
                    title =
                        thread.str("name").ifBlank {
                            thread.str("preview").take(80).ifBlank { "Conversation" }
                        },
                    historyCursor = history.cursor(),
                    newTaskOptions = options.options,
                    threadModel = threadModel,
                    threadReasoningEffort =
                        response.str("reasoningEffort").takeIf(String::isNotBlank),
                    threadServiceTier = response.str("serviceTier").takeIf(String::isNotBlank),
                    threadServiceTierKnown = response.containsKey("serviceTier"),
                    threadCwd = thread.str("cwd").takeIf(String::isNotBlank),
                    modelCatalogMessage =
                        if (options.removedUnsupportedChoice)
                            "The server no longer supports one of the selected choices. Unsupported overrides were cleared."
                        else it.modelCatalogMessage,
                    attention = thread.map("status")["activeFlags"].toString().contains("waiting"),
                )
            }
            readQueue(id)
            if (n == selection) {
                activityMonitor.opened(id)
            }
        } finally {
            if (n == selection) {
                hydrating = false
                val queued = buffered.toList()
                buffered.clear()
                queued.forEach { applyEvent(it) }
                _state.update { it.copy(busy = false) }
                publish()
            }
        }
    }

    override fun updateNewTaskOptions(options: NewTaskOptions) {
        val before = _state.value
        reports.actions.add("taskOptions", options.projectId, options.executionTarget.name)
        val normalized =
            when {
                options.projectId == null ->
                    options.copy(
                        workingDirectory = null,
                        executionTarget = ExecutionTarget.Projectless,
                    )
                options.executionTarget == ExecutionTarget.Projectless ->
                    options.copy(executionTarget = ExecutionTarget.CurrentWorkspace)
                else -> options
            }
        _state.update {
            val changesModel =
                normalized.model != it.newTaskOptions.model ||
                    normalized.reasoningEffort != it.newTaskOptions.reasoningEffort
            if (changesModel && it.modelCatalogStatus != ModelCatalogStatus.Ready) {
                it.copy(
                    modelCatalogMessage =
                        if (it.modelCatalogStatus == ModelCatalogStatus.Loading)
                            "The model catalog is still loading."
                        else "Models are unavailable. Refresh models in Conversation settings."
                )
            } else {
                val reconciled =
                    if (it.modelCatalogStatus == ModelCatalogStatus.Ready)
                        reconcileModelOptions(normalized, it.models, it.threadModel ?: it.inheritedSettings.takeIf { config -> config.cwd == it.settingsCwd() }?.model)
                    else ReconciledModelOptions(normalized, removedUnsupportedChoice = false)
                it.copy(
                    newTaskOptions = reconciled.options,
                    modelCatalogMessage =
                        if (reconciled.removedUnsupportedChoice)
                            "That model or reasoning effort is not supported. Unsupported overrides were cleared."
                        else null,
                )
            }
        }
        val after = _state.value
        if (after.thread == null) {
            val chosen = after.newTaskOptions
            val explicitChange = chosen.model != before.newTaskOptions.model ||
                chosen.reasoningEffort != before.newTaskOptions.reasoningEffort
            val config = after.inheritedSettings.takeIf { it.cwd == after.settingsCwd() && it.status == ModelCatalogStatus.Ready }
            val model = chosen.model ?: config?.model
            // Returning to inherited settings only removes the draft override.
            if (explicitChange && model != null && (chosen.model != null || chosen.reasoningEffort != null) &&
                after.ready && after.modelCatalogStatus == ModelCatalogStatus.Ready) {
                val effort = chosen.reasoningEffort ?: after.models.firstOrNull { it.id == model }?.defaultReasoningEffort
                val epoch = rpc.generation
                viewModelScope.launch {
                    modelDefaultsMutex.withLock {
                        if (epoch != rpc.generation) return@withLock
                        if (uncertainModelDefaultsGeneration == epoch) {
                            _state.update { it.copy(modelCatalogMessage = "Model default save outcome unknown. Reconnect to read server settings; the save will not be retried.") }
                            return@withLock
                        }
                        try {
                            val target = config ?: parseInheritedSettings(
                                rpc.call("config/read", obj("includeLayers" to JsonPrimitive(false))), "")
                            if (epoch != rpc.generation) return@withLock
                            val result = rpc.call("config/batchWrite", modelDefaultsParams(model, effort, target.profile))
                            if (epoch != rpc.generation) return@withLock
                            _state.value.settingsCwd()?.let { readComposerConfig(it) }
                            if (result.str("status") == "okOverridden") _state.update {
                                it.copy(modelCatalogMessage = "Default saved, but another config layer overrides it here.")
                            }
                        } catch (e: Exception) {
                            if (e is CancellationException && e !is TimeoutCancellationException) throw e
                            if (e !is RpcRejected) uncertainModelDefaultsGeneration = epoch
                            _state.update { it.copy(modelCatalogMessage = if (e is RpcRejected)
                                "The server could not save the model default. This draft still uses your selection."
                            else "Model default save outcome unknown. Reconnect to read server settings; the save will not be retried.") }
                        }
                    }
                }
            }
            viewModelScope.launch {
                local.put(
                    "options/new",
                    newTaskOptionsJson(chosen).toString(),
                )
            }
        }
    }

    override fun selectSpeed(fast: Boolean) {
        val before = _state.value
        if (!before.ready || before.busy || before.journal != null || before.speedSaving || before.speedUncertain) return
        if (fast && !before.canSelectFast()) return
        val thread = before.thread
        if (thread == null) {
            updateNewTaskOptions(before.newTaskOptions.copy(serviceTier = if (fast) "priority" else "default"))
            return
        }
        if (!before.threadServiceTierKnown) return
        val n = selection
        val epoch = rpc.generation
        val revision = speedRevision
        _state.update { it.copy(speedSaving = true, speedError = null) }
        viewModelScope.launch {
            try {
                rpc.call("thread/settings/update", speedUpdateParams(thread, fast))
                if (n != selection || epoch != rpc.generation) return@launch
                _state.update {
                    if (it.thread != thread) it else it.copy(
                        speedSaving = false,
                        // A notification delivered during the request is newer than this acknowledgement.
                        threadServiceTier = if (revision == speedRevision) if (fast) "priority" else null else it.threadServiceTier,
                        threadServiceTierKnown = true,
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException && e !is TimeoutCancellationException) throw e
                if (n != selection || epoch != rpc.generation) return@launch
                _state.update {
                    if (it.thread != thread) it else it.copy(
                        speedSaving = false,
                        speedUncertain = e !is RpcRejected && revision == speedRevision,
                        speedError = if (e is RpcRejected) "Server: ${e.message}"
                            else if (revision != speedRevision) null
                            else SPEED_OUTCOME_UNKNOWN,
                    )
                }
            }
        }
    }

    override fun refreshModels() {
        if (!_state.value.ready || _state.value.modelCatalogStatus == ModelCatalogStatus.Loading) return
        viewModelScope.launch { refreshModelCatalog() }
        _state.value.settingsCwd()?.let { cwd -> viewModelScope.launch { readComposerConfig(cwd) } }
    }

    private suspend fun readComposerConfig(cwd: String) {
        try {
            val result = rpc.call("config/read", obj("cwd" to s(cwd), "includeLayers" to JsonPrimitive(false)))
            _state.update {
                if (it.ready && it.settingsCwd() == cwd) {
                    val config = parseInheritedSettings(result, cwd)
                    val reconciled = if (it.modelCatalogStatus == ModelCatalogStatus.Ready)
                        reconcileModelOptions(it.newTaskOptions, it.models, it.threadModel ?: config.model)
                    else ReconciledModelOptions(it.newTaskOptions, false)
                    it.copy(inheritedSettings = config, newTaskOptions = reconciled.options,
                        modelCatalogMessage = if (reconciled.removedUnsupportedChoice)
                            "The selected reasoning is unavailable for this project's model. The override was cleared."
                        else it.modelCatalogMessage)
                } else it
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            _state.update {
                if (it.settingsCwd() == cwd) it.copy(inheritedSettings = InheritedSettings(cwd, ModelCatalogStatus.Error)) else it
            }
        }
    }

    private suspend fun refreshModelCatalog() {
        if (!_state.value.ready) return
        val n = ++modelCatalogSelection
        _state.update {
            it.copy(modelCatalogStatus = ModelCatalogStatus.Loading, modelCatalogMessage = null, fastModeAllowed = null)
        }
        try {
            val fastAllowed = try {
                val requirements = rpc.call("configRequirements/read", obj()).map("requirements")
                (requirements.map("featureRequirements")["fast_mode"] as? JsonPrimitive)?.booleanOrNull != false
            } catch (e: Exception) {
                if (e is CancellationException && e !is TimeoutCancellationException) throw e
                null
            }
            val rows = mutableListOf<ServerModelOption>()
            var cursor: String? = null
            val seenCursors = mutableSetOf<String>()
            do {
                val result =
                    rpc.call(
                        "model/list",
                        obj(
                            "limit" to JsonPrimitive(100),
                            "cursor" to cursor?.let(::s),
                        ),
                    )
                rows += parseModelCatalog(result)
                val next = result.cursor()
                cursor = next?.takeIf(seenCursors::add)
            } while (cursor != null)
            if (n != modelCatalogSelection) return
            val catalog = rows.distinctBy(ServerModelOption::id)
            _state.update {
                val reconciled =
                    reconcileModelOptions(it.newTaskOptions, catalog, it.threadModel ?: it.inheritedSettings.takeIf { config -> config.cwd == it.settingsCwd() }?.model)
                it.copy(
                    models = catalog,
                    fastModeAllowed = fastAllowed,
                    modelCatalogStatus = ModelCatalogStatus.Ready,
                    newTaskOptions = reconciled.options,
                    modelCatalogMessage =
                        if (reconciled.removedUnsupportedChoice)
                            "The server no longer supports one of the selected choices. Unsupported overrides were cleared."
                        else null,
                )
            }
            if (_state.value.thread == null)
                local.put(
                    "options/new",
                    newTaskOptionsJson(_state.value.newTaskOptions).toString(),
                )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (n != modelCatalogSelection) return
            if (!_state.value.ready) return
            _state.update {
                it.copy(
                    modelCatalogStatus = ModelCatalogStatus.Error,
                    modelCatalogMessage =
                        "Could not load models from ${host.displayName}. Refresh models to try again.",
                )
            }
        }
    }

    override fun older() {
        viewModelScope.launch {
            guarded {
                val before = _state.value
                val id = before.thread ?: return@guarded
                val cursor = before.historyCursor ?: return@guarded
                val n = selection
                val history =
                    rpc.call(
                        "thread/turns/list",
                        obj(
                            "threadId" to s(id),
                            "cursor" to s(cursor),
                            "limit" to JsonPrimitive(20),
                            "itemsView" to s("full"),
                            "sortDirection" to s("desc"),
                        ),
                    )
                if (n == selection) {
                    timeline.snapshot(history.list("data").reversed(), true)
                    _state.update {
                        it.copy(historyCursor = history.cursor()?.takeUnless { c -> c == cursor })
                    }
                    publish()
                }
            }
        }
    }

    override fun draft(value: String) {
        _state.update { it.copy(draft = value) }
        val key = _state.value.thread ?: "new"
        viewModelScope.launch { local.put("draft/$key", value) }
    }

    override fun addAttachments(uris: List<Uri>) {
        val before = _state.value
        if (before.busy || before.journal != null || uris.isEmpty()) return
        val n = selection
        val key = before.thread ?: "new"
        viewModelScope.launch {
            var values = before.attachments
            for (uri in uris) {
                try {
                    val added = attachmentStore.importImage(uri, values.sumOf { it.byteSize })
                    if (n != selection || (_state.value.thread ?: "new") != key) {
                        attachmentStore.delete(added)
                        return@launch
                    }
                    values += added
                    persistAttachments(key, values)
                    _state.update { it.copy(attachments = values, error = null) }
                } catch (e: Exception) {
                    _state.update {
                        it.copy(error = e.message ?: "The selected image could not be added.")
                    }
                    break
                }
            }
        }
    }

    override fun addFiles(uris: List<Uri>) {
        val before = _state.value
        if (before.busy || before.journal != null || uris.isEmpty()) return
        val n = selection
        val key = before.thread ?: "new"
        viewModelScope.launch {
            var values = before.attachments
            for (uri in uris) {
                try {
                    val added = attachmentStore.importDocument(uri, values.sumOf { it.byteSize })
                    if (n != selection || (_state.value.thread ?: "new") != key) {
                        attachmentStore.delete(added)
                        return@launch
                    }
                    values += added
                    persistAttachments(key, values)
                    _state.update { it.copy(attachments = values, error = null) }
                } catch (e: Exception) {
                    _state.update {
                        it.copy(error = e.message ?: "The selected file could not be added.")
                    }
                    break
                }
            }
        }
    }

    override fun prepareCamera(): Uri? {
        val before = _state.value
        if (before.busy || before.journal != null) return null
        return runCatching { attachmentStore.prepareCamera() }
            .onFailure {
                _state.update { state -> state.copy(error = "The camera could not be opened.") }
            }
            .getOrNull()
    }

    override fun finishCamera(success: Boolean) {
        val before = _state.value
        val n = selection
        val key = before.thread ?: "new"
        viewModelScope.launch {
            try {
                val added =
                    attachmentStore.finishCamera(success, before.attachments.sumOf { it.byteSize })
                        ?: return@launch
                if (n != selection || (_state.value.thread ?: "new") != key) {
                    attachmentStore.delete(added)
                    return@launch
                }
                val values = before.attachments + added
                persistAttachments(key, values)
                _state.update { it.copy(attachments = values, error = null) }
            } catch (e: Exception) {
                _state.update {
                    it.copy(error = e.message ?: "The camera image could not be added.")
                }
            }
        }
    }

    override fun removeAttachment(id: String) {
        val before = _state.value
        if (before.busy || before.journal != null) return
        val removed = before.attachments.find { it.id == id } ?: return
        val values = before.attachments.filterNot { it.id == id }
        val key = before.thread ?: "new"
        _state.update { it.copy(attachments = values) }
        viewModelScope.launch {
            persistAttachments(key, values)
            attachmentStore.delete(removed)
        }
    }

    override suspend fun loadMedia(media: MediaRef): ByteArray =
        mediaRepository.load(media) { rpc.readFile(it) }

    override suspend fun loadVisualization(reference: VisualizationRef): String =
        withContext(Dispatchers.IO) { readVisualization(reference, rpc::getMetadata, rpc::readFile) }

    override suspend fun visualizationState(key: String): String =
        local.get("visualization/$key").takeIf(::validWidgetState) ?: "null"

    override suspend fun saveVisualizationState(key: String, value: String) {
        require(validWidgetState(value))
        local.put("visualization/$key", value)
    }

    override fun stageVisualizationFollowUp(prompt: String) {
        if (prompt.isBlank() || prompt.length > 16_384) return
        val current = _state.value.draft
        draft(if (current.isBlank()) prompt else "$current\n\n$prompt")
    }

    override fun inspectFile(file: FileRef) {
        val before = _state.value
        val resolved =
            runCatching { remoteFileRepository.resolve(file, before.threadCwd) }
                .getOrElse { failure ->
                    _state.update {
                        it.copy(
                            filePreview =
                                FilePreviewState(
                                    reference = file,
                                    loading = false,
                                    error = failure.message ?: "The file path could not be resolved.",
                                )
                        )
                    }
                    return
                }
        _state.update { it.copy(filePreview = FilePreviewState(resolved)) }
        val n = selection
        viewModelScope.launch {
            val preview =
                runCatching {
                        remoteFileRepository.load(resolved, rpc::getMetadata, rpc::readFile)
                    }
                    .getOrElse { failure ->
                        FilePreviewState(
                            reference = resolved,
                            loading = false,
                            error = failure.message ?: "The file is unavailable.",
                        )
                    }
            if (n == selection && _state.value.filePreview?.reference?.key == file.key)
                _state.update { it.copy(filePreview = preview) }
        }
    }

    override fun dismissFile() {
        _state.update { it.copy(filePreview = null) }
    }

    override fun saveFile(destination: Uri) {
        val preview = _state.value.filePreview?.takeIf { !it.loading && it.error == null } ?: return
        viewModelScope.launch {
            runCatching { remoteFileRepository.save(preview, destination) }
                .onFailure { failure ->
                    _state.update {
                        it.copy(error = failure.message ?: "The file could not be saved.")
                    }
                }
        }
    }

    private suspend fun restoreAttachments(key: String): List<DraftAttachment> =
        runCatching { attachmentStore.restore(local.get("attachments/$key")) }
            .getOrElse {
                local.remove("attachments/$key")
                emptyList()
            }

    private suspend fun persistAttachments(key: String, values: List<DraftAttachment>) {
        if (values.isEmpty()) local.remove("attachments/$key")
        else local.put("attachments/$key", attachmentStore.serialize(values))
    }

    override fun send() {
        reports.actions.add("send", _state.value.thread)
        val before = _state.value
        submit(before.draft, before.newTaskOptions.collaborationMode, clearDraft = true)
    }

    override fun refreshQueue() {
        val id = _state.value.thread ?: return
        if (!_state.value.ready) return
        viewModelScope.launch { readQueue(id) }
    }

    private suspend fun readQueue(id: String) {
        val selected = selection
        val revision = ++queueSelection
        val epoch = rpc.generation
        if (_state.value.thread == id) _state.update { it.copy(queueReady = false) }
        fun current() = selected == selection && revision == queueSelection &&
            epoch == rpc.generation && _state.value.thread == id && _state.value.ready
        try {
            val messages = mutableListOf<QueuedMessage>()
            val cursors = mutableSetOf<String>()
            var cursor: String? = null
            do {
                val page = rpc.call(
                    "thread/queue/list",
                    obj("threadId" to s(id), "limit" to JsonPrimitive(100), "cursor" to cursor?.let(::s)),
                )
                require(page["data"] is JsonArray)
                messages.addAll(page.list("data").map(QueuedMessage::parse))
                cursor = page.cursor()
                require(cursor == null || cursors.add(cursor))
                if (!current()) return
            } while (cursor != null)
            _state.update {
                it.copy(queuedMessages = messages.distinctBy(QueuedMessage::id), queueReady = true, queueError = null)
            }
        } catch (e: Exception) {
            if (e is CancellationException && e !is TimeoutCancellationException) throw e
            if (current()) _state.update {
                it.copy(queueReady = false, queueError = "Could not refresh queued messages.")
            }
        }
    }

    override fun sendQueuedNow(id: String) {
        reports.actions.add("sendQueuedNow", id)
        mutateQueued(id, sendNow = true)
    }

    override fun removeQueued(id: String) {
        reports.actions.add("removeQueued", id)
        mutateQueued(id, sendNow = false)
    }

    private fun mutateQueued(id: String, sendNow: Boolean) {
        val before = _state.value
        val thread = before.thread ?: return
        if (!before.ready || !before.queueReady || before.busy || before.journal != null) return
        val message = before.queuedMessages.singleOrNull { it.id == id } ?: return
        val journal = obj(
            "operation" to s(UUID.randomUUID().toString()),
            "threadId" to s(thread),
            "text" to s(message.preview),
            "clearDraft" to JsonPrimitive(false),
            "queuedMessage" to message.json(),
            "sendNow" to JsonPrimitive(sendNow),
            "stage" to s("queueActionReady"),
        )
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch { executeQueueAction(journal) }
    }

    private suspend fun executeQueueAction(initial: JsonObject, removed: Boolean = false) {
        val thread = initial.str("threadId")
        val message = QueuedMessage.parse(initial.map("queuedMessage"))
        val sendNow = initial.str("sendNow") == "true"
        var journal = initial
        suspend fun record(stage: String) {
            journal = JsonObject(journal.filterKeys { it !in setOf("failure", "uncertain") } + ("stage" to s(stage)))
            local.put("journal/$thread", journal.toString())
            _state.update { it.copy(journal = journal) }
        }
        suspend fun finish() {
            record("accepted")
            local.remove("journal/$thread")
            _state.update { it.copy(journal = null) }
        }
        try {
            val turn = _state.value.activeTurn
            if (!removed && sendNow && turn == null) {
                record("startingQueued")
                rpc.call("thread/queue/start", queueItemParams(thread, message.id))
                finish()
            } else {
                if (!removed) {
                    record("removingQueued")
                    val response = rpc.call("thread/queue/delete", queueItemParams(thread, message.id))
                    if ((response["deleted"] as? JsonPrimitive)?.booleanOrNull != true) {
                        // Another client or the server already consumed it. Never send our stale copy.
                        finish()
                        _state.update { it.copy(error = "That message is no longer queued. The queue has been refreshed.") }
                        return
                    }
                    record("queuedRemoved")
                }
                if (sendNow) {
                    record("steeringQueued")
                    if (turn != null)
                        rpc.call("turn/steer", turnSteerParams(thread, message.input, message.clientUserMessageId, turn))
                    else
                        rpc.call("turn/start", turnStartParams(thread, message.input, message.clientUserMessageId, NewTaskOptions(), null))
                }
                finish()
            }
        } catch (e: Exception) {
            if (e is CancellationException && e !is TimeoutCancellationException) throw e
            val rejected = e is RpcRejected
            if (rejected && journal.str("stage") in setOf("removingQueued", "startingQueued")) {
                local.remove("journal/$thread")
                _state.update { it.copy(journal = null, error = "The queue action was rejected. The queue has been refreshed.") }
            } else {
                val stage = if (rejected && journal.str("stage") == "steeringQueued") "queuedSteerRejected" else journal.str("stage")
                val failure = if (stage == "queuedSteerRejected")
                    "The message was removed from the queue, but the turn changed or rejected it. Your message is saved below. Tap Send saved message to try again."
                else "The queue action is uncertain. Inspect the task before sending again. Your message is saved below."
                journal = JsonObject(journal + ("stage" to s(stage)) + ("failure" to s(failure)) + ("uncertain" to JsonPrimitive(!rejected)))
                local.put("journal/$thread", journal.toString())
                _state.update { it.copy(journal = journal, error = failure) }
            }
        } finally {
            if (_state.value.ready) readQueue(thread)
            _state.update { it.copy(busy = false) }
            if (foreground && !_state.value.ready) connect()
        }
    }

    override fun implementPlan(planKey: String) {
        val before = _state.value
        val plan = before.entries.lastOrNull { it.kind != "reasoning" }
        if (
            plan?.key != planKey ||
                plan.kind != "plan" ||
                !plan.completed ||
                before.activeTurn != null ||
                before.queuedMessages.isNotEmpty() ||
                before.collaborationModes.none {
                    it.mode == "default" &&
                        it.turnSetting(before.collaborationModel()) != null
                }
        )
            return
        submit("Implement the proposed plan.", "default", clearDraft = false)
    }

    private fun submit(text: String, selectedMode: String?, clearDraft: Boolean) {
        val before = _state.value
        val queue = clearDraft && before.thread != null && before.willQueueMessage()
        val hasTurnStartOverrides =
            !queue &&
                (before.newTaskOptions.model != null ||
                    before.newTaskOptions.reasoningEffort != null)
        if (
            !before.ready ||
                before.busy || before.speedSaving || before.speedUncertain ||
                before.thread == null && isFastTier(before.newTaskOptions.serviceTier) && !before.canSelectFast() ||
                queue && selectedMode != null ||
                (text.isBlank() && before.attachments.isEmpty()) ||
                before.journal != null ||
                before.thread != null && !before.queueReady ||
                before.thread == null && !before.newTaskOptions.hasExecutionDestination() ||
                hasTurnStartOverrides &&
                    before.modelCatalogStatus != ModelCatalogStatus.Ready
        )
            return
        val mode =
            selectedMode?.let { selection ->
                before.collaborationModes.singleOrNull { it.mode == selection } ?: return
            }
        val modeSetting =
            mode?.turnSetting(before.collaborationModel()) ?: if (mode == null) null else return
        val operation = UUID.randomUUID().toString()
        val plan =
            if (before.thread == null)
                try {
                    WorkspacePlans.create(before.newTaskOptions, operation, host.expectedCodexHome)
                } catch (e: IllegalArgumentException) {
                    _state.update { it.copy(error = e.message ?: "Choose a workspace") }
                    return
                }
            else null
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val originalKey = before.thread ?: "new"
            val journal =
                obj(
                    "operation" to s(operation),
                    "text" to s(text),
                    "clearDraft" to JsonPrimitive(clearDraft),
                    "queue" to JsonPrimitive(queue),
                    "stage" to s(if (plan == null) "taskReady" else "validatingWorkspace"),
                    "cwd" to plan?.workingDirectory?.let(::s),
                    "sourceCwd" to plan?.sourceDirectory?.let(::s),
                    "worktreeRoot" to plan?.worktreeRoot?.let(::s),
                    "executionTarget" to plan?.target?.name?.let(::s),
                    "projectId" to plan?.projectId?.let(::s),
                    "model" to before.newTaskOptions.model?.let(::s),
                    "reasoningEffort" to before.newTaskOptions.reasoningEffort?.let(::s),
                    "serviceTier" to before.newTaskOptions.serviceTier?.let(::s),
                    "threadId" to before.thread?.let(::s),
                    "expectedTurnId" to before.activeTurn?.let(::s),
                    "attachments" to JsonArray(before.attachments.map { it.json() }),
                    "collaborationMode" to mode?.mode?.let(::s),
                    "collaborationModeSetting" to modeSetting,
                )
            local.put("journal/$originalKey", journal.toString())
            _state.update { it.copy(journal = journal) }
            executeSubmission(journal, originalKey, before.activeTurn)
        }
    }

    private suspend fun executeSubmission(
        initialJournal: JsonObject,
        initialKey: String,
        expectedTurn: String? = null,
    ) {
        var journal = initialJournal
        var key = initialKey
        suspend fun record(stage: String, vararg values: Pair<String, JsonElement>) {
            journal =
                JsonObject(
                    journal.filterKeys { it !in setOf("failure", "uncertain") } +
                        values.toMap() +
                        ("stage" to s(stage))
                )
            local.put("journal/$key", journal.toString())
            _state.update { it.copy(journal = journal, error = null) }
        }
        suspend fun failure(message: String, uncertain: Boolean) {
            journal =
                JsonObject(
                    journal +
                        ("failure" to s(message)) +
                        ("uncertain" to JsonPrimitive(uncertain))
                )
            local.put("journal/$key", journal.toString())
            _state.update { it.copy(journal = journal, error = message) }
        }
        suspend fun validationFailure(message: String) {
            local.remove("journal/$key")
            _state.update { it.copy(journal = null, error = message) }
        }

        try {
            var stage = journal.str("stage")
            var id = journal.str("threadId").ifEmpty { null }
            if (id == null && stage == "validatingWorkspace") {
                val target = ExecutionTarget.valueOf(journal.str("executionTarget"))
                val plan = journal.toWorkspacePlan(target)
                if (target != ExecutionTarget.Projectless) workspaces.validateSelectedProject(plan)
                when (target) {
                    ExecutionTarget.Projectless -> {
                        record("creatingDirectory")
                        workspaces.createProjectlessDirectory(journal.str("cwd"))
                        record("workspaceReady")
                    }
                    ExecutionTarget.CurrentWorkspace -> record("workspaceReady")
                    ExecutionTarget.NewWorktree -> {
                        val commit = workspaces.resolveDefaultCommit(journal.str("sourceCwd"))
                        record("creatingWorktreeRoot", "commit" to s(commit))
                        workspaces.createDirectory(journal.str("worktreeRoot"))
                        record("worktreeRootReady")
                    }
                }
                stage = journal.str("stage")
            }
            if (id == null && stage == "worktreeRootReady") {
                record("creatingWorktree")
                workspaces.createDetachedWorktree(
                    journal.str("sourceCwd"),
                    journal.str("cwd"),
                    journal.str("commit"),
                )
                record("workspaceReady")
                stage = journal.str("stage")
            }
            if (id == null && stage == "workspaceReady") {
                record("creatingTask")
                val projectId = journal.str("projectId").ifEmpty { null }
                val result =
                    rpc.call(
                        "thread/start",
                        obj(
                            "cwd" to s(journal.str("cwd")),
                            "historyMode" to s("paginated"),
                            "ephemeral" to JsonPrimitive(false),
                            "threadSource" to s("agent_created_thread"),
                            "projectId" to (projectId?.let(::s) ?: JsonNull),
                            "model" to journal.str("model").ifBlank { null }?.let(::s),
                            "serviceTier" to journal.str("serviceTier").ifBlank { null }?.let(::s),
                        ),
                    )
                val thread = result.map("thread")
                val createdId = thread.str("id")
                if (createdId.isEmpty())
                    throw WorkspaceSetupFailure("The task response did not include an ID", true)
                id = createdId
                if (thread.str("projectId").ifEmpty { null } != projectId) {
                    throw WorkspaceSetupFailure(
                        "The task did not retain the selected project; no message was sent"
                    )
                }
                journal =
                    JsonObject(
                        journal.filterKeys { it !in setOf("failure", "uncertain") } +
                            ("threadId" to s(id)) +
                            ("stage" to s("taskReady"))
                    )
                // Save the authoritative ID before moving the journal to its task key.
                local.put("journal/$key", journal.toString())
                key = id
                local.put("journal/$key", journal.toString())
                local.put("draft/$key", journal.str("text"))
                persistAttachments(key, journalAttachments(journal))
                local.remove("journal/new")
                local.remove("draft/new")
                local.remove("attachments/new")
                local.remove("options/new")
                _state.update {
                    it.copy(
                        thread = id,
                        threadCwd = journal.str("cwd").takeIf(String::isNotBlank),
                        title = journal.str("text").take(80).ifBlank { "Attachment message" },
                        journal = journal,
                        threadModel =
                            result.str("model").takeIf(String::isNotBlank),
                        threadReasoningEffort =
                            result.str("reasoningEffort").takeIf(String::isNotBlank),
                        threadServiceTier = result.str("serviceTier").takeIf(String::isNotBlank),
                        threadServiceTierKnown = result.containsKey("serviceTier"),
                        newTaskOptions = it.newTaskOptions.copy(serviceTier = null),
                    )
                }
                stage = "taskReady"
            }
            val attachments = journalAttachments(journal)
            if (id != null && stage == "taskReady" && attachments.isNotEmpty()) {
                AttachmentPolicy.validateCombined(
                    attachments.map { attachment -> attachment.kind to attachment.byteSize }
                )
                val safeThread = id.replace(Regex("[^A-Za-z0-9._-]"), "-")
                val remoteDirectory =
                    "$codexHome/attachments/remote-android/$safeThread/${journal.str("operation")}"
                record("creatingAttachmentDirectory", "remoteDirectory" to s(remoteDirectory))
                rpc.createDirectory(remoteDirectory)
                record("attachmentDirectoryReady")
                stage = "attachmentDirectoryReady"
            }
            if (
                id != null &&
                    stage in setOf("attachmentDirectoryReady", "attachmentUploaded")
            ) {
                val remoteDirectory = journal.str("remoteDirectory")
                val remotePaths =
                    journal.list("remotePaths").mapIndexed { index, row ->
                        val attachment = attachments[index]
                        TurnAttachment(
                            kind =
                                runCatching { AttachmentKind.valueOf(row.str("kind")) }
                                    .getOrDefault(attachment.kind),
                            displayName = row.str("displayName").ifBlank { attachment.displayName },
                            path = row.str("path"),
                        )
                    }.toMutableList()
                var uploadedCount = journal.str("uploadedCount").toIntOrNull() ?: 0
                for (index in uploadedCount until attachments.size) {
                    val attachment = attachments[index]
                    val file = File(attachment.localPath)
                    val format =
                        if (attachment.kind == AttachmentKind.IMAGE) ImagePolicy.inspect(file)
                        else null
                    AttachmentPolicy.validateSize(attachment.kind, file.length())
                    check(file.length() == attachment.byteSize) {
                        "A draft attachment changed before it could be sent."
                    }
                    val remotePath =
                        "$remoteDirectory/${safeAttachmentName(index + 1, attachment.displayName, attachment.kind, format)}"
                    val uploaded =
                        TurnAttachment(attachment.kind, attachment.displayName, remotePath)
                    val pendingPaths = remotePaths + uploaded
                    record(
                        "uploadingAttachment",
                        "uploadIndex" to JsonPrimitive(index),
                        "remotePaths" to JsonArray(pendingPaths.map(::turnAttachmentJson)),
                    )
                    rpc.writeFile(remotePath, withContext(Dispatchers.IO) { file.readBytes() })
                    remotePaths += uploaded
                    uploadedCount = index + 1
                    record(
                        "attachmentUploaded",
                        "uploadedCount" to JsonPrimitive(uploadedCount),
                        "remotePaths" to JsonArray(remotePaths.map(::turnAttachmentJson)),
                    )
                }
                record("attachmentsReady")
                stage = "attachmentsReady"
            }
            if (id != null && stage == "taskReady") {
                record("attachmentsReady")
                stage = "attachmentsReady"
            }
            if (id != null && stage == "attachmentsReady") {
                record("sending")
                val remotePaths =
                    journal.list("remotePaths").mapIndexed { index, row ->
                        val attachment = attachments[index]
                        TurnAttachment(
                            kind =
                                runCatching { AttachmentKind.valueOf(row.str("kind")) }
                                    .getOrDefault(attachment.kind),
                            displayName = row.str("displayName").ifBlank { attachment.displayName },
                            path = row.str("path"),
                        )
                    }
                val input = turnInput(journal.str("text"), remotePaths)
                if (journal.str("queue") == "true")
                    rpc.call(
                        "thread/queue/add",
                        queueAddParams(id, input, journal.str("operation")),
                    )
                else if (expectedTurn == null)
                    rpc.call(
                        "turn/start",
                        turnStartParams(
                            id,
                            input,
                            journal.str("operation"),
                            NewTaskOptions(
                                model = journal.str("model").takeIf(String::isNotBlank),
                                reasoningEffort =
                                    journal.str("reasoningEffort").takeIf(String::isNotBlank),
                            ),
                            journal["collaborationModeSetting"] as? JsonObject,
                        ),
                    )
                else
                    rpc.call(
                        "turn/steer",
                        turnSteerParams(id, input, journal.str("operation"), expectedTurn),
                    )
                record("accepted")
                local.remove("journal/$key")
                val clearDraft =
                    (journal["clearDraft"] as? JsonPrimitive)?.booleanOrNull != false
                if (clearDraft) {
                    local.remove("draft/$key")
                    local.remove("attachments/$key")
                    attachments.forEach(attachmentStore::delete)
                }
                _state.update {
                    it.copy(
                        draft = if (clearDraft) "" else it.draft,
                        attachments = if (clearDraft) emptyList() else it.attachments,
                        journal = null,
                        error = null,
                        newTaskOptions =
                            if (!clearDraft && journal.str("collaborationMode").isNotEmpty())
                                it.newTaskOptions.copy(
                                    collaborationMode = journal.str("collaborationMode")
                                )
                            else it.newTaskOptions,
                    )
                }
                readQueue(id)
            }
        } catch (e: TimeoutCancellationException) {
            if (journal.str("stage") == "validatingWorkspace") {
                validationFailure(
                    "The host timed out while checking the selected workspace. Try again."
                )
            } else {
                throw e
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: WorkspaceSetupFailure) {
            if (journal.str("stage") == "validatingWorkspace" && !e.uncertain) {
                validationFailure(e.userMessage)
            } else failure(e.userMessage, e.uncertain)
        } catch (e: RpcRejected) {
            if (journal.str("stage") == "validatingWorkspace") {
                validationFailure(
                    "The host could not inspect the selected workspace. Refresh projects and try again."
                )
            } else if (journal.str("stage") == "sending") {
                local.remove("journal/$key")
                _state.update {
                    it.copy(journal = null, error = "Server rejected the message. It was not sent.")
                }
            } else {
                failure(
                    when (journal.str("stage")) {
                        "creatingTask" -> "Task creation was rejected. The workspace was retained."
                        "creatingWorktree" -> "Worktree creation was rejected. Check the retained destination."
                        else -> "Workspace preparation was rejected by the host."
                    },
                    false,
                )
            }
        } catch (_: Exception) {
            if (journal.str("stage") == "validatingWorkspace") {
                validationFailure(
                    "The selected workspace could not be checked. Check the connection and try again."
                )
            } else {
                failure(
                    when (journal.str("stage")) {
                        "creatingDirectory", "creatingWorktreeRoot", "creatingWorktree" ->
                            "Workspace preparation is uncertain. Reconnect to inspect the retained destination."
                        "creatingTask" ->
                            "Task creation is uncertain. Reconnect to inspect the workspace before retrying."
                        else -> "Delivery is uncertain. Inspect the task before sending again."
                    },
                    true,
                )
            }
        } finally {
            _state.update { it.copy(busy = false) }
            if (foreground && !_state.value.ready) connect()
        }
    }

    private fun JsonObject.toWorkspacePlan(target: ExecutionTarget) =
        WorkspacePlan(
            target = target,
            projectId = str("projectId").ifEmpty { null },
            sourceDirectory = str("sourceCwd").ifEmpty { null },
            workingDirectory = str("cwd"),
            worktreeRoot = str("worktreeRoot").ifEmpty { null },
        )

    private fun journalAttachments(journal: JsonObject): List<DraftAttachment> {
        val raw = journal["attachments"] as? JsonArray ?: return emptyList()
        val restored = attachmentStore.restore(raw.toString())
        require(restored.size == raw.size) { "A draft attachment is no longer available." }
        return restored
    }

    private fun turnAttachmentJson(value: TurnAttachment) =
        obj(
            "kind" to s(value.kind.name),
            "displayName" to s(value.displayName),
            "path" to s(value.path),
        )

    private suspend fun recoverNew(userInitiated: Boolean = false) {
        var journal = _state.value.journal ?: return
        if (!_state.value.ready || _state.value.busy) return
        _state.update { it.copy(busy = true, error = null) }
        suspend fun record(stage: String, message: String? = null, uncertain: Boolean? = null) {
            val values = journal.filterKeys { it !in setOf("failure", "uncertain") }.toMutableMap()
            values["stage"] = s(stage)
            if (message != null) values["failure"] = s(message)
            if (uncertain != null) values["uncertain"] = JsonPrimitive(uncertain)
            journal = JsonObject(values)
            local.put("journal/new", journal.toString())
            _state.update { it.copy(journal = journal, error = message) }
        }
        try {
            val stage = journal.str("stage")
            val wasUncertain = journal.str("uncertain") == "true"
            when (stage) {
                "validatingWorkspace" ->
                    if (journal.str("failure").isEmpty() || userInitiated)
                        executeSubmission(journal, "new")
                "creatingDirectory" -> {
                    if (workspaces.directoryExists(journal.str("cwd"))) {
                        record("workspaceReady")
                        executeSubmission(journal, "new")
                    } else if (userInitiated && !wasUncertain) {
                        record("validatingWorkspace")
                        executeSubmission(journal, "new")
                    } else
                        record(
                            stage,
                            "The conversation directory was not found. Nothing was retried automatically.",
                            true,
                        )
                }
                "creatingWorktreeRoot" -> {
                    if (workspaces.directoryExists(journal.str("worktreeRoot"))) {
                        record("worktreeRootReady")
                        executeSubmission(journal, "new")
                    } else if (userInitiated && !wasUncertain) {
                        record("validatingWorkspace")
                        executeSubmission(journal, "new")
                    } else
                        record(
                            stage,
                            "The worktree destination was not found. Nothing was retried automatically.",
                            true,
                        )
                }
                "creatingWorktree" -> {
                    if (
                        workspaces.worktreeExists(
                            journal.str("sourceCwd"),
                            journal.str("cwd"),
                        )
                    ) {
                        record("workspaceReady")
                        executeSubmission(journal, "new")
                    } else if (userInitiated && !wasUncertain) {
                        record("worktreeRootReady")
                        executeSubmission(journal, "new")
                    } else
                        record(
                            stage,
                            "Git does not report the expected worktree. Nothing was retried automatically.",
                            true,
                        )
                }
                "worktreeRootReady", "workspaceReady" ->
                    if (journal.str("failure").isEmpty() || userInitiated)
                        executeSubmission(journal, "new")
                "creatingTask" -> {
                    val found = findPreparedTask(journal)
                    if (found != null) {
                        journal = adoptRecoveredTask(journal, found)
                        executeSubmission(journal, found)
                    } else
                        record(
                            stage,
                            "No matching task was found. Task creation was not retried; inspect the host before discarding this record.",
                            true,
                        )
                }
                "taskReady" -> {
                    val found = journal.str("threadId")
                    if (found.isNotEmpty()) {
                        journal = adoptRecoveredTask(journal, found)
                        executeSubmission(journal, found)
                    }
                }
                "creatingAttachmentDirectory" -> {
                    val found = journal.str("threadId")
                    if (found.isNotEmpty() && workspaces.directoryExists(journal.str("remoteDirectory"))) {
                        record("attachmentDirectoryReady")
                        journal = adoptRecoveredTask(journal, found)
                        executeSubmission(journal, found)
                    } else
                        record(
                            stage,
                            "The attachment directory could not be confirmed. Nothing was retried automatically.",
                            true,
                        )
                }
                "attachmentDirectoryReady", "attachmentUploaded", "attachmentsReady" -> {
                    val found = journal.str("threadId")
                    if (found.isNotEmpty()) {
                        journal = adoptRecoveredTask(journal, found, stage)
                        executeSubmission(journal, found)
                    }
                }
                "uploadingAttachment" ->
                    record(
                        stage,
                                "An attachment write may have reached the host. It was not retried automatically.",
                        true,
                    )
                "sending", "accepted" -> {
                    val known = journal.str("threadId")
                    val found = if (known.isNotEmpty()) known else findPreparedTask(journal)
                    if (!found.isNullOrEmpty()) {
                        local.put(
                            "journal/$found",
                            JsonObject(journal + ("threadId" to s(found))).toString(),
                        )
                        local.put("draft/$found", journal.str("text"))
                        persistAttachments(found, journalAttachments(journal))
                        local.remove("journal/new")
                        local.remove("draft/new")
                        local.remove("attachments/new")
                        loadTask(found)
                    }
                }
            }
        } catch (_: Exception) {
            val message = "Could not inspect the saved setup. Check the connection and try again."
            val failed =
                JsonObject(
                    journal +
                        ("failure" to s(message)) +
                        ("uncertain" to JsonPrimitive(true))
                )
            local.put("journal/new", failed.toString())
            _state.update { it.copy(journal = failed, error = message) }
        } finally {
            _state.update { it.copy(busy = false) }
        }
    }

    private suspend fun adoptRecoveredTask(
        journal: JsonObject,
        id: String,
        stage: String = "taskReady",
    ): JsonObject {
        val adopted =
            JsonObject(
                journal.filterKeys { it !in setOf("failure", "uncertain") } +
                    ("threadId" to s(id)) +
                    ("stage" to s(stage))
            )
        local.put("journal/$id", adopted.toString())
        local.put("draft/$id", adopted.str("text"))
        persistAttachments(id, journalAttachments(adopted))
        local.remove("journal/new")
        local.remove("draft/new")
        local.remove("attachments/new")
        local.remove("options/new")
        _state.update {
            it.copy(
                thread = id,
                title = adopted.str("text").take(80).ifBlank { "Attachment message" },
                journal = adopted,
            )
        }
        return adopted
    }

    private suspend fun findPreparedTask(journal: JsonObject): String? {
        var cursor: String? = null
        repeat(10) {
            val projectId = journal.str("projectId")
            val page =
                rpc.call(
                    "thread/list",
                    obj(
                        "cwd" to s(journal.str("cwd")),
                        "projectId" to
                            (projectId.ifEmpty { null }?.let(::s) ?: JsonNull),
                        "limit" to JsonPrimitive(100),
                        "cursor" to cursor?.let(::s),
                    ),
                )
            val matches =
                page.list("data").filter {
                    it.str("cwd") == journal.str("cwd") &&
                        it.str("projectId") == projectId
                }
            if (matches.size > 1) return null
            if (matches.size == 1) return matches.single().str("id").ifEmpty { null }
            val next = page.cursor() ?: return null
            if (next == cursor) return null
            cursor = next
        }
        return null
    }

    override fun recoverPreparation() {
        reports.actions.add("recoverPreparation", _state.value.thread)
        val before = _state.value
        if (!before.ready || before.busy || before.journal == null) return
        if (before.thread != null) {
            val stage = before.journal.str("stage")
            if (stage in setOf("queuedRemoved", "queuedSteerRejected")) {
                // These stages prove no uncertain turn mutation remains. Only this explicit
                // user action may attempt the saved input against the current active turn.
                _state.update { it.copy(busy = true, error = null) }
                viewModelScope.launch { executeQueueAction(before.journal, removed = true) }
            } else if (stage in setOf("attachmentDirectoryReady", "attachmentUploaded", "attachmentsReady")) {
                _state.update { it.copy(busy = true, error = null) }
                viewModelScope.launch {
                    executeSubmission(
                        before.journal,
                        before.thread,
                        before.journal.str("expectedTurnId").ifEmpty { null },
                    )
                }
            } else viewModelScope.launch { guarded { loadTask(before.thread) } }
        } else {
            viewModelScope.launch { recoverNew(userInitiated = true) }
        }
    }

    override fun unlockAfterReview() {
        viewModelScope.launch {
            val key = _state.value.thread ?: "new"
            val record = _state.value.journal ?: return@launch
            local.put("reviewed/${record.str("operation")}", record.toString())
            local.remove("journal/$key")
            _state.update { it.copy(journal = null, error = null) }
        }
    }

    override fun stop() {
        reports.actions.add("stop", _state.value.thread)
        viewModelScope.launch {
            guarded {
                val st = _state.value
                val turn = st.activeTurn ?: return@guarded
                rpc.call("turn/interrupt", obj("threadId" to s(st.thread!!), "turnId" to s(turn)))
            }
        }
    }

    override fun answer(decision: Decision, result: JsonObject) {
        reports.actions.add("answer", decision.key, decision.method)
        viewModelScope.launch {
            guarded {
                if (requests[decision.key] != decision || !_state.value.ready)
                    throw ConnectionLost()
                rpc.respond(decision.id, result, decision.epoch)
                requests.remove(decision.key)
                publish()
            }
        }
    }

    private fun handle(event: JsonObject) {
        if (event.str("_epoch").toLongOrNull()?.let { it != rpc.generation } == true) return
        if (event.str("method") == "connection/lost") {
            activityMonitor.disconnected()
            requests.clear()
            _state.update {
                it.copy(
                    ready = false,
                    queueReady = false,
                    connection = "Disconnected",
                    speedUncertain = it.speedUncertain || it.speedSaving,
                    speedError = if (it.speedSaving) SPEED_OUTCOME_UNKNOWN else it.speedError,
                    speedSaving = false,
                    decisions = emptyList(),
                    modelCatalogStatus = ModelCatalogStatus.Unavailable,
                )
            }
            if (foreground && connectionJob?.isActive != true) {
                connect()
            }
            return
        }
        if (event.str("method") == "project/changed") {
            viewModelScope.launch {
                guarded {
                    refreshProjects()
                    if (_state.value.page in listOf("home", "archives")) {
                        cancelList()
                        launchList()
                    }
                }
            }
            return
        }
        if (event.str("method") in setOf("thread/archived", "thread/unarchived")) {
            invalidateArchiveSnapshots()
            cancelList()
            if (_state.value.page in listOf("home", "archives")) launchList()
            else _state.update { it.copy(listInitialized = false) }
            return
        }
        activityMonitor.event(event)
        if (hydrating) {
            buffered.add(event)
            return
        }
        applyEvent(event)
    }

    private fun applyEvent(event: JsonObject) {
        val method = event.str("method")
        val p = event.map("params")
        if (event.containsKey("id")) {
            val d =
                Decision(
                    event["id"]!!,
                    method,
                    p,
                    event.str("_epoch").toLongOrNull() ?: rpc.generation,
                )
            requests[d.key] = d
            publish()
            return
        }
        if (method == "serverRequest/resolved") {
            requests.remove(p["requestId"].toString())
            publish()
            return
        }
        if (p.str("threadId") != _state.value.thread) return
        if (method == "thread/queue/changed") {
            refreshQueue()
            return
        }
        timeline.event(method, p)
        if (method == "thread/settings/updated") {
            val settings = p.map("threadSettings")
            if (settings.containsKey("serviceTier")) speedRevision++
            _state.update {
                val threadModel = settings.str("model").takeIf(String::isNotBlank)
                val reconciled =
                    reconcileModelOptions(it.newTaskOptions, it.models, threadModel)
                it.copy(
                    threadModel = threadModel,
                    threadReasoningEffort = settings.str("effort").takeIf(String::isNotBlank),
                    threadServiceTier = if (settings.containsKey("serviceTier")) settings.str("serviceTier").takeIf(String::isNotBlank) else it.threadServiceTier,
                    threadServiceTierKnown = it.threadServiceTierKnown || settings.containsKey("serviceTier"),
                    speedUncertain = if (settings.containsKey("serviceTier")) false else it.speedUncertain,
                    speedError = if (settings.containsKey("serviceTier")) null else it.speedError,
                    threadCwd = settings.str("cwd").takeIf(String::isNotBlank) ?: it.threadCwd,
                    threadMode = settings.map("collaborationMode").str("mode").takeIf(String::isNotBlank),
                    newTaskOptions = reconciled.options,
                    modelCatalogMessage =
                        if (reconciled.removedUnsupportedChoice)
                            "The server no longer supports the selected reasoning effort. The unsupported override was cleared."
                        else it.modelCatalogMessage,
                )
            }
        }
        if (method == "thread/status/changed")
            _state.update {
                it.copy(attention = p.map("status")["activeFlags"].toString().contains("waiting"))
            }
        if (method == "turn/completed") _state.update { it.copy(attention = false) }
        publish()
    }

    private fun publish() {
        _state.update { st ->
            st.copy(
                entries = timeline.values(),
                turnStatuses = timeline.turnStatuses,
                activeTurn = timeline.activeTurn,
                decisions = requests.values.filter { it.thread == st.thread },
            )
        }
    }

    private suspend fun guarded(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            _state.update {
                it.copy(
                    error =
                        if (e is RpcRejected) "Server: ${e.message}"
                        else "Could not refresh. Check the connection and try again.",
                    busy = false,
                )
            }
        }
    }

    private fun parse(value: String) =
        runCatching { wire.parseToJsonElement(value).jsonObject }.getOrNull()

    private fun newTaskOptionsJson(options: NewTaskOptions) =
        obj(
            "projectId" to options.projectId?.let(::s),
            "workingDirectory" to options.workingDirectory?.let(::s),
            "executionTarget" to s(options.executionTarget.name),
            "model" to options.model?.let(::s),
            "reasoningEffort" to options.reasoningEffort?.let(::s),
            "serviceTier" to options.serviceTier?.let(::s),
            "approvalPolicy" to options.approvalPolicy?.let(::s),
            "collaborationMode" to options.collaborationMode?.let(::s),
        )

    private fun parseNewTaskOptions(value: String): NewTaskOptions {
        val saved = parse(value) ?: return NewTaskOptions()
        val target =
            runCatching { ExecutionTarget.valueOf(saved.str("executionTarget")) }
                .getOrDefault(ExecutionTarget.Projectless)
        return NewTaskOptions(
            projectId = saved.str("projectId").ifBlank { null },
            workingDirectory = saved.str("workingDirectory").ifBlank { null },
            executionTarget = target,
            model = saved.str("model").ifBlank { null },
            reasoningEffort = saved.str("reasoningEffort").ifBlank { null },
            serviceTier = saved.str("serviceTier").ifBlank { null },
            approvalPolicy = saved.str("approvalPolicy").ifBlank { null },
            collaborationMode = saved.str("collaborationMode").ifBlank { null },
        )
    }

    private fun optionsFromJournal(journal: JsonObject, saved: NewTaskOptions): NewTaskOptions {
        val projectId = (journal["projectId"] as? JsonPrimitive)?.contentOrNull
        val target =
            runCatching { ExecutionTarget.valueOf(journal.str("executionTarget")) }
                .getOrDefault(
                    if (projectId == null) ExecutionTarget.Projectless
                    else ExecutionTarget.CurrentWorkspace
                )
        return saved.copy(
            projectId = projectId,
            workingDirectory =
                journal.str("sourceCwd").ifBlank { journal.str("cwd") }.takeIf {
                    projectId != null
                },
            executionTarget = target,
            model = journal.str("model").takeIf(String::isNotBlank) ?: saved.model,
            serviceTier = journal.str("serviceTier").takeIf(String::isNotBlank),
            reasoningEffort =
                journal.str("reasoningEffort").takeIf(String::isNotBlank)
                    ?: saved.reasoningEffort,
            collaborationMode =
                journal.str("collaborationMode").takeIf(String::isNotBlank)
                    ?: saved.collaborationMode,
        )
    }

    override fun onCleared() {
        network.unregisterNetworkCallback(callback)
        rpc.dispose()
    }
}
