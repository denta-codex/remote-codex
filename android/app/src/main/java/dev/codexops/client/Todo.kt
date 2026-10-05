package dev.codexops.client

import dev.codexops.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

val todoStatuses = listOf("To Do", "In Progress", "Done")

data class TodoItem(
    val id: Long, val title: String, val status: String, val revision: Long,
    val description: String = "", val notes: List<String> = emptyList(),
)

data class TodoEditor(val original: TodoItem? = null, val title: String = "", val description: String = "") {
    val dirty: Boolean get() = title != (original?.title ?: "") || description != (original?.description ?: "")
}

data class TodoState(
    val items: List<TodoItem> = emptyList(), val status: String = "To Do",
    val loaded: Boolean = false, val busy: Boolean = false,
    val editor: TodoEditor? = null, val confirmDiscard: Boolean = false,
    val error: String? = null, val pending: String? = null, val reviewed: Boolean = false,
)

interface TodoActions {
    fun openTodo()
    fun refreshTodo()
    fun selectTodoStatus(status: String)
    fun newTodo()
    fun openTodoTask(id: Long)
    fun todoTitle(value: String)
    fun todoDescription(value: String)
    fun saveTodo()
    fun moveTodoTask(id: Long, status: String)
    fun reorderTodo(id: Long, target: Long, after: Boolean)
    fun moveTodo(status: String)
    fun closeTodoEditor()
    fun discardTodoEditor()
    fun keepTodoEditor()
    fun acknowledgeTodoOutcome()
}

internal class TodoRejected(val code: String) : Exception(code)

internal interface TodoOperations {
    suspend fun list(): List<TodoItem>
    suspend fun show(id: Long): TodoItem
    suspend fun save(editor: TodoEditor): TodoItem
    suspend fun move(task: TodoItem, status: String): TodoItem
}

internal class StockTodoOperations(private val rpc: RemoteSession) : TodoOperations {
    companion object {
        const val PROGRAM = "/home/agent/.local/bin/todo"
        const val DATABASE = "/home/agent/.local/share/todo/tasks.sqlite3"
        const val DIRECTORY = "/home/agent/workspaces/todo"

        private fun JsonObject.text(key: String): String =
            (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("Invalid Todo text")
        private fun JsonObject.number(key: String): Long =
            (get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.takeIf { it > 0 } ?: error("Invalid Todo number")

        fun task(value: JsonElement, detail: Boolean): TodoItem {
            val row = value.jsonObject
            require(row["archived"] == JsonPrimitive(false))
            val status = row.text("status").also { require(it in todoStatuses) }
            val title = row.text("title").also { require(it.isNotBlank()) }
            val description = if (detail) row.text("description") else ""
            val notes = if (detail) row.getValue("notes").jsonArray.map { it.jsonObject.text("text") } else emptyList()
            return TodoItem(row.number("id"), title, status, row.number("revision"), description, notes)
        }
    }

    private suspend fun call(args: List<String>, mutating: Boolean, kind: String): JsonObject {
        val generation = rpc.generation
        val response = rpc.callWithTimeout("command/exec", obj(
            "command" to JsonArray((listOf(PROGRAM, "--db", DATABASE, "--json") + args).map(::s)),
            "cwd" to s(DIRECTORY),
            "sandboxPolicy" to obj("type" to s(if (mutating) "dangerFullAccess" else "readOnly")),
            "timeoutMs" to JsonPrimitive(15_000), "outputBytesCap" to JsonPrimitive(1_048_576),
        ), 20_000)
        check(rpc.generation == generation)
        val exit = response.str("exitCode").toIntOrNull() ?: error("Missing command result")
        if (exit != 0) {
            // Only the CLI's documented rollback/validation errors establish that no write committed.
            val codes = mapOf(2 to "invalid_input", 3 to "not_found", 4 to "revision_conflict", 5 to "database_error")
            val error = runCatching { wire.parseToJsonElement(response.str("stderr")).jsonObject }.getOrNull()
            if (codes[exit] != null && error?.get("schema_version") == JsonPrimitive(1) &&
                error["ok"] == JsonPrimitive(false) && error.map("error").str("code") == codes[exit])
                throw TodoRejected(codes.getValue(exit))
            error("Todo command outcome unavailable")
        }
        return wire.parseToJsonElement(response.str("stdout")).jsonObject.also {
            require(it["schema_version"] == JsonPrimitive(1) && it["ok"] == JsonPrimitive(true) && it.str("kind") == kind)
        }
    }

    override suspend fun list(): List<TodoItem> =
        call(listOf("list"), false, "task-list").getValue("tasks").jsonArray.map { task(it, false) }.also {
            require(it.map(TodoItem::id).distinct().size == it.size)
        }

    override suspend fun show(id: Long): TodoItem =
        task(call(listOf("show", id.toString()), false, "task").getValue("task"), true).also { require(it.id == id) }

    override suspend fun save(editor: TodoEditor): TodoItem {
        val original = editor.original
        val args = if (original == null) listOf("add", "--description=${editor.description}", "--", editor.title)
        else listOf("edit", original.id.toString(), "--expect-revision", original.revision.toString(),
            "--title=${editor.title}", "--description=${editor.description}")
        return task(call(args, true, "task").getValue("task"), true).also {
            require(it.title == editor.title.trim() && it.description == editor.description)
            require(if (original == null) it.revision == 1L && it.status == "To Do"
                else it.id == original.id && it.revision == original.revision + 1 && it.status == original.status)
        }
    }

    override suspend fun move(task: TodoItem, status: String): TodoItem {
        require(status in todoStatuses)
        return task(call(listOf("move", task.id.toString(), status, "--expect-revision", task.revision.toString()), true, "task").getValue("task"), true).also {
            require(it.id == task.id && it.status == status && it.revision == task.revision + 1)
        }
    }
}

internal class TodoController(
    private val scope: CoroutineScope, private val store: ClientStore, private val operations: TodoOperations,
    private val screen: () -> ScreenState, private val update: (TodoState) -> Unit,
) {
    private val state get() = screen().todo
    private val journal get() = "todo/native/pending/${screen().host.endpoint}/${screen().host.expectedCodexHome}"
    private var journalLoaded = false
    private val orderKey get() = "todo/native/order/${screen().host.endpoint}/${screen().host.expectedCodexHome}"
    private var order: List<Long> = emptyList()
    private fun ordered(items: List<TodoItem>): List<TodoItem> {
        val ranks = order.withIndex().associate { it.value to it.index }
        return items.sortedBy { ranks[it.id] ?: Int.MAX_VALUE }
    }

    fun disconnected() {
        update(state.copy(items = emptyList(), loaded = false, reviewed = false))
    }

    fun refresh() {
        if (state.busy || !screen().ready) return
        update(state.copy(busy = true, error = null, reviewed = false))
        scope.launch {
            try {
                loadJournal()
                order = store.get(orderKey).split(",").mapNotNull(String::toLongOrNull)
                val items = operations.list()
                if (screen().ready) update(state.copy(items = ordered(items), loaded = true, reviewed = state.pending != null))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                update(state.copy(loaded = false, error = "Could not load Todo. Check the connection and that the todo CLI is installed on Grace, then refresh."))
            } finally { update(state.copy(busy = false)) }
        }
    }

    private suspend fun loadJournal() {
        if (!journalLoaded) {
            val pending = store.get(journal).takeIf { it.isNotBlank() }
            update(state.copy(pending = pending))
            journalLoaded = true
        }
    }

    fun select(status: String) { if (status in todoStatuses) update(state.copy(status = status)) }

    fun new() {
        if (canWrite()) update(state.copy(editor = TodoEditor(), error = null))
    }

    fun open(id: Long) {
        if (state.busy || !screen().ready || state.editor != null) return
        update(state.copy(busy = true, error = null))
        scope.launch {
            try {
                val task = operations.show(id)
                if (screen().ready) update(state.copy(editor = TodoEditor(task, task.title, task.description)))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                update(state.copy(error = "Could not open this task. It may have been archived or changed; refresh the board."))
            } finally { update(state.copy(busy = false)) }
        }
    }

    private fun canWrite() = screen().ready && journalLoaded && state.loaded && !state.busy && state.pending == null

    fun title(value: String) { if (canWrite()) update(state.copy(editor = state.editor?.copy(title = value), error = null)) }
    fun description(value: String) { if (canWrite()) update(state.copy(editor = state.editor?.copy(description = value), error = null)) }
    fun close() {
        if (state.busy) return
        if (state.editor?.dirty == true) update(state.copy(confirmDiscard = true)) else discard()
    }
    fun discard() { if (!state.busy) update(state.copy(editor = null, confirmDiscard = false, error = null)) }
    fun keep() { update(state.copy(confirmDiscard = false)) }

    fun save() {
        val editor = state.editor ?: return
        if (!canWrite() || editor.title.isBlank() || editor.title.any(Char::isISOControl)) return
        if (editor.original != null && !editor.dirty) return
        mutate(if (editor.original == null) "Create: ${editor.title}" else "Save task #${editor.original.id}: ${editor.title}") {
            operations.save(editor)
        }
    }

    fun move(status: String) {
        val editor = state.editor ?: return
        val task = editor.original ?: return
        if (!canWrite() || editor.dirty || status !in todoStatuses || status == task.status) return
        mutate("Move task #${task.id} to $status: ${task.title}") { operations.move(task, status) }
    }

    fun moveTask(id: Long, status: String) {
        val task = state.items.firstOrNull { it.id == id } ?: return
        if (!canWrite() || state.editor != null || status !in todoStatuses || status == task.status) return
        mutate("Move task #${task.id} to $status: ${task.title}") { operations.move(task, status) }
    }

    // The CLI has no priority field. Persist this phone's board order separately from task data.
    fun reorder(id: Long, target: Long, after: Boolean) {
        if (!canWrite() || state.editor != null || id == target) return
        val task = state.items.firstOrNull { it.id == id } ?: return
        if (state.items.none { it.id == target && it.status == task.status }) return
        val next = state.items.toMutableList()
        next.removeAll { it.id == id }
        next.add(next.indexOfFirst { it.id == target } + if (after) 1 else 0, task)
        update(state.copy(busy = true, error = null))
        scope.launch {
            try {
                val ids = next.map { it.id }
                store.put(orderKey, ids.joinToString(","))
                order = ids
                if (screen().ready) update(state.copy(items = ordered(state.items)))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                update(state.copy(error = "Could not save the priority order on this phone."))
            } finally { update(state.copy(busy = false)) }
        }
    }

    private fun mutate(description: String, operation: suspend () -> TodoItem) {
        update(state.copy(busy = true, error = null, reviewed = false))
        scope.launch {
            var dispatched = false
            var confirmed = false
            try {
                store.put(journal, description) // A failed journal write must prevent dispatch.
                update(state.copy(pending = description))
                dispatched = true
                val task = operation()
                confirmed = true
                // Preserve confirmed data even if clearing the local journal subsequently fails.
                update(state.copy(editor = null, status = task.status,
                    items = ordered(state.items.filterNot { it.id == task.id } + task)))
                store.remove(journal)
                update(state.copy(pending = null))
                try {
                    val items = operations.list()
                    if (screen().ready) update(state.copy(items = ordered(items), loaded = true))
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    update(state.copy(error = "Saved. Could not refresh the board; refresh when connected."))
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (e is TodoRejected) {
                    val cleared = runCatching { store.remove(journal) }.isSuccess
                    update(state.copy(pending = if (cleared) null else description, error = when (e.code) {
                        "revision_conflict" -> "This task changed on Grace. Your edits are still here. Close the editor and reopen the task to compare before saving again."
                        "invalid_input" -> "The change was rejected. Check the title and description."
                        "not_found" -> "This task no longer exists. Close the editor and refresh."
                        else -> "The database could not save this change. No change was applied; check Grace before trying again."
                    }))
                } else update(state.copy(error = when {
                    confirmed -> "Saved on Grace, but the local save record needs review. Refresh and check the task."
                    dispatched -> "Save outcome unknown. Nothing will be retried. Refresh and check the task before unlocking edits."
                    else -> "Could not record the save on this phone. Nothing was sent."
                }))
            } finally { update(state.copy(busy = false)) }
        }
    }

    fun acknowledge() {
        if (!screen().ready || state.busy || state.pending == null || !state.reviewed) return
        update(state.copy(busy = true))
        scope.launch {
            try {
                store.remove(journal)
                // Discard the old edit base; never offer a stale save after an uncertain outcome.
                update(state.copy(pending = null, reviewed = false, editor = null, error = null))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                update(state.copy(error = "Could not clear the save record. No command was sent to Grace."))
            } finally { update(state.copy(busy = false)) }
        }
    }
}
