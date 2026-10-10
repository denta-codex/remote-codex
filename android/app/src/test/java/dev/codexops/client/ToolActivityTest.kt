package dev.codexops.client

import dev.codexops.core.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ToolActivityTest {
    private fun entry(id: String, kind: String = "commandExecution", turn: String = "t",
        completed: Boolean = true, vararg fields: Pair<String, JsonElement>) =
        Entry(turn, obj("id" to s(id), "type" to s(kind), "_completed" to JsonPrimitive(completed), *fields))

    @Test fun groupsConsecutiveCallsAcrossHiddenReasoningButNotMessagesOrTurns() {
        val calls = (1..8).map { entry("c$it") }
        val entries = listOf(entry("u", "userMessage")) + calls.take(3) + entry("r", "reasoning") + calls.drop(3) +
            listOf(entry("a", "agentMessage"), entry("w", "webSearch"), entry("p", "plan"),
                entry("v", "imageView"), entry("next", turn = "next"))
        val rows = conversationRows(entries, null, true)
        val groups = rows.filterIsInstance<ConversationRow.Activity>()
        assertEquals(listOf(8, 1, 1), groups.map { it.calls.size })
        assertEquals("8 commands", groups.first().summary)
        assertEquals(calls.map { it.key }, groups.first().calls.map { it.entry.key })
        val adjacentTurns = conversationRows(listOf(entry("c"), entry("c", turn = "next")), null, true)
        assertEquals(2, adjacentTurns.size)
    }

    @Test fun groupIdentitySurvivesStreamingAndPrependingEarlierHistory() {
        val first = entry("one", completed = false)
        val before = conversationRows(listOf(first), "t", true).single()
        val after = conversationRows(listOf(entry("older", turn = "old"), first, entry("two")), "t", true).last()
        assertEquals(before.key, after.key)
    }

    @Test fun recoveryPageBoundaryPreservesExistingGroupAndItsContents() {
        val existing = listOf(entry("one"), entry("two"))
        val before = conversationRows(existing, null, true).single() as ConversationRow.Activity
        val after = conversationRows(listOf(entry("older")) + existing, null, true,
            groupStarts = setOf(existing.first().key)).filterIsInstance<ConversationRow.Activity>()
        assertEquals(2, after.size)
        assertEquals(before.key, after.last().key)
        assertEquals(before.entries, after.last().entries)
    }

    @Test fun usesServerCommandActionsAndFormatsWebActionsWithoutBookkeeping() {
        val read = entry("read", fields = arrayOf("commandActions" to JsonArray(listOf(
            obj("type" to s("read"), "name" to s("Timeline.kt"), "path" to s("/src/Timeline.kt"))))))
        assertEquals(ToolCategory.Read, toolCall(read).category)
        assertEquals("File read · Timeline.kt", toolCall(read).title)
        val edit = entry("edit", "fileChange", completed = false, fields = arrayOf("changes" to JsonArray(listOf(
            obj("path" to s("/src/Timeline.kt")), obj("path" to s("/src/ToolActivity.kt"))))))
        assertEquals("Editing files · Timeline.kt, ToolActivity.kt", toolCall(edit, active = true).activity)
        val search = entry("web", "webSearch", fields = arrayOf("action" to obj(
            "type" to s("search"), "queries" to JsonArray(listOf(s("median wages"), s("government data")))),
            "results" to JsonNull))
        val presentation = toolCall(search)
        assertEquals("median wages\ngovernment data", presentation.details.single().value)
        assertFalse(presentation.technicalDetails.contains("_completed"))
        assertFalse(presentation.details.any { it.label == "Results" })
        val other = toolCall(entry("other", "webSearch", fields = arrayOf("action" to obj("type" to s("other")))))
        assertEquals("Web activity", other.title)
        assertTrue(other.details.isEmpty())
    }

    @Test fun activeGroupUsesLatestRunningActivityAndRetainsFailures() {
        val failed = entry("bad", fields = arrayOf("status" to s("failed")))
        val running = entry("search", "webSearch", completed = false,
            fields = arrayOf("action" to obj("type" to s("search"))))
        val group = conversationRows(listOf(failed, running), "t", true).single() as ConversationRow.Activity
        assertEquals("Searching the web · 1 failed", group.summary)
        assertTrue(group.working)
        assertTrue(group.representsActiveTurn)
        val gap = conversationRows(listOf(entry("done")), "t", true).single() as ConversationRow.Activity
        assertEquals("Waiting for the next update", gap.summary)
        assertTrue(gap.working)
        val afterMessage = conversationRows(listOf(entry("done"), entry("a", "agentMessage")), "t", true)
            .filterIsInstance<ConversationRow.Activity>().single()
        assertFalse(afterMessage.representsActiveTurn)
        assertFalse(afterMessage.working)
    }

    @Test fun terminalTurnAndConnectionStatesStopShimmerWithoutInventingSuccess() {
        val pending = entry("running", completed = false, fields = arrayOf("status" to s("inProgress")))
        fun group(active: String?, connected: Boolean, status: String) =
            conversationRows(listOf(pending), active, connected, mapOf("t" to status)).single() as ConversationRow.Activity
        assertEquals(ToolState.Interrupted, group(null, true, "interrupted").calls.single().state)
        assertEquals("Interrupted · 1 command", group(null, true, "interrupted").summary)
        assertEquals(ToolState.Failed, group(null, true, "failed").calls.single().state)
        assertEquals(ToolState.Unknown, group(null, true, "completed").calls.single().state)
        assertEquals("Connection lost", group("t", false, "inProgress").summary)
        listOf(group(null, true, "interrupted"), group(null, true, "failed"), group(null, true, "completed"),
            group("t", false, "inProgress")).forEach { assertFalse(it.working) }
        // Rehydrated history may set _completed while the server still reports an unfinished item.
        val restored = pending.copy(raw = JsonObject(pending.raw + ("_completed" to JsonPrimitive(true))))
        assertEquals(ToolState.Running, toolCall(restored, active = true).state)
    }

    @Test fun approvalPausesActivityAndCompletedSummariesCountMixedCalls() {
        val approval = entry("waiting", completed = false, fields = arrayOf("status" to s("awaitingApproval")))
        val group = conversationRows(listOf(approval), "t", true).single() as ConversationRow.Activity
        assertEquals("Waiting for you", group.summary)
        assertFalse(group.working)
        val summary = conversationRows(listOf(entry("c"), entry("s", "webSearch",
            fields = arrayOf("action" to obj("type" to s("search"))))), null, true)
            .single() as ConversationRow.Activity
        assertEquals("1 command · 1 web search", summary.summary)
        assertFalse(summary.working)
    }

    @Test fun reportsToolErrorsAndKeepsUnknownPayloadsBehindTechnicalDetails() {
        val dynamic = entry("d", "dynamicToolCall", fields = arrayOf("success" to JsonPrimitive(false)))
        assertEquals(ToolState.Failed, toolCall(dynamic).state)
        val mcp = entry("m", "mcpToolCall", fields = arrayOf("result" to obj("isError" to JsonPrimitive(true))))
        assertEquals(ToolState.Failed, toolCall(mcp).state)
        val unknown = entry("x", "futureTool", fields = arrayOf("opaque" to obj("value" to s("retained"))))
        val call = toolCall(unknown)
        assertEquals("Activity", call.title)
        assertTrue(call.details.isEmpty())
        assertTrue(call.technicalDetails.contains("retained"))
        assertFalse(call.technicalDetails.contains("_completed"))
    }

    @Test fun summaryOnlyActivityHasLivePreviewAndInspectableTerminalHistory() {
        val reason = entry("r", "reasoning", completed = false, fields = arrayOf(
            "summary" to JsonArray(listOf(s("Checking recovery settings"), s(""), s("Inspecting the photo")))))
        val active = conversationRows(listOf(reason), "t", true).single() as ConversationRow.Activity
        assertEquals("Progress update", active.summary)
        assertEquals("Inspecting the photo", active.previewSummary)
        assertTrue(active.representsActiveTurn)
        assertTrue(active.calls.isEmpty())
        assertEquals(listOf(reason), active.entries)
        val done = conversationRows(listOf(reason), null, true, mapOf("t" to "completed"))
            .single() as ConversationRow.Activity
        assertEquals("2 progress summaries", done.summary)
        assertNull(done.previewSummary)
        assertFalse(done.working)
        assertEquals(active.key, done.key)
        val interrupted = conversationRows(listOf(reason), null, true, mapOf("t" to "interrupted"))
            .single() as ConversationRow.Activity
        assertEquals("Interrupted · 2 progress summaries", interrupted.summary)
        assertNull(interrupted.previewSummary)
    }

    @Test fun livePreviewIncludesTheRunningTargetAndToolProgress() {
        val reason = entry("r", "reasoning", completed = false,
            fields = arrayOf("summary" to JsonArray(listOf(s("Checking instructions")))))
        val search = entry("s", "webSearch", completed = false,
            fields = arrayOf("action" to obj("type" to s("search"), "queries" to JsonArray(listOf(s("Dell recovery"))))))
            .copy(progressMessage = "Checking the official guide")
        val group = conversationRows(listOf(reason, search), "t", true).single() as ConversationRow.Activity
        assertEquals("Searching the web · Dell recovery", group.summary)
        assertEquals("Checking instructions", group.previewSummary)
        assertEquals("Checking the official guide", group.progressMessage)
        assertEquals("tools/t/r", group.key)
        val waiting = conversationRows(listOf(reason, search), "t", true, waitingForUser = true)
            .single() as ConversationRow.Activity
        assertEquals("Waiting for you", waiting.summary)
        assertNull(waiting.previewSummary)
        assertNull(waiting.progressMessage)
        val disconnected = conversationRows(listOf(reason, search), "t", false).single() as ConversationRow.Activity
        assertEquals("Connection lost", disconnected.summary)
        assertNull(disconnected.previewSummary)
    }

    @Test fun reasoningSectionsPreserveMessageBoundariesAndGroupExpansionIdentity() {
        val first = entry("r", "reasoning", completed = false)
        val before = conversationRows(listOf(first), "t", true).single()
        val streaming = first.copy(raw = JsonObject(first.raw + ("summary" to JsonArray(listOf(s("Reviewing"))))))
        val after = conversationRows(listOf(streaming, entry("tool", completed = false)), "t", true).single()
        assertEquals(before.key, after.key)
        val rows = conversationRows(listOf(streaming, entry("a", "agentMessage"), entry("r2", "reasoning")), "t", true)
        assertEquals(3, rows.size)
        assertTrue(rows[1] is ConversationRow.Message)
        assertEquals(listOf(streaming), (rows[0] as ConversationRow.Activity).entries)
    }

    @Test fun emptyReasoningHasNoTerminalHistoryOrInventedDescription() {
        val empty = entry("r", "reasoning", completed = false)
        val active = conversationRows(listOf(empty), "t", true).single() as ConversationRow.Activity
        assertEquals("Waiting for the next update", active.summary)
        assertNull(active.previewSummary)
        assertTrue(conversationRows(listOf(empty), null, true, mapOf("t" to "completed")).isEmpty())
        assertTrue(conversationRows(listOf(empty), null, true, mapOf("t" to "interrupted")).isEmpty())
    }
}
