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
    private val prepared = AtomicInteger()
    private val modelLists = AtomicInteger()
    @Volatile private var dropSend = false
    @Volatile private var fastModelAvailable = true
    @Volatile private var peer: WebSocket? = null
    @Volatile private var acceptedText = ""
    @Volatile private var lastThreadStartParams: JsonObject? = null
    @Volatile private var lastTurnStartParams: JsonObject? = null
    @Volatile private var historyOverride: JsonObject? = null
    @Volatile private var fixtureTitle = "Fixture task"
    @Volatile private var lastThreadStart: JsonObject? = null
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
                                            "project/list" ->
                                                if (params.str("cursor").isEmpty())
                                                    obj(
                                                        "data" to
                                                            JsonArray(
                                                                listOf(
                                                                    project(
                                                                        "project-remote",
                                                                        "Remote Codex",
                                                                        "/fixture/remote-codex",
                                                                    )
                                                                )
                                                            ),
                                                        "nextCursor" to s("projects-2"),
                                                    )
                                                else
                                                    obj(
                                                        "data" to
                                                            JsonArray(
                                                                listOf(
                                                                    project(
                                                                        "project-notes",
                                                                        "Notes",
                                                                        "/fixture/notes",
                                                                    )
                                                                )
                                                            )
                                                    )
                                            "project/read" -> {
                                                val projectId = params.str("projectId")
                                                val (name, root) =
                                                    when (projectId) {
                                                        "project-remote" ->
                                                            "Remote Codex" to "/fixture/remote-codex"
                                                        "project-notes" -> "Notes" to "/fixture/notes"
                                                        else -> "Fixture project" to "/fixture/repo"
                                                    }
                                                obj("project" to project(projectId, name, root))
                                            }
                                            "model/list" -> {
                                                modelLists.incrementAndGet()
                                                modelCatalog()
                                            }
                                            "thread/list" ->
                                                obj(
                                                    "data" to
                                                        JsonArray(
                                                            if (
                                                                createdTaskCwd.isNotEmpty() &&
                                                                    params.str("cwd") == createdTaskCwd
                                                            )
                                                                listOf(
                                                                    obj(
                                                                        "id" to s("task-test"),
                                                                        "name" to s("Recovered task"),
                                                                        "cwd" to s(createdTaskCwd),
                                                                        "projectId" to
                                                                            (createdTaskProject
                                                                                    .takeIf(String::isNotEmpty)
                                                                                    ?.let(::s)
                                                                                ?: JsonNull),
                                                                    )
                                                                )
                                                            else fixtureTasks(params)
                                                        )
                                                )
                                            "thread/search" ->
                                                obj(
                                                    "data" to
                                                        JsonArray(
                                                            fixtureTasks(obj()).map { task ->
                                                                obj(
                                                                    "thread" to task,
                                                                    "snippet" to s("fixture match"),
                                                                )
                                                            }
                                                        )
                                                )
                                            "thread/resume" ->
                                                obj(
                                                    "thread" to
                                                        obj(
                                                            "id" to s(params.str("threadId")),
                                                            "name" to
                                                                s(
                                                                    if (
                                                                        params.str("threadId") ==
                                                                            "project-task"
                                                                    )
                                                                        "Remote Codex project task"
                                                                    else fixtureTitle
                                                                ),
                                                            "model" to s("gpt-fixture"),
                                                            "reasoningEffort" to s("low"),
                                                        )
                                                )
                                            "thread/start" -> {
                                                lastThreadStart = params
                                                lastThreadStartParams = params
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
                                                            "model" to params["model"],
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
                                                        demoPause(1800)
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
                                                    else -> {
                                                        if (
                                                            command.firstOrNull() == "mkdir" &&
                                                                command.lastOrNull()?.startsWith(
                                                                    "/home/agent/Documents/RemoteCodex/"
                                                                ) == true
                                                        )
                                                            prepared.incrementAndGet()
                                                        obj("exitCode" to JsonPrimitive(0))
                                                    }
                                                }
                                            }
                                            "thread/turns/list" -> history()
                                            "turn/start",
                                            "turn/steer" -> {
                                                if (method == "turn/start") lastTurnStartParams = params
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
            listOf(
                    "draft/new",
                    "journal/new",
                    "options/new",
                    "draft/task-test",
                    "journal/task-test",
                    "draft/project-task",
                    "journal/project-task",
                )
                .forEach { local.remove(it) }
        }
        compose.runOnUiThread {
            model = ClientModel(app, "ws://127.0.0.1:${server.port}/rpc", "/fixture", true)
            store.put("fixture", model)
            compose.activity.setContent { RemoteTheme { App(model) } }
            model.saveCredential("fixture-credential-0000000000000000000000000000000000000")
        }
        compose.waitUntil(15000) {
            model.state.value.ready &&
                model.state.value.projects.size == 2 &&
                model.state.value.tasks.isNotEmpty() &&
                model.state.value.modelCatalogStatus == ModelCatalogStatus.Ready
        }
        demoPause()
    }

    private fun project(id: String, name: String, root: String) =
        obj(
            "id" to s(id),
            "name" to s(name),
            "roots" to JsonArray(listOf(obj("path" to s(root)))),
        )

    private fun fixtureTasks(params: JsonObject): List<JsonObject> {
        val tasks =
            listOf(
                obj(
                    "id" to s("task-test"),
                    "name" to s(fixtureTitle),
                    "cwd" to s("/fixture"),
                    "projectId" to JsonNull,
                ),
                obj(
                    "id" to s("project-task"),
                    "name" to s("Remote Codex project task"),
                    "cwd" to s("/fixture/remote-codex"),
                    "projectId" to s("project-remote"),
                    "status" to obj("type" to s("idle")),
                ),
            ) +
                if (fixtureTitle == "Fixture task") emptyList()
                else
                    listOf(
                        obj(
                            "id" to s("demo-2"),
                            "name" to s("Review the Android build"),
                            "cwd" to s("/fixture/remote-codex"),
                            "projectId" to s("project-remote"),
                            "status" to obj("type" to s("idle")),
                        ),
                        obj(
                            "id" to s("demo-3"),
                            "name" to s("Tidy up the connection screen"),
                            "cwd" to s("/fixture/remote-codex"),
                            "projectId" to s("project-remote"),
                            "status" to obj("type" to s("notLoaded")),
                        ),
                        obj(
                            "id" to s("demo-4"),
                            "name" to s("Check the release notes"),
                            "cwd" to s("/fixture/notes"),
                            "projectId" to s("project-notes"),
                            "status" to obj("type" to s("idle")),
                        ),
                    )
        return tasks.filter { task ->
            val cwdMatches = params.str("cwd").let { it.isEmpty() || it == task.str("cwd") }
            val projectMatches =
                if (!params.containsKey("projectId")) true
                else if (params["projectId"] is JsonNull) task["projectId"] is JsonNull
                else params.str("projectId") == task.str("projectId")
            cwdMatches && projectMatches
        }
    }

    private fun modelCatalog() =
        obj(
            "data" to
                JsonArray(
                    listOf(
                        obj(
                            "model" to s("gpt-fixture"),
                            "displayName" to s("Fixture Default"),
                            "description" to s("Default fixture model"),
                            "defaultReasoningEffort" to s("low"),
                            "supportedReasoningEfforts" to
                                JsonArray(
                                    listOf(
                                        obj(
                                            "reasoningEffort" to s("low"),
                                            "description" to s("Quick fixture reasoning"),
                                        ),
                                        obj(
                                            "reasoningEffort" to s("high"),
                                            "description" to s("Thorough fixture reasoning"),
                                        ),
                                    )
                                ),
                            "isDefault" to JsonPrimitive(true),
                        )
                    ) +
                        if (fastModelAvailable)
                            listOf(
                                obj(
                                    "model" to s("gpt-fixture-fast"),
                                    "displayName" to s("Fixture Fast"),
                                    "description" to s("Fast fixture model"),
                                    "defaultReasoningEffort" to s("medium"),
                                    "supportedReasoningEfforts" to
                                        JsonArray(
                                            listOf(
                                                obj(
                                                    "reasoningEffort" to s("medium"),
                                                    "description" to s("Balanced fixture reasoning"),
                                                )
                                            )
                                        ),
                                    "isDefault" to JsonPrimitive(false),
                                )
                            )
                        else emptyList()
                )
        )

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
        compose.onNodeWithText("Check for updates").performScrollTo().assertIsDisplayed()
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
        assertTrue(lastThreadStart?.get("projectId") is JsonNull)
        assertEquals(1, prepared.get())
        compose.waitUntil(5000) {
            compose.onAllNodesWithText("Hello from Grace").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Hello from Grace").assertIsDisplayed()
        assertFalse(lastThreadStartParams!!.containsKey("model"))
        assertFalse(lastTurnStartParams!!.containsKey("model"))
        assertFalse(lastTurnStartParams!!.containsKey("effort"))
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
    fun modelControlsUseCatalogAndRefreshUnsupportedSelection() {
        compose.onNodeWithContentDescription("New chat").performClick()
        compose.waitUntil { model.state.value.page == "chat" }
        demoPause()

        compose.onNodeWithTag("model-selector").performClick()
        demoPause()
        compose.onNodeWithText("Fixture Fast").performClick()
        assertEquals("gpt-fixture-fast", model.state.value.newTaskOptions.model)
        demoPause()

        compose.onNodeWithTag("reasoning-selector").performClick()
        compose.onNodeWithText("medium").assertExists()
        compose.onNodeWithText("low").assertDoesNotExist()
        demoPause()
        compose.onNodeWithText("medium").performClick()
        assertEquals("medium", model.state.value.newTaskOptions.reasoningEffort)
        demoPause()

        compose.onNodeWithTag("composer").performTextInput("Use the selected model")
        demoPause()
        compose.onNodeWithTag("send").performClick()
        compose.waitUntil(15000) { model.state.value.entries.any { it.text == "Hello from Grace" } }
        assertEquals("gpt-fixture-fast", lastThreadStartParams!!.str("model"))
        assertEquals("gpt-fixture-fast", lastTurnStartParams!!.str("model"))
        assertEquals("medium", lastTurnStartParams!!.str("effort"))
        demoPause(2500)

        val previousLists = modelLists.get()
        fastModelAvailable = false
        compose.onNodeWithContentDescription("Refresh models").performClick()
        compose.waitUntil(5000) {
            modelLists.get() > previousLists &&
                model.state.value.modelCatalogStatus == ModelCatalogStatus.Ready
        }
        assertNull(model.state.value.newTaskOptions.model)
        assertNull(model.state.value.newTaskOptions.reasoningEffort)
        compose.onNodeWithText("Unsupported overrides were cleared", substring = true).assertExists()
        demoPause(3000)
    }

    @Test
    fun uncertainSubmissionIsNotReplayed() {
        dropSend = true
        val catalogLoadsBeforeReconnect = modelLists.get()
        compose.runOnUiThread { model.openTask("task-test") }
        compose.waitUntil(10000) { model.state.value.page == "chat" && !model.state.value.busy }
        demoPause()
        compose.onNodeWithTag("composer").performTextInput("Do this once")
        demoPause()
        compose.onNodeWithTag("send").performClick()
        compose.waitUntil(15000) { !model.state.value.busy && model.state.value.journal != null }
        demoPause(2500)
        compose.runOnUiThread { model.connect() }
        compose.waitUntil(15000) {
            model.state.value.ready &&
                !model.state.value.busy &&
                model.state.value.modelCatalogStatus == ModelCatalogStatus.Ready
        }
        assertEquals(1, sent.get())
        assertTrue(modelLists.get() > catalogLoadsBeforeReconnect)
        assertNotNull(model.state.value.journal)
        compose.onNodeWithTag("send").assertIsNotEnabled()
        demoPause(2000)
    }

    @Test
    fun projectsAndChatsFilterTaskBrowser() {
        assertEquals(listOf("Remote Codex", "Notes"), model.state.value.projects.map { it.name })
        demoPause(2000)

        compose.onNode(hasText("Remote Codex") and hasClickAction()).performClick()
        compose.waitUntil(5000) {
            model.state.value.projectFilter == TaskProjectFilter.Project("project-remote") &&
                model.state.value.tasks.map { it.str("id") } == listOf("project-task")
        }
        compose.onNodeWithText("Remote Codex project task").assertIsDisplayed()
        compose.onNodeWithText(fixtureTitle).assertDoesNotExist()
        demoPause(2000)

        compose.onNodeWithText("Chats").performClick()
        compose.waitUntil(5000) {
            model.state.value.projectFilter == TaskProjectFilter.Projectless &&
                model.state.value.tasks.map { it.str("id") } == listOf("task-test")
        }
        compose.onNodeWithText(fixtureTitle).assertIsDisplayed()
        compose.onNodeWithText("Remote Codex project task").assertDoesNotExist()
        demoPause(2000)

        compose.onNodeWithContentDescription("New chat").performClick()
        compose.waitUntil { model.state.value.page == "chat" }
        demoPause(2000)
        compose.onNodeWithTag("project-selector").performClick()
        compose.onNodeWithText("Remote Codex").assertIsDisplayed()
        demoPause(2000)
        compose.onNodeWithText("Remote Codex").performClick()
        compose.waitUntil {
            model.state.value.newTaskOptions.projectId == "project-remote" &&
                model.state.value.newTaskOptions.workingDirectory == "/fixture/remote-codex"
        }
        demoPause(2000)
        compose.onNodeWithTag("composer").performTextInput("Review the project status")
        demoPause(2000)
        compose.onNodeWithTag("send").performClick()
        compose.waitUntil(15000) {
            sent.get() == 1 &&
                !model.state.value.busy &&
                model.state.value.journal == null &&
                model.state.value.entries.any { it.text == "Hello from Grace" }
        }
        compose.onNodeWithText("Hello from Grace").assertIsDisplayed()
        assertEquals("project-remote", lastThreadStart?.str("projectId"))
        assertEquals("/fixture/remote-codex", lastThreadStart?.str("cwd"))
        assertEquals(0, prepared.get())
        demoPause(3000)
    }

    @Test
    fun selectedProjectSurvivesDraftRecreationAndStartsInItsRoot() {
        compose.onNodeWithContentDescription("New chat").performClick()
        compose.waitUntil { model.state.value.page == "chat" }
        compose.onNodeWithTag("project-selector").assertTextContains("No project").performClick()
        compose.onNodeWithText("Remote Codex").performClick()
        compose.waitUntil {
            model.state.value.newTaskOptions.projectId == "project-remote" &&
                model.state.value.newTaskOptions.workingDirectory == "/fixture/remote-codex"
        }
        compose.onNodeWithTag("composer").performTextInput("Review this project")
        compose.waitUntil(5000) {
            runBlocking {
                LocalStore(app).get("draft/new") == "Review this project" &&
                    LocalStore(app).get("options/new").contains("project-remote")
            }
        }

        compose.runOnUiThread {
            store.clear()
            model = ClientModel(app, "ws://127.0.0.1:${server.port}/rpc", "/fixture", true)
            store.put("fixture", model)
            compose.activity.setContent { RemoteTheme { App(model) } }
            model.newChat()
            model.connect()
        }
        compose.waitUntil(15000) {
            model.state.value.ready &&
                model.state.value.projects.size == 2 &&
                model.state.value.draft == "Review this project" &&
                model.state.value.newTaskOptions.projectId == "project-remote"
        }
        compose.onNodeWithTag("project-selector").assertTextContains("Remote Codex")
        compose.onNodeWithTag("send").performClick()
        compose.waitUntil(15000) { sent.get() == 1 && model.state.value.journal == null }

        assertEquals("project-remote", lastThreadStart?.str("projectId"))
        assertEquals("/fixture/remote-codex", lastThreadStart?.str("cwd"))
        assertEquals(0, prepared.get())
        assertEquals("", runBlocking { LocalStore(app).get("options/new") })
    }

    @Test
    fun projectJournalReconnectRecoversWithoutReplayingMutation() {
        val journal =
            obj(
                "operation" to s("fixture-operation"),
                "text" to s("Recover this project task"),
                "stage" to s("creatingTask"),
                "cwd" to s("/fixture/remote-codex"),
                "sourceCwd" to s("/fixture/remote-codex"),
                "executionTarget" to s(ExecutionTarget.CurrentWorkspace.name),
                "projectId" to s("project-remote"),
            )
        runBlocking {
            LocalStore(app).put("draft/new", "Recover this project task")
            LocalStore(app).put("journal/new", journal.toString())
        }

        compose.runOnUiThread { model.newChat() }
        compose.waitUntil(15000) {
            model.state.value.thread == "project-task" &&
                !model.state.value.busy &&
                sent.get() == 1 &&
                model.state.value.journal == null
        }

        assertNull(lastThreadStart)
        assertEquals("", runBlocking { LocalStore(app).get("journal/new") })
        assertEquals("", runBlocking { LocalStore(app).get("journal/project-task") })
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
        demoPause()
        compose.runOnUiThread {
            model.updateNewTaskOptions(
                NewTaskOptions(
                    projectId = "project-remote",
                    workingDirectory = "/fixture/remote-codex",
                    executionTarget = ExecutionTarget.CurrentWorkspace,
                )
            )
        }
        compose.onNodeWithTag("workspace-current").assertIsSelected()
        demoPause(2200)
        compose.onNodeWithTag("workspace-new-worktree").performClick()
        compose.onNodeWithTag("workspace-new-worktree").assertIsSelected()
        demoPause(1800)
        compose.onNodeWithTag("composer").performTextInput("Change this in isolation")
        demoPause(2200)
        compose.onNodeWithTag("send").performClick()

        compose.waitUntil(15000) {
            sent.get() == 1 &&
                model.state.value.journal == null &&
                model.state.value.entries.any { it.text == "Hello from Grace" }
        }
        compose.onNodeWithText("Hello from Grace").assertIsDisplayed()
        assertEquals(1, worktreeAdds.get())
        assertEquals("project-remote", threadStartParams!!.str("projectId"))
        assertEquals(createdWorktreePath, threadStartParams!!.str("cwd"))
        assertTrue(createdWorktreePath.startsWith("/fixture/worktrees/remote-codex-"))
        assertTrue(createdWorktreePath.endsWith("/workspace"))
        demoPause(3500)
    }

    @Test
    fun selectedProjectCanUseItsCurrentWorkspaceWithoutGitMutation() {
        compose.onNodeWithContentDescription("New chat").performClick()
        compose.waitUntil { model.state.value.page == "chat" }
        compose.runOnUiThread {
            model.updateNewTaskOptions(
                NewTaskOptions(
                    projectId = "project-remote",
                    workingDirectory = "/fixture/remote-codex",
                    executionTarget = ExecutionTarget.CurrentWorkspace,
                )
            )
        }
        compose.onNodeWithTag("workspace-current").assertIsSelected()
        compose.onNodeWithTag("composer").performTextInput("Use the selected checkout")
        compose.onNodeWithTag("send").performClick()

        compose.waitUntil(15000) { sent.get() == 1 && model.state.value.journal == null }
        assertEquals(0, worktreeAdds.get())
        assertEquals("project-remote", threadStartParams!!.str("projectId"))
        assertEquals("/fixture/remote-codex", threadStartParams!!.str("cwd"))
    }

    @Test
    fun uncertainWorktreeCreationIsInspectedAndNotRepeated() {
        dropWorktreeReply = true
        compose.onNodeWithContentDescription("New chat").performClick()
        compose.waitUntil { model.state.value.page == "chat" }
        compose.runOnUiThread {
            model.updateNewTaskOptions(
                NewTaskOptions(
                    projectId = "project-remote",
                    workingDirectory = "/fixture/remote-codex",
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
                    projectId = "project-remote",
                    workingDirectory = "/fixture/remote-codex",
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
