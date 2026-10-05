package dev.codexops.client

import dev.codexops.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ProjectAdditionTest {
    private class MemoryStore : ClientStore {
        val values = mutableMapOf<String, String>()
        var failPut = false
        var readGate: CompletableDeferred<Unit>? = null
        override suspend fun get(id: String): String { readGate?.await(); return values[id].orEmpty() }
        override suspend fun put(id: String, value: String) { check(!failPut); values[id] = value }
        override suspend fun remove(id: String) { values.remove(id) }
        override suspend fun token() = ""
        override suspend fun saveToken(value: String) {}
    }

    private class Fixture : AutoCloseable, RemoteSession {
        override val events = Channel<JsonObject>(Channel.UNLIMITED)
        override var generation = 1L
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store = MemoryStore()
        var state = ScreenState(page = "chat", ready = true, draft = "Keep my draft")
        val projects = mutableListOf<CodexProject>()
        val aliases = mutableMapOf<String, String>()
        val files = mutableSetOf<String>()
        val unavailable = mutableSetOf<String>()
        val calls = mutableListOf<Pair<String, JsonObject>>()
        var createGate: CompletableDeferred<Unit>? = null
        var browseGate: CompletableDeferred<Unit>? = null
        var reject = false
        var loseReply = false
        var failRefresh = false
        var loopCursor = false
        var beforeCreate: (() -> Unit)? = null
        val creates get() = calls.count { it.first == "project/create" }
        var selected = 0
        var selectionGate: CompletableDeferred<Unit>? = null
        fun controller() = ProjectAdditionController(scope, store, this, { state },
            { state = state.copy(projectAddition = it) }, { list, project, path, current ->
                selectionGate?.await()
                if (current()) {
                selected++
                state = state.copy(projects = list, newTaskOptions = state.newTaskOptions.copy(
                    projectId = project.id, workingDirectory = path, executionTarget = ExecutionTarget.CurrentWorkspace))
                true
                } else false
            })
        fun prepared(path: String = "/host/op bridge"): ProjectAdditionController = controller().also {
            it.open(); it.browse(path); it.confirmFolder()
            assertTrue(state.projectAddition.confirming)
        }
        override suspend fun connect(url: String, token: String) = obj()
        override suspend fun call(method: String, params: JsonObject): JsonObject {
            calls += method to params
            return when (method) {
                "fs/getMetadata" -> {
                    if (params.str("path") in unavailable) throw RpcRejected(-32000, "Unavailable")
                    obj("isDirectory" to JsonPrimitive(params.str("path") !in files))
                }
                "command/exec" -> {
                    val command = params["command"]!!.jsonArray.map { it.jsonPrimitive.content }
                    assertEquals(listOf("realpath", "-e", "--"), command.take(3))
                    assertEquals("readOnly", params.map("sandboxPolicy").str("type"))
                    obj("exitCode" to JsonPrimitive(0), "stdout" to s((aliases[command[3]] ?: command[3]) + "\n"))
                }
                "fs/readDirectory" -> {
                    browseGate?.await()
                    obj("entries" to JsonArray(listOf(
                        obj("fileName" to s("op bridge"), "isDirectory" to JsonPrimitive(true)),
                        obj("fileName" to s("readme"), "isDirectory" to JsonPrimitive(false)))))
                }
                "project/list" -> {
                    if (failRefresh && creates > 0) error("Connection lost while refreshing")
                    val index = params.str("cursor").toIntOrNull() ?: 0
                    obj("data" to JsonArray(projects.drop(index).take(1).map(::row)),
                        "nextCursor" to if (loopCursor) s("0") else if (index + 1 < projects.size) s((index + 1).toString()) else JsonNull)
                }
                "project/create" -> {
                    beforeCreate?.invoke()
                    createGate?.await()
                    if (reject) throw RpcRejected(-32000, "Rejected")
                    val created = CodexProject("new-project", params.str("name"), params.list("roots").map { it.str("path") })
                    projects += created
                    if (loseReply) error("Lost reply")
                    obj("project" to row(created))
                }
                else -> error("Unexpected RPC: $method")
            }
        }
        private fun row(p: CodexProject) = obj("id" to s(p.id), "name" to s(p.name),
            "roots" to JsonArray(p.roots.map { obj("path" to s(it)) }))
        override fun respond(id: JsonElement, result: JsonObject, epoch: Long) {}
        override fun close() { scope.cancel() }
        override fun dispose() {}
    }

    @Test fun createsOncePersistsBeforeSendingAndPreservesDraft() = Fixture().use { f ->
        val attachment = DraftAttachment("attachment", "/fixture/photo.png", "photo.png", "image/png", 12)
        f.state = f.state.copy(attachments = listOf(attachment))
        val c = f.prepared("/host/space ' \$x; repo")
        c.name("My project")
        f.beforeCreate = { assertEquals(1, f.store.values.size) }
        f.createGate = CompletableDeferred()
        c.add(); c.add()
        assertEquals(1, f.creates)
        f.createGate!!.complete(Unit)
        assertEquals("new-project", f.state.newTaskOptions.projectId)
        assertEquals("/host/space ' \$x; repo", f.state.newTaskOptions.workingDirectory)
        assertEquals("My project", f.state.projects.single().name)
        assertEquals("Keep my draft", f.state.draft)
        assertEquals(listOf(attachment), f.state.attachments)
        assertFalse(f.state.projectAddition.visible)
        assertTrue(f.store.values.isEmpty())
        assertEquals(1, f.selected)
    }

    @Test fun reusesCanonicalMatchOnLaterPageAndKeepsNameAndMatchingRoot() = Fixture().use { f ->
        f.projects += CodexProject("other", "Other", listOf("/other"))
        f.projects += CodexProject("existing", "Original name", listOf("/unrelated", "/alias"))
        f.aliases["/alias"] = "/host/op bridge"
        val c = f.prepared()
        c.name("Do not rename"); c.add()
        assertEquals(0, f.creates)
        assertEquals("existing", f.state.newTaskOptions.projectId)
        assertEquals("/alias", f.state.newTaskOptions.workingDirectory)
        assertEquals("Original name", f.state.projects.last().name)
    }

    @Test fun multipleMatchesRequireAnExplicitChoice() = Fixture().use { f ->
        f.projects += listOf(CodexProject("a", "One", listOf("/host/op bridge")), CodexProject("b", "Two", listOf("/host/op bridge")))
        val c = f.prepared(); c.add()
        assertEquals(2, f.state.projectAddition.matches.size)
        assertNull(f.state.newTaskOptions.projectId)
        c.choose("b")
        assertEquals("b", f.state.newTaskOptions.projectId)
        assertEquals(0, f.creates)
    }

    @Test fun lostReplySurvivesRestartAndReconcilesWithoutReplay() = Fixture().use { f ->
        f.loseReply = true
        val c = f.prepared(); c.add()
        assertNotNull(f.state.projectAddition.pending)
        assertNull(f.state.newTaskOptions.projectId)
        val restarted = f.controller(); restarted.open(); restarted.add(); restarted.checkAgain()
        assertEquals(1, f.creates)
        assertEquals("new-project", f.state.newTaskOptions.projectId)
        assertTrue(f.store.values.isEmpty())
    }

    @Test fun absentOutcomeRemainsPendingAndNeverReplays() = Fixture().use { f ->
        f.loseReply = true
        val c = f.prepared(); c.add(); f.projects.clear()
        c.checkAgain(); c.add(); c.checkAgain()
        assertEquals(1, f.creates)
        assertNotNull(f.state.projectAddition.pending)
        assertTrue(f.state.projectAddition.error!!.contains("not confirmed"))
        assertEquals(1, f.store.values.size)
    }

    @Test fun acknowledgedCreationRetainsIdWhenRefreshFails() = Fixture().use { f ->
        f.failRefresh = true
        val c = f.prepared(); c.add()
        assertEquals("new-project", f.state.projectAddition.pending?.str("projectId"))
        f.failRefresh = false
        c.checkAgain()
        assertEquals(1, f.creates)
        assertEquals("new-project", f.state.newTaskOptions.projectId)
    }

    @Test fun explicitRejectionClearsPendingAndAllowsExplicitRetry() = Fixture().use { f ->
        f.reject = true
        val c = f.prepared(); c.add()
        assertNull(f.state.projectAddition.pending)
        assertTrue(f.store.values.isEmpty())
        assertNotNull(f.state.projectAddition.error)
        f.reject = false; c.add()
        assertEquals(2, f.creates)
        assertEquals("new-project", f.state.newTaskOptions.projectId)
    }

    @Test fun failureToPersistDoesNotSendCreation() = Fixture().use { f ->
        val c = f.prepared(); f.store.failPut = true; c.add()
        assertEquals(0, f.creates)
        assertNotNull(f.state.projectAddition.error)
    }

    @Test fun folderOnlyBrowsingRejectsFilesRelativeAndInaccessiblePaths() = Fixture().use { f ->
        val c = f.controller(); c.open()
        assertEquals(listOf("op bridge"), f.state.projectAddition.folders)
        f.files += "/file"
        for (path in listOf("relative", "/file", "/missing")) {
            f.unavailable += "/missing"
            c.browse(path)
            assertNull(f.state.projectAddition.loadedPath)
            assertNotNull(f.state.projectAddition.error)
        }
        assertEquals(0, f.creates)
    }

    @Test fun staleBrowseCannotOverwriteEditedPathOrDisconnectedState() = Fixture().use { f ->
        val c = f.controller(); c.open()
        f.browseGate = CompletableDeferred()
        c.browse("/slow"); c.editPath("/typed")
        f.browseGate!!.complete(Unit)
        assertEquals("/typed", f.state.projectAddition.path)
        assertNull(f.state.projectAddition.loadedPath)
        f.browseGate = CompletableDeferred()
        c.browse("/old-connection"); f.generation++; f.state = f.state.copy(ready = false); c.disconnected()
        f.browseGate!!.complete(Unit)
        assertNull(f.state.projectAddition.loadedPath)
        assertFalse(f.state.projectAddition.loading)
    }

    @Test fun closingDuringCreationDoesNotChangeSelectionAndCanRecover() = Fixture().use { f ->
        val c = f.prepared(); f.createGate = CompletableDeferred(); c.add(); c.dismiss()
        f.createGate!!.complete(Unit)
        assertNull(f.state.newTaskOptions.projectId)
        assertNotNull(f.state.projectAddition.pending)
        c.open(); c.checkAgain()
        assertEquals("new-project", f.state.newTaskOptions.projectId)
        assertEquals(1, f.creates)
    }

    @Test fun repeatedCursorFailsBeforeMutation() = Fixture().use { f ->
        val c = f.prepared(); f.loopCursor = true; c.add()
        assertEquals(0, f.creates)
        assertNotNull(f.state.projectAddition.error)
    }

    @Test fun pendingRegistrationIsScopedToTheHostAccount() = Fixture().use { f ->
        f.loseReply = true
        val c = f.prepared(); c.add(); c.dismiss()
        f.state = f.state.copy(host = f.state.host.copy(expectedCodexHome = "/another/.codex"))
        f.controller().open()
        assertNull(f.state.projectAddition.pending)
        assertEquals(1, f.store.values.size)
    }
    @Test fun pastedSymlinkValidatesItsTargetAndIgnoresUnavailableSavedRoots() = Fixture().use { f ->
        f.aliases["/link"] = "/host/op bridge"
        // Link metadata itself is not a directory; resolved target metadata is.
        f.files += "/link"
        f.unavailable += "/stale"
        f.projects += CodexProject("stale", "Stale", listOf("/stale"))
        val c = f.prepared("/link"); c.add()
        assertEquals("new-project", f.state.newTaskOptions.projectId)
        assertEquals("/host/op bridge", f.state.newTaskOptions.workingDirectory)
        assertFalse(f.calls.any { it.first == "fs/getMetadata" && it.second.str("path") == "/link" })
    }

    @Test fun disconnectInvalidatesConfirmationUntilFolderIsReloaded() = Fixture().use { f ->
        val c = f.prepared(); f.state = f.state.copy(ready = false); c.disconnected()
        assertFalse(f.state.projectAddition.confirming)
        f.state = f.state.copy(ready = true); c.add()
        assertEquals(0, f.creates)
        c.browse("/host/op bridge"); c.confirmFolder(); c.add()
        assertEquals(1, f.creates)
    }

    @Test fun disconnectDuringLocalRecoveryDoesNotForgetPendingCreation() = Fixture().use { f ->
        f.loseReply = true
        val first = f.prepared(); first.add(); first.dismiss()
        val next = f.controller()
        f.store.readGate = CompletableDeferred()
        next.open()
        f.state = f.state.copy(ready = false); next.disconnected()
        f.store.readGate!!.complete(Unit)
        assertNotNull(f.state.projectAddition.pending)
        f.state = f.state.copy(ready = true)
        next.browse("/other"); next.add()
        assertEquals(1, f.creates)
        next.checkAgain()
        assertEquals("new-project", f.state.newTaskOptions.projectId)
    }

    @Test fun selectionCannotArriveAfterNavigatingAwayDuringPersistence() = Fixture().use { f ->
        val c = f.prepared()
        f.selectionGate = CompletableDeferred()
        c.add()
        f.state = f.state.copy(page = "home")
        c.dismiss()
        f.selectionGate!!.complete(Unit)
        assertEquals(0, f.selected)
        assertNotNull(f.state.projectAddition.pending)
        assertEquals(1, f.creates)
    }

}
