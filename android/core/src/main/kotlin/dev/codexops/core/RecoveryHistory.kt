package dev.codexops.core

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

/** Session-only pagination for explicit recovery. Cursors are stock cursors, never encoded. */
class RecoveryHistory(val thread: String) {
    var itemLimit = 20
        private set
    var turn: JsonObject? = null
        private set
    var hasMore = true
        private set
    var readingItems = false
        private set
    private var turnCursor: String? = null
    private var itemCursor: String? = null
    private val turnCursors = mutableSetOf<String>()
    private val itemCursors = mutableSetOf<String>()

    fun smallerPages() { itemLimit = 1 }

    fun resuming() { readingItems = false }

    fun skipTurn(): String {
        val id = requireNotNull(turn).str("id")
        finishTurn()
        return id
    }

    private fun finishTurn() {
        turn = null
        itemCursor = null
        itemCursors.clear()
        hasMore = turnCursor != null
    }

    /** At most one summary request and one item request. Failed item reads keep their position. */
    suspend fun next(call: suspend (String, JsonObject) -> JsonObject): List<JsonObject> {
        check(hasMore)
        readingItems = false
        if (turn == null) {
            val page = call("thread/turns/list", obj("threadId" to s(thread),
                "limit" to JsonPrimitive(1), "itemsView" to s("summary"),
                "sortDirection" to s("desc"), "cursor" to turnCursor?.let(::s)))
            currentCoroutineContext().ensureActive()
            require(page["data"] is JsonArray)
            val turns = page.list("data")
            require(turns.size <= 1 && turns.all { it.str("id").isNotBlank() })
            val next = page.cursor()
            require(next == null || next != turnCursor && next !in turnCursors) { "Repeated turn cursor" }
            next?.let(turnCursors::add)
            turnCursor = next
            // Summary payloads are not live item snapshots, even if a server includes items.
            turn = turns.singleOrNull()?.let { JsonObject(it - "items") }
            if (turn == null) {
                hasMore = next != null
                return emptyList()
            }
        }
        val selected = requireNotNull(turn)
        readingItems = true
        val page = call("thread/items/list", obj("threadId" to s(thread),
            "turnId" to s(selected.str("id")), "limit" to JsonPrimitive(itemLimit),
            "sortDirection" to s("desc"), "cursor" to itemCursor?.let(::s)))
        currentCoroutineContext().ensureActive()
        require(page["data"] is JsonArray)
        val rows = page.list("data")
        require(rows.size <= itemLimit && rows.all {
            it.str("turnId") == selected.str("id") && it.map("item").str("id").isNotBlank()
        })
        val next = page.cursor()
        require(next == null || next != itemCursor && next !in itemCursors) { "Repeated item cursor" }
        next?.let(itemCursors::add)
        itemCursor = next
        val result = JsonObject(selected + ("items" to JsonArray(rows.reversed().map { it.map("item") })))
        if (next == null) finishTurn()
        readingItems = false
        return listOf(result)
    }
}
