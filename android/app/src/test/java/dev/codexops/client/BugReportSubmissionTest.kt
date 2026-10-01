package dev.codexops.client

import dev.codexops.core.*
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class BugReportSubmissionTest {
    private val id = "12345678-1234-1234-1234-123456789abc"
    private val host = HostIdentity("test", "Test", "wss://test", "/codex", "/repo")

    @Test fun createsConfiguredProjectTaskAfterSetupAndUploadsWithServerDefaults() = runBlocking {
        fixture { rpc, original ->
            val saved = mutableListOf<String>()
            val done = BugReportSubmission(rpc, host).run(original) { saved += it.journal.str("stage") }
            assertEquals("accepted", done.journal.str("stage"))
            assertEquals("project", rpc.startParams!!.str("projectId"))
            assertEquals("/codex/worktrees/remote-codex-$id/workspace", rpc.startParams!!.str("cwd"))
            assertNull(rpc.startParams!!["model"])
            assertNull(rpc.sendParams!!["model"])
            assertNull(rpc.sendParams!!["collaborationMode"])
            assertEquals(130_000L, rpc.setupDeadline)
            assertTrue(rpc.operations.indexOf("setup") < rpc.operations.indexOf("start"))
            assertTrue(rpc.operations.indexOf("upload") < rpc.operations.indexOf("send"))
            val text = rpc.sendParams!!.list("input").first().str("text")
            assertTrue(text.contains("Human-authored bug description"))
            assertTrue(text.contains("The button loses my message"))
            assertTrue(text.contains("/report/"))
            assertTrue(saved.containsAll(listOf("settingUp", "uploading", "creatingTask", "sending", "accepted")))
        }
    }

    @Test fun lostAcknowledgementsAreReconciledWithoutReplayingAnyMutation() = runBlocking {
        for (failure in listOf("mkdir", "worktree", "setup", "evidence", "upload", "start", "name", "send")) {
            fixture { rpc, original ->
                rpc.dropAfter = failure
                var saved = original
                try {
                    BugReportSubmission(rpc, host).run(saved) { saved = it }
                    fail("Expected lost $failure reply")
                } catch (_: IOException) { }
                assertNotEquals("accepted", saved.journal.str("stage"))
                // Reconstruct the runner, as after process death or reconnect.
                saved = BugReportSubmission(rpc, host).run(saved) { saved = it }
                assertEquals("accepted after $failure", "accepted", saved.journal.str("stage"))
                for (mutation in listOf("mkdir", "worktree", "setup", "evidence", "upload", "start", "name", "send"))
                    assertEquals("$mutation repeated after $failure", 1, rpc.operations.count { it == mutation })
            }
        }
    }

    @Test fun absentUncertainResultNeverAuthorizesAnotherAttempt() = runBlocking {
        fixture { rpc, original ->
            rpc.dropAfter = "send"
            var saved = original
            try { BugReportSubmission(rpc, host).run(saved) { saved = it } } catch (_: IOException) { }
            rpc.messageVisible = false
            repeat(2) {
                try {
                    BugReportSubmission(rpc, host).run(saved) { saved = it }
                    fail("An unconfirmed send must stop")
                } catch (e: IllegalStateException) { assertTrue(e.message!!.contains("Nothing was retried")) }
            }
            assertEquals(1, rpc.operations.count { it == "send" })
            assertEquals("sending", saved.journal.str("stage"))
        }
    }

    @Test fun changedUploadIsNotOverwrittenAndSetupNeedsItsCompletionReceipt() = runBlocking {
        for (stage in listOf("upload", "setup")) fixture { rpc, original ->
            rpc.dropAfter = stage
            var saved = original
            try { BugReportSubmission(rpc, host).run(saved) { saved = it } } catch (_: IOException) { }
            rpc.files.keys.toList().forEach { rpc.files[it] = "different".toByteArray() }
            try {
                BugReportSubmission(rpc, host).run(saved) { saved = it }
                fail("Changed evidence cannot confirm $stage")
            } catch (_: IllegalStateException) { }
            assertEquals(1, rpc.operations.count { it == stage })
            assertEquals(0, rpc.operations.count { it == "start" })
        }
    }

    @Test fun wrongRepositoryFailsBeforeAnyMutation() = runBlocking {
        fixture { rpc, original ->
            rpc.origin = "https://github.com/another/repo.git"
            try {
                BugReportSubmission(rpc, host).run(original) { }
                fail("Must reject wrong repository")
            } catch (_: IllegalArgumentException) { }
            assertTrue(rpc.operations.isEmpty())
        }
    }

    private suspend fun fixture(block: suspend (ReportSession, BugReportDraft) -> Unit) {
        val dir = Files.createTempDirectory("report-submission").toFile()
        try {
            val store = BugReportStore(dir)
            val file = store.attachment(id, "context.txt", "frozen context".toByteArray())
            block(ReportSession(), BugReportDraft(id, 123, obj("threadReference" to s("codex://threads/source")),
                "The button loses my message", listOf(file)))
        } finally { dir.deleteRecursively() }
    }

    private inner class ReportSession : RemoteSession {
        override val events = Channel<JsonObject>()
        override val generation = 1L
        val operations = mutableListOf<String>()
        val files = mutableMapOf<String, ByteArray>()
        val directories = mutableSetOf("/repo")
        var worktree: String? = null
        var dropAfter: String? = null
        var taskName = ""
        var messageVisible = false
        var origin = "git@github.com:denta-codex/remote-codex.git"
        var startParams: JsonObject? = null
        var sendParams: JsonObject? = null
        var setupDeadline = 0L
        override suspend fun connect(url: String, token: String) = obj()
        override fun close() = Unit
        override fun dispose() = Unit
        override fun respond(id: JsonElement, result: JsonObject, epoch: Long) = Unit
        private fun mutation(operation: String) {
            operations += operation
            if (dropAfter == operation) { dropAfter = null; throw IOException("Lost response") }
        }
        override suspend fun callWithTimeout(method: String, params: JsonObject, timeoutMillis: Long): JsonObject {
            if (params["command"].toString().contains("remote-codex-environment")) setupDeadline = timeoutMillis
            return call(method, params)
        }
        override suspend fun writeFile(path: String, bytes: ByteArray) { files[path] = bytes; mutation("upload") }
        override suspend fun readFile(path: String) = files[path] ?: throw IOException("Not found")
        override suspend fun call(method: String, params: JsonObject): JsonObject = when (method) {
            "project/list" -> obj("data" to JsonArray(listOf(project())))
            "project/read" -> obj("project" to project())
            "fs/getMetadata" -> {
                check(params.str("path") in directories)
                obj("type" to s("directory"))
            }
            "command/exec" -> {
                val args = (params["command"] as JsonArray).map { (it as JsonPrimitive).content }
                var stdout = ""
                when {
                    "get-url" in args -> stdout = origin
                    "symbolic-ref" in args -> stdout = "refs/remotes/origin/main"
                    "rev-parse" in args -> stdout = "a".repeat(40)
                    args.first() == "mkdir" -> {
                        directories += args.last()
                        mutation(if (args.last().endsWith("/report")) "evidence" else "mkdir")
                    }
                    "add" in args && "worktree" in args -> { worktree = args[args.indexOf("--") + 1]; mutation("worktree") }
                    "list" in args && "worktree" in args -> stdout = "worktree $worktree\nHEAD ${"a".repeat(40)}\n"
                    "remote-codex-environment" in args -> {
                        assertEquals("120000", params.str("timeoutMs"))
                        files[args[5]] = "${args[6]}\n${args[7]}\n".toByteArray()
                        mutation("setup")
                    }
                    else -> error("Unexpected command $args")
                }
                obj("exitCode" to JsonPrimitive(0), "stdout" to s(stdout))
            }
            "thread/start" -> {
                startParams = params
                mutation("start")
                obj("thread" to obj("id" to s("fix-task"), "projectId" to s("project")))
            }
            "thread/list" -> obj("data" to JsonArray(if (startParams == null) emptyList() else listOf(
                obj("id" to s("fix-task"), "projectId" to s("project"), "cwd" to s(startParams!!.str("cwd"))))))
            "thread/name/set" -> { taskName = params.str("name"); mutation("name"); obj() }
            "thread/read" -> obj("thread" to obj("id" to s("fix-task"), "name" to s(taskName)))
            "turn/start" -> { sendParams = params; messageVisible = true; mutation("send"); obj() }
            "thread/turns/list" -> obj("data" to JsonArray(if (!messageVisible) emptyList() else listOf(
                obj("items" to JsonArray(listOf(obj("type" to s("userMessage"), "id" to s(id))))))))
            else -> error("Unexpected RPC $method")
        }
        private fun project() = obj("id" to s("project"), "roots" to JsonArray(listOf(obj("path" to s("/repo")))))
    }
}
