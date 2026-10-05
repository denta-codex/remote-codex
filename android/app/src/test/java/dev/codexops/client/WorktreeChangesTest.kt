package dev.codexops.client

import java.nio.file.Files
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class WorktreeChangesTest {
    private fun command(root: File, vararg args: String): String {
        val process = ProcessBuilder(*args).directory(root).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(args.joinToString(" "), 0, process.waitFor())
        return output
    }

    private fun repository(test: (File) -> Unit) {
        val root = Files.createTempDirectory("remote-codex-worktree-test-").toFile()
        try {
            command(root, "git", "init", "-q")
            command(root, "git", "config", "user.email", "fixture@example.com")
            command(root, "git", "config", "user.name", "Fixture")
            test(root)
        } finally { root.deleteRecursively() }
    }

    private fun snapshot(root: File): WorktreeChanges {
        val process = ProcessBuilder("bash", "-c", worktreeReadScript, "remote-codex-worktree", root.path)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
        process.environment().putAll(mapOf("LC_ALL" to "C", "GIT_OPTIONAL_LOCKS" to "0", "GIT_LITERAL_PATHSPECS" to "1"))
        val running = process.start()
        val output = running.inputStream.bufferedReader().readText()
        assertEquals(0, running.waitFor())
        return parseWorktreeChanges(output, root.path)
    }

    @Test fun stagedUnstagedAndUntrackedAreNetChangesAgainstHeadWithoutGitWrites() = repository { root ->
        File(root, "README.md").writeText("old\n")
        File(root, "deleted.txt").writeText("removed\n")
        File(root, "staged.txt").writeText("before\n")
        File(root, ".gitignore").writeText("ignored.txt\n")
        command(root, "git", "add", ".")
        command(root, "git", "commit", "-qm", "Fixture")
        File(root, "README.md").writeText("intermediate\n")
        command(root, "git", "add", "README.md")
        File(root, "README.md").writeText("new\n")
        File(root, "staged.txt").appendText("added\n")
        command(root, "git", "add", "staged.txt")
        File(root, "deleted.txt").delete()
        File(root, "new [file]\t\n.txt").writeText("one\ntwo\n")
        File(root, "empty.txt").writeText("")
        File(root, "binary.bin").writeBytes(byteArrayOf(0, 1, 2))
        File(root, "ignored.txt").writeText("ignored\n")
        val before = command(root, "git", "status", "--porcelain=v1", "-z")
        val indexBefore = File(root, ".git/index").readBytes()
        val result = snapshot(root)
        assertEquals(WorktreeStatus.Ready, result.status)
        assertEquals(6, result.files.size)
        assertEquals(4L, result.added)
        assertEquals(2L, result.removed)
        val readme = result.files.single { it.path == "README.md" }
        assertTrue(readme.diff.contains("-old\n+new"))
        assertFalse(readme.diff.contains("intermediate"))
        assertEquals(2L, result.files.single { it.path == "new [file]\t\n.txt" }.added)
        assertEquals(0L, result.files.single { it.path == "empty.txt" }.added)
        assertNull(result.files.single { it.path == "binary.bin" }.added)
        assertEquals(before, command(root, "git", "status", "--porcelain=v1", "-z"))
        assertArrayEquals(indexBefore, File(root, ".git/index").readBytes())
    }

    @Test fun unbornRepositoriesIncludeStagedFilesAndCleanReposHaveZeroTotals() = repository { root ->
        File(root, "initial.txt").writeText("first\n")
        command(root, "git", "add", "initial.txt")
        assertEquals(1L, snapshot(root).added)
        command(root, "git", "commit", "-qm", "Fixture")
        assertTrue(snapshot(root).files.isEmpty())
        assertEquals(0L, snapshot(root).added)
    }

    @Test fun subdirectoryReadsWholeRepositoryAndRevertedEditsDisappear() = repository { root ->
        File(root, "sub").mkdir()
        File(root, "outside.txt").writeText("before\n")
        command(root, "git", "add", ".")
        command(root, "git", "commit", "-qm", "Fixture")
        File(root, "outside.txt").writeText("after\n")
        assertEquals("outside.txt", snapshot(File(root, "sub")).files.single().path)
        File(root, "outside.txt").writeText("before\n")
        assertTrue(snapshot(root).files.isEmpty())
    }

    @Test fun unavailableAndTruncatedOutputCannotLookLikeACleanWorktree() {
        assertEquals(WorktreeStatus.NotRepository,
            parseWorktreeChanges("remote-codex-worktree-v1\u0000not-repository\u0000end\u0000", "/tmp").status)
        for (invalid in listOf("", "remote-codex-worktree-v1\u0000ready\u0000/repo\u0000",
            "remote-codex-worktree-v1\u0000ready\u0000/repo\u0000file\u0000-1\u00000\u0000diff\u0000end\u0000"))
            assertTrue(runCatching { parseWorktreeChanges(invalid, "/repo") }.isFailure)
    }

    @Test fun failedGitEnumerationCannotProduceACleanSnapshot() = repository { root ->
        val realGit = command(root, "bash", "-c", "command -v git").trim()
        val bin = File(root, "fixture-bin").apply { mkdir() }
        for (failedCommand in listOf("diff", "ls-files")) {
            File(bin, "git").apply {
                writeText("#!/usr/bin/env bash\nif [[ \"\$1\" == '$failedCommand' ]]; then exit 3; fi\nexec '${realGit.replace("'", "'\"'\"'")}' \"\$@\"\n")
                assertTrue(setExecutable(true))
            }
            val builder = ProcessBuilder("bash", "-c", worktreeReadScript, "remote-codex-worktree", root.path)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
            builder.environment()["PATH"] = bin.path + File.pathSeparator + System.getenv("PATH")
            val process = builder.start()
            val output = process.inputStream.bufferedReader().readText()
            assertNotEquals(0, process.waitFor())
            assertTrue(runCatching { parseWorktreeChanges(output, root.path) }.isFailure)
        }
    }
}
