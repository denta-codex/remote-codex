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
import kotlinx.serialization.json.*

class ClientModel
@JvmOverloads
constructor(
    app: Application,
    private val endpoint: String = GraceHost.endpoint,
    private val expectedHome: String = GraceHost.expectedCodexHome,
    allowLoopbackTest: Boolean = false,
) : AndroidViewModel(app), ClientActions {
    private val host =
        GraceHost.copy(endpoint = endpoint, expectedCodexHome = expectedHome)
    private val local: ClientStore = LocalStore(app)
    private val rpc: RemoteSession = StockRemoteSession(allowLoopbackTest)
    private val updater = AppUpdater(app, endpoint, allowLoopbackTest)
    private val workspaces = StockWorkspaceAdapter(rpc)
    private val timeline = Timeline()
    private val attachmentStore = AttachmentStore(app)
    private val mediaRepository = MediaRepository(app)
    private val remoteFileRepository = RemoteFileRepository(app)
    private val _state = MutableStateFlow(ScreenState(host = host))
    val state = _state.asStateFlow()
    private var connectionJob: Job? = null
    private var updateJob: Job? = null
    private var foreground = false
    private var selection = 0
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
        _state.update { it.copy(listLoading = false) }
    }

    private fun saveList() {
        val st = _state.value
        listSnapshots[st.archived] = ChatListSnapshot(st.query, st.projectFilter, st.chatSort,
            st.tasks, st.listCursor, st.listInitialized, st.listFailed, st.listIndex, st.listOffset)
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

    private fun launchList(more: Boolean = false, debounce: Boolean = false) {
        if (!_state.value.ready || listJob?.isActive == true || _state.value.listLoading) return
        _state.update { it.copy(listLoading = true, listFailed = false) }
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
        network.registerDefaultNetworkCallback(callback)
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
        if (value) UpdateInstallResults.consume(getApplication())?.let(::applyInstallResult)
        if (value && !_state.value.ready) connect()
    }

    override fun connect() {
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
                            newTaskOptions =
                                it.newTaskOptions.copy(collaborationMode = null),
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
        settingsOrigin = _state.value.page
        cancelList()
        saveList()
        _state.update { it.copy(page = "settings", error = null) }
    }

    override fun back() {
        if (_state.value.busy) return
        when (_state.value.page) {
            "archives" -> {
                cancelList(); saveList()
                _state.update { it.copy(page = "settings", error = null) }
            }
            "settings" -> if (settingsOrigin == "chat") _state.update { it.copy(page = "chat") } else home()
            "chat" -> if (chatOrigin == "archives") showList(true) else home()
            else -> home()
        }
    }

    override fun home() {
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

    override fun moreTasks() {
        val st = _state.value
        if (st.listLoading || st.listFailed || st.listCursor == null) return
        launchList(more = true)
    }

    override fun retryList() { launchList(more = _state.value.listInitialized && _state.value.listCursor != null) }

    override fun listPosition(index: Int, offset: Int) {
        _state.update { it.copy(listIndex = index, listOffset = offset) }
    }

    private fun invalidateList() {
        cancelList()
        _state.update { it.copy(tasks = emptyList(), listCursor = null, listInitialized = false,
            listFailed = false, listIndex = 0, listOffset = 0) }
    }

    override fun restoreChat(id: String) {
        if (!_state.value.ready || id in _state.value.restoring) return
        val reconcileOnly = id in _state.value.uncertainRestores
        _state.update { it.copy(restoring = it.restoring + id, error = null) }
        viewModelScope.launch {
            try {
                if (!reconcileOnly) {
                    try {
                        rpc.call("thread/unarchive", obj("threadId" to s(id)))
                        restored(id)
                        return@launch
                    } catch (e: CancellationException) { throw e
                    } catch (_: Exception) {
                        _state.update { it.copy(uncertainRestores = it.uncertainRestores + id) }
                    }
                }
                // A failed write may have succeeded. Reconcile before enabling another write.
                var cursor: String? = null
                val seen = mutableSetOf<String>()
                do {
                    val response = rpc.call("thread/list", obj("archived" to JsonPrimitive(true),
                        "limit" to JsonPrimitive(30), "cursor" to cursor?.let(::s),
                        "modelProviders" to JsonArray(emptyList()), "sourceKinds" to listSources()))
                    if (response.list("data").any { it.str("id") == id }) {
                        _state.update { it.copy(uncertainRestores = it.uncertainRestores - id,
                            error = "Chat is still archived. You can try Restore again.") }
                        return@launch
                    }
                    cursor = response.cursor()
                    check(cursor == null || seen.add(cursor)) { "Archive cursor repeated" }
                } while (cursor != null)
                restored(id)
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) {
                _state.update { it.copy(error = "Restore could not be verified. Use Check status before trying again.",
                    uncertainRestores = it.uncertainRestores + id) }
            } finally {
                _state.update { it.copy(restoring = it.restoring - id) }
            }
        }
    }

    private fun restored(id: String) {
        // An older in-flight archive page must not put the restored row back.
        if (_state.value.archived) cancelList()
        val archived = listSnapshots[true]
        if (archived != null) listSnapshots[true] = archived.copy(tasks = archived.tasks.filterNot { it.str("id") == id })
        // Reload the inbox when it is next visited so the restored chat can appear in server order.
        listSnapshots.remove(false)
        _state.update { st -> st.copy(
            tasks = if (st.archived) st.tasks.filterNot { it.str("id") == id } else st.tasks,
            uncertainRestores = st.uncertainRestores - id,
            listInitialized = if (st.archived) st.listInitialized else false) }
    }

    private suspend fun refreshProjects() {
        if (!_state.value.ready) return
        val projects = linkedMapOf<String, CodexProject>()
        val cursors = mutableSetOf<String>()
        var cursor: String? = null
        var requests = 0
        do {
            check(requests++ < 100) { "Project loading exceeded its request limit" }
            val result =
                rpc.call(
                    "project/list",
                    obj(
                        "cursor" to cursor?.let(::s),
                        "limit" to JsonPrimitive(50),
                        "sortKey" to s("position"),
                        "sortDirection" to s("asc"),
                    ),
                )
            result.list("data").forEach { row ->
                val id = row.str("id")
                if (id.isNotEmpty()) {
                    projects[id] =
                        CodexProject(
                            id = id,
                            name = row.str("name").ifBlank { "Untitled project" },
                            roots = row.list("roots").map { it.str("path") }.filter(String::isNotBlank),
                        )
                }
            }
            check(projects.size <= 5000) { "Project loading exceeded its catalog limit" }
            cursor = result.cursor()
            check(cursor == null || cursors.add(cursor!!)) { "Project loading repeated a page" }
        } while (cursor != null)
        val values = projects.values.toList()
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
                        workingDirectory = selected.primaryRoot,
                        executionTarget = ExecutionTarget.CurrentWorkspace,
                    )
                else before.newTaskOptions
            before.copy(projects = values, projectFilter = availableFilter, newTaskOptions = options)
        }
        if (_state.value.page == "chat" && _state.value.thread == null) {
            local.put("options/new", newTaskOptionsJson(_state.value.newTaskOptions).toString())
        }
    }

    private fun listSources() = JsonArray(listOf("cli", "vscode", "exec", "appServer", "subAgent",
        "subAgentReview", "subAgentCompact", "subAgentThreadSpawn", "subAgentOther", "unknown").map(::s))

    private suspend fun refreshList(more: Boolean = false) {
        if (!_state.value.ready) { _state.update { it.copy(listLoading = false) }; return }
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
                    "sourceKinds" to listSources(), "cursor" to cursor?.let(::s),
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
                _state.update { it.copy(tasks = rows.values.toList(), listCursor = cursor, listInitialized = true) }
            } while (scopedSearch && rows.size < target && cursor != null)
        } catch (e: CancellationException) { throw e
        } catch (_: Exception) {
            if (n == listSelection) _state.update { it.copy(listFailed = true) }
        } finally {
            if (n == listSelection) _state.update { it.copy(listLoading = false) }
        }
    }

    override fun newChat() {
        if (_state.value.busy) return
        chatOrigin = "home"
        cancelList(); saveList()
        listSnapshots.remove(false)
        _state.update { it.copy(listInitialized = false) }
        viewModelScope.launch {
            selection++
            timeline.clear()
            buffered.clear()
            hydrating = false
            val draft = local.get("draft/new")
            val attachments = restoreAttachments("new")
            val journal = parse(local.get("journal/new"))
            val savedOptions = parseNewTaskOptions(local.get("options/new"))
            val options = journal?.let { optionsFromJournal(it, savedOptions) } ?: savedOptions
            _state.update {
                it.copy(
                    page = "chat",
                    thread = null,
                    threadCwd = null,
                    title = "New chat",
                    entries = emptyList(),
                    activeTurn = null,
                    queuedMessages = emptyList(),
                    queueReady = false,
                    queueError = null,
                    decisions = emptyList(),
                    historyCursor = null,
                    draft = draft,
                    attachments = attachments,
                    newTaskOptions = options,
                    threadModel = null,
                    threadReasoningEffort = null,
                    journal = journal,
                    error = null,
                    attention = false,
                    filePreview = null,
                )
            }
            if (_state.value.ready) guarded { recoverNew() }
        }
    }

    override fun openTask(id: String) {
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
                activeTurn = null,
                queuedMessages = emptyList(),
                queueReady = false,
                queueError = null,
                historyCursor = null,
                attachments = emptyList(),
                newTaskOptions = NewTaskOptions(),
                threadModel = null,
                threadReasoningEffort = null,
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
                val threadModel = thread.str("model").takeIf(String::isNotBlank)
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
                        thread.str("reasoningEffort").takeIf(String::isNotBlank),
                    threadCwd = thread.str("cwd").takeIf(String::isNotBlank),
                    modelCatalogMessage =
                        if (options.removedUnsupportedChoice)
                            "The server no longer supports one of the selected choices. Unsupported overrides were cleared."
                        else it.modelCatalogMessage,
                    attention = thread.map("status")["activeFlags"].toString().contains("waiting"),
                )
            }
            readQueue(id)
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
                        else "Models are unavailable. Refresh the server catalog and try again."
                )
            } else {
                val reconciled =
                    if (it.modelCatalogStatus == ModelCatalogStatus.Ready)
                        reconcileModelOptions(normalized, it.models, it.threadModel)
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
        if (_state.value.thread == null)
            viewModelScope.launch {
                local.put(
                    "options/new",
                    newTaskOptionsJson(_state.value.newTaskOptions).toString(),
                )
            }
    }

    override fun refreshModels() {
        if (!_state.value.ready || _state.value.modelCatalogStatus == ModelCatalogStatus.Loading)
            return
        viewModelScope.launch { refreshModelCatalog() }
    }

    private suspend fun refreshModelCatalog() {
        if (!_state.value.ready) return
        val n = ++modelCatalogSelection
        _state.update {
            it.copy(modelCatalogStatus = ModelCatalogStatus.Loading, modelCatalogMessage = null)
        }
        try {
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
                    reconcileModelOptions(it.newTaskOptions, catalog, it.threadModel)
                it.copy(
                    models = catalog,
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
                        "Could not load models from ${host.displayName}. Refresh the catalog to try again.",
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

    override fun sendQueuedNow(id: String) = mutateQueued(id, sendNow = true)

    override fun removeQueued(id: String) = mutateQueued(id, sendNow = false)

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
                before.busy ||
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
                            thread.str("model").takeIf(String::isNotBlank)
                                ?: journal.str("model").takeIf(String::isNotBlank),
                        threadReasoningEffort =
                            thread.str("reasoningEffort").takeIf(String::isNotBlank),
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
        viewModelScope.launch {
            guarded {
                val st = _state.value
                val turn = st.activeTurn ?: return@guarded
                rpc.call("turn/interrupt", obj("threadId" to s(st.thread!!), "turnId" to s(turn)))
            }
        }
    }

    override fun answer(decision: Decision, result: JsonObject) {
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
            requests.clear()
            _state.update {
                it.copy(
                    ready = false,
                    queueReady = false,
                    connection = "Disconnected",
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
            _state.update {
                val threadModel = settings.str("model").takeIf(String::isNotBlank)
                val reconciled =
                    reconcileModelOptions(it.newTaskOptions, it.models, threadModel)
                it.copy(
                    threadModel = threadModel,
                    threadReasoningEffort = settings.str("effort").takeIf(String::isNotBlank),
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
