package dev.codexops.client

import dev.codexops.core.*
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TaskSnoozeTest {
    @Test fun customTimeRejectsClockGapAndResolvesOverlapWithAnExplicitOffset() {
        val zone = java.time.ZoneId.of("America/New_York")
        assertNull(snoozeDeadline(java.time.LocalDateTime.parse("2026-03-08T02:30:00"), zone))
        assertEquals(Instant.parse("2026-11-01T05:30:00Z"),
            snoozeDeadline(java.time.LocalDateTime.parse("2026-11-01T01:30:00"), zone))
    }
    private val future = Instant.parse("2030-01-02T17:30:00Z")
    private val id = "11111111-1111-4111-8111-111111111111"
    private class MemoryStore : ClientStore {
        val rows = mutableMapOf<String, String>()
        var fail = false
        override suspend fun get(id: String) = rows[id].orEmpty()
        override suspend fun put(id: String, value: String) { check(!fail); rows[id] = value }
        override suspend fun remove(id: String) { rows.remove(id) }
        override suspend fun token() = ""
        override suspend fun saveToken(value: String) {}
    }
    private inner class Fixture : AutoCloseable, SnoozeOperations {
        override var generation = 1L
        val store = MemoryStore()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var screen = ScreenState(ready = true, tasks = listOf(obj("id" to s(id), "name" to s("Keep context"))))
        var records = emptyList<SnoozedTask>()
        val writes = mutableListOf<Pair<String, Instant?>>()
        var gate: CompletableDeferred<Unit>? = null
        var listGate: CompletableDeferred<Unit>? = null
        var unknown = false
        var changeEpoch = false
        var failList = false
        var callbacks = 0
        fun controller() = SnoozeController(scope, store, this, "guard", { screen }, { screen = it(screen) },
            { callbacks++ }, { _, _ -> "Recovered title" })
        suspend fun prepared() = controller().also { it.inspect() }
        override suspend fun list(epoch: Long): List<SnoozedTask> {
            check(!failList)
            val snapshot = records
            listGate?.await()
            return snapshot
        }
        override suspend fun snooze(id: String, until: Instant?, epoch: Long): SnoozedTask {
            assertTrue(store.rows.getValue("guard").contains(id))
            writes += id to until
            gate?.await()
            if (changeEpoch) generation++
            val record = SnoozedTask(id, until ?: future, "scheduled")
            records = listOf(record)
            if (unknown) error("Lost reply")
            return record
        }
        override suspend fun restore(id: String, epoch: Long) {
            writes += "return:$id" to null
            gate?.await()
            records = emptyList()
            if (unknown) error("Lost return reply")
        }
        override fun close() { scope.cancel() }
    }

    @Test fun defaultHourIsAnOperationChoiceAndConfirmationUsesHostDeadline() = runBlocking {
        Fixture().use { f ->
            val c = f.prepared()
            c.snooze(id)
            assertEquals(listOf(id to null), f.writes)
            assertEquals(future, f.screen.snooze.tasks.getValue(id).deadline)
            assertEquals("Keep context", f.screen.snooze.tasks.getValue(id).title)
            assertEquals(id, f.screen.taskNotice?.changeSnooze)
            assertTrue(f.screen.taskNotice!!.message.contains(snoozeTime(future)))
            assertEquals("[]", f.store.rows["guard"])
            assertTrue(f.screen.pendingTaskActions.isEmpty())
        }
    }

    @Test fun changesTimeAndReturnsWithoutStartingATurn() = runBlocking {
        Fixture().use { f ->
            val c = f.prepared()
            c.snooze(id)
            c.edit(id)
            val later = future.plusSeconds(7200)
            c.snooze(id, later)
            assertEquals(later, f.screen.snooze.tasks.getValue(id).deadline)
            assertNull(f.screen.snooze.editor)
            c.restore(id)
            assertTrue(f.screen.snooze.tasks.isEmpty())
            assertEquals(listOf(id to null, id to later, "return:$id" to null), f.writes)
            assertEquals("Chat returned", f.screen.taskNotice?.message)
        }
    }

    @Test fun reservesBeforeDispatchAndNoDoubleSendOrUnknownReplay() = runBlocking {
        Fixture().use { f ->
            val c = f.prepared()
            f.gate = CompletableDeferred()
            f.unknown = true
            c.snooze(id); c.snooze(id)
            assertEquals(1, f.writes.size)
            assertTrue(id in f.screen.pendingTaskActions)
            f.gate!!.complete(Unit)
            assertTrue(id in f.screen.snooze.uncertain)
            assertTrue(f.store.rows.getValue("guard").contains(id))
            c.snooze(id)
            assertEquals(1, f.writes.size)
            assertNull(f.screen.taskNotice)
            c.inspect() // Read-only recovery finds the scheduled operation.
            assertTrue(f.screen.snooze.uncertain.isEmpty())
            assertEquals("scheduled", f.screen.snooze.tasks.getValue(id).status)
            assertEquals(1, f.writes.size)
        }
    }

    @Test fun processRestartInspectsPersistedGuardBeforeEnablingActions() = runBlocking {
        Fixture().use { f ->
            f.store.rows["guard"] = JsonArray(listOf(s(id))).toString()
            f.screen = f.screen.copy(tasks = emptyList())
            f.failList = true
            val c = f.controller()
            c.inspect()
            assertTrue(id in f.screen.snooze.uncertain)
            c.snooze(id)
            assertTrue(f.writes.isEmpty())
            f.failList = false
            f.records = listOf(SnoozedTask(id, future, "archive_dispatched"))
            c.inspect()
            assertEquals("Recovered title", f.screen.snooze.tasks.getValue(id).title)
            c.snooze(id) // Incomplete schedule only supports explicit Return now.
            assertTrue(f.writes.isEmpty())
            c.restore(id)
            assertEquals(listOf("return:$id" to null), f.writes)
        }
    }

    @Test fun guardWriteFailureAndPastTimeDoNotDispatch() = runBlocking {
        Fixture().use { f ->
            val c = f.prepared()
            c.snooze(id, Instant.EPOCH)
            assertTrue(f.writes.isEmpty())
            f.store.fail = true
            c.snooze(id)
            assertTrue(f.writes.isEmpty())
            assertTrue(f.screen.pendingTaskActions.isEmpty())
            assertNotNull(f.screen.snooze.error)
        }
    }

    @Test fun staleMutationResultRemainsUncertainAndDoesNotOfferChangeTime() = runBlocking {
        Fixture().use { f ->
            val c = f.prepared()
            f.changeEpoch = true
            c.snooze(id)
            assertTrue(id in f.screen.snooze.uncertain)
            assertNull(f.screen.taskNotice)
            assertTrue(f.store.rows.getValue("guard").contains(id))
        }
    }

    @Test fun pendingSnoozesRemainVisibleAndWakeInventoryRefreshesMembership() = runBlocking {
        Fixture().use { f ->
            val c = f.prepared()
            f.records = listOf(SnoozedTask(id, future, "waiting_for_idle"))
            c.inspect()
            assertTrue(f.screen.snooze.tasks.getValue(id).waiting)
            assertEquals(1, f.callbacks)
            f.records = emptyList()
            c.inspect()
            assertEquals(2, f.callbacks)
            assertTrue(f.screen.snooze.tasks.isEmpty())
            assertTrue(f.writes.isEmpty())
        }
    }

    @Test fun inventoryStartedBeforeMutationCannotEraseItsResultOrDeliveryGuard() = runBlocking {
        Fixture().use { f ->
            val c = f.prepared()
            f.listGate = CompletableDeferred()
            val read = f.scope.launch { c.inspect() }
            c.snooze(id)
            f.listGate!!.complete(Unit)
            read.join()
            assertEquals(future, f.screen.snooze.tasks.getValue(id).deadline)

            f.listGate = CompletableDeferred()
            val oldRead = f.scope.launch { c.inspect() }
            f.unknown = true
            c.restore(id)
            f.listGate!!.complete(Unit)
            oldRead.join()
            assertTrue(id in f.screen.snooze.uncertain)
            assertTrue(f.store.rows.getValue("guard").contains(id))
        }
    }

    private inner class RpcFixture : RemoteSession {
        override val events = Channel<JsonObject>(Channel.UNLIMITED)
        override var generation = 1L
        val calls = mutableListOf<JsonObject>()
        var hostName = "grace"
        var badIdentity = false
        var malformed = false
        override suspend fun connect(url: String, token: String) = obj()
        override suspend fun call(method: String, params: JsonObject): JsonObject {
            assertEquals("command/exec", method)
            calls += params
            val argv = params["command"]!!.jsonArray.map { it.jsonPrimitive.content }
            if (StockSnoozeOperations.PROBE_NAME in argv) return obj("exitCode" to JsonPrimitive(0),
                "stdout" to s("/home/agent/.local/bin/codex-tasks\n$hostName\nagent\n{\"snooze_protocol\":2}\n"))
            val action = argv[1]
            val row = obj("task_id" to s(id), "host" to s(if (badIdentity) "wrong" else "grace"), "account" to s("agent"),
                "deadline" to s(future.toString()), "state" to s(if (action == "unsnooze") "absent" else "scheduled"))
            val result = obj("host" to s("grace"), "account" to s("agent"), "snooze_protocol" to JsonPrimitive(2),
                "action" to s(action), "outcome" to s(when (action) { "snooze" -> "snoozed"; "unsnooze" -> "unsnoozed"; else -> "ok" }),
                "snooze" to if (action == "snoozes") null else row,
                "snoozes" to if (action == "snoozes") JsonArray(listOf(row)) else null)
            return obj("exitCode" to JsonPrimitive(0), "stdout" to s(if (malformed) "truncated" else result.toString()))
        }
        override fun respond(id: JsonElement, result: JsonObject, epoch: Long) {}
        override fun close() {}
        override fun dispose() {}
    }

    @Test fun stockAdapterPinsAccountAndUsesOneHourOrExplicitDeadlineArguments() = runBlocking {
        val f = RpcFixture()
        val op = StockSnoozeOperations(f, GraceHost)
        op.list(1)
        op.snooze(id, null, 1)
        var params = f.calls.last()
        var args = params["command"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("--for", "1h"), args.takeLast(2))
        assertEquals(GraceHost.expectedCodexHome, params.map("env").str("CODEX_HOME"))
        assertEquals("dangerFullAccess", params.map("sandboxPolicy").str("type"))
        assertTrue(args.containsAll(listOf("--target", "local", "--json")))
        op.snooze(id, future, 1)
        args = f.calls.last()["command"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("--until", future.toString()), args.takeLast(2))
        op.restore(id, 1)
        assertEquals("unsnooze", f.calls.last()["command"]!!.jsonArray[1].jsonPrimitive.content)
        assertEquals(1, f.calls.count { StockSnoozeOperations.PROBE_NAME in it["command"]!!.jsonArray.map { it.jsonPrimitive.content } })
    }

    @Test fun invalidHostStopsBeforeAnyMutationAndMalformedAckIsUnknown() = runBlocking {
        val f = RpcFixture()
        f.hostName = "other-host"
        val op = StockSnoozeOperations(f, GraceHost)
        try { op.snooze(id, null, 1); fail("Expected incompatible host") } catch (e: SnoozeFailure) { assertFalse(e.unknown) }
        assertEquals(1, f.calls.size)
        f.hostName = "grace"
        op.list(1)
        f.malformed = true
        try { op.snooze(id, null, 1); fail("Expected uncertain response") } catch (e: SnoozeFailure) { assertTrue(e.unknown) }
        f.malformed = false
        f.badIdentity = true
        try { op.list(1); fail("Expected account mismatch") } catch (_: IllegalArgumentException) {}
    }
}
