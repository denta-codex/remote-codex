package dev.codexops.client

import dev.codexops.core.*
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

data class SnoozedTask(val id: String, val deadline: Instant?, val status: String, val title: String = "Snoozed chat") {
    val scheduled: Boolean get() = status in setOf("scheduled", "waiting_for_idle")
    val waiting: Boolean get() = status == "waiting_for_idle"
}

data class SnoozeState(
    val tasks: Map<String, SnoozedTask> = emptyMap(),
    val available: Boolean = false,
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val pending: Set<String> = emptySet(),
    val uncertain: Set<String> = emptySet(),
    val editor: String? = null,
    val error: String? = null,
)

internal fun snoozeTime(deadline: Instant): String =
    DateTimeFormatter.ofPattern("EEE, MMM d 'at' h:mm a").withZone(ZoneId.systemDefault()).format(deadline)

internal fun snoozeDeadline(time: LocalDateTime, zone: ZoneId): Instant? =
    time.takeIf { zone.rules.getValidOffsets(it).isNotEmpty() }?.atZone(zone)?.toInstant()

internal class SnoozeFailure(val unknown: Boolean, val userMessage: String) : Exception(userMessage)

internal interface SnoozeOperations {
    val generation: Long
    suspend fun list(epoch: Long): List<SnoozedTask>
    suspend fun snooze(id: String, until: Instant?, epoch: Long): SnoozedTask
    suspend fun restore(id: String, epoch: Long)
}

/** Public CLI over stock command/exec; the owning host owns all schedules. */
internal class StockSnoozeOperations(private val rpc: RemoteSession, private val host: HostIdentity) : SnoozeOperations {
    override val generation: Long get() = rpc.generation
    private data class Helper(val path: String, val account: String, val epoch: Long)
    private var helper: Helper? = null

    private suspend fun helper(epoch: Long): Helper {
        helper?.takeIf { it.epoch == epoch }?.let { return it }
        val response = rpc.callForGeneration("command/exec", obj(
            "command" to JsonArray(listOf("sh", "-c", PROBE, PROBE_NAME).map(::s)),
            "sandboxPolicy" to obj("type" to s("readOnly")),
            "timeoutMs" to JsonPrimitive(10000), "outputBytesCap" to JsonPrimitive(4096)), epoch)
        val lines = response.str("stdout").trimEnd().lines()
        if (response.str("exitCode") != "0" || lines.size != 4 || !lines[0].startsWith('/') ||
            lines[1].lowercase() != host.id || !Regex("[a-zA-Z0-9_.-]+").matches(lines[2]) ||
            runCatching { Json.parseToJsonElement(lines[3]).jsonObject.str("snooze_protocol") }.getOrNull() != "2")
            throw SnoozeFailure(false, "Snooze is unavailable on ${host.displayName}. Reconnect or refresh to check again.")
        return Helper(lines[0], lines[2], epoch).also { helper = it }
    }

    private suspend fun run(action: String, id: String?, until: Instant?, epoch: Long): JsonObject {
        val helper = helper(epoch)
        val command = buildList {
            add(helper.path); add(action)
            id?.let(::add)
            addAll(listOf("--target", "local", "--json"))
            if (action == "snooze") {
                if (until == null) addAll(listOf("--for", "1h"))
                else addAll(listOf("--until", until.toString()))
            }
        }
        val mutation = action != "snoozes"
        val response = rpc.callForGenerationWithTimeout("command/exec", obj(
            "command" to JsonArray(command.map(::s)),
            // The CLI verifies that the socket reports this exact Codex home.
            "env" to obj("CODEX_HOME" to s(host.expectedCodexHome)),
            // Even inventory needs the owning account's existing systemd user bus.
            "sandboxPolicy" to obj("type" to s("dangerFullAccess")),
            "timeoutMs" to JsonPrimitive(120000), "outputBytesCap" to JsonPrimitive(524288)), epoch, 130000)
        val result = runCatching { Json.parseToJsonElement(response.str("stdout")).jsonObject }.getOrNull()
        if (result == null || result.str("snooze_protocol") != "2" || result.str("host") != host.id ||
            result.str("account") != helper.account || result.str("action") != action)
            throw SnoozeFailure(mutation, "Could not confirm the snooze result. Refresh to check its status. No retry was sent.")
        if (response.str("exitCode") != "0" || result.str("outcome") == "unknown" || result.str("error").isNotEmpty())
            // A failed helper can retain a partially installed schedule, even before archive.
            throw SnoozeFailure(mutation, "The snooze operation did not finish. Refresh to check its status before another action.")
        return result
    }

    private fun parse(row: JsonObject): SnoozedTask {
        require(row.str("host") == host.id && row.str("account") == helper?.account)
        val id = row.str("task_id").also { require(it.isNotBlank()) }
        val deadline = row.str("deadline").takeIf { it.isNotBlank() }?.let(Instant::parse)
        val status = row.str("state").also { require(it.isNotBlank()) }
        require(status !in setOf("scheduled", "waiting_for_idle") || deadline != null)
        return SnoozedTask(id, deadline, status)
    }

    override suspend fun list(epoch: Long): List<SnoozedTask> {
        val result = run("snoozes", null, null, epoch)
        require(result.str("outcome") == "ok")
        val entries = result["snoozes"]
        require(entries == null || entries is JsonArray)
        val rows = (entries as? JsonArray)?.map { parse(it.jsonObject) } ?: emptyList()
        require(rows.size <= 1000 && rows.map { it.id }.distinct().size == rows.size)
        return rows
    }

    override suspend fun snooze(id: String, until: Instant?, epoch: Long): SnoozedTask {
        val result = run("snooze", id, until, epoch)
        val task = parse(result.map("snooze"))
        require(task.id == id && task.scheduled && result.str("outcome") == if (task.waiting) "snooze_pending" else "snoozed")
        require(until == null || task.deadline == until)
        return task
    }

    override suspend fun restore(id: String, epoch: Long) {
        val result = run("unsnooze", id, null, epoch)
        require(result.map("snooze").str("task_id") == id && result.map("snooze").str("state") == "absent")
        require(result.str("outcome") in setOf("unsnoozed", "ok"))
    }

    companion object {
        const val PROBE_NAME = "remote-codex-snooze-probe"
        val PROBE = "set -eu; command -v codex-tasks; hostname; id -un; codex-tasks --snooze-protocol"
    }
}

/** Persist a delivery guard before mutations. Reconnects only inspect, never replay. */
internal class SnoozeController(
    private val scope: CoroutineScope,
    private val store: ClientStore,
    private val operations: SnoozeOperations,
    private val key: String,
    private val state: () -> ScreenState,
    private val update: ((ScreenState) -> ScreenState) -> Unit,
    private val changed: () -> Unit,
    private val title: suspend (String, Long) -> String,
) {
    val refreshRevision = MutableStateFlow(0L)
    private val lock = Mutex()
    private val readLock = Mutex()
    private var loadedGuards = false
    private val guards = mutableSetOf<String>()
    private var mutationRevision = 0L

    private suspend fun loadGuards() {
        if (loadedGuards) return
        val saved = store.get(key)
        if (saved.isNotBlank()) guards.addAll(Json.parseToJsonElement(saved).jsonArray.map { it.jsonPrimitive.content })
        loadedGuards = true
        update { it.copy(snooze = it.snooze.copy(uncertain = guards.toSet())) }
    }
    private suspend fun saveGuards() = store.put(key, JsonArray(guards.map(::s)).toString())
    fun refresh() { refreshRevision.value++ }
    fun disconnected() = update { it.copy(snooze = it.snooze.copy(available = false, loading = false)) }
    fun edit(id: String) {
        if (id !in state().snooze.tasks) return
        update { it.copy(snooze = it.snooze.copy(editor = id, error = null)) }
    }
    fun dismissEditor() = update { it.copy(snooze = it.snooze.copy(editor = null)) }

    suspend fun inspect() = readLock.withLock {
        val epoch = operations.generation
        val revision = mutationRevision
        if (!state().ready || state().snooze.pending.isNotEmpty()) return@withLock
        update { it.copy(snooze = it.snooze.copy(loading = true)) }
        try {
            lock.withLock { loadGuards() }
            val records = operations.list(epoch)
            if (operations.generation != epoch || mutationRevision != revision || !state().ready || state().snooze.pending.isNotEmpty()) return@withLock
            val previous = state().snooze.tasks
            val rows = records.associate { record ->
                val known = previous[record.id]?.title ?: state().tasks.firstOrNull { it.str("id") == record.id }?.str("name")
                val name = known ?: try { title(record.id, epoch) } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    "Snoozed chat"
                }
                record.id to record.copy(title = name.ifBlank { "Snoozed chat" })
            }
            if (operations.generation != epoch || mutationRevision != revision || !state().ready || state().snooze.pending.isNotEmpty()) return@withLock
            lock.withLock reconcile@{
                if (state().snooze.pending.isNotEmpty() || mutationRevision != revision || operations.generation != epoch || !state().ready) return@reconcile
                // An authoritative inventory resolves delivery uncertainty. Failed schedules
                // remain visible and offer explicit Return now instead of rescheduling.
                guards.clear(); saveGuards()
                val different = previous.mapValues { it.value.status } != rows.mapValues { it.value.status }
                update { it.copy(snooze = it.snooze.copy(tasks = rows, available = true, loaded = true, uncertain = emptySet(), error = null)) }
                if (different) changed()
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (operations.generation != epoch || mutationRevision != revision || !state().ready) return@withLock
            update { it.copy(snooze = it.snooze.copy(available = false,
                error = (e as? SnoozeFailure)?.userMessage ?: "Could not load snoozed chats. Refresh to check again.")) }
        } finally {
            update { it.copy(snooze = it.snooze.copy(loading = false)) }
        }
    }

    fun snooze(id: String, until: Instant? = null) {
        val before = state()
        if (until != null && !until.isAfter(Instant.now())) {
            update { it.copy(snooze = it.snooze.copy(error = "Choose a future time.")) }; return
        }
        val existing = before.snooze.tasks[id]
        if (existing != null && !existing.scheduled) return
        if (existing == null && (before.archived || before.tasks.none { it.str("id") == id })) return
        mutate(id, until, restore = false)
    }
    fun restore(id: String) {
        if (id !in state().snooze.tasks) return
        mutate(id, null, restore = true)
    }

    private fun mutate(id: String, until: Instant?, restore: Boolean) {
        val before = state()
        if (!before.ready || !before.snooze.available || !before.snooze.loaded ||
            id in before.pendingTaskActions || id in before.uncertainTaskActions || id in before.snooze.uncertain) return
        val epoch = operations.generation
        val name = before.snooze.tasks[id]?.title ?: before.tasks.firstOrNull { it.str("id") == id }?.str("name") ?: "Snoozed chat"
        mutationRevision++
        update { it.copy(pendingTaskActions = it.pendingTaskActions + id,
            snooze = it.snooze.copy(pending = it.snooze.pending + id, error = null)) }
        scope.launch {
            var dispatched = false
            try {
                lock.withLock { loadGuards(); guards.add(id); saveGuards() }
                if (operations.generation != epoch || !state().ready) throw SnoozeFailure(false, "Reconnect before snoozing chats.")
                dispatched = true
                val record = if (restore) { operations.restore(id, epoch); null } else operations.snooze(id, until, epoch).copy(title = name)
                if (operations.generation != epoch || !state().ready) throw ConnectionLost()
                lock.withLock { guards.remove(id); saveGuards() }
                update {
                    val records = if (record == null) it.snooze.tasks - id else it.snooze.tasks + (id to record)
                    it.copy(snooze = it.snooze.copy(tasks = records, editor = null, uncertain = it.snooze.uncertain - id),
                        taskNotice = TaskNotice(UUID.randomUUID().toString(),
                            if (record == null) "Chat returned" else if (record.waiting)
                                "Snoozes after it finishes, until ${snoozeTime(record.deadline!!)}"
                            else "Snoozed until ${snoozeTime(record.deadline!!)}", id,
                            changeSnooze = record?.id))
                }
                changed()
            } catch (e: Exception) {
                val unknown = dispatched && (e !is SnoozeFailure || e.unknown)
                if (!unknown) lock.withLock { guards.remove(id); runCatching { saveGuards() } }
                update { it.copy(snooze = it.snooze.copy(uncertain = if (unknown) it.snooze.uncertain + id else it.snooze.uncertain,
                    error = (e as? SnoozeFailure)?.userMessage ?: if (unknown)
                        "Snooze outcome unknown. Refresh to check its status. No retry was sent." else "Could not save the snooze request.")) }
                if (e is CancellationException) throw e
            } finally {
                update { it.copy(pendingTaskActions = it.pendingTaskActions - id, snooze = it.snooze.copy(pending = it.snooze.pending - id)) }
            }
        }
    }
}
