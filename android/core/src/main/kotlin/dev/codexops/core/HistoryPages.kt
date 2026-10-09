package dev.codexops.core

import java.util.Base64
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

data class HistoryPage(val turns: List<JsonObject>, val nextCursor: String?, val partial: Boolean)
internal class HistoryPageFull : Exception()

/** A local opaque continuation wrapping the server's unchanged turn and item cursors. */
object HistoryPages {
    private val metadata = setOf("id", "status", "error", "startedAt", "completedAt", "durationMs")

    suspend fun read(
        thread: String,
        cursor: String? = null,
        turnLimit: Int = 1,
        itemLimit: Int = 20,
        initial: JsonObject? = null,
        call: suspend (String, JsonObject) -> JsonObject,
        normalize: suspend (JsonObject, String?) -> JsonObject = { item, _ -> item },
        progress: suspend (JsonObject, String?) -> Unit = { _, _ -> },
        checkpoint: (String?) -> Unit = {},
    ): HistoryPage {
        require(turnLimit in 1..10 && itemLimit in 1..200)
        var position = cursor?.let { decode(it, thread) }
        val page = if (position == null || position.list("turns").isEmpty()) {
            initial ?: call("thread/turns/list", obj("threadId" to s(thread),
                "limit" to JsonPrimitive(turnLimit), "itemsView" to s("summary"),
                "sortDirection" to s("desc"), "cursor" to position?.str("turnCursor")?.takeIf(String::isNotEmpty)?.let(::s)))
        } else null
        if (page != null) {
            require(page["data"] is JsonArray) { "Turn summary page omitted data" }
            val turns = page.list("data")
            require(turns.size <= turnLimit && turns.all { it.str("id").isNotBlank() }) { "Invalid turn summary page" }
            val next = page.cursor()
            require(next == null || next != position?.str("turnCursor")) { "History cursor did not advance" }
            position = obj("thread" to s(thread), "turns" to JsonArray(turns.map { JsonObject(it.filterKeys { key -> key in metadata }) }),
                "turnCursor" to next?.let(::s))
        }
        var state = requireNotNull(position)
        checkpoint(continuation(state))
        val results = linkedMapOf<String, JsonObject>()
        var count = 0
        var scans = 0
        while (state.list("turns").isNotEmpty() && count < itemLimit && scans++ < 200) {
            currentCoroutineContext().ensureActive()
            val turns = state.list("turns")
            val turn = turns.first()
            val previous = state
            val before = encode(state)
            val oldCursor = state.str("itemCursor").takeIf(String::isNotEmpty)
            val items = call("thread/items/list", obj("threadId" to s(thread), "turnId" to s(turn.str("id")),
                "limit" to JsonPrimitive(1), "sortDirection" to s("desc"), "cursor" to oldCursor?.let(::s)))
            val entries = items.list("data")
            require(items["data"] is JsonArray) { "Item history page omitted data" }
            require(entries.size <= 1 && entries.all { it.str("turnId") == turn.str("id") && it.map("item").str("id").isNotBlank() }) { "Invalid item history page" }
            val next = items.cursor()
            require(next == null || next != oldCursor) { "Item history cursor did not advance" }
            state = JsonObject(state - "itemCursor" + ("turns" to JsonArray(if (next == null) turns.drop(1) else turns)) +
                if (next == null) emptyMap() else mapOf("itemCursor" to s(next)))
            val continuation = continuation(state)
            val normalized = try { entries.map { normalize(it.map("item"), before) } }
                catch (_: HistoryPageFull) { state = previous; break }
            val delta = JsonObject(turn + ("items" to JsonArray(normalized)))
            val existing = results[turn.str("id")]?.list("items").orEmpty()
            results[turn.str("id")] = JsonObject(turn + ("items" to JsonArray(normalized + existing)))
            progress(delta, continuation)
            count += entries.size
        }
        return HistoryPage(results.values.toList(), continuation(state), state.list("turns").isNotEmpty())
    }

    private fun continuation(state: JsonObject): String? =
        if (state.list("turns").isEmpty() && state.str("turnCursor").isBlank()) null else encode(state)

    private fun encode(state: JsonObject) = Base64.getUrlEncoder().withoutPadding().encodeToString(state.toString().toByteArray())
    private fun decode(cursor: String, thread: String): JsonObject {
        require(cursor.length <= 65536) { "Invalid history cursor" }
        val state = wire.parseToJsonElement(Base64.getUrlDecoder().decode(cursor).toString(Charsets.UTF_8)).jsonObject
        require(state.str("thread") == thread) { "History cursor belongs to another task" }
        return state
    }
}
