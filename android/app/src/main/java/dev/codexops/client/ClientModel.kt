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
import kotlin.concurrent.thread
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

const val ENDPOINT = "wss://grace.taila198f.ts.net/codex/rpc"

data class ScreenState(
    val page: String = "home",
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
    val attachments: List<DraftAttachment> = emptyList(),
    val activeTurn: String? = null,
    val decisions: List<Decision> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
    val journal: JsonObject? = null,
    val attention: Boolean = false,
)

class ClientModel
@JvmOverloads
constructor(
    app: Application,
    private val endpoint: String = ENDPOINT,
    private val expectedHome: String = "/home/agent/.codex",
    allowLoopbackTest: Boolean = false,
) : AndroidViewModel(app) {
    private val local = LocalStore(app)
    private val rpc = Rpc(allowLoopbackTest)
    private val timeline = Timeline()
    private val attachmentStore = AttachmentStore(app)
    private val mediaRepository = MediaRepository(app)
    private val _state = MutableStateFlow(ScreenState())
    val state = _state.asStateFlow()
    private var connectionJob: Job? = null
    private var foreground = false
    private var selection = 0
    private var listSelection = 0
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
    }

    fun foreground(value: Boolean) {
        foreground = value
        if (value && !_state.value.ready) connect()
    }

    fun connect() {
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
                        val init = rpc.connect(endpoint, token)
                        require(init.str("codexHome") == expectedHome) {
                            "Unexpected Codex account"
                        }
                        codexHome = init.str("codexHome")
                        _state.update { it.copy(connection = "Connected to Grace", ready = true) }
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
                                        "Grace rejected the connection credential. Scan the setup QR again in Settings."
                                    e is ConnectionFailure && e.httpStatus != null ->
                                        "Grace rejected the WebSocket connection (HTTP ${e.httpStatus})."
                                    e is ConnectionFailure ->
                                        "Cannot reach Grace (${e.transport}). Check Tailscale and reconnect."
                                    e is RpcRejected ->
                                        "Grace rejected connection setup (RPC ${e.code})."
                                    else -> "Cannot connect to Grace (${e.javaClass.simpleName})."
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

    fun saveCredential(value: String) {
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

    fun settings() {
        _state.update { it.copy(page = "settings", error = null) }
    }

    fun home() {
        if (_state.value.busy) return
        selection++
        _state.update { it.copy(page = "home", error = null) }
        viewModelScope.launch { guarded { refreshList() } }
    }

    fun query(value: String) {
        _state.update { it.copy(query = value) }
        val n = ++listSelection
        viewModelScope.launch {
            delay(300)
            if (n == listSelection) guarded { refreshList() }
        }
    }

    fun archived(value: Boolean) {
        _state.update { it.copy(archived = value) }
        viewModelScope.launch { guarded { refreshList() } }
    }

    fun moreTasks() {
        viewModelScope.launch { guarded { refreshList(true) } }
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
                        .distinctBy { t -> t.str("id") },
                listCursor = result.cursor(),
            )
        }
    }

    fun newChat() {
        if (_state.value.busy) return
        viewModelScope.launch {
            selection++
            timeline.clear()
            buffered.clear()
            hydrating = false
            val draft = local.get("draft/new")
            val attachments = restoreAttachments("new")
            val journal = parse(local.get("journal/new"))
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
                    attachments = attachments,
                    journal = journal,
                    error = null,
                    attention = false,
                )
            }
            if (_state.value.ready) guarded { recoverNew() }
        }
    }

    fun openTask(id: String) {
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
                attachments = emptyList(),
                error = null,
                busy = true,
                attention = false,
            )
        }
        var draft = local.get("draft/$id")
        var attachments = restoreAttachments(id)
        var journal = parse(local.get("journal/$id"))
        if (journal?.str("stage") == "accepted") {
            if (draft == journal.str("text")) {
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

    fun older() {
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

    fun draft(value: String) {
        _state.update { it.copy(draft = value) }
        val key = _state.value.thread ?: "new"
        viewModelScope.launch { local.put("draft/$key", value) }
    }

    fun addAttachments(uris: List<Uri>) {
        val before = _state.value
        if (before.busy || before.journal != null || uris.isEmpty()) return
        val n = selection
        val key = before.thread ?: "new"
        viewModelScope.launch {
            var values = before.attachments
            for (uri in uris) {
                try {
                    val added = attachmentStore.import(uri, values.sumOf { it.byteSize })
                    if (n != selection || (_state.value.thread ?: "new") != key) {
                        attachmentStore.delete(added)
                        return@launch
                    }
                    values = values + added
                    persistAttachments(key, values)
                    _state.update { it.copy(attachments = values, error = null) }
                } catch (e: Exception) {
                    _state.update { it.copy(error = e.message ?: "The selected image could not be added.") }
                    break
                }
            }
        }
    }

    fun prepareCamera(): Uri? {
        val st = _state.value
        if (st.busy || st.journal != null) return null
        return runCatching { attachmentStore.prepareCamera() }
            .onFailure {
                _state.update { state -> state.copy(error = "The camera could not be opened.") }
            }
            .getOrNull()
    }

    fun finishCamera(success: Boolean) {
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
                _state.update { it.copy(error = e.message ?: "The camera image could not be added.") }
            }
        }
    }

    fun removeAttachment(id: String) {
        val st = _state.value
        if (st.busy || st.journal != null) return
        val removed = st.attachments.find { it.id == id } ?: return
        val values = st.attachments.filterNot { it.id == id }
        val key = st.thread ?: "new"
        _state.update { it.copy(attachments = values) }
        viewModelScope.launch {
            persistAttachments(key, values)
            attachmentStore.delete(removed)
        }
    }

    suspend fun loadMedia(media: MediaRef): ByteArray =
        mediaRepository.load(media) { rpc.readFile(it) }

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

    fun send() {
        val before = _state.value
        if (
            !before.ready ||
                before.busy ||
                (before.draft.isBlank() && before.attachments.isEmpty()) ||
                before.journal != null
        ) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val originalKey = before.thread ?: "new"
            val operation = UUID.randomUUID().toString()
            var journal =
                obj(
                    "operation" to s(operation),
                    "text" to s(before.draft),
                    "stage" to s("preparing"),
                    "cwd" to s("/home/agent/Documents/RemoteCodex/$operation"),
                    "threadId" to before.thread?.let(::s),
                    "attachments" to JsonArray(before.attachments.map { it.json() }),
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
                    record("creating")
                    val result =
                        rpc.call(
                            "thread/start",
                            obj(
                                "cwd" to s(journal.str("cwd")),
                                "historyMode" to s("paginated"),
                                "ephemeral" to JsonPrimitive(false),
                                "threadSource" to s("agent_created_thread"),
                                "projectId" to JsonNull,
                            ),
                        )
                    id = result.map("thread").str("id")
                    check(id.isNotEmpty())
                    check(result.map("thread").str("projectId").isEmpty()) {
                        "Unexpected project assignment; no message sent"
                    }
                    journal = JsonObject(journal + ("threadId" to s(id)))
                    // Persist the known ID under the original key before moving the record.
                    local.put("journal/new", journal.toString())
                    key = id
                    local.put("journal/$key", journal.toString())
                    local.put("draft/$key", before.draft)
                    persistAttachments(key, before.attachments)
                    local.remove("journal/new")
                    local.remove("draft/new")
                    local.remove("attachments/new")
                    _state.update {
                        it.copy(
                            thread = id,
                            title = before.draft.take(80).ifBlank { "Image message" },
                        )
                    }
                }
                val remotePaths = mutableListOf<String>()
                if (before.attachments.isNotEmpty()) {
                    ImagePolicy.validateCombined(before.attachments.map { it.byteSize })
                    val safeThread = id.replace(Regex("[^A-Za-z0-9._-]"), "-")
                    val remoteDirectory =
                        "$codexHome/attachments/remote-android/$safeThread/$operation"
                    journal = JsonObject(journal + ("remoteDirectory" to s(remoteDirectory)))
                    record("creatingAttachmentDirectory")
                    rpc.createDirectory(remoteDirectory)
                    before.attachments.forEachIndexed { index, attachment ->
                        val file = File(attachment.localPath)
                        val format = ImagePolicy.inspect(file)
                        check(file.length() == attachment.byteSize) {
                            "A draft image changed before it could be sent."
                        }
                        val remotePath =
                            "$remoteDirectory/${safeAttachmentName(index + 1, attachment.displayName, format)}"
                        remotePaths += remotePath
                        journal =
                            JsonObject(
                                journal +
                                    mapOf(
                                        "remotePaths" to JsonArray(remotePaths.map(::s)),
                                        "uploadIndex" to JsonPrimitive(index),
                                    )
                            )
                        record("uploadingAttachment")
                        rpc.writeFile(remotePath, withContext(Dispatchers.IO) { file.readBytes() })
                        journal =
                            JsonObject(journal + ("uploadedCount" to JsonPrimitive(index + 1)))
                        record("attachmentUploaded")
                    }
                }
                record("sending")
                rpc.call(
                    if (before.activeTurn == null) "turn/start" else "turn/steer",
                    obj(
                        "threadId" to s(id),
                        "input" to turnInput(before.draft, remotePaths),
                        "clientUserMessageId" to s(operation),
                        "expectedTurnId" to before.activeTurn?.let(::s),
                    ),
                )
                record("accepted")
                local.remove("journal/$key")
                local.remove("draft/$key")
                local.remove("attachments/$key")
                before.attachments.forEach(attachmentStore::delete)
                _state.update { it.copy(draft = "", attachments = emptyList(), journal = null) }
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
                        obj("cwd" to s(journal.str("cwd")), "limit" to JsonPrimitive(10)),
                    )
                    .list("data")
                    .singleOrNull()
                    ?.str("id")
        if (!found.isNullOrEmpty()) {
            local.put("journal/$found", JsonObject(journal + ("threadId" to s(found))).toString())
            local.put("draft/$found", journal.str("text"))
            persistAttachments(found, _state.value.attachments)
            local.remove("journal/new")
            local.remove("draft/new")
            local.remove("attachments/new")
            loadTask(found)
        }
    }

    fun unlockAfterReview() {
        viewModelScope.launch {
            val key = _state.value.thread ?: "new"
            val record = _state.value.journal ?: return@launch
            local.put("reviewed/${record.str("operation")}", record.toString())
            local.remove("journal/$key")
            _state.update { it.copy(journal = null, error = null) }
        }
    }

    fun stop() {
        viewModelScope.launch {
            guarded {
                val st = _state.value
                val turn = st.activeTurn ?: return@guarded
                rpc.call("turn/interrupt", obj("threadId" to s(st.thread!!), "turnId" to s(turn)))
            }
        }
    }

    fun answer(d: Decision, result: JsonObject) {
        viewModelScope.launch {
            guarded {
                if (requests[d.key] != d || !_state.value.ready) throw ConnectionLost()
                rpc.respond(d.id, result, d.epoch)
                requests.remove(d.key)
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

    override fun onCleared() {
        network.unregisterNetworkCallback(callback)
        // OkHttp may close a live socket while evicting its connection pool.
        // ViewModel cleanup runs on the main thread, where Android forbids that I/O.
        thread(name = "remote-codex-rpc-cleanup") { rpc.dispose() }
    }
}
