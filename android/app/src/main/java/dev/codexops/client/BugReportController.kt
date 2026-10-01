package dev.codexops.client

import android.app.Application
import android.net.Uri
import dev.codexops.core.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

internal class BugReportController(
    private val app: Application,
    private val scope: CoroutineScope,
    private val local: ClientStore,
    private val rpc: RemoteSession,
    private val host: HostIdentity,
    private val current: () -> ScreenState,
    private val accepted: (String) -> Unit,
) {
    private val _state = MutableStateFlow(BugReportState())
    val state = _state.asStateFlow()
    val actions = RecentAppActions()
    private val store = BugReportStore(File(app.filesDir, "bug-reports"))
    private val importer = AttachmentStore(app)
    private val lock = Mutex()
    private var storageBlocked = false

    init {
        scope.launch {
            try {
                val enabled = local.get("bug-report/shake") != "false"
                val last = local.get("bug-report/last-task").ifBlank { null }
                val draft = withContext(Dispatchers.IO) { store.load() }
                _state.value = BugReportState(loaded = true, shakeEnabled = enabled, draft = draft, lastTask = last)
                if (draft?.journal?.str("stage") == "accepted") finish(draft)
            } catch (_: Exception) {
                storageBlocked = true
                _state.update { it.copy(loaded = true, error = "The saved report could not be loaded. Its files have been retained.") }
            }
        }
    }

    fun shakeEnabled(value: Boolean) {
        _state.update { it.copy(shakeEnabled = value) }
        scope.launch { lock.withLock {
            try { local.put("bug-report/shake", value.toString()) }
            catch (_: Exception) { _state.update { it.copy(error = "The shake preference could not be saved.") } }
        } }
    }

    fun open(capture: ScreenshotCapture? = null) {
        val before = _state.value
        if (!before.loaded || before.capturing || before.visible) return
        if (before.draft != null || storageBlocked) {
            _state.update { it.copy(visible = true) }
            return
        }
        if (before.busy) return
        val instant = System.currentTimeMillis()
        val screen = current()
        val snapshot = bugReportContext(screen, reportDeviceInfo(app), actions.snapshot(instant))
        val id = UUID.randomUUID().toString()
        _state.update { it.copy(capturing = true, error = null) }
        scope.launch {
            lock.withLock {
                try {
                    // Start copying the original window before credential/storage reads can delay it.
                    val screenshot = async(start = CoroutineStart.UNDISPATCHED) {
                        collectBugReportDiagnostics(mapOf("screenshot" to ScreenshotCollector(screen.page == "settings", capture)), instant)
                            .getValue("screenshot")
                    }
                    val credential = runCatching { local.token() }.getOrDefault("")
                    // Redact a known pairing credential if it was pasted into a conversation or error.
                    val safeContext = redactReportJson(snapshot, credential) as JsonObject
                    var draft = BugReportDraft(id, instant, safeContext,
                        diagnostics = obj("capture" to obj("status" to s("interrupted"), "message" to s("Diagnostic capture did not finish; the frozen context is retained."))))
                    val files = mutableListOf<DraftAttachment>()
                    withContext(Dispatchers.IO) {
                        files += store.attachment(id, "context.json", safeContext.toString().toByteArray(), "application/json")
                        val readable = "Remote Codex bug report $id\nCaptured at epoch milliseconds $instant\n\n" +
                            Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), safeContext)
                        files += store.attachment(id, "context.txt", readable.toByteArray())
                    }
                    draft = draft.copy(attachments = files.toList())
                    persist(draft)
                    val results = collectBugReportDiagnostics(linkedMapOf(
                        "logcat" to AppLogCollector(credential),
                        "processExit" to ExitInfoCollector(app, credential),
                    ), instant) + ("screenshot" to screenshot.await())
                    val statuses = mutableMapOf<String, JsonElement>()
                    withContext(Dispatchers.IO) {
                        results.forEach { (name, result) ->
                            val (at, diagnostic) = result
                            var message = diagnostic.message
                            var status = diagnostic.status
                            for (artifact in diagnostic.artifacts) {
                                try {
                                    AttachmentPolicy.validateCombined(files.map { it.kind to it.byteSize } +
                                        ((if (artifact.mime.startsWith("image/")) AttachmentKind.IMAGE else AttachmentKind.FILE) to artifact.bytes.size.toLong()))
                                    files += store.attachment(id, artifact.name, artifact.bytes, artifact.mime)
                                } catch (_: Exception) { status = "partial"; message += " An artifact could not be saved within attachment limits." }
                            }
                            statuses[name] = obj("capturedAt" to JsonPrimitive(at), "status" to s(status), "message" to s(message))
                        }
                    }
                    draft = draft.copy(attachments = files.sortedBy { if (it.id == "screenshot.png") 0 else 1 }, diagnostics = JsonObject(statuses))
                    persist(draft)
                    actions.add("bugReportCaptured", id)
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) {
                    _state.update { it.copy(error = "Some report evidence could not be saved. Available evidence has been retained.") }
                } finally { _state.update { it.copy(capturing = false, visible = true) } }
            }
        }
    }

    fun close() { _state.update { it.copy(visible = false) } }

    fun describe(text: String) {
        if (_state.value.busy || _state.value.draft?.journal?.isNotEmpty() == true) return
        val draft = _state.value.draft ?: return
        _state.update { it.copy(draft = draft.copy(description = text), error = null) }
        scope.launch { lock.withLock {
            try { _state.value.draft?.let { saveOnly(it) } }
            catch (_: Exception) { _state.update { it.copy(error = "The report description could not be saved. Keep this screen open and try again.") } }
        } }
    }

    fun removeAttachment(id: String) = edit { draft ->
        val file = draft.attachments.firstOrNull { it.id == id } ?: return@edit draft
        val next = draft.copy(attachments = draft.attachments.filterNot { it.id == id },
            diagnostics = JsonObject(draft.diagnostics + ("removed-$id" to obj("status" to s("removed"), "message" to s("${file.displayName} was removed by the reporter.")))))
        saveOnly(next) // Persist removal before deleting bytes.
        withContext(Dispatchers.IO) { store.removeAttachment(draft.id, file) }
        next
    }

    fun addFiles(uris: List<Uri>) = edit { original ->
        var draft = original
        for (uri in uris) {
            val file = importer.importDocument(uri, draft.attachments.sumOf { it.byteSize })
            try {
                val stored = withContext(Dispatchers.IO) {
                    val target = File(store.directory(draft.id), "extra-${file.id}.${if (file.kind == AttachmentKind.IMAGE) ImagePolicy.inspect(File(file.localPath)).extension else "file"}")
                    File(file.localPath).copyTo(target)
                    file.copy(localPath = target.path)
                }
                draft = draft.copy(attachments = draft.attachments + stored)
                persist(draft)
            } finally { importer.delete(file) }
        }
        draft
    }

    private fun edit(change: suspend (BugReportDraft) -> BugReportDraft) {
        if (_state.value.busy || _state.value.capturing || _state.value.draft?.journal?.isNotEmpty() == true) return
        _state.update { it.copy(busy = true, error = null) }
        scope.launch { lock.withLock {
            try { _state.value.draft?.let { persist(change(it)) } }
            catch (e: CancellationException) { throw e }
            catch (e: IllegalArgumentException) { _state.update { it.copy(error = e.message ?: "The attachment could not be added.") } }
            catch (_: Exception) { _state.update { it.copy(error = "The report could not be updated. Existing evidence is retained.") } }
            finally { _state.update { it.copy(busy = false) } }
        } }
    }

    fun discard() {
        if (_state.value.busy || _state.value.capturing || _state.value.draft?.journal?.isNotEmpty() == true) return
        scope.launch { lock.withLock {
            try {
                _state.value.draft?.let { withContext(Dispatchers.IO) { store.discard(it) } }
                _state.update { it.copy(draft = null, visible = false, error = null) }
            } catch (_: Exception) { _state.update { it.copy(error = "The saved report could not be discarded.") } }
        } }
    }

    fun submit() {
        val before = _state.value
        if (before.busy || before.capturing || before.draft?.description.isNullOrBlank()) return
        if (!current().ready) {
            _state.update { it.copy(busy = true, error = null) }
            scope.launch { lock.withLock {
                try { _state.value.draft?.let { saveOnly(it) }; close() }
                catch (_: Exception) { _state.update { it.copy(error = "The report could not be saved. Keep it open and try again.") } }
                finally { _state.update { it.copy(busy = false) } }
            } }
            return
        }
        if (current().busy) {
            _state.update { it.copy(error = "Wait for the current app operation to finish, then start the fix task.") }
            return
        }
        _state.update { it.copy(busy = true, error = null) }
        scope.launch { lock.withLock {
            try {
                val draft = requireNotNull(_state.value.draft)
                withContext(Dispatchers.IO) { store.validate(draft) }
                val result = BugReportSubmission(rpc, host).run(draft, ::persist)
                if (result.journal.str("stage") == "accepted") finish(result)
            } catch (e: CancellationException) {
                if (e is TimeoutCancellationException) _state.update { it.copy(error = "The connection timed out. Check and continue to inspect the saved operation; it will not be replayed.") }
                else throw e
            } catch (e: IllegalArgumentException) {
                _state.update { it.copy(error = e.message ?: "The reporting destination or artifacts could not be validated.") }
            } catch (e: IllegalStateException) {
                _state.update { it.copy(error = e.message ?: "The saved report operation needs review.") }
            } catch (_: Exception) {
                _state.update { it.copy(error = "The report operation could not be completed. Check the connection, then check and continue. Uncertain mutations are never replayed.") }
            } finally { _state.update { it.copy(busy = false) } }
        } }
    }

    private suspend fun saveOnly(draft: BugReportDraft) = withContext(Dispatchers.IO) { store.save(draft) }
    private suspend fun persist(draft: BugReportDraft) {
        saveOnly(draft)
        _state.update { it.copy(draft = draft) }
    }

    private suspend fun finish(draft: BugReportDraft) {
        val id = draft.journal.str("threadId")
        check(id.isNotBlank())
        local.put("bug-report/last-task", id)
        withContext(Dispatchers.IO) { store.discard(draft) }
        _state.update { it.copy(draft = null, visible = false, lastTask = id, error = null) }
        accepted(id)
    }
}
