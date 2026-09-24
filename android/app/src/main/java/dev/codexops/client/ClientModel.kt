package dev.codexops.client

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.codexops.core.*
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
    private val workspaces = StockWorkspaceAdapter(rpc)
    private val timeline = Timeline()
    private val _state = MutableStateFlow(ScreenState(host = host))
    val state = _state.asStateFlow()
    private var connectionJob: Job? = null
    private var foreground = false
    private var selection = 0
    private var listSelection = 0
    private var modelCatalogSelection = 0
    private var hydrating = false
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
    }

    fun foreground(value: Boolean) {
        foreground = value
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
                        _state.update {
                            it.copy(connection = "Connected to ${host.displayName}", ready = true)
                        }
                        refreshProjects()
                        viewModelScope.launch { refreshModelCatalog() }
                        refreshList()
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
                connect()
            } catch (_: Exception) {
                _state.update {
                    it.copy(error = "Could not save credential. Check its value and try again.")
                }
            }
        }
    }

    override fun settings() {
        _state.update { it.copy(page = "settings", error = null) }
    }

    override fun home() {
        if (_state.value.busy) return
        selection++
        _state.update { it.copy(page = "home", error = null) }
        viewModelScope.launch {
            guarded {
                refreshProjects()
                refreshList()
            }
        }
    }

    override fun query(value: String) {
        _state.update { it.copy(query = value) }
        val n = ++listSelection
        viewModelScope.launch {
            delay(300)
            if (n == listSelection) guarded { refreshList() }
        }
    }

    override fun archived(value: Boolean) {
        _state.update { it.copy(archived = value) }
        viewModelScope.launch { guarded { refreshList() } }
    }

    override fun projectFilter(value: TaskProjectFilter) {
        _state.update { it.copy(projectFilter = value) }
        viewModelScope.launch { guarded { refreshList() } }
    }

    override fun moreTasks() {
        viewModelScope.launch { guarded { refreshList(true) } }
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

    private suspend fun refreshList(more: Boolean = false) {
        if (!_state.value.ready) return
        val before = _state.value
        val n = ++listSelection
        val params =
            obj(
                "limit" to JsonPrimitive(30),
                "archived" to JsonPrimitive(before.archived),
                "modelProviders" to if (before.query.isBlank()) JsonArray(emptyList()) else null,
                "sortKey" to s("updated_at"),
                "sourceKinds" to
                    JsonArray(
                        listOf(
                                "cli",
                                "vscode",
                                "exec",
                                "appServer",
                                "subAgent",
                                "subAgentReview",
                                "subAgentCompact",
                                "subAgentThreadSpawn",
                                "subAgentOther",
                                "unknown",
                            )
                            .map(::s)
                    ),
                "cursor" to if (more) before.listCursor?.let(::s) else null,
                "searchTerm" to before.query.takeIf { it.isNotBlank() }?.let(::s),
                "projectId" to
                    if (before.query.isNotBlank()) null
                    else
                        when (val filter = before.projectFilter) {
                            TaskProjectFilter.All -> null
                            TaskProjectFilter.Projectless -> JsonNull
                            is TaskProjectFilter.Project -> s(filter.id)
                        },
            )
        val result =
            rpc.call(if (before.query.isBlank()) "thread/list" else "thread/search", params)
        if (n != listSelection) return
        _state.update {
            it.copy(
                tasks =
                    ((if (more) before.tasks else emptyList()) +
                            result.list("data").map { row ->
                                if (before.query.isBlank()) row else row.map("thread")
                            })
                        .filter { task ->
                            when (val filter = before.projectFilter) {
                                TaskProjectFilter.All -> true
                                TaskProjectFilter.Projectless ->
                                    task["projectId"] == null || task["projectId"] is JsonNull
                                is TaskProjectFilter.Project -> task.str("projectId") == filter.id
                            }
                        }
                        .distinctBy { t -> t.str("id") },
                listCursor = result.cursor(),
            )
        }
    }

    override fun newChat() {
        if (_state.value.busy) return
        viewModelScope.launch {
            selection++
            timeline.clear()
            buffered.clear()
            hydrating = false
            val draft = local.get("draft/new")
            val journal = parse(local.get("journal/new"))
            val savedOptions = parseNewTaskOptions(local.get("options/new"))
            val options = journal?.let { optionsFromJournal(it, savedOptions) } ?: savedOptions
            _state.update {
                it.copy(
                    page = "chat",
                    thread = null,
                    title = "New chat",
                    entries = emptyList(),
                    activeTurn = null,
                    decisions = emptyList(),
                    historyCursor = null,
                    draft = draft,
                    newTaskOptions = options,
                    threadModel = null,
                    threadReasoningEffort = null,
                    journal = journal,
                    error = null,
                    attention = false,
                )
            }
            if (_state.value.ready) guarded { recoverNew() }
        }
    }

    override fun openTask(id: String) {
        if (_state.value.busy) return
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
                title = "Conversation",
                entries = emptyList(),
                activeTurn = null,
                historyCursor = null,
                newTaskOptions = NewTaskOptions(),
                threadModel = null,
                threadReasoningEffort = null,
                error = null,
                busy = true,
                attention = false,
            )
        }
        var draft = local.get("draft/$id")
        var journal = parse(local.get("journal/$id"))
        if (journal?.str("stage") == "accepted") {
            if (draft == journal.str("text")) {
                local.remove("draft/$id")
                draft = ""
            }
            local.remove("journal/$id")
            journal = null
        }
        if (n != selection) return
        _state.update { it.copy(draft = draft, journal = journal) }
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
                    modelCatalogMessage =
                        if (options.removedUnsupportedChoice)
                            "The server no longer supports one of the selected choices. Unsupported overrides were cleared."
                        else it.modelCatalogMessage,
                    attention = thread.map("status")["activeFlags"].toString().contains("waiting"),
                )
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

    override fun send() {
        val before = _state.value
        val hasTurnStartOverrides =
            before.activeTurn == null &&
                (before.newTaskOptions.model != null ||
                    before.newTaskOptions.reasoningEffort != null)
        if (
            !before.ready ||
                before.busy ||
                before.draft.isBlank() ||
                before.journal != null ||
                before.thread == null && !before.newTaskOptions.hasExecutionDestination() ||
                hasTurnStartOverrides &&
                    before.modelCatalogStatus != ModelCatalogStatus.Ready
        )
            return
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
                    "text" to s(before.draft),
                    "stage" to s(if (plan == null) "taskReady" else "validatingWorkspace"),
                    "cwd" to plan?.workingDirectory?.let(::s),
                    "sourceCwd" to plan?.sourceDirectory?.let(::s),
                    "worktreeRoot" to plan?.worktreeRoot?.let(::s),
                    "executionTarget" to plan?.target?.name?.let(::s),
                    "projectId" to plan?.projectId?.let(::s),
                    "model" to before.newTaskOptions.model?.let(::s),
                    "reasoningEffort" to before.newTaskOptions.reasoningEffort?.let(::s),
                    "threadId" to before.thread?.let(::s),
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
                local.remove("journal/new")
                local.remove("draft/new")
                local.remove("options/new")
                _state.update {
                    it.copy(
                        thread = id,
                        title = journal.str("text").take(80),
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
            if (id != null && stage == "taskReady") {
                record("sending")
                val input =
                    JsonArray(listOf(obj("type" to s("text"), "text" to s(journal.str("text")))))
                if (expectedTurn == null)
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
                        ),
                    )
                else
                    rpc.call(
                        "turn/steer",
                        turnSteerParams(id, input, journal.str("operation"), expectedTurn),
                    )
                record("accepted")
                local.remove("journal/$key")
                local.remove("draft/$key")
                _state.update { it.copy(draft = "", journal = null, error = null) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: WorkspaceSetupFailure) {
            if (journal.str("stage") == "validatingWorkspace" && !e.uncertain) {
                local.remove("journal/$key")
                _state.update { it.copy(journal = null, error = e.userMessage) }
            } else failure(e.userMessage, e.uncertain)
        } catch (e: RpcRejected) {
            if (journal.str("stage") == "sending") {
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
                "sending", "accepted" -> {
                    val known = journal.str("threadId")
                    val found = if (known.isNotEmpty()) known else findPreparedTask(journal)
                    if (!found.isNullOrEmpty()) {
                        local.put(
                            "journal/$found",
                            JsonObject(journal + ("threadId" to s(found))).toString(),
                        )
                        local.put("draft/$found", journal.str("text"))
                        local.remove("journal/new")
                        local.remove("draft/new")
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

    private suspend fun adoptRecoveredTask(journal: JsonObject, id: String): JsonObject {
        val adopted =
            JsonObject(
                journal.filterKeys { it !in setOf("failure", "uncertain") } +
                    ("threadId" to s(id)) +
                    ("stage" to s("taskReady"))
            )
        local.put("journal/$id", adopted.toString())
        local.put("draft/$id", adopted.str("text"))
        local.remove("journal/new")
        local.remove("draft/new")
        local.remove("options/new")
        _state.update {
            it.copy(
                thread = id,
                title = adopted.str("text").take(80),
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
            viewModelScope.launch { guarded { loadTask(before.thread) } }
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
                    refreshList()
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
            "attachments" to
                JsonArray(
                    options.attachments.map { attachment ->
                        obj(
                            "id" to s(attachment.id),
                            "displayName" to s(attachment.displayName),
                            "localUri" to s(attachment.localUri),
                        )
                    }
                ),
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
            attachments =
                saved.list("attachments").mapNotNull { attachment ->
                    val id = attachment.str("id")
                    val uri = attachment.str("localUri")
                    if (id.isBlank() || uri.isBlank()) null
                    else ComposerAttachment(id, attachment.str("displayName"), uri)
                },
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
        )
    }

    override fun onCleared() {
        network.unregisterNetworkCallback(callback)
        rpc.dispose()
    }
}
