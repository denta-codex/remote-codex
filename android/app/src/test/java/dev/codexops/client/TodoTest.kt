package dev.codexops.client

import dev.codexops.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TodoTest {
    private class MemoryStore : ClientStore {
        val data = mutableMapOf<String, String>()
        var failPut = false
        var failRemove = false
        override suspend fun get(id: String) = data[id].orEmpty()
        override suspend fun put(id: String, value: String) { check(!failPut); data[id] = value }
        override suspend fun remove(id: String) { check(!failRemove); data.remove(id) }
        override suspend fun token() = ""
        override suspend fun saveToken(value: String) {}
    }

    private class Fixture : TodoOperations, AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store = MemoryStore()
        var screen = ScreenState(ready = true, page = "todo")
        val tasks = mutableListOf(TodoItem(1, "Existing", "To Do", 1, "Original text"))
        var writes = 0
        var loseReply = false
        var failList = false
        var rejected: String? = null
        var gate: CompletableDeferred<Unit>? = null
        fun controller() = TodoController(scope, store, this, { screen }, { screen = screen.copy(todo = it) })
        override suspend fun list(): List<TodoItem> { check(!failList); return tasks.toList() }
        override suspend fun show(id: Long) = tasks.single { it.id == id }
        override suspend fun save(editor: TodoEditor): TodoItem {
            assertTrue(store.data.isNotEmpty())
            writes++
            gate?.await()
            rejected?.let { throw TodoRejected(it) }
            val before = editor.original
            val task = TodoItem(before?.id ?: 2, editor.title.trim(), before?.status ?: "To Do",
                (before?.revision ?: 0) + 1, editor.description)
            tasks.removeAll { it.id == task.id }; tasks += task
            check(!loseReply)
            return task
        }
        override suspend fun move(task: TodoItem, status: String): TodoItem {
            assertTrue(store.data.isNotEmpty()); writes++
            return task.copy(status = status, revision = task.revision + 1).also { next ->
                tasks.removeAll { it.id == task.id }; tasks += next
            }
        }
        override fun close() { scope.cancel() }
    }

    @Test fun createOnceJournalsBeforeDispatchAndRefreshes() = Fixture().use { f ->
        val c = f.controller(); c.refresh(); c.new(); c.title("New task"); c.description("- [ ] item")
        f.gate = CompletableDeferred(); c.save(); c.save()
        assertEquals(1, f.writes)
        assertTrue(f.screen.todo.busy)
        f.gate!!.complete(Unit)
        assertNull(f.screen.todo.editor)
        assertEquals(2, f.screen.todo.items.size)
        assertTrue(f.store.data.isEmpty())
        assertNull(f.screen.todo.pending)
    }

    @Test fun failedJournalNeverDispatchesAndKeepsDraft() = Fixture().use { f ->
        val c = f.controller(); c.refresh(); c.new(); c.title("Keep me")
        f.store.failPut = true; c.save()
        assertEquals(0, f.writes)
        assertEquals("Keep me", f.screen.todo.editor?.title)
        assertNull(f.screen.todo.pending)
    }

    @Test fun uncertainCreateSurvivesControllerRecreationAndNeverReplays() = Fixture().use { f ->
        var c = f.controller(); c.refresh(); c.new(); c.title("Saved but reply lost")
        f.loseReply = true; c.save()
        assertEquals(1, f.writes)
        assertNotNull(f.screen.todo.pending)
        c.acknowledge() // No successful read since the uncertain write.
        assertFalse(f.store.data.isEmpty())
        f.screen = ScreenState(ready = true, page = "todo")
        c = f.controller(); c.refresh()
        assertEquals(2, f.screen.todo.items.size)
        assertNotNull(f.screen.todo.pending)
        c.new(); c.save()
        assertEquals(1, f.writes)
        c.acknowledge()
        assertTrue(f.store.data.isEmpty())
        assertNull(f.screen.todo.editor)
        assertNull(f.screen.todo.pending)
        assertEquals(1, f.writes)
    }

    @Test fun rejectionKeepsDraftAndRequiresFreshRevisionForChangedTask() = Fixture().use { f ->
        val c = f.controller(); c.refresh(); c.open(1); c.title("My edit")
        f.rejected = "revision_conflict"; c.save()
        assertEquals("My edit", f.screen.todo.editor?.title)
        assertEquals(1L, f.screen.todo.editor?.original?.revision)
        assertTrue(f.store.data.isEmpty())
        assertNull(f.screen.todo.pending)
        assertTrue(f.screen.todo.error!!.contains("changed"))
        c.close(); assertTrue(f.screen.todo.confirmDiscard)
        c.keep(); assertEquals("My edit", f.screen.todo.editor?.title)
        c.close(); c.discard(); c.open(1)
        assertEquals("Existing", f.screen.todo.editor?.title)
    }

    @Test fun disconnectDisablesWritesButPreservesAnOpenDraft() = Fixture().use { f ->
        val c = f.controller(); c.refresh(); c.open(1); c.description("Unsaved")
        f.screen = f.screen.copy(ready = false); c.disconnected(); c.save(); c.new(); c.refresh()
        assertEquals(0, f.writes)
        assertTrue(f.screen.todo.items.isEmpty())
        assertFalse(f.screen.todo.loaded)
        assertEquals("Unsaved", f.screen.todo.editor?.description)
        f.screen = f.screen.copy(ready = true); c.refresh()
        assertEquals("Unsaved", f.screen.todo.editor?.description)
        c.save(); assertEquals(1, f.writes)
    }

    @Test fun moveDoesNotDiscardDirtyTextAndSavedRefreshFailureIsNotUnknown() = Fixture().use { f ->
        val c = f.controller(); c.refresh(); c.open(1); c.title("Dirty"); c.move("Done")
        assertEquals(0, f.writes)
        c.discard(); c.open(1); f.failList = true; c.move("Done")
        assertEquals(1, f.writes)
        assertEquals("Done", f.screen.todo.status)
        assertNull(f.screen.todo.pending)
        assertTrue(f.screen.todo.error!!.startsWith("Saved."))
    }

    @Test fun failureToClearJournalKeepsWritesLockedUntilExplicitReview() = Fixture().use { f ->
        val c = f.controller(); c.refresh(); c.new(); c.title("Confirmed")
        f.store.failRemove = true; c.save()
        assertNotNull(f.screen.todo.pending)
        assertNull(f.screen.todo.editor)
        assertTrue(f.screen.todo.error!!.startsWith("Saved on Grace"))
        c.new(); c.save(); assertEquals(1, f.writes)
        f.store.failRemove = false; c.refresh(); c.acknowledge()
        assertNull(f.screen.todo.pending)
        assertEquals(1, f.writes)
    }

    private class RpcFixture : RemoteSession {
        override val events = Channel<JsonObject>()
        override var generation = 1L
        var response = obj()
        var last = obj()
        override suspend fun call(method: String, params: JsonObject): JsonObject {
            assertEquals("command/exec", method); last = params; return response
        }
        override suspend fun connect(url: String, token: String) = obj()
        override fun respond(id: JsonElement, result: JsonObject, epoch: Long) {}
        override fun close() {}
        override fun dispose() {}
    }
    private fun row(id: Long = 1, title: String = "Task", revision: Long = 1, description: String = "") = obj(
        "id" to JsonPrimitive(id), "title" to s(title), "status" to s("To Do"), "revision" to JsonPrimitive(revision),
        "archived" to JsonPrimitive(false), "description" to s(description), "notes" to JsonArray(emptyList()))
    private fun result(kind: String, key: String, value: JsonElement) = obj("exitCode" to JsonPrimitive(0), "stdout" to s(
        obj("schema_version" to JsonPrimitive(1), "ok" to JsonPrimitive(true), "kind" to s(kind), key to value).toString()))

    @Test fun transportUsesArgvAndRevisionWithoutShellOrPreview() = runBlocking {
        val rpc = RpcFixture(); val ops = StockTodoOperations(rpc)
        rpc.response = result("task-list", "tasks", JsonArray(listOf(row())))
        assertEquals(1, ops.list().size)
        assertEquals("readOnly", rpc.last.map("sandboxPolicy").str("type"))
        val title = "- literal \$(touch /tmp/not-executed)"
        val description = "- [ ] literal\n'\"; echo unsafe"
        rpc.response = result("task", "task", row(title = title, description = description))
        ops.save(TodoEditor(title = title, description = description))
        val args = rpc.last["command"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf(StockTodoOperations.PROGRAM, "--db", StockTodoOperations.DATABASE, "--json", "add", "--description=$description", "--", title), args)
        assertEquals("dangerFullAccess", rpc.last.map("sandboxPolicy").str("type"))
        rpc.response = result("task", "task", row(title = title, revision = 2, description = description))
        ops.save(TodoEditor(TodoItem(1, "Task", "To Do", 1), title, description))
        assertTrue(rpc.last["command"]!!.jsonArray.map { it.jsonPrimitive.content }.containsAll(listOf("--expect-revision", "1")))
    }

    @Test fun malformedSuccessAndOutputErrorsAreUncertainNotRejected() = runBlocking {
        val rpc = RpcFixture(); val ops = StockTodoOperations(rpc)
        rpc.response = result("task", "task", row(id = 9))
        assertTrue(runCatching { ops.show(1) }.isFailure)
        rpc.response = obj("exitCode" to JsonPrimitive(6), "stderr" to s("output delivery failed"))
        val error = runCatching { ops.save(TodoEditor(title = "Task")) }.exceptionOrNull()
        assertNotNull(error); assertFalse(error is TodoRejected)
        rpc.response = obj("exitCode" to JsonPrimitive(4), "stderr" to s(obj(
            "schema_version" to JsonPrimitive(1), "ok" to JsonPrimitive(false), "error" to obj("code" to s("revision_conflict"))).toString()))
        assertTrue(runCatching { ops.save(TodoEditor(title = "Task")) }.exceptionOrNull() is TodoRejected)
        rpc.response = result("task-list", "tasks", JsonArray(listOf(row(), row())))
        assertTrue(runCatching { ops.list() }.isFailure)
    }
}
