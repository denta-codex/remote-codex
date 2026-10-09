package dev.codexops.core

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class HistoryPagesTest {
    private val summary = obj("data" to JsonArray(listOf(obj("id" to s("turn"), "status" to s("inProgress")))))
    @Test fun largePreviewPreservesStatusIdentityAndDetailsCursorAcrossLiveProjection() {
        val raw = obj("id" to s("command"), "type" to s("commandExecution"),
            "aggregatedOutput" to s("x".repeat(TOOL_PREVIEW_CHARS + 1)), "status" to s("failed"))
        val preview = boundedToolItem(raw, "continuation")
        val timeline = Timeline()
        timeline.event("item/completed", obj("turnId" to s("turn"), "item" to preview))
        val retained = timeline.values().single()
        assertEquals("command", retained.id)
        assertEquals("failed", retained.raw.str("status"))
        assertEquals("continuation", retained.raw.str("_detailsCursor"))
        assertEquals(TOOL_PREVIEW_CHARS, retained.raw.str("aggregatedOutput").length)
        assertTrue(retained.completed)
    }
    @Test fun hundredMiBTurnIsLoadedAsSingleItemsWithExactContinuation() = runBlocking {
        var downloaded = 0L
        val output = "x".repeat(5 * 1024 * 1024)
        val calls = mutableListOf<String>()
        val call: suspend (String, JsonObject) -> JsonObject = { method, params ->
            calls += method
            when (method) {
                "thread/turns/list" -> { assertEquals("summary", params.str("itemsView")); summary }
                "thread/items/list" -> {
                    assertEquals("1", params.str("limit")); assertEquals("desc", params.str("sortDirection"))
                    val index = params.str("cursor").ifBlank { "0" }.toInt()
                    downloaded += output.length
                    obj("data" to JsonArray(listOf(obj("turnId" to s("turn"), "item" to obj(
                        "id" to s("item-$index"), "type" to s("commandExecution"), "aggregatedOutput" to s(output))))),
                        "nextCursor" to if (index < 19) s("${index + 1}") else null)
                }
                else -> error("Unexpected method $method")
            }
        }
        val seen = mutableListOf<String>()
        val first = HistoryPages.read("thread", itemLimit = 7, call = call,
            normalize = { raw, cursor -> boundedToolItem(raw, cursor) }, progress = { turn, _ -> seen += turn.list("items").single().str("id") })
        assertEquals(7, seen.size); assertTrue(first.partial); assertNotNull(first.nextCursor)
        val second = HistoryPages.read("thread", first.nextCursor, call = call, normalize = { raw, cursor -> boundedToolItem(raw, cursor) })
        assertEquals(13, second.turns.single().list("items").size)
        assertNull(second.nextCursor)
        assertEquals(100L * 1024 * 1024, downloaded)
        assertEquals(1, calls.count { it == "thread/turns/list" })
        for (turn in first.turns + second.turns) for (item in turn.list("items")) {
            assertTrue(item.str("aggregatedOutput").length <= TOOL_PREVIEW_CHARS)
            assertEquals(JsonPrimitive(true), item["_detailsOmitted"])
            assertFalse(item.str("_detailsCursor").isBlank())
        }
    }

    @Test fun cancellationStopsBeforeAnotherReadAndCheckpointSurvivesFailure() = runBlocking {
        var reads = 0
        var saved: String? = null
        val job = launch {
            HistoryPages.read("thread", initial = summary,
                call = { _, _ -> reads++; obj("data" to JsonArray(listOf(obj("turnId" to s("turn"), "item" to obj("id" to s("a"), "type" to s("agentMessage"))))), "nextCursor" to s("next")) },
                checkpoint = { saved = it }, progress = { _, cursor -> saved = cursor; currentCoroutineContext().cancel() })
        }
        job.join()
        assertEquals(1, reads)
        assertNotNull(saved)
        try {
            HistoryPages.read("other-thread", saved, call = { _, _ -> error("Foreign cursor reached server") })
            fail("Accepted foreign cursor")
        } catch (_: IllegalArgumentException) { }
        try {
            HistoryPages.read("thread", saved, call = { _, params -> assertEquals("next", params.str("cursor")); throw RpcMessageTooLarge() })
            fail("Lost oversized failure")
        } catch (_: RpcMessageTooLarge) { }
    }

    @Test fun itemPagesRejectForeignTurnRepeatedCursorAndMissingData() = runBlocking {
        for (bad in listOf(obj(), obj("data" to JsonArray(listOf(obj("turnId" to s("foreign"), "item" to obj("id" to s("a")))))),
            obj("data" to JsonArray(emptyList()), "nextCursor" to s("same")))) {
            var reads = 0
            try {
                HistoryPages.read("thread", initial = summary, call = { _, _ ->
                    reads++; if (reads == 1 && bad.str("nextCursor") == "same") obj("data" to JsonArray(emptyList()), "nextCursor" to s("same")) else bad
                })
                fail("Accepted invalid page")
            } catch (_: IllegalArgumentException) { }
        }
    }
}
