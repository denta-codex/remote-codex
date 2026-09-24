package dev.codexops.client

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.codexops.core.*
import java.util.UUID
import kotlin.concurrent.thread
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
    private val timeline = Timeline()
    private val _state = MutableStateFlow(ScreenState(host = host))
    val state = _state.asStateFlow()
    private var connectionJob: Job? = null
    private var foreground = false
    private var selection = 0
    private var listSelection = 0
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
                it.copy(
                    title =
                        thread.str("name").ifBlank {
                            thread.str("preview").take(80).ifBlank { "Conversation" }
                        },
                    historyCursor = history.cursor(),
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
        _state.update { it.copy(newTaskOptions = options) }
        if (_state.value.thread == null)
            viewModelScope.launch { local.put("options/new", newTaskOptionsJson(options).toString()) }
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
        val selectedProject =
            before.newTaskOptions.projectId?.let { id -> before.projects.firstOrNull { it.id == id } }
        if (
            !before.ready ||
                before.busy ||
                before.draft.isBlank() ||
                before.journal != null ||
                (before.thread == null &&
                    before.newTaskOptions.projectId != null &&
                    selectedProject?.primaryRoot == null)
        ) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val originalKey = before.thread ?: "new"
            val operation = UUID.randomUUID().toString()
            val projectId = if (before.thread == null) before.newTaskOptions.projectId else null
            val cwd =
                if (before.thread == null && projectId != null) selectedProject!!.primaryRoot!!
                else "/home/agent/Documents/RemoteCodex/$operation"
            var journal =
                obj(
                    "operation" to s(operation),
                    "text" to s(before.draft),
                    "stage" to s("preparing"),
                    "cwd" to s(cwd),
                    "projectId" to (projectId?.let(::s) ?: JsonNull),
                    "threadId" to before.thread?.let(::s),
                )
            var key = originalKey
            suspend fun record(stage: String) {
                journal = JsonObject(journal + ("stage" to s(stage)))
                local.put("journal/$key", journal.toString())
                _state.update { it.copy(journal = journal) }
            }
            try {
                record("preparing")
                var id = before.thread
                if (id == null) {
                    if (projectId == null) {
                        val mkdir =
                            rpc.call(
                                "command/exec",
                                obj(
                                    "command" to
                                        JsonArray(
                                            listOf(
                                                s("mkdir"),
                                                s("-p"),
                                                s("-m"),
                                                s("0700"),
                                                s("--"),
                                                s(journal.str("cwd")),
                                            )
                                        ),
                                    "cwd" to s("/home/agent/Documents/RemoteCodex"),
                                    "sandboxPolicy" to
                                        obj(
                                            "type" to s("workspaceWrite"),
                                            "writableRoots" to
                                                JsonArray(
                                                    listOf(s("/home/agent/Documents/RemoteCodex"))
                                                ),
                                            "networkAccess" to JsonPrimitive(false),
                                        ),
                                    "timeoutMs" to JsonPrimitive(10000),
                                    "outputBytesCap" to JsonPrimitive(2048),
                                ),
                            )
                        check(mkdir.str("exitCode") == "0") {
                            "Could not prepare the conversation directory"
                        }
                    }
                    record("creating")
                    val result =
                        rpc.call(
                            "thread/start",
                            obj(
                                "cwd" to s(journal.str("cwd")),
                                "historyMode" to s("paginated"),
                                "ephemeral" to JsonPrimitive(false),
                                "threadSource" to s("agent_created_thread"),
                                "projectId" to (projectId?.let(::s) ?: JsonNull),
                            ),
                        )
                    id = result.map("thread").str("id")
                    check(id.isNotEmpty())
                    val assignedProject =
                        (result.map("thread")["projectId"] as? JsonPrimitive)?.contentOrNull
                    check(assignedProject == projectId) {
                        "Unexpected project assignment; no message sent"
                    }
                    journal = JsonObject(journal + ("threadId" to s(id)))
                    // Persist the known ID under the original key before moving the record.
                    local.put("journal/new", journal.toString())
                    key = id
                    local.put("journal/$key", journal.toString())
                    local.put("draft/$key", before.draft)
                    local.remove("journal/new")
                    local.remove("draft/new")
                    local.remove("options/new")
                    _state.update { it.copy(thread = id, title = before.draft.take(80)) }
                }
                record("sending")
                val input = JsonArray(listOf(obj("type" to s("text"), "text" to s(before.draft))))
                rpc.call(
                    if (before.activeTurn == null) "turn/start" else "turn/steer",
                    obj(
                        "threadId" to s(id),
                        "input" to input,
                        "clientUserMessageId" to s(operation),
                        "expectedTurnId" to before.activeTurn?.let(::s),
                    ),
                )
                record("accepted")
                local.remove("journal/$key")
                local.remove("draft/$key")
                _state.update { it.copy(draft = "", journal = null) }
                // Refresh only after acceptance; a failed history read must never become a resend.
                if (timeline.values().isEmpty()) _state.update { it.copy(error = null) }
            } catch (e: Exception) {
                if (e is RpcRejected) {
                    local.remove("journal/$key")
                    _state.update {
                        it.copy(
                            journal = null,
                            error = "Server rejected the operation: ${e.message}",
                        )
                    }
                } else {
                    _state.update {
                        it.copy(
                            error = "Delivery uncertain. Inspect the task before sending again.",
                            journal = journal,
                        )
                    }
                }
            } finally {
                _state.update { it.copy(busy = false) }
                if (foreground && !_state.value.ready) connect()
            }
        }
    }

    private suspend fun recoverNew() {
        val journal = _state.value.journal ?: return
        val known = journal.str("threadId")
        val found =
            if (known.isNotEmpty()) known
            else
                rpc.call(
                        "thread/list",
                        obj(
                            "cwd" to s(journal.str("cwd")),
                            "projectId" to (journal["projectId"] ?: JsonNull),
                            "limit" to JsonPrimitive(10),
                        ),
                    )
                    .list("data")
                    .singleOrNull()
                    ?.str("id")
        if (!found.isNullOrEmpty()) {
            local.put("journal/$found", JsonObject(journal + ("threadId" to s(found))).toString())
            local.put("draft/$found", journal.str("text"))
            local.remove("journal/new")
            local.remove("draft/new")
            local.remove("options/new")
            loadTask(found)
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
                it.copy(ready = false, connection = "Disconnected", decisions = emptyList())
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
        return saved.copy(
            projectId = projectId,
            workingDirectory = journal.str("cwd").takeIf { projectId != null },
            executionTarget =
                if (projectId == null) ExecutionTarget.Projectless
                else ExecutionTarget.CurrentWorkspace,
        )
    }

    override fun onCleared() {
        network.unregisterNetworkCallback(callback)
        // OkHttp may close a live socket while evicting its connection pool.
        // ViewModel cleanup runs on the main thread, where Android forbids that I/O.
        thread(name = "remote-codex-rpc-cleanup") { rpc.dispose() }
    }
}
