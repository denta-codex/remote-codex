package dev.codexops.client

import dev.codexops.core.*
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GitMergeTest {
    private class Repository : AutoCloseable {
        val root = Files.createTempDirectory("remote-merge-test-").toFile()
        val main = File(root, "main checkout").apply { mkdir() }
        val source = File(root, "source [task]")
        val script = File(root, "merge.sh").apply { writeText(StockGitMergeOperations.script) }
        fun process(vararg args: String, cwd: File = main): Pair<Int, String> {
            val out = File(root, "output-${UUID.randomUUID()}")
            val p = ProcessBuilder(*args).directory(cwd).redirectErrorStream(true).redirectOutput(out)
            p.environment().putAll(mapOf("GIT_CONFIG_NOSYSTEM" to "1", "GIT_CONFIG_GLOBAL" to "/dev/null"))
            val child = p.start()
            if (!child.waitFor(15, TimeUnit.SECONDS)) { child.destroyForcibly(); error("Git fixture timed out") }
            return (child.exitValue() to out.readText()).also { out.delete() }
        }
        fun git(vararg args: String, cwd: File = main): String {
            val (code, output) = process("git", *args, cwd = cwd)
            check(code == 0) { "Git fixture failed: $output" }
            return output.trim()
        }
        fun commit(directory: File, name: String, content: String) {
            File(directory, name).writeText(content)
            git("add", "--", name, cwd = directory)
            git("commit", "-m", "Update $name", cwd = directory)
        }
        init {
            git("init", "-b", "main")
            git("config", "user.name", "Merge Fixture")
            git("config", "user.email", "fixture@example.invalid")
            git("config", "commit.gpgsign", "false")
            commit(main, "base.txt", "base\n")
            git("worktree", "add", "--detach", source.path, "main")
            commit(source, "feature.txt", "feature\n")
        }
        fun run(action: String, snapshot: JsonObject = obj(), operation: String = UUID.randomUUID().toString()): JsonObject {
            val (code, output) = process("bash", script.path, action, source.path, operation, snapshot.toString())
            check(code == 0) { output }
            return try { wire.parseToJsonElement(output.trim()).jsonObject } catch (e: Exception) { error(output) }
        }
        override fun close() { root.deleteRecursively() }
    }

    @Test fun directFastForwardAndDetachedSource() = Repository().use { r ->
        r.git("config", "branch.main.mergeOptions", "--squash --autostash")
        val preview = r.run("inspect")
        assertEquals(preview.toString(), "ready", preview.str("status"))
        assertEquals("", preview.str("sourceRef"))
        val id = UUID.randomUUID().toString()
        val result = r.run("merge", preview, id)
        assertEquals(result.toString(), "succeeded", result.str("status"))
        assertEquals(r.git("rev-parse", "HEAD", cwd = r.source), r.git("rev-parse", "main"))
        assertEquals("succeeded", r.run("reconcile", preview, id).str("status"))
        assertEquals("needsReview", r.run("merge", preview, id).str("status"))
        assertEquals("blocked", r.run("inspect").str("status"))
        assertEquals("", r.git("status", "--porcelain"))
    }

    @Test fun divergentMergeHasBothParentsAndCleansTemporaryWorktree() = Repository().use { r ->
        r.commit(r.main, "main.txt", "main\n")
        val preview = r.run("inspect")
        val result = r.run("merge", preview)
        assertEquals(result.toString(), "succeeded", result.str("status"))
        assertEquals("${preview.str("targetHead")} ${preview.str("sourceHead")}", r.git("show", "-s", "--format=%P", "HEAD"))
        assertTrue(File(r.main, "feature.txt").exists())
        assertFalse(r.git("worktree", "list", "--porcelain").contains("remote-codex-merges/worktree-"))
    }

    @Test fun conflictsLeaveMainAndItsIndexUntouched() = Repository().use { r ->
        r.commit(r.main, "base.txt", "main edit\n")
        r.commit(r.source, "base.txt", "task edit\n")
        val head = r.git("rev-parse", "HEAD")
        val index = File(r.main, ".git/index").readBytes()
        val preview = r.run("inspect")
        assertEquals("blocked", preview.str("status"))
        assertTrue(preview["conflicts"].toString().contains("base.txt"))
        assertEquals(head, r.git("rev-parse", "HEAD"))
        assertArrayEquals(index, File(r.main, ".git/index").readBytes())
        assertEquals("main edit\n", File(r.main, "base.txt").readText())
        assertFalse(File(r.main, ".git/MERGE_HEAD").exists())
    }

    @Test fun dirtyCheckoutsAndChangedPreviewsAreRejected() = Repository().use { r ->
        val preview = r.run("inspect")
        for (directory in listOf(r.main, r.source)) {
            val untracked = File(directory, "unsaved.txt").apply { writeText("keep") }
            assertEquals(if (directory == r.main) "blocked" else "ready", r.run("inspect").str("status"))
            assertEquals("blocked", r.run("merge", preview).str("status"))
            assertEquals("keep", untracked.readText())
            untracked.delete()
        }
        r.commit(r.main, "later.txt", "later\n")
        val newHead = r.git("rev-parse", "HEAD")
        assertEquals("blocked", r.run("merge", preview).str("status"))
        assertEquals(newHead, r.git("rev-parse", "HEAD"))
    }

    @Test fun commitsAllTaskChangesWithoutChangingIndexDuringPreview() = Repository().use { r ->
        File(r.source, "base.txt").writeText("staged version\n")
        r.git("add", "base.txt", cwd = r.source)
        File(r.source, "base.txt").writeText("final version\n")
        File(r.source, "feature.txt").delete()
        File(r.source, "new file.txt").writeText("new\n")
        File(r.source, ".gitignore").writeText("ignored.txt\n")
        File(r.source, "ignored.txt").writeText("private\n")
        val index = File(r.git("rev-parse", "--git-path", "index", cwd = r.source))
        val before = index.readBytes()
        val preview = r.run("inspect")
        assertEquals(preview.toString(), "ready", preview.str("status"))
        assertEquals("4", preview.str("uncommittedCount"))
        assertArrayEquals(before, index.readBytes())
        assertFalse(preview["uncommitted"].toString().contains("ignored.txt"))
        val id = UUID.randomUUID().toString()
        val result = r.run("merge", preview, id)
        assertEquals(result.toString(), "succeeded", result.str("status"))
        assertEquals("final version\n", File(r.main, "base.txt").readText())
        assertEquals("new\n", File(r.main, "new file.txt").readText())
        assertFalse(File(r.main, "feature.txt").exists())
        assertFalse(File(r.main, "ignored.txt").exists())
        assertEquals(preview.str("sourceHead"), r.git("rev-parse", "HEAD^", cwd = r.source))
        assertEquals("", r.git("status", "--porcelain", cwd = r.source))
        assertEquals("succeeded", r.run("reconcile", preview, id).str("status"))
    }

    @Test fun uncommittedOnlyTaskCanMergeFromMainBase() = Repository().use { r ->
        r.git("reset", "--hard", "main", cwd = r.source)
        File(r.source, "new.txt").writeText("new")
        val preview = r.run("inspect")
        assertEquals(preview.toString(), "ready", preview.str("status"))
        assertEquals("0", preview.str("count"))
        assertEquals("succeeded", r.run("merge", preview).str("status"))
        assertEquals("new", File(r.main, "new.txt").readText())
    }

    @Test fun uncommittedConflictAndChangedContentDoNotCommit() = Repository().use { r ->
        r.commit(r.main, "base.txt", "main edit\n")
        File(r.source, "base.txt").writeText("task edit\n")
        val head = r.git("rev-parse", "HEAD", cwd = r.source)
        val blocked = r.run("inspect")
        assertEquals("blocked", blocked.str("status"))
        assertTrue(blocked["conflicts"].toString().contains("base.txt"))
        File(r.source, "base.txt").writeText("base\n")
        File(r.source, "new.txt").writeText("first")
        val preview = r.run("inspect")
        assertEquals("ready", preview.str("status"))
        File(r.source, "new.txt").writeText("changed")
        assertEquals("blocked", r.run("merge", preview).str("status"))
        assertEquals(head, r.git("rev-parse", "HEAD", cwd = r.source))
        assertEquals("changed", File(r.source, "new.txt").readText())
    }

    @Test fun failedCommitAndLostReplyAreCheckedWithoutReplaying() = Repository().use { r ->
        File(r.source, "new.txt").writeText("keep")
        val hooks = File(r.root, "hooks").apply { mkdir() }
        File(hooks, "pre-commit").apply { writeText("#!/bin/sh\nexit 1\n"); setExecutable(true) }
        r.git("config", "core.hooksPath", hooks.path)
        val preview = r.run("inspect")
        val id = UUID.randomUUID().toString()
        assertEquals("needsReview", r.run("merge", preview, id).str("status"))
        assertEquals(preview.str("targetHead"), r.git("rev-parse", "HEAD"))
        assertEquals(preview.str("sourceHead"), r.git("rev-parse", "HEAD", cwd = r.source))
        assertEquals("failed", r.run("reconcile", preview, id).str("status"))
        assertEquals("keep", File(r.source, "new.txt").readText())
        File(hooks, "pre-commit").delete()
        val next = r.run("inspect")
        val nextId = UUID.randomUUID().toString()
        assertEquals("succeeded", r.run("merge", next, nextId).str("status"))
        val taskHead = r.git("rev-parse", "HEAD", cwd = r.source)
        assertEquals("succeeded", r.run("reconcile", next, nextId).str("status"))
        assertEquals(taskHead, r.git("rev-parse", "HEAD", cwd = r.source))
        assertEquals("needsReview", r.run("merge", next, nextId).str("status"))
    }

    @Test fun commitHookCannotSilentlyChangeTheApprovedTree() = Repository().use { r ->
        File(r.source, "new.txt").writeText("reviewed")
        val hooks = File(r.root, "hooks").apply { mkdir() }
        File(hooks, "pre-commit").apply {
            writeText("#!/bin/sh\nprintf changed > new.txt\ngit add new.txt\n")
            setExecutable(true)
        }
        r.git("config", "core.hooksPath", hooks.path)
        val preview = r.run("inspect")
        val id = UUID.randomUUID().toString()
        assertEquals("needsReview", r.run("merge", preview, id).str("status"))
        assertEquals(preview.str("targetHead"), r.git("rev-parse", "HEAD"))
        assertFalse(File(r.main, "new.txt").exists())
        assertEquals("failed", r.run("reconcile", preview, id).str("status"))
    }

    @Test fun missingMainCheckoutAndActiveGitOperationAreBlocked() = Repository().use { r ->
        r.git("checkout", "--detach", "HEAD")
        assertTrue(r.run("inspect").str("reason").contains("checked out"))
        r.git("checkout", "main")
        File(r.main, ".git/MERGE_HEAD").writeText(r.git("rev-parse", "HEAD", cwd = r.source))
        assertTrue(r.run("inspect").str("reason").contains("unfinished"))
        File(r.main, ".git/MERGE_HEAD").delete()
        File(r.main, ".git/index.lock").writeText("")
        assertTrue(r.run("inspect").str("reason").contains("unfinished"))
        File(r.main, ".git/index.lock").delete()
        r.git("branch", "-m", "main", "different")
        assertTrue(r.run("inspect").str("reason").contains("no local main"))
    }

    @Test fun unrelatedHistoriesAndSubmodulesAreBlocked() = Repository().use { r ->
        r.git("checkout", "--orphan", "unrelated", cwd = r.source)
        r.git("rm", "-rf", ".", cwd = r.source)
        r.commit(r.source, "unrelated.txt", "unrelated")
        assertTrue(r.run("inspect").str("reason").contains("unrelated"))
        r.git("checkout", "--detach", "main", cwd = r.source)
        r.git("update-index", "--add", "--cacheinfo", "160000,${r.git("rev-parse", "main")},module", cwd = r.source)
        r.git("commit", "-m", "Submodule", cwd = r.source)
        assertTrue(r.run("inspect").str("reason").contains("Submodule"))
    }

    @Test fun failedPreparationKeepsMainAndCleansUp() = Repository().use { r ->
        r.commit(r.main, "main.txt", "main")
        val hooks = File(r.root, "hooks").apply { mkdir() }
        File(hooks, "pre-merge-commit").apply { writeText("#!/bin/sh\nexit 1\n"); setExecutable(true) }
        r.git("config", "core.hooksPath", hooks.path)
        val preview = r.run("inspect")
        val result = r.run("merge", preview)
        assertEquals(result.toString(), "failed", result.str("status"))
        assertEquals(preview.str("targetHead"), r.git("rev-parse", "HEAD"))
        assertFalse(r.git("worktree", "list", "--porcelain").contains("remote-codex-merges/worktree-"))
    }

    @Test fun repositoryLockPreventsAnotherMutation() = Repository().use { r ->
        val preview = r.run("inspect")
        val ready = File(r.root, "lock-ready")
        val child = ProcessBuilder("bash", "-c", "exec 9>\"\$1\"; flock 9; touch -- \"\$2\"; read -r _", "fixture",
            File(r.main, ".git/remote-codex-merge.lock").path, ready.path).start()
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!ready.exists() && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue(ready.exists())
            assertTrue(r.run("merge", preview).str("reason").contains("Another merge"))
            assertEquals(preview.str("targetHead"), r.git("rev-parse", "HEAD"))
        } finally { child.outputStream.close(); child.waitFor(5, TimeUnit.SECONDS); child.destroyForcibly() }
    }

    @Test fun lostReplyBeforeAndAfterAdvancementCanBeReconciledWithoutReplay() = Repository().use { r ->
        val preview = r.run("inspect")
        for (after in listOf(false, true)) {
            val id = UUID.randomUUID().toString()
            val receipt = File(r.main, ".git/remote-codex-merges/$id.json")
            requireNotNull(receipt.parentFile).mkdirs()
            // Simulate process interruption at the durable 'advancing' stage.
            receipt.writeText(JsonObject(preview + mapOf("operation" to s(id), "stage" to s("advancing"),
                "result" to s(preview.str("sourceHead")), "temporary" to s(""))).toString())
            if (after) r.git("merge", "--ff-only", preview.str("sourceHead"))
            val result = r.run("reconcile", preview, id)
            assertEquals(result.toString(), if (after) "succeeded" else "failed", result.str("status"))
            assertEquals(if (after) preview.str("sourceHead") else preview.str("targetHead"), r.git("rev-parse", "HEAD"))
        }
    }

    @Test fun branchSourceAndConcurrentMainCommitAreHandled() = Repository().use { r ->
        r.git("checkout", "-b", "topic", cwd = r.source)
        r.commit(r.main, "main.txt", "main")
        val preview = r.run("inspect")
        assertEquals("topic", preview.str("sourceRef"))
        val hooks = File(r.root, "hooks").apply { mkdir() }
        File(hooks, "pre-merge-commit").apply {
            writeText("#!/bin/sh\nunset GIT_DIR GIT_WORK_TREE GIT_INDEX_FILE GIT_PREFIX GIT_COMMON_DIR\ngit -C '${r.main.path}' commit --allow-empty -m Concurrent\n")
            setExecutable(true)
        }
        r.git("config", "core.hooksPath", hooks.path)
        val result = r.run("merge", preview)
        assertEquals(result.toString(), "failed", result.str("status"))
        assertEquals("Concurrent", r.git("log", "-1", "--format=%s"))
        assertFalse(File(r.main, "feature.txt").exists())
        assertFalse(r.git("worktree", "list", "--porcelain").contains("remote-codex-merges/worktree-"))
    }

    @Test fun ignoredLocalFilesAreNeverOverwrittenByFinalFastForward() = Repository().use { r ->
        r.commit(r.main, ".gitignore", "feature.txt\n")
        File(r.main, "feature.txt").writeText("keep ignored local file")
        val preview = r.run("inspect")
        assertEquals(preview.toString(), "ready", preview.str("status"))
        val id = UUID.randomUUID().toString()
        assertEquals("needsReview", r.run("merge", preview, id).str("status"))
        assertEquals("keep ignored local file", File(r.main, "feature.txt").readText())
        assertEquals(preview.str("targetHead"), r.git("rev-parse", "HEAD"))
        assertEquals("failed", r.run("reconcile", preview, id).str("status"))
    }

    private class MemoryStore : ClientStore {
        val values = mutableMapOf<String, String>()
        override suspend fun get(id: String) = values[id].orEmpty()
        override suspend fun put(id: String, value: String) { values[id] = value }
        override suspend fun remove(id: String) { values.remove(id) }
        override suspend fun token() = ""
        override suspend fun saveToken(value: String) {}
    }

    @Test fun controllerRetainsUncertainOperationAcrossRestartAndOnlyReconciles() = runBlocking {
        val store = MemoryStore()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var state = ScreenState(page = "chat", thread = "task", threadCwd = "/repo", ready = true,
            queueReady = true, draft = "Keep this draft")
        val calls = mutableListOf<String>()
        val operations = object : GitMergeOperations {
            override suspend fun run(action: String, cwd: String, operation: String, snapshot: JsonObject): JsonObject {
                calls += action
                return when (action) {
                    "inspect" -> obj("status" to s("ready"))
                    "merge" -> throw IllegalStateException("Lost reply")
                    else -> obj("status" to s("succeeded"), "result" to s("commit"))
                }
            }
        }
        fun controller() = GitMergeController(scope, store, operations, { state }, { _, merge -> state = state.copy(merge = merge) })
        val first = controller()
        first.inspect(); first.merge(); first.merge()
        assertNotNull(state.merge.pending)
        assertFalse(state.canInspectMerge())
        val second = controller()
        second.restore("task")
        second.inspect()
        assertEquals(listOf("inspect", "merge", "reconcile"), calls)
        assertNull(state.merge.pending)
        assertEquals("succeeded", state.merge.report?.str("status"))
        assertEquals("Keep this draft", state.draft)
        assertTrue(store.values.isEmpty())
        scope.cancel()
    }

    @Test fun anUnavailableRecoveryDoesNotClearTheSavedMutation() = runBlocking {
        val store = MemoryStore()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val record = obj("operation" to s(UUID.randomUUID().toString()), "cwd" to s("/missing"), "snapshot" to obj())
        store.put("git-merge/task", record.toString())
        var state = ScreenState(page = "chat", thread = "task", ready = true)
        val operations = object : GitMergeOperations {
            override suspend fun run(action: String, cwd: String, operation: String, snapshot: JsonObject) =
                obj("status" to s("blocked"), "reason" to s("Checkout unavailable"))
        }
        val controller = GitMergeController(scope, store, operations, { state }, { _, merge -> state = state.copy(merge = merge) })
        controller.restore("task")
        controller.reconcile()
        assertNotNull(state.merge.pending)
        assertEquals("needsReview", state.merge.report?.str("status"))
        assertEquals(record.toString(), store.get("git-merge/task"))
        scope.cancel()
    }

    @Test fun availabilityRequiresAnIdleSynchronizedTask() {
        val idle = ScreenState(page = "chat", thread = "task", threadCwd = "/repo", ready = true, queueReady = true)
        assertTrue(idle.canInspectMerge())
        listOf(idle.copy(ready = false), idle.copy(busy = true), idle.copy(activeTurn = "turn"),
            idle.copy(queueReady = false), idle.copy(journal = obj()), idle.copy(attention = true),
            idle.copy(threadCwd = null), idle.copy(thread = null), idle.copy(page = "home"),
            idle.copy(queuedMessages = listOf(QueuedMessage("q", "c", JsonArray(emptyList())))),
            idle.copy(decisions = listOf(Decision(s("a"), "approval", obj(), 0))),
            idle.copy(merge = GitMergeState(working = true)), idle.copy(merge = GitMergeState(pending = obj())))
            .forEach { assertFalse(it.canInspectMerge()) }
    }
}
