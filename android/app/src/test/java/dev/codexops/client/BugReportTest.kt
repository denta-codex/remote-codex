package dev.codexops.client

import dev.codexops.core.*
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class BugReportTest {
    private val id = "12345678-1234-1234-1234-123456789abc"

    @Test fun snapshotContainsVisibleEvidenceWithoutRawProtocolOrCredentials() {
        val pending = obj("stage" to s("sending"), "operation" to s("op"), "credential" to s("secret-key"), "rawPayload" to s("hidden"))
        var state = ScreenState(thread = "source", draft = "unsent words", journal = pending,
            entries = listOf(Entry("turn", obj("id" to s("item"), "type" to s("agentMessage"), "text" to s("original"), "raw" to s("hidden")))),
            historyCursor = "older", error = "Visible failure")
        val snapshot = bugReportContext(state, obj("version" to s("1")), JsonArray(emptyList()))
        state = state.copy(draft = "later", entries = emptyList())
        assertEquals("unsent words", snapshot.str("draft"))
        assertEquals("original", snapshot.list("conversation").single().str("text"))
        assertEquals("true", snapshot.str("unloadedHistory"))
        assertFalse(snapshot.toString().contains("secret-key"))
        assertFalse(snapshot.toString().contains("hidden"))
        assertEquals("later", state.draft)
    }

    @Test fun actionHistoryIsBoundedAndExpires() {
        var time = 1_000_000L
        val actions = RecentAppActions { time }
        repeat(120) { actions.add("click", it.toString()); time++ }
        assertEquals(100, actions.snapshot().size)
        assertEquals("20", (actions.snapshot().first() as JsonObject).str("target"))
        time += 300_001
        assertTrue(actions.snapshot().isEmpty())
    }

    @Test fun logSnapshotHonorsTimeByteLineAndCredentialBoundaries() {
        val now = 1_000_000L
        val credential = "this-is-the-pairing-credential-123456789"
        val lines = sequenceOf("699.0 old", "1001.0 future", "999.0 token=$credential", "999.1 {\"params\":\"private\"}") +
            (0..3000).asSequence().map { "999.9 ${"é".repeat(300)} $it" }
        val result = boundedReportLogs(lines, now, credential)
        assertTrue(result.toByteArray().size <= 512 * 1024)
        assertTrue(result.lineSequence().filter(String::isNotEmpty).count() <= 2000)
        assertFalse(result.contains("old"))
        assertFalse(result.contains("future"))
        assertFalse(result.contains("private"))
        assertFalse(result.contains(credential))
        assertTrue(result.contains("3000"))
        assertFalse(redactReportText("Bearer $credential", credential).contains(credential))
    }

    @Test fun draftsAndArtifactsSurviveStoreRecreationAndAreScopedToReport() {
        val root = Files.createTempDirectory("report-test").toFile()
        try {
            val store = BugReportStore(root)
            val file = store.attachment(id, "context.txt", "frozen".toByteArray())
            val draft = BugReportDraft(id, 123L, obj("draft" to s("original")), "A bug", listOf(file))
            store.save(draft)
            val restored = requireNotNull(BugReportStore(root).load())
            assertEquals(draft, restored)
            store.validate(restored)
            val foreign = File(root, "foreign.txt").apply { writeText("outside") }
            assertThrows(IllegalArgumentException::class.java) {
                store.validate(restored.copy(attachments = listOf(file.copy(localPath = foreign.path, byteSize = foreign.length()))))
            }
            store.discard(restored)
            assertNull(store.load())
            assertTrue(foreign.exists())
            assertFalse(File(file.localPath).exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun screenshotExclusionDoesNotInvokeCaptureAndFailuresAreIndependent() = runBlocking {
        var called = false
        val results = collectBugReportDiagnostics(mapOf(
            "screen" to ScreenshotCollector(true) { called = true; byteArrayOf() },
            "broken" to DiagnosticCollector { error("private exception text") },
            "good" to DiagnosticCollector { DiagnosticResult("captured", "ok", listOf(DiagnosticArtifact("ok.txt", byteArrayOf(1)))) },
        ), 100)
        assertFalse(called)
        assertEquals("omitted", results.getValue("screen").second.status)
        assertEquals("unavailable", results.getValue("broken").second.status)
        assertFalse(results.toString().contains("private exception text"))
        assertEquals(1, results.getValue("good").second.artifacts.size)
    }

    @Test fun shakeGateHonorsLifecyclePreferenceOpenReportAndCooldown() {
        val gate = ReportShakeGate()
        assertFalse(gate.accept(0, false, true, false))
        assertFalse(gate.accept(0, true, false, false))
        assertFalse(gate.accept(0, true, true, true))
        assertTrue(gate.accept(0, true, true, false))
        assertFalse(gate.accept(2999, true, true, false))
        assertTrue(gate.accept(3000, true, true, false))
    }

    @Test fun screenshotPromptRequiresAndroid14VisibilityPreferenceAndNoOpenReport() {
        val ready = BugReportState(loaded = true)
        assertTrue(screenshotPromptAllowed(34, true, ready))
        assertFalse(screenshotPromptAllowed(33, true, ready))
        assertFalse(screenshotPromptAllowed(34, false, ready))
        assertFalse(screenshotPromptAllowed(34, true, ready.copy(loaded = false)))
        assertFalse(screenshotPromptAllowed(34, true, ready.copy(screenshotEnabled = false)))
        assertFalse(screenshotPromptAllowed(34, true, ready.copy(visible = true)))
        assertFalse(screenshotPromptAllowed(34, true, ready.copy(capturing = true)))
        assertFalse(screenshotPromptAllowed(34, true, ready.copy(busy = true)))
        assertFalse(ready.shakeEnabled)
    }

    @Test fun aNativeCollectorThatIgnoresInterruptsCannotBlockTheDeadline() = runBlocking {
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        try {
            val result = withTimeoutOrNull(500) {
                reportBlocking {
                    started.countDown()
                    while (release.count > 0) {
                        try { release.await() } catch (_: InterruptedException) { }
                    }
                    "finished"
                }
            }
            assertEquals(0L, started.count)
            assertNull(result)
            assertEquals("other collector", reportBlocking { "other collector" })
        } finally { release.countDown() }
    }

    @Test fun redactingStructuredContextPreservesQuotedUserText() {
        val original = obj("text" to s("A quoted payload: {\"token\":\"private-value\"}"))
        val redacted = redactReportJson(original, "")
        assertFalse(redacted.toString().contains("private-value"))
        assertEquals(redacted, wire.parseToJsonElement(redacted.toString()))
    }

    @Test fun repositoryIdentityDoesNotAcceptLookalikesOrCredentialUrls() {
        assertTrue(validBugReportOrigin("git@github.com:denta-codex/remote-codex.git"))
        assertFalse(validBugReportOrigin("https://github.com/another/remote-codex.git"))
        assertFalse(validBugReportOrigin("https://github.com.evil/denta-codex/remote-codex.git"))
        assertFalse(validBugReportOrigin("https://token@github.com/denta-codex/remote-codex.git"))
    }
}
