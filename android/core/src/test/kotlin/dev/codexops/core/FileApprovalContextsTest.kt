package dev.codexops.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class FileApprovalContextsTest {
    private fun item(diff: String = "-old\n+new", type: String = "fileChange") = obj(
        "id" to s("patch"), "type" to s(type),
        "changes" to JsonArray(listOf(obj("path" to s("src/main.kt"), "diff" to s(diff), "kind" to obj("type" to s("update"))))),
    )
    private fun decision(thread: String = "thread", turn: String = "turn", epoch: Long = 1) = Decision(
        JsonPrimitive(7), "item/fileChange/requestApproval",
        obj("threadId" to s(thread), "turnId" to s(turn), "itemId" to s("patch")), epoch,
    )
    private fun event(item: JsonObject = item(), thread: String = "thread") =
        obj("threadId" to s(thread), "turnId" to s("turn"), "item" to item)
    private fun turns(item: JsonObject = item()) = listOf(obj(
        "id" to s("turn"), "status" to s("inProgress"), "items" to JsonArray(listOf(item)),
    ))

    @Test fun requiresExactConnectionThreadTurnItemAndFileChangeType() {
        val contexts = FileApprovalContexts()
        contexts.select("thread", 1)
        contexts.event("item/started", event(item(type = "agentMessage")), 1)
        contexts.event("item/started", event(thread = "other"), 1)
        contexts.event("item/started", event(), 0)
        assertEquals("", contexts.text(decision()))
        contexts.event("item/started", event(), 1)
        assertEquals("src/main.kt\n-old\n+new", contexts.text(decision()))
        assertEquals("", contexts.text(decision(thread = "other")))
        assertEquals("", contexts.text(decision(turn = "other")))
        assertEquals("", contexts.text(decision().copy(params = obj(
            "threadId" to s("thread"), "turnId" to s("turn"), "itemId" to s("other"),
        ))))
        assertEquals("", contexts.text(decision(epoch = 2)))
        contexts.select("thread", 2)
        assertEquals("", contexts.text(decision(epoch = 2)))
    }

    @Test fun newlyCreatedTaskCanReceiveLiveContextWithoutLoadingHistory() {
        val contexts = FileApprovalContexts()
        contexts.select(null, 1)
        contexts.ensureSelected("thread", 1)
        contexts.event("item/started", event(), 1)
        contexts.ensureSelected("thread", 1)
        assertTrue(contexts.text(decision()).isNotBlank())
        contexts.ensureSelected("other", 1)
        assertEquals("", contexts.text(decision()))
    }

    @Test fun livePatchUpdateWinsOverResumeAndPersistedSnapshots() {
        val contexts = FileApprovalContexts()
        contexts.select("thread", 1)
        contexts.event("item/fileChange/patchUpdated", obj(
            "threadId" to s("thread"), "turnId" to s("turn"), "itemId" to s("patch"),
            "changes" to item("-new\n+latest")["changes"],
        ), 1)
        contexts.resumed(obj("thread" to obj("id" to s("thread")),
            "initialTurnsPage" to obj("data" to JsonArray(turns()))), 1)
        contexts.snapshot("thread", turns(), 1)
        assertEquals("src/main.kt\n-new\n+latest", contexts.text(decision()))
        contexts.event("item/fileChange/patchUpdated", obj(
            "threadId" to s("thread"), "turnId" to s("turn"), "itemId" to s("patch"),
            "changes" to JsonArray(emptyList()),
        ), 1)
        contexts.snapshot("thread", turns(), 1)
        assertEquals("", contexts.text(decision()))
    }

    @Test fun boundedResumeRecoversContextButCannotCreateOrOwnARequest() {
        val contexts = FileApprovalContexts()
        contexts.select("thread", 1)
        val response = obj("thread" to obj("id" to s("other")), "initialTurnsPage" to obj("data" to JsonArray(turns())))
        contexts.resumed(response, 1)
        assertEquals("", contexts.text(decision()))
        contexts.resumed(JsonObject(response + ("thread" to obj("id" to s("thread")))), 1)
        assertTrue(contexts.text(decision()).isNotBlank())
        assertEquals("", contexts.text(decision(epoch = 0)))
        val params = FileApprovalContexts.resumeParams("thread")
        assertEquals(JsonPrimitive(true), params["excludeTurns"])
        assertEquals(obj("limit" to JsonPrimitive(1), "itemsView" to s("full"), "sortDirection" to s("desc")), params["initialTurnsPage"])
    }
}
