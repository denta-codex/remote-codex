package dev.codexops.client

import android.app.Application
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.codexops.core.*
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*

class AppTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var server: MockWebServer
    private lateinit var model: ClientModel
    private val store = ViewModelStore()
    private val sent = AtomicInteger()
    @Volatile private var dropSend = false
    @Volatile private var peer: WebSocket? = null
    @Volatile private var acceptedText = ""
    @Volatile private var historyOverride: JsonObject? = null
    @Volatile private var fixtureTitle = "Fixture task"
    private val worktreeAdds = AtomicInteger()
    private val threadStarts = AtomicInteger()
    @Volatile private var dropWorktreeReply = false
    @Volatile private var dropThreadStartReply = false
    @Volatile private var createdWorktreePath = ""
    @Volatile private var createdTaskCwd = ""
    @Volatile private var createdTaskProject = ""
    @Volatile private var threadStartParams: JsonObject? = null
    private val app
        get() = ApplicationProvider.getApplicationContext<Application>()
    private val demo by lazy {
        InstrumentationRegistry.getArguments().getString("demo") == "true"
    }

    private fun demoPause(milliseconds: Long = 1500) {
        if (demo) SystemClock.sleep(milliseconds)
    }

    @Before
    fun setup() {
        server = MockWebServer()
        server.dispatcher =
            object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: RecordedRequest) =
                    MockResponse()
                        .withWebSocketUpgrade(
                            object : WebSocketListener() {
                                override fun onOpen(webSocket: WebSocket, response: Response) {
                                    peer = webSocket
                                }

                                override fun onMessage(ws: WebSocket, text: String) {
                                    val m = wire.parseToJsonElement(text).jsonObject
                                    val method = m.str("method")
                                    val params = m.map("params")
                                    val command =
                                        (params["command"] as? JsonArray)
                                            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                                            ?: emptyList()
                                    if (method.isEmpty() || method == "initialized") return
                                    val result =
                                        when (method) {
                                            "initialize" -> obj("codexHome" to s("/fixture"))
                                            "project/read" ->
                                                obj(
                                                    "project" to
                                                        obj(
                                                            "id" to s("project-1"),
                                                            "name" to s("Fixture project"),
                                                            "roots" to
                                                                JsonArray(
                                                                    listOf(
                                                                        obj(
                                                                            "path" to
                                                                                s("/fixture/repo")
                                                                        )
                                                                    )
                                                                ),
                                                        )
                                                )
                                            "thread/list" ->
                                                obj(
                                                    "data" to
                                                        JsonArray(
                                                            (when {
                                                                params.str("cwd").isEmpty() ||
                                                                    params.str("cwd") == "/fixture" ->
                                                                    listOf(
                                                                        obj(
                                                                            "id" to s("task-test"),
                                                                            "name" to s(fixtureTitle),
                                                                            "cwd" to s("/fixture"),
                                                                        )
                                                                    )
                                                                params.str("cwd") == createdTaskCwd ->
                                                                    listOf(
                                                                        obj(
                                                                            "id" to s("task-test"),
                                                                            "name" to s("Recovered task"),
                                                                            "cwd" to s(createdTaskCwd),
                                                                            "projectId" to s(createdTaskProject),
                                                                        )
                                                                    )
                                                                else -> emptyList()
                                                            }) + if (fixtureTitle == "Fixture task") emptyList() else listOf(
                                                                obj("id" to s("demo-2"), "name" to s("Review the Android build"),
                                                                    "cwd" to s("/fixture/remote-codex"), "status" to obj("type" to s("idle"))),
                                                                obj("id" to s("demo-3"), "name" to s("Tidy up the connection screen"),
                                                                    "cwd" to s("/fixture/remote-codex"), "status" to obj("type" to s("notLoaded"))),
                                                                obj("id" to s("demo-4"), "name" to s("Check the release notes"),
                                                                    "cwd" to s("/fixture/notes"), "status" to obj("type" to s("idle"))),
                                                            )
                                                        )
                                                )
                                            "thread/search" ->
                                                obj(
                                                    "data" to
                                                        JsonArray(
                                                            listOf(
                                                                obj(
                                                                    "thread" to
                                                                        obj(
                                                                            "id" to s("task-test"),
                                                                            "name" to
                                                                                s(fixtureTitle),
                                                                            "cwd" to s("/fixture"),
                                                                        ),
                                                                    "snippet" to s("fixture match"),
                                                                )
                                                            )
                                                        )
                                                )
                                            "thread/resume" ->
                                                obj(
                                                    "thread" to
                                                        obj(
                                                            "id" to s("task-test"),
                                                            "name" to s(fixtureTitle),
                                                        )
                                                )
                                            "thread/start" -> {
                                                threadStartParams = params
                                                threadStarts.incrementAndGet()
                                                createdTaskCwd = params.str("cwd")
                                                createdTaskProject = params.str("projectId")
                                                if (dropThreadStartReply) {
                                                    dropThreadStartReply = false
                                                    ws.cancel()
                                                    return
                                                }
                                                obj(
                                                    "thread" to
                                                        obj(
                                                            "id" to s("task-test"),
                                                            "projectId" to
                                                                (params["projectId"] ?: JsonNull),
                                                        )
                                                )
                                            }
                                            "command/exec" -> {
                                                when {
                                                    "symbolic-ref" in command ->
                                                        obj(
                                                            "exitCode" to JsonPrimitive(0),
                                                            "stdout" to
                                                                s("refs/remotes/origin/main\n"),
                                                        )
                                                    "rev-parse" in command ->
                                                        obj(
                                                            "exitCode" to JsonPrimitive(0),
                                                            "stdout" to s("a".repeat(40) + "\n"),
                                                        )
                                                    command.containsAll(
                                                        listOf("worktree", "add", "--detach")
                                                    ) -> {
                                                        createdWorktreePath = command[command.size - 2]
                                                        worktreeAdds.incrementAndGet()
                                                        if (dropWorktreeReply) {
                                                            dropWorktreeReply = false
                                                            ws.cancel()
                                                            return
                                                        }
                                                        obj("exitCode" to JsonPrimitive(0))
                                                    }
                                                    command.containsAll(
                                                        listOf("worktree", "list", "--porcelain")
                                                    ) ->
                                                        obj(
                                                            "exitCode" to JsonPrimitive(0),
                                                            "stdout" to
                                                                s(
                                                                    "worktree /fixture/repo\nHEAD ${"b".repeat(40)}\n\n" +
                                                                        if (createdWorktreePath.isEmpty()) ""
                                                                        else "worktree $createdWorktreePath\nHEAD ${"a".repeat(40)}\ndetached\n"
                                                                ),
                                                        )
                                                    else ->
                                                        obj("exitCode" to JsonPrimitive(0))
                                                }
                                            }
                                            "thread/turns/list" -> history()
                                            "turn/start",
                                            "turn/steer" -> {
                                                sent.incrementAndGet()
                                                acceptedText =
                                                    params.list("input").first().str("text")
                                                if (dropSend) {
                                                    ws.cancel()
                                                    return
                                                }
                                                obj("turn" to obj("id" to s("turn-test")))
                                            }
                                            else -> obj()
                                        }
                                    ws.send(obj("id" to m["id"], "result" to result).toString())
                                    if (method == "turn/start") {
                                        emit(
                                            ws,
                                            "turn/started",
                                            obj("turn" to obj("id" to s("turn-test"))),
                                        )
                                        emit(
                                            ws,
                                            "item/completed",
                                            obj(
                                                "turnId" to s("turn-test"),
                                                "item" to
                                                    obj(
                                                        "id" to s("u"),
                                                        "type" to s("userMessage"),
                                                        "content" to
                                                            JsonArray(
                                                                listOf(
                                                                    obj(
                                                                        "type" to s("text"),
                                                                        "text" to s(acceptedText),
                                                                    )
                                                                )
                                                            ),
                                                    ),
                                            ),
                                        )
                                        emit(
                                            ws,
                                            "item/agentMessage/delta",
                                            obj(
                                                "turnId" to s("turn-test"),
                                                "itemId" to s("a"),
                                                "delta" to s("Hello from Grace"),
                                            ),
                                        )
                                        emit(
                                            ws,
                                            "turn/completed",
                                            obj(
                                                "turn" to
                                                    obj(
                                                        "id" to s("turn-test"),
                                                        "status" to s("completed"),
                                                    )
                                            ),
                                        )
                                    }
                                }
                            }
                        )
            }
        server.start()
        runBlocking {
            val local = LocalStore(app)
            listOf("draft/new", "journal/new", "draft/task-test", "journal/task-test").forEach {
                local.remove(it)
            }
        }
        compose.runOnUiThread {
            model = ClientModel(app, "ws://127.0.0.1:${server.port}/rpc", "/fixture", true)
            store.put("fixture", model)
            compose.activity.setContent { RemoteTheme { App(model) } }
            model.saveCredential("fixture-credential-0000000000000000000000000000000000000")
        }
        compose.waitUntil(15000) { model.state.value.ready && model.state.value.tasks.isNotEmpty() }
        demoPause()
    }

    private fun history(): JsonObject {
        historyOverride?.let { return it }
        if (acceptedText.isEmpty()) return obj("data" to JsonArray(emptyList()))
        val user =
            obj(
                "id" to s("u"),
                "type" to s("userMessage"),
                "content" to JsonArray(listOf(obj("type" to s("text"), "text" to s(acceptedText)))),
            )
        val assistant =
            obj("id" to s("a"), "type" to s("agentMessage"), "text" to s("Hello from Grace"))
        val turn =
            obj(
                "id" to s("turn-test"),
                "status" to s("completed"),
                "items" to JsonArray(listOf(user, assistant)),
            )
        return obj("data" to JsonArray(listOf(turn)))
    }

    private fun emit(ws: WebSocket, method: String, params: JsonObject) {
        ws.send(
            obj(
                    "method" to s(method),
                    "params" to JsonObject(params + ("threadId" to s("task-test"))),
                )
                .toString()
        )
    }

    private fun longHistory(tallLastMessage: Boolean = false): JsonObject {
        val turns = (1..20).map { index ->
            val user = obj(
                "id" to s("user-$index"), "type" to s("userMessage"),
                "content" to JsonArray(listOf(obj("type" to s("text"),
                    "text" to s(if (index == 20) "Give the task list a little more breathing room."
                        else "Review the spacing in section $index.")))),
            )
            val text = if (index == 20) {
                (if (tallLastMessage) (1..35).joinToString("\n\n") { "Review note $it: Keep the layout clear and comfortable to read." }
                else "### A little room to breathe\n\nThe task list has cleaner spacing, quiet dividers, and consistent icons.\n\n") +
                    "Latest reply — ready for review."
            } else "Checkpoint $index\n\nThe spacing looks consistent. Keep the title easy to scan and the workspace details a little quieter."
            val command = obj("id" to s("command-$index"), "type" to s("commandExecution"),
                "command" to s("scripts/check"), "aggregatedOutput" to s("All checks passed."), "status" to s("completed"))
            obj("id" to s("history-$index"), "status" to s("completed"),
                "items" to JsonArray(listOf(user) + (if (index == 20) listOf(command) else emptyList()) +
                    obj("id" to s("reply-$index"), "type" to s("agentMessage"), "text" to s(text))))
        }
        return obj("data" to JsonArray(turns.reversed()))
    }

    private fun openLongHistory(tallLastMessage: Boolean = false) {
        fixtureTitle = "Polish the task list"
        historyOverride = longHistory(tallLastMessage)
        compose.runOnUiThread { model.home() }
        compose.waitUntil(5000) { model.state.value.tasks.first().str("name") == fixtureTitle }
        demoPause(2000)
        compose.onNodeWithText(fixtureTitle).performClick()
        compose.waitUntil(10000) { !model.state.value.busy && model.state.value.entries.size >= 40 }
        compose.waitForIdle()
    }

    private fun latestReply() = compose.onNodeWithText("Latest reply — ready for review.", substring = true)

    @Test
    fun polishedConversationOpensAtLatestAndKeepsReadingPosition() {
        openLongHistory()
        latestReply().assertIsDisplayed()
        demoPause(3000)
        compose.onNodeWithText("Command").performClick()
        compose.onNodeWithText("scripts/check", substring = true).assertIsDisplayed()
        demoPause(2000)
        compose.onNodeWithText("Command").performClick()
        compose.onNodeWithTag("timeline").performTouchInput { swipeDown() }
        compose.onNodeWithTag("timeline").performTouchInput { swipeDown() }
        compose.waitForIdle()
        val position = compose.onNodeWithTag("timeline").fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertTrue("Scrolled into older messages", position > 2f)
        demoPause(2000)
        emit(peer!!, "item/agentMessage/delta", obj("turnId" to s("history-20"),
            "itemId" to s("reply-20"), "delta" to s("\n\nA final spacing check is complete.")))
        compose.waitUntil(5000) { model.state.value.entries.last().text.endsWith("complete.") }
        compose.waitForIdle()
        val after = compose.onNodeWithTag("timeline").fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertEquals("Incoming content must not pull the reader to the bottom", position, after, .01f)
        demoPause(2000)
        compose.onNodeWithContentDescription("Tasks").performClick()
        compose.waitUntil { model.state.value.page == "home" }
        demoPause(1500)
        compose.onNodeWithText(fixtureTitle).performClick()
        compose.waitUntil(10000) { !model.state.value.busy && model.state.value.entries.size >= 40 }
        latestReply().assertIsDisplayed()
        demoPause(2500)
        compose.onNodeWithTag("composer").performTextInput("Looks good. Thanks!")
        demoPause(2000)
        compose.onNodeWithTag("send").performClick()
        compose.waitUntil(10000) { model.state.value.entries.any { it.text == "Hello from Grace" } }
        compose.onNodeWithText("Hello from Grace").assertIsDisplayed()
        assertEquals(1, sent.get())
        demoPause(2500)
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Scan setup QR").assertIsDisplayed()
        demoPause(2000)
        compose.onNodeWithContentDescription("Tasks").performClick()
        compose.onNodeWithContentDescription("New chat").performClick()
        compose.waitUntil { model.state.value.page == "chat" && model.state.value.thread == null }
        compose.onNodeWithText("What shall we work on?").assertIsDisplayed()
        demoPause(2000)
        compose.runOnUiThread {
            model.home()
            compose.activity.setContent { RemoteTheme(darkTheme = true) { App(model) } }
            // Match system chrome to this fixture-only theme override. Production
            // follows the system theme through MainActivity.enableEdgeToEdge().
            WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
        compose.waitUntil { model.state.value.page == "home" }
        demoPause(2000)
        compose.onNodeWithText(fixtureTitle).performClick()
        compose.waitUntil(10000) { !model.state.value.busy && model.state.value.entries.size >= 40 }
        latestReply().assertIsDisplayed()
        demoPause(3000)
    }

    @Test
    fun tallLatestMessageOpensAtItsEndAndFollowsGrowth() {
        openLongHistory(tallLastMessage = true)
        latestReply().assertIsDisplayed()
        emit(peer!!, "item/agentMessage/delta", obj("turnId" to s("history-20"),
            "itemId" to s("reply-20"), "delta" to s("\n\nStreaming finished here.")))
        compose.waitUntil(5000) { model.state.value.entries.last().text.endsWith("here.") }
        compose.onNodeWithText("Streaming finished here.", substring = true).assertIsDisplayed()
    }

    @After
    fun cleanup() {
        compose.runOnUiThread {
            model.foreground(false)
            store.clear()
        }
        server.shutdown()
    }

    @Test
    fun textChatStreamsAndCanReopen() {
        compose.onNodeWithContentDescription("New chat").performClick()
        compose.waitUntil { model.state.value.page == "chat" }
        demoPause()
        compose.onNodeWithTag("composer").performTextInput("What is running on Grace?")
        demoPause()
        compose.onNodeWithTag("send").performClick()
        compose.waitUntil(15000) { model.state.value.entries.any { it.text == "Hello from Grace" } }
        assertEquals(1, sent.get())
        compose.waitUntil(5000) {
            compose.onAllNodesWithText("Hello from Grace").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Hello from Grace").assertIsDisplayed()
        demoPause(2500)
        compose.runOnUiThread {
            model.home()
            model.openTask("task-test")
        }
        compose.waitUntil(5000) {
            model.state.value.thread == "task-test" && model.state.value.busy
        }
        compose.waitUntil(15000) {
            !model.state.value.busy &&
                model.state.value.entries.any { it.text == "Hello from Grace" }
        }
        compose.onNodeWithText("Hello from Grace").assertIsDisplayed()
        demoPause(3000)
    }

    @Test
    fun uncertainSubmissionIsNotReplayed() {
        dropSend = true
        compose.runOnUiThread { model.openTask("task-test") }
        compose.waitUntil(10000) { model.state.value.page == "chat" && !model.state.value.busy }
        demoPause()
        compose.onNodeWithTag("composer").performTextInput("Do this once")
        demoPause()
        compose.onNodeWithTag("send").performClick()
        compose.waitUntil(15000) { !model.state.value.busy && model.state.value.journal != null }
        demoPause(2500)
        compose.runOnUiThread { model.connect() }
        compose.waitUntil(15000) { model.state.value.ready && !model.state.value.busy }
        assertEquals(1, sent.get())
        assertNotNull(model.state.value.journal)
        compose.onNodeWithTag("send").assertIsNotEnabled()
        demoPause(2000)
    }

    @Test
    fun desktopResolutionRemovesPhoneApproval() {
        compose.runOnUiThread { model.openTask("task-test") }
        compose.waitUntil(10000) {
            !model.state.value.busy && model.state.value.thread == "task-test"
        }
        peer!!.send(
            obj(
                    "id" to JsonPrimitive(77),
                    "method" to s("item/commandExecution/requestApproval"),
                    "params" to
                        obj(
                            "threadId" to s("task-test"),
                            "turnId" to s("turn-test"),
                            "itemId" to s("cmd"),
                            "command" to s("printf hello"),
                        ),
                )
                .toString()
        )
        compose.waitUntil(5000) { model.state.value.decisions.size == 1 }
        demoPause(2500)
        compose.runOnUiThread { model.newChat() }
        compose.waitUntil { model.state.value.thread == null }
        demoPause()
        compose.runOnUiThread { model.openTask("task-test") }
        compose.waitUntil(10000) {
            !model.state.value.busy && model.state.value.decisions.size == 1
        }
        demoPause(2000)
        emit(peer!!, "serverRequest/resolved", obj("requestId" to JsonPrimitive(77)))
        compose.waitUntil(5000) { model.state.value.decisions.isEmpty() }
        demoPause(2000)
    }

    @Test
    fun draftSurvivesNewModel() {
        compose.runOnUiThread { model.newChat() }
        compose.waitUntil { model.state.value.page == "chat" }
        demoPause()
        compose.onNodeWithTag("composer").performTextInput("Keep this idea")
        compose.waitUntil(5000) {
            runBlocking { LocalStore(app).get("draft/new") } == "Keep this idea"
        }
        demoPause(2500)
        compose.runOnUiThread {
            store.clear()
            model = ClientModel(app, "ws://127.0.0.1:${server.port}/rpc", "/fixture", true)
            store.put("fixture", model)
            compose.activity.setContent { RemoteTheme { App(model) } }
            model.newChat()
        }
        compose.waitUntil { model.state.value.draft == "Keep this idea" }
        compose.onNodeWithTag("composer").assertTextContains("Keep this idea")
        demoPause(3500)
    }

    @Test
    fun selectedProjectCanRunInANewIsolatedWorktree() {
        compose.onNodeWithContentDescription("New chat").performClick()
        compose.waitUntil { model.state.value.page == "chat" }
        compose.runOnUiThread {
            model.updateNewTaskOptions(
                NewTaskOptions(
                    projectId = "project-1",
                    workingDirectory = "/fixture/repo",
                    executionTarget = ExecutionTarget.CurrentWorkspace,
                )
            )
        }
        compose.onNodeWithTag("workspace-current").assertIsSelected()
        compose.onNodeWithTag("workspace-new-worktree").performClick()
        compose.onNodeWithTag("workspace-new-worktree").assertIsSelected()
        compose.onNodeWithTag("composer").performTextInput("Change this in isolation")
        compose.onNodeWithTag("send").performClick()

        compose.waitUntil(15000) { sent.get() == 1 && model.state.value.journal == null }
        assertEquals(1, worktreeAdds.get())
        assertEquals("project-1", threadStartParams!!.str("projectId"))
        assertEquals(createdWorktreePath, threadStartParams!!.str("cwd"))
        assertTrue(createdWorktreePath.startsWith("/fixture/worktrees/remote-codex-"))
        assertTrue(createdWorktreePath.endsWith("/workspace"))
    }

    @Test
    fun selectedProjectCanUseItsCurrentWorkspaceWithoutGitMutation() {
        compose.onNodeWithContentDescription("New chat").performClick()
        compose.waitUntil { model.state.value.page == "chat" }
        compose.runOnUiThread {
            model.updateNewTaskOptions(
                NewTaskOptions(
                    projectId = "project-1",
                    workingDirectory = "/fixture/repo",
                    executionTarget = ExecutionTarget.CurrentWorkspace,
                )
            )
        }
        compose.onNodeWithTag("workspace-current").assertIsSelected()
        compose.onNodeWithTag("composer").performTextInput("Use the selected checkout")
        compose.onNodeWithTag("send").performClick()

        compose.waitUntil(15000) { sent.get() == 1 && model.state.value.journal == null }
        assertEquals(0, worktreeAdds.get())
        assertEquals("project-1", threadStartParams!!.str("projectId"))
        assertEquals("/fixture/repo", threadStartParams!!.str("cwd"))
    }

    @Test
    fun uncertainWorktreeCreationIsInspectedAndNotRepeated() {
        dropWorktreeReply = true
        compose.onNodeWithContentDescription("New chat").performClick()
        compose.waitUntil { model.state.value.page == "chat" }
        compose.runOnUiThread {
            model.updateNewTaskOptions(
                NewTaskOptions(
                    projectId = "project-1",
                    workingDirectory = "/fixture/repo",
                    executionTarget = ExecutionTarget.NewWorktree,
                )
            )
        }
        compose.onNodeWithTag("composer").performTextInput("Recover this setup once")
        compose.onNodeWithTag("send").performClick()
        compose.waitUntil(15000) {
            !model.state.value.busy &&
                model.state.value.journal?.str("stage") == "creatingWorktree"
        }
        assertEquals(1, worktreeAdds.get())

        compose.runOnUiThread { model.connect() }
        compose.waitUntil(20000) {
            model.state.value.ready &&
                sent.get() == 1 &&
                model.state.value.journal == null
        }
        assertEquals("An uncertain worktree mutation must not be replayed", 1, worktreeAdds.get())
        assertEquals(createdWorktreePath, threadStartParams!!.str("cwd"))
    }

    @Test
    fun uncertainTaskCreationIsFoundAndNotRepeated() {
        dropThreadStartReply = true
        compose.onNodeWithContentDescription("New chat").performClick()
        compose.waitUntil { model.state.value.page == "chat" }
        compose.runOnUiThread {
            model.updateNewTaskOptions(
                NewTaskOptions(
                    projectId = "project-1",
                    workingDirectory = "/fixture/repo",
                    executionTarget = ExecutionTarget.CurrentWorkspace,
                )
            )
        }
        compose.onNodeWithTag("composer").performTextInput("Create this task once")
        compose.onNodeWithTag("send").performClick()
        compose.waitUntil(15000) {
            !model.state.value.busy &&
                model.state.value.journal?.str("stage") == "creatingTask"
        }
        assertEquals(1, threadStarts.get())

        compose.runOnUiThread { model.connect() }
        compose.waitUntil(20000) {
            model.state.value.ready &&
                sent.get() == 1 &&
                model.state.value.journal == null
        }
        assertEquals("An uncertain task mutation must not be replayed", 1, threadStarts.get())
        assertEquals("task-test", model.state.value.thread)
    }
}
