package dev.codexops.client

import dev.codexops.core.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TurnChangesTest {
    private fun edit(id: String = "edit", turn: String = "t", status: String = "completed", completed: Boolean = true,
        vararg paths: String = arrayOf("src/main.kt")) = Entry(turn, obj(
        "id" to s(id), "type" to s("fileChange"), "status" to s(status), "_completed" to JsonPrimitive(completed),
        "changes" to JsonArray(paths.map { obj("path" to s(it), "diff" to s("-$id\n+$it"), "kind" to obj("type" to s("update"))) })))
    private fun reply(turn: String = "t") = Entry(turn, obj("id" to s("reply"), "type" to s("agentMessage"), "text" to s("Done")))

    @Test fun requiresCompletedTurnAndSuccessfulRecordedEdit() {
        for (status in listOf("inProgress", "failed", "interrupted", ""))
            assertTrue(completedTurnChanges(listOf(edit()), mapOf("t" to status)).isEmpty())
        for (status in listOf("inProgress", "failed", "declined", ""))
            assertTrue(completedTurnChanges(listOf(edit(status = status)), mapOf("t" to "completed")).isEmpty())
        assertTrue(completedTurnChanges(listOf(edit(completed = false)), mapOf("t" to "completed")).isEmpty())
        assertTrue(completedTurnChanges(listOf(edit(paths = arrayOf(" "))), mapOf("t" to "completed")).isEmpty())
        assertTrue(completedTurnChanges(listOf(reply()), mapOf("t" to "completed")).isEmpty())
    }

    @Test fun countsDistinctPathsAndRetainsOrderedPatchesWithoutDuplicatingItems() {
        val first = edit("first", paths = arrayOf("src/a file.kt", "image.png"))
        val second = edit("second", paths = arrayOf("src/a file.kt"))
        val changes = completedTurnChanges(listOf(first, first, second), mapOf("t" to "completed")).getValue("t")
        assertEquals(listOf("src/a file.kt", "image.png"), changes.files.map { it.path })
        assertEquals(listOf("-first\n+src/a file.kt", "-second\n+src/a file.kt"), changes.files.first().patches.map { it.str("diff") })
    }

    @Test fun placesChangesAfterReplyAndRetainsFailedActivity() {
        val failed = edit("bad", status = "failed")
        val next = reply("next")
        val rows = conversationRows(listOf(edit(), failed, reply(), next), null, true, mapOf("t" to "completed"))
        assertEquals(listOf("tools/t/bad", "t/reply", "changes/t", "next/reply"), rows.map { it.key })
        assertEquals(failed, (rows.first() as ConversationRow.Activity).calls.single().entry)
        assertEquals("changes/t", conversationRows(listOf(edit(), reply()), null, true, mapOf("t" to "completed")).last().key)
    }

    @Test fun editOnlyTurnsStayInChronologicalOrderAndActiveEditsRemainActivity() {
        val rows = conversationRows(listOf(edit(turn = "old"), edit(), reply("next")), null, true,
            mapOf("old" to "completed", "t" to "completed"))
        assertEquals(listOf("changes/old", "changes/t", "next/reply"), rows.map { it.key })
        val active = conversationRows(listOf(edit()), "t", true, mapOf("t" to "inProgress"))
        assertTrue(active.single() is ConversationRow.Activity)
    }

    @Test fun missingTextDiffStillHasAFileAndReloadPreservesIdentity() {
        val change = edit().copy(raw = JsonObject(edit().raw + ("changes" to JsonArray(listOf(
            obj("path" to s("image.png"), "diff" to s(""), "kind" to obj("type" to s("add"))))))))
        val timeline = Timeline()
        timeline.event("turn/started", obj("turn" to obj("id" to s("t"), "status" to s("inProgress"))))
        timeline.event("item/completed", obj("turnId" to s("t"), "item" to change.raw))
        timeline.event("turn/completed", obj("turn" to obj("id" to s("t"), "status" to s("completed"))))
        val live = conversationRows(timeline.values(), timeline.activeTurn, true, timeline.turnStatuses)
        val restored = Timeline()
        val turn = obj("id" to s("t"), "status" to s("completed"), "items" to JsonArray(listOf(change.raw)))
        restored.hydrate(listOf(turn), emptyList())
        restored.snapshot(listOf(turn), prepend = true)
        assertEquals(live, conversationRows(restored.values(), restored.activeTurn, true, restored.turnStatuses))
        assertEquals("image.png", (live.single() as ConversationRow.Changes).files.single().path)
        restored.clear()
        assertTrue(conversationRows(restored.values(), restored.activeTurn, true, restored.turnStatuses).isEmpty())
    }
}
