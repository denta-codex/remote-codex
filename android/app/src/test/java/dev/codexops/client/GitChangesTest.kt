package dev.codexops.client

import dev.codexops.core.*
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GitChangesTest {
    @Test fun countsNetCommittedStagedUnstagedAndUntrackedChangesWithoutWritingIndex() {
        val root = Files.createTempDirectory("git-changes-test").toFile()
        try {
            fun run(vararg command: String): String {
                val process = ProcessBuilder(*command).directory(root).redirectErrorStream(true).start()
                assertTrue(process.waitFor(10, TimeUnit.SECONDS))
                val output = process.inputStream.bufferedReader().readText()
                assertEquals(output, 0, process.exitValue())
                return output
            }
            fun read() = wire.parseToJsonElement(run("bash", "-c", GitChanges.script, "test", root.path)).jsonObject
            assertEquals("notRepository", read().str("status"))
            run("git", "init", "-b", "main")
            File(root, "first").writeText("old\nkeep\n")
            // Unborn repositories are supported too.
            assertEquals("2", read().str("added"))
            run("git", "add", ".")
            run("git", "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "-c", "commit.gpgsign=false", "commit", "-m", "base")
            run("git", "checkout", "--detach")
            File(root, "committed").writeText("committed\n")
            run("git", "add", ".")
            run("git", "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "-c", "commit.gpgsign=false", "commit", "-m", "task")
            File(root, "first").writeText("staged\nkeep\n")
            run("git", "add", ".")
            File(root, "first").writeText("final\nkeep\nextra\n")
            File(root, "new\nfile with spaces").writeText("one\ntwo\n")
            File(root, "binary").writeBytes(byteArrayOf(0, 1, 2))
            File(root, ".git/info/exclude").appendText("\nignored\n")
            File(root, "ignored").writeText("ignored\n")
            val index = File(root, ".git/index").readBytes()
            val report = read()
            assertEquals("main", report.str("baseline"))
            assertEquals("4", report.str("files"))
            assertEquals("5", report.str("added"))
            assertEquals("1", report.str("removed"))
            assertEquals("1", report.str("binary"))
            assertArrayEquals(index, File(root, ".git/index").readBytes())
            run("git", "branch", "-D", "main")
            assertEquals("HEAD", read().str("baseline"))
            assertEquals("4", read().str("added"))
        } finally { root.deleteRecursively() }
    }
}
