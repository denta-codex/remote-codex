package dev.codexops.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TimelineActivityTest {
    private fun reasoning(vararg parts: String) = obj("id" to s("reason"), "type" to s("reasoning"),
        "summary" to JsonArray(parts.map(::s)))
    private fun turn(status: String, vararg items: JsonObject) = obj("id" to s("t"),
        "status" to s(status), "items" to JsonArray(items.toList()))
    private fun summary(index: Int, text: String) = obj("turnId" to s("t"), "itemId" to s("reason"),
        "summaryIndex" to JsonPrimitive(index), "delta" to s(text))
    private fun delta(index: Int, text: String) = obj("method" to s("item/reasoning/summaryTextDelta"),
        "params" to summary(index, text))
    private fun progress(message: String) = obj("turnId" to s("t"), "itemId" to s("tool"), "message" to s(message))
    private fun tool(status: String) = obj("id" to s("tool"), "type" to s("mcpToolCall"),
        "tool" to s("inspect"), "status" to s(status))

    @Test fun summaryOnlyTurnStreamsMultipleSectionsAndKeepsIdentity() {
        val timeline = Timeline()
        timeline.event("turn/started", obj("turn" to turn("inProgress")))
        timeline.event("item/reasoning/summaryPartAdded", summary(1, ""))
        timeline.event("item/reasoning/summaryTextDelta", summary(1, "Looking at "))
        timeline.event("item/reasoning/summaryTextDelta", summary(0, "Checking settings"))
        timeline.event("item/reasoning/summaryTextDelta", summary(1, "the photo"))
        assertEquals("t", timeline.activeTurn)
        assertEquals("t/reason", timeline.values().single().key)
        assertEquals(listOf("Checking settings", "Looking at the photo"), timeline.values().single().summaries)
        // A repeated section notification must not erase its text.
        timeline.event("item/reasoning/summaryPartAdded", summary(1, ""))
        timeline.event("item/started", obj("turnId" to s("t"), "item" to reasoning()))
        assertEquals(listOf("Checking settings", "Looking at the photo"), timeline.values().single().summaries)
    }

    @Test fun completionReplacesSummariesAndRejectsLatePartialUpdates() {
        val timeline = Timeline()
        timeline.event("item/reasoning/summaryTextDelta", summary(0, "Draft"))
        timeline.event("item/completed", obj("turnId" to s("t"), "item" to reasoning("Final", "Second")))
        timeline.event("item/reasoning/summaryTextDelta", summary(0, " stale"))
        timeline.event("item/reasoning/summaryPartAdded", summary(2, ""))
        timeline.event("item/started", obj("turnId" to s("t"), "item" to reasoning()))
        assertEquals(listOf("Final", "Second"), timeline.values().single().summaries)
        assertTrue(timeline.values().single().summaryFinal)
        assertTrue(timeline.values().single().completed)
    }

    @Test fun hydrationMergesEachSectionWithoutDuplicatingSnapshotText() {
        val timeline = Timeline()
        timeline.hydrate(listOf(turn("inProgress", reasoning("Checking", "Reading"))), listOf(
            delta(0, "Checking settings"), delta(1, "Reading"), delta(2, "Next step")))
        assertEquals(listOf("Checking settings", "Reading", "Next step"), timeline.values().single().summaries)
        // Includes suffix-only deltas buffered while the history call was in flight.
        timeline.hydrate(listOf(turn("inProgress", reasoning("Checking settings", "Reading the"))),
            listOf(delta(1, "the photo")))
        assertEquals("Reading the photo", timeline.values().single().summaries[1])
        assertEquals(1, timeline.values().size)
    }

    @Test fun terminalSnapshotsWinOverBufferedPartialSummaries() {
        val timeline = Timeline()
        timeline.hydrate(listOf(turn("completed", reasoning("Authoritative"))),
            listOf(delta(0, "Old partial"), delta(1, "Stale section")))
        assertEquals(listOf("Authoritative"), timeline.values().single().summaries)
        assertTrue(timeline.values().single().summaryFinal)
        timeline.event("item/reasoning/summaryTextDelta", summary(0, " stale"))
        assertEquals(listOf("Authoritative"), timeline.values().single().summaries)
    }

    @Test fun progressBeforeStartSurvivesStartCompletionAndSameThreadReconnect() {
        val timeline = Timeline()
        timeline.event("item/mcpToolCall/progress", progress("Reading the first page"))
        val key = timeline.values().single().key
        timeline.event("item/started", obj("turnId" to s("t"), "item" to tool("inProgress")))
        timeline.event("item/mcpToolCall/progress", progress("Reading the second page"))
        timeline.event("item/completed", obj("turnId" to s("t"), "item" to tool("completed")))
        assertEquals(key, timeline.values().single().key)
        assertEquals("Reading the second page", timeline.values().single().progressMessage)
        val observed = timeline.progressMessages()
        timeline.clear()
        timeline.restoreProgress(observed)
        timeline.snapshot(listOf(turn("completed", tool("completed"))))
        assertEquals("Reading the second page", timeline.values().single().progressMessage)
        assertFalse(timeline.values().single().raw.containsKey("message"))
        timeline.clear()
        timeline.snapshot(listOf(turn("completed", tool("completed"))))
        assertEquals("", timeline.values().single().progressMessage)
    }

    @Test fun bufferedProgressAndCompletionRemainAttachedToTheSameTool() {
        val timeline = Timeline()
        timeline.hydrate(listOf(turn("inProgress", tool("inProgress"))), listOf(
            obj("method" to s("item/mcpToolCall/progress"), "params" to progress("Inspecting page")),
            obj("method" to s("item/completed"), "params" to obj("turnId" to s("t"), "item" to tool("completed")))))
        assertEquals(1, timeline.values().size)
        assertEquals("Inspecting page", timeline.values().single().progressMessage)
        assertEquals("completed", timeline.values().single().raw.str("status"))
    }

    @Test fun malformedSectionsAndUpdatesAfterTurnInterruptionAreIgnored() {
        val timeline = Timeline()
        timeline.event("item/reasoning/summaryTextDelta", summary(-1, "bad"))
        timeline.event("item/reasoning/summaryPartAdded", summary(Int.MAX_VALUE, ""))
        assertTrue(timeline.values().isEmpty())
        timeline.event("turn/completed", obj("turn" to turn("interrupted")))
        timeline.event("item/reasoning/summaryTextDelta", summary(0, "late"))
        assertTrue(timeline.values().isEmpty())
        // Raw reasoning text is outside the summary presentation path.
        timeline.event("item/reasoning/textDelta", summary(0, "private content"))
        assertTrue(timeline.values().isEmpty())
    }
}
