package dev.codexops.core

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RecoveryHistoryTest {
    private fun summary(next: String? = null) = obj("data" to JsonArray(listOf(
        obj("id" to s("turn"), "status" to s("inProgress"), "items" to JsonArray(listOf(obj("id" to s("not-a-snapshot"))))))),
        "nextCursor" to next?.let(::s))
    private fun items(vararg ids: String, next: String? = null) = obj("data" to JsonArray(ids.map {
        obj("turnId" to s("turn"), "item" to obj("id" to s(it), "type" to s("agentMessage"), "text" to s(it)))
    }), "nextCursor" to next?.let(::s))

    @Test fun batchesAndCommitsOnlyOneVisiblePageAtATime() = runBlocking {
        val history = RecoveryHistory("thread")
        val calls = mutableListOf<Pair<String, JsonObject>>()
        val page = history.next { method, params ->
            calls.add(method to params)
            if (method == "thread/turns/list") summary() else items("new", "old", next = "opaque/items")
        }
        assertEquals(2, calls.size)
        assertEquals("summary", calls.first().second.str("itemsView"))
        assertEquals("20", calls.last().second.str("limit"))
        assertEquals(listOf("old", "new"), page.single().list("items").map { it.str("id") })
        assertTrue(history.hasMore)
        history.next { method, params ->
            assertEquals("thread/items/list", method)
            assertEquals("opaque/items", params.str("cursor"))
            items("earlier")
        }
        assertFalse(history.hasMore)
    }

    @Test fun rejectedBatchKeepsCursorForExplicitSmallerRetryAndSkip() = runBlocking {
        val history = RecoveryHistory("thread")
        history.next { method, _ -> if (method == "thread/turns/list") summary("older-turn") else items("latest", next = "failed-page") }
        try {
            history.next { _, _ -> throw RpcMessageTooLarge() }
            fail("Expected size rejection")
        } catch (_: RpcMessageTooLarge) { }
        assertTrue(history.readingItems)
        history.smallerPages()
        try {
            history.next { method, params ->
                assertEquals("thread/items/list", method)
                assertEquals("failed-page", params.str("cursor"))
                assertEquals("1", params.str("limit"))
                throw RpcMessageTooLarge()
            }
            fail("Expected single item rejection")
        } catch (_: RpcMessageTooLarge) { }
        assertEquals("turn", history.skipTurn())
        assertTrue(history.hasMore)
        history.next { method, params ->
            assertEquals("thread/turns/list", method)
            assertEquals("older-turn", params.str("cursor"))
            obj("data" to JsonArray(emptyList()))
        }
        assertFalse(history.hasMore)
        assertEquals(1, history.itemLimit)
    }

    @Test fun detectsNonAdjacentCursorCyclesWithoutAdvancing() = runBlocking {
        val history = RecoveryHistory("thread")
        history.next { method, _ -> if (method == "thread/turns/list") summary() else items("a", next = "A") }
        history.next { _, _ -> items("b", next = "B") }
        try {
            history.next { _, _ -> items("c", next = "A") }
            fail("Expected cursor cycle rejection")
        } catch (_: IllegalArgumentException) { }
        history.next { _, params -> assertEquals("B", params.str("cursor")); items("d") }
        assertFalse(history.hasMore)
    }

    @Test fun rejectsItemsFromAnotherTurn() = runBlocking {
        val history = RecoveryHistory("thread")
        try {
            history.next { method, _ -> if (method == "thread/turns/list") summary() else
                obj("data" to JsonArray(listOf(obj("turnId" to s("other"), "item" to obj("id" to s("wrong")))))) }
            fail("Expected wrong-turn rejection")
        } catch (_: IllegalArgumentException) { }
        assertTrue(history.hasMore)
    }

    @Test fun cancellationDoesNotAdvanceAnItemPage() = runBlocking {
        val history = RecoveryHistory("thread")
        val started = CompletableDeferred<Unit>()
        val job = launch {
            history.next { method, _ ->
                if (method == "thread/turns/list") summary() else {
                    started.complete(Unit)
                    awaitCancellation()
                }
            }
        }
        started.await()
        job.cancelAndJoin()
        history.next { method, params ->
            assertEquals("thread/items/list", method)
            assertFalse(params.containsKey("cursor"))
            items("retry")
        }
        assertFalse(history.hasMore)
    }

    @Test fun recoverySnapshotsPreserveNewerLiveContentAndMergeOverlap() {
        val timeline = Timeline()
        val turn = obj("id" to s("turn"), "status" to s("inProgress"), "items" to JsonArray(listOf(
            obj("id" to s("reply"), "type" to s("agentMessage"), "text" to s("Hello")))))
        timeline.snapshot(listOf(turn), prepend = true)
        timeline.hydrate(emptyList(), listOf(obj("method" to s("item/agentMessage/delta"), "params" to
            obj("threadId" to s("thread"), "turnId" to s("turn"), "itemId" to s("reply"), "delta" to s("Hello world")))))
        timeline.event("turn/completed", obj("turn" to obj("id" to s("turn"), "status" to s("completed"))))
        timeline.snapshot(listOf(turn), prepend = true)
        assertEquals("Hello world", timeline.values().single().text)
        assertEquals("completed", timeline.turnStatuses["turn"])
    }
}
