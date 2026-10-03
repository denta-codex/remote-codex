package dev.codexops.client

import dev.codexops.core.*
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

data class GitMergeState(
    val visible: Boolean = false,
    val working: Boolean = false,
    val pending: JsonObject? = null,
    val report: JsonObject? = null,
) {
    val blocksTask: Boolean get() = working || pending != null
}

internal fun ScreenState.canInspectMerge(): Boolean =
    page == "chat" && thread != null && !threadCwd.isNullOrBlank() && ready && !busy &&
        activeTurn == null && queueReady && queuedMessages.isEmpty() && decisions.isEmpty() &&
        !attention && journal == null && !merge.blocksTask

internal interface GitMergeOperations {
    suspend fun run(action: String, cwd: String, operation: String = "", snapshot: JsonObject = obj()): JsonObject
}

internal class StockGitMergeOperations(private val rpc: RemoteSession) : GitMergeOperations {
    override suspend fun run(action: String, cwd: String, operation: String, snapshot: JsonObject): JsonObject {
        val response = rpc.callWithTimeout("command/exec", obj(
            "command" to JsonArray(listOf("bash", "-c", script, "remote-codex-merge", action, cwd,
                operation, snapshot.toString()).map(::s)),
            "cwd" to s(cwd),
            "sandboxPolicy" to obj("type" to s("dangerFullAccess")),
            "timeoutMs" to JsonPrimitive(60_000),
            "outputBytesCap" to JsonPrimitive(524_288),
        ), 70_000)
        require(response.str("exitCode") == "0") { "Git command did not complete" }
        val result = wire.parseToJsonElement(response.str("stdout")).jsonObject
        require(result.str("status") in setOf("ready", "blocked", "failed", "succeeded", "needsReview"))
        return result
    }

    companion object {
        val script: String by lazy {
            requireNotNull(StockGitMergeOperations::class.java.getResourceAsStream("/merge-main.sh"))
                .bufferedReader().use { it.readText() }
        }
    }
}

/** Owns only direct Git operations. It never submits a turn or changes the draft. */
internal class GitMergeController(
    private val scope: CoroutineScope,
    private val store: ClientStore,
    private val operations: GitMergeOperations,
    private val screen: () -> ScreenState,
    private val update: (String, GitMergeState) -> Unit,
) {
    private var running: String? = null
    private fun key(thread: String) = "git-merge/$thread"
    private fun problem(message: String) = obj("status" to s("needsReview"), "reason" to s(message))

    suspend fun restore(thread: String) {
        if (running == thread) return
        val saved = store.get(key(thread))
        if (saved.isBlank()) { update(thread, GitMergeState()); return }
        val record = runCatching { wire.parseToJsonElement(saved).jsonObject }.getOrNull()
        // A damaged record must block a new mutation, not silently disappear.
        update(thread, GitMergeState(pending = record ?: obj(), report = problem(
            "A previous merge needs a state check. Open Merge into main to review it.")))
    }

    fun dismiss() {
        val st = screen()
        st.thread?.let { update(it, st.merge.copy(visible = false)) }
    }

    fun inspect() {
        val st = screen()
        val thread = st.thread ?: return
        if (st.merge.pending != null) {
            update(thread, st.merge.copy(visible = true))
            if (st.ready && !st.merge.working) reconcile()
            return
        }
        if (!st.canInspectMerge() || running != null) return
        running = thread
        update(thread, GitMergeState(visible = true, working = true))
        scope.launch {
            try {
                val result = operations.run("inspect", st.threadCwd!!)
                update(thread, GitMergeState(visible = true, report = result))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                update(thread, GitMergeState(visible = true, report = obj(
                    "status" to s("blocked"), "reason" to s("Could not inspect Git state. Reconnect and refresh."))))
            } finally { running = null }
        }
    }

    fun merge() {
        val st = screen()
        val thread = st.thread ?: return
        val approved = st.merge.report?.takeIf { it.str("status") == "ready" } ?: return
        if (!st.canInspectMerge() || running != null) return
        val record = obj("operation" to s(UUID.randomUUID().toString()),
            "cwd" to s(st.threadCwd!!), "snapshot" to approved)
        running = thread
        update(thread, st.merge.copy(working = true, pending = record))
        scope.launch {
            var persisted = false
            try {
                store.put(key(thread), record.toString())
                persisted = true
                val result = operations.run("merge", record.str("cwd"), record.str("operation"), approved)
                complete(thread, record, result)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                update(thread, GitMergeState(visible = true, pending = if (persisted) record else null,
                    report = if (persisted) problem("The merge outcome is uncertain. Check its state; it will not be retried automatically.")
                    else obj("status" to s("blocked"), "reason" to s("Could not save the operation record. No merge was sent."))))
            } finally { running = null }
        }
    }

    fun reconcile() {
        val st = screen()
        val thread = st.thread ?: return
        val record = st.merge.pending ?: return
        if (!st.ready || st.merge.working || running != null) return
        if (record.str("operation").isBlank() || record.str("cwd").isBlank() || record["snapshot"] !is JsonObject) {
            update(thread, st.merge.copy(visible = true, report = problem(
                "The saved record is incomplete. Inspect this task on the host before continuing.")))
            return
        }
        running = thread
        update(thread, st.merge.copy(visible = true, working = true))
        scope.launch {
            try {
                complete(thread, record, operations.run("reconcile", record.str("cwd"),
                    record.str("operation"), record.map("snapshot")), reconciling = true)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                update(thread, GitMergeState(visible = true, pending = record,
                    report = problem("Could not verify the merge. Reconnect and check again; the merge will not be replayed.")))
            } finally { running = null }
        }
    }

    private suspend fun complete(thread: String, record: JsonObject, report: JsonObject, reconciling: Boolean = false) {
        val pending = report.str("status") !in setOf("succeeded", "failed") &&
            (reconciling || report.str("status") != "blocked")
        if (!pending) store.remove(key(thread))
        val displayed = if (pending && report.str("status") != "needsReview")
            problem("The host did not establish the merge outcome. Inspect its recorded state before continuing.") else report
        update(thread, GitMergeState(visible = true, pending = record.takeIf { pending }, report = displayed))
    }
}
