package dev.codexops.client

import android.app.Application
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.codexops.core.*
import java.io.File
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.rules.TestName
import org.junit.Assert.*

class AppTest {
    @Test
    fun taskSwipeMarksUnread() {
        compose.onNodeWithText("Fixture task").performTouchInput { swipeRight() }
        compose.waitUntil(5000) { model.state.value.chatActivity["task-test"]?.unread == true }
        compose.onNodeWithContentDescription("Unread reply").assertExists()
        // Repeating the same gesture does not toggle the task back to read.
        compose.onNodeWithTag("task-row-task-test").performTouchInput { swipeRight() }
        compose.onNodeWithContentDescription("Unread reply").assertExists()
        compose.runOnUiThread {
            store.clear()
            model = ClientModel(app, "ws://127.0.0.1:${server.port}/rpc", "/fixture", true)
            store.put("fixture", model)
            compose.activity.setContent { RemoteTheme { App(model) } }
            model.foreground(true)
        }
        compose.waitUntil(15000) { model.state.value.ready && model.state.value.tasks.isNotEmpty() }
        compose.waitUntil(5000) { model.state.value.chatActivity["task-test"]?.unread == true }
        compose.onNodeWithContentDescription("Unread reply").assertExists()
        compose.onNodeWithText("Fixture task").performClick()
        compose.waitUntil(10000) { !model.state.value.busy && model.state.value.thread == "task-test" }
        assertFalse(model.state.value.chatActivity["task-test"]?.unread == true)
        compose.runOnUiThread { model.home() }
        compose.waitUntil(5000) { model.state.value.page == "home" && !model.state.value.listLoading }
        compose.onNodeWithContentDescription("Unread reply").assertDoesNotExist()
    }

    @Test
    fun taskSwipesArchiveUndoAndUnarchive() {
        val row = compose.onNodeWithTag("task-row-task-test")
        // An incomplete drag must spring back without sending anything.
        row.performTouchInput {
            swipe(center, Offset(center.x - width * 0.12f, center.y), 300)
        }
        compose.waitForIdle()
        assertTrue(archiveMutations.isEmpty())
        row.performTouchInput { swipeLeft() }
        compose.waitUntil(5000) { model.state.value.taskNotice?.message == "Task archived" && !model.state.value.listLoading }
        row.assertDoesNotExist()
        assertEquals(listOf("thread/archive"), archiveMutations.map { it.str("method") })
        compose.onNodeWithText("Undo").performClick()
        compose.waitUntil(5000) { model.state.value.tasks.any { it.str("id") == "task-test" } && !model.state.value.listLoading }
        row.assertExists()
        row.performTouchInput { swipeLeft() }
        compose.waitUntil(5000) { model.state.value.tasks.none { it.str("id") == "task-test" } && !model.state.value.listLoading }
        compose.runOnUiThread { model.settings(); model.openArchives() }
        compose.waitUntil(5000) { model.state.value.tasks.any { it.str("id") == "task-test" } && !model.state.value.listLoading }
        row.performTouchInput { swipeRight() }
        compose.waitUntil(5000) { model.state.value.chatActivity["task-test"]?.unread == true }
        row.performTouchInput { swipeLeft() }
        compose.waitUntil(5000) { model.state.value.taskNotice?.message == "Task unarchived" && !model.state.value.listLoading }
        row.assertDoesNotExist()
        assertEquals(listOf("thread/archive", "thread/unarchive", "thread/archive", "thread/unarchive"), archiveMutations.map { it.str("method") })
        assertTrue(archiveMutations.all { it.map("params").str("threadId") == "task-test" })
    }

    @Test
    fun taskLongPressCopiesLinkAndAccessibleActionsWork() {
        val row = compose.onNodeWithTag("task-row-task-test")
        row.performTouchInput { longClick() }
        compose.runOnUiThread {
            assertEquals("codex://threads/task-test", app.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text.toString())
        }
        assertEquals("home", model.state.value.page)
        assertTrue(archiveMutations.isEmpty())
        val unreadActions = row.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.CustomActions]
        compose.runOnUiThread {
            val actions = unreadActions
            assertEquals(setOf("Archive", "Mark unread", "Copy deep link"), actions.map { it.label }.toSet())
            actions.single { it.label == "Mark unread" }.action()
        }
        compose.waitUntil(5000) { model.state.value.chatActivity["task-test"]?.unread == true }
        val archiveAction = row.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.CustomActions].single { it.label == "Archive" }
        compose.runOnUiThread {
            archiveAction.action()
        }
        compose.waitUntil(5000) { model.state.value.taskNotice?.message == "Task archived" }
        assertEquals(1, archiveMutations.size)
    }

    @Test
    fun uncertainArchiveDoesNotReplayOnReconnect() {
        dropArchiveReply = true
        compose.onNodeWithTag("task-row-task-test").performTouchInput { swipeLeft() }
        compose.waitUntil(5000) { "task-test" in model.state.value.uncertainTaskActions }
        compose.onNodeWithTag("task-row-task-test").assertExists()
        assertNull(model.state.value.taskNotice)
        assertTrue(model.state.value.error.orEmpty().contains("outcome unknown"))
        compose.onNodeWithTag("task-row-task-test").performTouchInput { swipeLeft() }
        compose.runOnUiThread { model.connect() }
        compose.waitUntil(15000) { model.state.value.ready && !model.state.value.listLoading && model.state.value.tasks.none { it.str("id") == "task-test" } }
        assertEquals(1, archiveMutations.size)
        compose.runOnUiThread { model.settings(); model.openArchives() }
        compose.waitUntil(5000) { model.state.value.tasks.any { it.str("id") == "task-test" } && !model.state.value.listLoading }
        assertFalse("task-test" in model.state.value.uncertainTaskActions)
        assertEquals(1, archiveMutations.size)
    }

    @Test
    fun rejectedArchiveKeepsTaskAndOffersNoUndo() {
        rejectArchive = true
        compose.onNodeWithTag("task-row-task-test").performTouchInput { swipeLeft() }
        compose.waitUntil(5000) { model.state.value.error?.contains("rejected the archive") == true }
        compose.onNodeWithTag("task-row-task-test").assertExists()
        assertNull(model.state.value.taskNotice)
        assertTrue(model.state.value.uncertainTaskActions.isEmpty())
        assertEquals(1, archiveMutations.size)
    }

    @Test
    fun taskGesturesOnCompactScreenRespectCancellationAndPhysicalDirection() {
        compose.runOnUiThread {
            compose.activity.setContent {
                RemoteTheme {
                    CompositionLocalProvider(
                        LocalLayoutDirection provides LayoutDirection.Rtl,
                    ) {
                        Box(Modifier.size(320.dp, 440.dp)) { App(model) }
                    }
                }
            }
        }
        val row = compose.onNodeWithTag("task-row-task-test")
        row.assertIsDisplayed()
        row.performTouchInput {
            down(center)
            moveTo(Offset(width * 0.05f, center.y), 400)
            cancel()
        }
        row.performTouchInput { swipe(center, Offset(center.x + 4, center.y - 24), 300) }
        compose.waitForIdle()
        assertTrue(archiveMutations.isEmpty())
        assertTrue(model.state.value.chatActivity.values.none { it.unread })
        row.performTouchInput { swipeRight() }
        compose.waitUntil(5000) { model.state.value.chatActivity["task-test"]?.unread == true }
        row.performTouchInput { swipeLeft() }
        compose.waitUntil(5000) { model.state.value.taskNotice?.message == "Task archived" }
        assertEquals("thread/archive", archiveMutations.single().str("method"))
    }

    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val testName = TestName()
    private val landscapeScreen
        get() = testName.methodName == "landscapeConversationLeavesRoomForMessagesAndDraft"
    private lateinit var server: MockWebServer
    private lateinit var model: ClientModel
    private val store = ViewModelStore()
    private val browserCalls = CopyOnWriteArrayList<Pair<String, JsonObject>>()
    @Volatile private var browserResponse: ((String, JsonObject) -> JsonObject?)? = null
    private val sent = AtomicInteger()
    private val archivedTaskIds = ConcurrentHashMap.newKeySet<String>()
    private val archiveMutations = CopyOnWriteArrayList<JsonObject>()
    @Volatile private var dropArchiveReply = false
    @Volatile private var rejectArchive = false
    private val prepared = AtomicInteger()
    private val modelLists = AtomicInteger()
    private val browserRequests = CopyOnWriteArrayList<JsonObject>()
    private val workspaceMetadataReads = AtomicInteger()
    private val workspaceMetadataRejections = AtomicInteger()
    private val invalidDirectoryProbes = AtomicInteger()
    @Volatile private var dropSend = false
    @Volatile private var dropWrite = false
    @Volatile private var fastModelAvailable = true
    @Volatile private var rejectModelList = false
    @Volatile private var peer: WebSocket? = null
    @Volatile private var acceptedText = ""
    @Volatile private var acceptedInput = JsonArray(emptyList())
    private val remoteFiles = ConcurrentHashMap<String, String>()
    @Volatile private var lastThreadStartParams: JsonObject? = null
    @Volatile private var lastTurnStartParams: JsonObject? = null
    @Volatile private var historyOverride: JsonObject? = null
    @Volatile private var emptyTaskList = false
    @Volatile private var holdTaskList = false
    private val heldTaskLists = CopyOnWriteArrayList<JsonObject>()
    @Volatile private var fixtureTitle = "Fixture task"
    private val recencyRequests = CopyOnWriteArrayList<JsonObject>()
    @Volatile private var lastThreadStart: JsonObject? = null
    private val worktreeAdds = AtomicInteger()
    private val threadStarts = AtomicInteger()
    private val environmentSetups = AtomicInteger()
    private var reportCoverOverride = false
    @Volatile private var dropWorktreeReply = false
    @Volatile private var dropThreadStartReply = false
    @Volatile private var rejectWorkspaceMetadata = false
    @Volatile private var createdWorktreePath = ""
    @Volatile private var createdTaskCwd = ""
    @Volatile private var createdTaskProject = ""
    @Volatile private var threadStartParams: JsonObject? = null
    @Volatile private var askPlanQuestion = false
    @Volatile private var planText = "1. Inspect the code\n2. Make the change"
    private val turnRequests = CopyOnWriteArrayList<JsonObject>()
    private val queueItems = CopyOnWriteArrayList<JsonObject>()
    private val queueMutations = CopyOnWriteArrayList<JsonObject>()
    private val steerRequests = CopyOnWriteArrayList<JsonObject>()
    private val queueIds = AtomicInteger()
    @Volatile private var holdTurnOpen = false
    @Volatile private var dropQueueMethod: String? = null
    @Volatile private var rejectSteer = false
    @Volatile private var rejectQueueRead = false
    private val userInputResponses = CopyOnWriteArrayList<JsonObject>()
    private val app
        get() = ApplicationProvider.getApplicationContext<Application>()
    private val demo by lazy {
        InstrumentationRegistry.getArguments().getString("demo") == "true"
    }
    private val coverScreen by lazy {
        InstrumentationRegistry.getArguments().getString("coverScreen") == "true"
    }

    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(
                InstrumentationRegistry.getInstrumentation().uiAutomation
                    .executeShellCommand(command)
            )
            .use { it.readBytes() }
    }

    private fun demoPause(milliseconds: Long = 1500) {
        if (demo) SystemClock.sleep(milliseconds)
    }

    @Before
    fun setup() {
        reportCoverOverride = testName.methodName in setOf("systemScreenshotOffersReportWithTheCapturedWindow", "commandTrayCoverLargeTextKeepsActionsReachable")
        if (coverScreen || landscapeScreen || reportCoverOverride) {
            shell(if (landscapeScreen) "wm size 2992x1224" else "wm size 1080x1272")
            shell(when {
                landscapeScreen -> "wm density 480"
                reportCoverOverride -> "wm density 360"
                else -> "wm density 420"
            })
            SystemClock.sleep(500)
            compose.activityRule.scenario.recreate()
            compose.waitForIdle()
        }
        // The rule starts MainActivity with its production model before this fixture is installed.
        // Dispose that model so a credential left by another test cannot keep reconnecting behind
        // the mock-backed UI and starve timing-sensitive instrumentation work.
        compose.runOnUiThread { compose.activity.viewModelStore.clear() }
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
                                    if (method.isEmpty()) {
                                        if (m["id"] == JsonPrimitive(88))
                                            userInputResponses.add(m.map("result"))
                                        return
                                    }
                                    if (method == "initialized") return
                                    if (method == "thread/list" && holdTaskList) {
                                        heldTaskLists.add(m)
                                        return
                                    }
                                    if (method in setOf("thread/archive", "thread/unarchive")) {
                                        archiveMutations.add(m)
                                        if (rejectArchive) {
                                            ws.send(obj("id" to m["id"], "error" to obj("code" to JsonPrimitive(-32000), "message" to s("Rejected archive"))).toString())
                                            return
                                        }
                                        if (method == "thread/archive") archivedTaskIds.add(params.str("threadId"))
                                        else archivedTaskIds.remove(params.str("threadId"))
                                        if (dropArchiveReply) {
                                            dropArchiveReply = false
                                            ws.close(1011, "fixture archive response lost")
                                            return
                                        }
                                    }
                                    if (method == "thread/list" || method == "thread/search")
                                        browserRequests.add(params)
                                    if (method.startsWith("thread/queue/") && method != "thread/queue/list")
                                        queueMutations.add(m)
                                    if (method in listOf("thread/list", "thread/search", "thread/unarchive")) browserCalls.add(method to params)
                                    val result = browserResponse?.invoke(method, params) ?: when (method) {
                                            "initialize" -> obj("codexHome" to s("/fixture"))
                                            "config/read" -> obj(
                                                "config" to obj("model" to s("gpt-fixture"), "model_reasoning_effort" to s("high")),
                                                "origins" to obj("model" to obj("name" to obj("type" to s(if (params.str("cwd") == "/fixture/remote-codex") "project" else "user"))),
                                                    "model_reasoning_effort" to obj("name" to obj("type" to s("user")))))
                                            "collaborationMode/list" ->
                                                obj(
                                                    "data" to
                                                        JsonArray(
                                                            listOf(
                                                                obj(
                                                                    "name" to s("Default"),
                                                                    "mode" to s("default"),
                                                                    "reasoning_effort" to s("medium"),
                                                                ),
                                                                obj(
                                                                    "name" to s("Plan"),
                                                                    "mode" to s("plan"),
                                                                    "reasoning_effort" to s("high"),
                                                                ),
                                                            )
                                                        )
                                                )
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
                                                if (rejectModelList)
                                                    obj("_fixtureError" to obj("code" to JsonPrimitive(-32603), "message" to s("Models unavailable")))
                                                else modelCatalog()
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
                                                            fixtureTasks(params).map { task ->
                                                                obj(
                                                                    "thread" to task,
                                                                    "snippet" to s("fixture match"),
                                                                )
                                                            }
                                                        )
                                                )
                                            "thread/read" -> obj("thread" to obj(
                                                "id" to params["threadId"],
                                                "status" to obj("type" to s("idle")),
                                            ))
                                            "thread/resume" ->
                                                obj(
                                                    "model" to s("gpt-fixture"),
                                                    "reasoningEffort" to s("low"),
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
                                                            "cwd" to s("/fixture/remote-codex"),
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
                                                    "model" to (params["model"] ?: s("gpt-fixture")),
                                                    "reasoningEffort" to s("low"),
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
                                                    "get-url" in command -> obj("exitCode" to JsonPrimitive(0), "stdout" to s("git@github.com:denta-codex/remote-codex.git\n"))
                                                    "remote-codex-environment" in command -> {
                                                        environmentSetups.incrementAndGet()
                                                        remoteFiles[command[5]] = Base64.getEncoder().encodeToString("${command[6]}\n${command[7]}\n".toByteArray())
                                                        obj("exitCode" to JsonPrimitive(0))
                                                    }
                                                    command.firstOrNull() == "test" -> {
                                                        invalidDirectoryProbes.incrementAndGet()
                                                        obj("exitCode" to JsonPrimitive(2))
                                                    }
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
                                            "fs/createDirectory" -> obj()
                                            "fs/writeFile" -> {
                                                if (dropWrite) {
                                                    ws.cancel()
                                                    return
                                                }
                                                remoteFiles[params.str("path")] =
                                                    params.str("dataBase64")
                                                obj()
                                            }
                                            "fs/readFile" ->
                                                obj(
                                                    "dataBase64" to
                                                        s(remoteFiles[params.str("path")].orEmpty())
                                                )
                                            "fs/getMetadata" -> {
                                                val path = params.str("path")
                                                if (
                                                    rejectWorkspaceMetadata &&
                                                        path == "/fixture/remote-codex"
                                                ) {
                                                    workspaceMetadataRejections.incrementAndGet()
                                                    obj(
                                                        "_fixtureError" to
                                                            obj(
                                                                "code" to
                                                                    JsonPrimitive(-32000),
                                                                "message" to
                                                                    s("Fixture metadata rejection"),
                                                            )
                                                    )
                                                } else if (
                                                    path == "/fixture/remote-codex" ||
                                                        path == "/fixture/notes" ||
                                                        path == "/fixture/repo" ||
                                                        path.startsWith("/fixture/worktrees/")
                                                ) {
                                                    workspaceMetadataReads.incrementAndGet()
                                                    obj(
                                                        "metadata" to
                                                            obj("type" to s("directory"))
                                                    )
                                                } else {
                                                    val encoded = remoteFiles[path]
                                                    obj(
                                                        "type" to s("file"),
                                                        "size" to
                                                            JsonPrimitive(
                                                                encoded?.let {
                                                                    java.util.Base64.getDecoder()
                                                                        .decode(it)
                                                                        .size
                                                                } ?: 0
                                                            ),
                                                    )
                                                }
                                            }
                                            "thread/turns/list" -> history()
                                            "thread/queue/list" ->
                                                if (rejectQueueRead)
                                                    obj("_fixtureError" to obj("code" to JsonPrimitive(-32601), "message" to s("Queue unavailable")))
                                                else obj("data" to JsonArray(if (params.str("threadId") == "task-test") queueItems.toList() else emptyList()))
                                            "thread/queue/add" -> {
                                                val queued = obj(
                                                    "id" to s("queue-${queueIds.incrementAndGet()}"),
                                                    "clientUserMessageId" to params["clientUserMessageId"],
                                                    "input" to params["input"],
                                                )
                                                queueItems.add(queued)
                                                obj("queuedSubmission" to queued)
                                            }
                                            "thread/queue/delete" ->
                                                obj("deleted" to JsonPrimitive(queueItems.removeAll { it.str("id") == params.str("queuedSubmissionId") }))
                                            "thread/queue/start" -> {
                                                queueItems.removeAll { it.str("id") == params.str("queuedSubmissionId") }
                                                obj("turn" to obj("id" to s("turn-queued")))
                                            }
                                            "turn/start",
                                            "turn/steer" -> {
                                                if (method == "turn/steer") {
                                                    steerRequests.add(params)
                                                    if (rejectSteer) {
                                                        ws.send(obj("id" to m["id"], "error" to obj("code" to JsonPrimitive(-32600), "message" to s("Active turn changed"))).toString())
                                                        return
                                                    }
                                                }
                                                if (method == "turn/start") {
                                                    lastTurnStartParams = params
                                                    turnRequests.add(params)
                                                }
                                                sent.incrementAndGet()
                                                acceptedInput =
                                                    params["input"] as? JsonArray
                                                        ?: JsonArray(emptyList())
                                                acceptedText =
                                                    params.list("input")
                                                        .firstOrNull { it.str("type") == "text" }
                                                        ?.str("text")
                                                        .orEmpty()
                                                if (dropSend) {
                                                    ws.cancel()
                                                    return
                                                }
                                                obj("turn" to obj("id" to s("turn-test")))
                                            }
                                            else -> obj()
                                        }
                                    if (method == dropQueueMethod) {
                                        ws.cancel()
                                        return
                                    }
                                    val fixtureError = result["_fixtureError"]
                                    ws.send(
                                        (if (fixtureError != null)
                                                obj("id" to m["id"], "error" to fixtureError)
                                            else obj("id" to m["id"], "result" to result))
                                            .toString()
                                    )
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
                                                                "content" to acceptedInput,
                                                            ),
                                            ),
                                        )
                                        acceptedInput
                                            .mapNotNull { it as? JsonObject }
                                            .firstOrNull { it.str("type") == "localImage" }
                                            ?.str("path")
                                            ?.let { path ->
                                                emit(
                                                    ws,
                                                    "item/completed",
                                                    obj(
                                                        "turnId" to s("turn-test"),
                                                        "item" to
                                                            obj(
                                                                "id" to s("view"),
                                                                "type" to s("imageView"),
                                                                "path" to s(path),
                                                            ),
                                                    ),
                                                )
                                            }
                                        val planMode =
                                            params.map("collaborationMode").str("mode") == "plan"
                                        val waitingForAnswer = planMode && askPlanQuestion
                                        if (waitingForAnswer) {
                                            ws.send(
                                                obj(
                                                        "id" to JsonPrimitive(88),
                                                        "method" to s("item/tool/requestUserInput"),
                                                        "params" to
                                                            obj(
                                                                "threadId" to s("task-test"),
                                                                "turnId" to s("turn-test"),
                                                                "itemId" to s("question"),
                                                                "isBlocking" to JsonPrimitive(true),
                                                                "questions" to
                                                                    JsonArray(
                                                                        listOf(
                                                                            obj(
                                                                                "header" to s("Scope"),
                                                                                "id" to s("scope"),
                                                                                "question" to s("Where should the plan focus?"),
                                                                                "options" to
                                                                                    JsonArray(
                                                                                        listOf(
                                                                                            obj(
                                                                                                "label" to s("Current workspace"),
                                                                                                "description" to s("Keep the proposal within this checkout."),
                                                                                            ),
                                                                                            obj(
                                                                                                "label" to s("Broader change"),
                                                                                                "description" to s("Include related projects."),
                                                                                            ),
                                                                                        )
                                                                                    ),
                                                                            )
                                                                        )
                                                                    ),
                                                            ),
                                                    )
                                                    .toString()
                                            )
                                        } else if (planMode) {
                                            emit(
                                                ws,
                                                "item/plan/delta",
                                                obj(
                                                    "turnId" to s("turn-test"),
                                                    "itemId" to s("p"),
                                                    "delta" to s("Draft plan"),
                                                ),
                                            )
                                            emit(
                                                ws,
                                                "item/completed",
                                                obj(
                                                    "turnId" to s("turn-test"),
                                                    "item" to
                                                        obj(
                                                            "id" to s("p"),
                                                            "type" to s("plan"),
                                                            "text" to
                                                                s(planText),
                                                        ),
                                                ),
                                            )
                                        } else {
                                            emit(
                                                ws,
                                                "item/agentMessage/delta",
                                                obj(
                                                    "turnId" to s("turn-test"),
                                                    "itemId" to s("a"),
                                                    "delta" to s("Hello from Grace"),
                                                ),
                                            )
                                        }
                                        if (!waitingForAnswer && !holdTurnOpen)
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
        File(app.filesDir, "bug-reports").deleteRecursively()
        runBlocking {
            val local = LocalStore(app)
            listOf(
                    "draft/new",
                    "journal/new",
                    "options/new",
                    "attachments/new",
                    "draft/task-test",
                    "journal/task-test",
                    "attachments/task-test",
                    "draft/project-task",
                    "journal/project-task",
                    "attachments/project-task",
                    "bug-report/shake",
                    "bug-report/screenshot",
                    "bug-report/last-task",
                )
                .forEach { local.remove(it) }
        }
        compose.runOnUiThread {
            model = ClientModel(app, "ws://127.0.0.1:${server.port}/rpc", "/fixture", true, "/fixture/remote-codex")
            store.put("fixture", model)
            compose.activity.setContent { RemoteTheme { App(model) } }
            model.saveCredential("fixture-credential-0000000000000000000000000000000000000")
        }
        compose.waitUntil(15000) {
            model.reports.state.value.loaded && model.state.value.ready &&
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

    private fun recencyPage(params: JsonObject, search: Boolean): JsonObject {
        val tasks = listOf(
            obj(
                "id" to s("old-lampshades"),
                "name" to s("lets 3d print some lampshades"),
                "updatedAt" to JsonPrimitive(300),
                "recencyAt" to JsonPrimitive(100),
            ),
            obj(
                "id" to s("recent-chat"),
                "name" to s("Recently active chat"),
                "updatedAt" to JsonPrimitive(200),
                "recencyAt" to JsonPrimitive(200),
            ),
        )
        val field = if (params.str("sortKey") == "recency_at") "recencyAt" else "updatedAt"
        val sorted = tasks.sortedByDescending { it[field]!!.jsonPrimitive.long }
        val more = params.str("cursor") == "recency-page-2"
        val task = sorted[if (more) 1 else 0]
        return obj(
            "data" to JsonArray(listOf(if (search) obj("thread" to task) else task)),
            "nextCursor" to if (more) null else s("recency-page-2"),
        )
    }

    private fun fixtureTasks(params: JsonObject): List<JsonObject> {
        if (emptyTaskList) return emptyList()
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
        // Stock list/search default to interactive sources. Internal reviewers are only
        // returned when the client explicitly includes their subagent source kind.
        val internalTasks =
            (params["sourceKinds"] as? JsonArray).orEmpty()
                .map { it.jsonPrimitive.content }
                .filter { it.startsWith("subAgent") }
                .map { kind ->
                    obj(
                        "id" to s("internal-$kind"),
                        "name" to s("Internal reviewer $kind"),
                        "cwd" to s("/fixture"),
                        "projectId" to JsonNull,
                    )
                }
        return (tasks + internalTasks).filter { task ->
            val cwdMatches = params.str("cwd").let { it.isEmpty() || it == task.str("cwd") }
            val projectMatches =
                if (!params.containsKey("projectId")) true
                else if (params["projectId"] is JsonNull) task["projectId"] is JsonNull
                else params.str("projectId") == task.str("projectId")
            val archived = (params["archived"] as? JsonPrimitive)?.booleanOrNull ?: false
            cwdMatches && projectMatches && ((task.str("id") in archivedTaskIds) == archived)
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
        if (acceptedInput.isEmpty()) return obj("data" to JsonArray(emptyList()))
        val user =
            obj(
                "id" to s("u"),
                "type" to s("userMessage"),
                "content" to acceptedInput,
            )
        val assistant =
            obj("id" to s("a"), "type" to s("agentMessage"), "text" to s("Hello from Grace"))
        val image =
            acceptedInput.mapNotNull { it as? JsonObject }
                .firstOrNull { it.str("type") == "localImage" }
                ?.str("path")
                ?.let { obj("id" to s("view"), "type" to s("imageView"), "path" to s(it)) }
        val turn =
            obj(
                "id" to s("turn-test"),
                "status" to s(if (holdTurnOpen) "inProgress" else "completed"),
                "items" to JsonArray(listOfNotNull(user, image, assistant)),
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

    private fun fixtureImage(name: String = "fixture.png"): File =
        File(app.cacheDir, name).also { file ->
            file.outputStream().use { output ->
                Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
                    .compress(Bitmap.CompressFormat.PNG, 100, output)
            }
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
        // Navigation now preserves the inbox; explicitly fetch the changed fixture data.
        compose.runOnUiThread { model.home(); model.retryList() }
        compose.waitUntil(5000) { model.state.value.tasks.first().str("name") == fixtureTitle }
        demoPause(2000)
        compose.onNodeWithText(fixtureTitle).performClick()
        compose.waitUntil(10000) { !model.state.value.busy && model.state.value.entries.size >= 40 }
        compose.waitForIdle()
        compose.waitUntil(5000) { latestReply().isDisplayed() }
    }

    private fun latestReply() = compose.onNodeWithText("Latest reply — ready for review.", substring = true)

    @Test
    fun streamingTallReplyKeepsVisibleParagraphStillWhenReading() {
        openLongHistory(tallLastMessage = true)
        compose.onNodeWithTag("timeline").performTouchInput {
            swipe(center, center.copy(y = height * .85f), durationMillis = 1200)
        }
        compose.waitForIdle()
        val paragraph = (1..35).map {
            "Review note $it: Keep the layout clear and comfortable to read."
        }.first { compose.onNodeWithText(it).isDisplayed() }
        val before = compose.onNodeWithText(paragraph).fetchSemanticsNode().boundsInRoot.top
        val delta = (1..6).joinToString("\n\n", prefix = "\n\n") {
            "Streaming addition $it: More text arriving while the reader stays here."
        }
        emit(peer!!, "item/agentMessage/delta", obj("turnId" to s("history-20"),
            "itemId" to s("reply-20"), "delta" to s(delta)))
        compose.waitUntil(5000) { model.state.value.entries.last().text.endsWith(delta) }
        compose.waitUntil(5000) {
            compose.onAllNodesWithText("Streaming addition 6:", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
        compose.onNodeWithText(paragraph).assertIsDisplayed()
        assertEquals("Growing the visible reply must preserve the paragraph's screen position",
            before, compose.onNodeWithText(paragraph).fetchSemanticsNode().boundsInRoot.top, 1f)
        compose.onNodeWithTag("jump-to-latest").performClick()
        compose.waitUntil(5000) {
            compose.onNodeWithText("Streaming addition 6:", substring = true).isDisplayed()
        }
        emit(peer!!, "item/agentMessage/delta", obj("turnId" to s("history-20"),
            "itemId" to s("reply-20"), "delta" to s("\n\nFollowing resumed here.")))
        compose.waitUntil(5000) {
            compose.onNodeWithText("Following resumed here.").isDisplayed()
        }
        compose.onNodeWithTag("jump-to-latest").assertDoesNotExist()
    }

    @Test
    fun markdownTableWrapsCompleteCellsAndScrollsToLastColumn() {
        val coverage = "\$0 delivery fees and reduced service fees at participating merchants, subject to order minimums"
        val timing = "Starts when you activate. Activate now and it runs through December 31, 2029."
        val longHeader = "When you get it and when the benefit expires"
        val markdown = """
            | What you get | What it covers | $longHeader |
            |---|---|---|
            | **Free DashPass** | $coverage | Starts when you activate. Activate now and it runs through **December 31, 2029**. |
            | **One ${'$'}15 promo monthly** | One qualifying DoorDash order—including restaurants or groceries | Each calendar month after activation |
            | **Two ${'$'}10 promos monthly** | Qualifying **non-restaurant** orders: groceries, convenience items, retail, etc. Each ${'$'}10 requires a separate order. | Both available each calendar month |
        """.trimIndent()
        compose.runOnUiThread {
            compose.activity.setContent {
                RemoteTheme {
                    Column(
                        Modifier.width(368.dp).verticalScroll(rememberScrollState())
                    ) {
                        SelectionContainer {
                            FileAwareMarkdown(markdown, model)
                        }
                    }
                }
            }
        }
        compose.waitUntil(5000) {
            compose.onAllNodesWithText(coverage, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        fun assertCompleteWrappedText(text: String) {
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            compose.onNodeWithText(text, substring = true).performSemanticsAction(
                androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult
            ) { it(layouts) }
            assertTrue("Expected wrapped text: $text", layouts.single().lineCount > 1)
            assertFalse("Text must not overflow: $text", layouts.single().hasVisualOverflow)
            val layout = layouts.single()
            assertEquals(layout.layoutInput.text.length, layout.getLineEnd(layout.lineCount - 1))
        }
        assertCompleteWrappedText(coverage)
        val horizontalTable = compose.onNode(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange)
        )
        horizontalTable.performTouchInput { swipeLeft() }
        compose.onNodeWithText(longHeader, substring = true).assertIsDisplayed()
        compose.onNodeWithText(timing, substring = true).assertIsDisplayed()
        assertCompleteWrappedText(longHeader)
        assertCompleteWrappedText(timing)
    }

    @Test
    fun polishedConversationOpensAtLatestAndKeepsReadingPosition() {
        openLongHistory()
        latestReply().assertIsDisplayed()
        demoPause(3000)
        compose.onNodeWithText("Command").performClick()
        compose.onNodeWithText("scripts/check", substring = true).assertIsDisplayed()
        demoPause(2000)
        compose.onNodeWithText("Command").performClick()
        val latestPosition = compose.onNodeWithTag("timeline").fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange].value()
        compose.onNodeWithTag("timeline").performTouchInput { swipeDown() }
        compose.onNodeWithTag("timeline").performTouchInput { swipeDown() }
        compose.waitForIdle()
        val position = compose.onNodeWithTag("timeline").fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertTrue("Scrolled into older messages", position < latestPosition)
        latestReply().assertIsNotDisplayed()
        demoPause(2000)
        emit(peer!!, "item/agentMessage/delta", obj("turnId" to s("history-20"),
            "itemId" to s("reply-20"), "delta" to s("\n\nA final spacing check is complete.")))
        compose.waitUntil(5000) { model.state.value.entries.last().text.endsWith("complete.") }
        compose.waitForIdle()
        val after = compose.onNodeWithTag("timeline").fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertEquals("Incoming content must not pull the reader to the bottom", position, after, .01f)
        demoPause(2000)
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitUntil(5000) { model.state.value.page == "home" }
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
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithContentDescription("New chat").performClick()
        compose.waitUntil(5000) { model.state.value.page == "chat" && model.state.value.thread == null }
        compose.onNodeWithText("What shall we work on?").assertIsDisplayed()
        demoPause(2000)
        compose.runOnUiThread {
            model.home()
            if (demo) {
                compose.activity.setContent { RemoteTheme(darkTheme = true) { App(model) } }
                // Match system chrome to this fixture-only theme override. Production
                // follows the system theme through MainActivity.enableEdgeToEdge().
                WindowCompat.getInsetsController(
                        compose.activity.window,
                        compose.activity.window.decorView,
                    )
                    .apply {
                        isAppearanceLightStatusBars = false
                        isAppearanceLightNavigationBars = false
                    }
            }
        }
        compose.waitUntil(5000) { model.state.value.page == "home" }
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
        compose.waitUntil(5000) {
            compose.onNodeWithText("Streaming finished here.", substring = true).isDisplayed()
        }
        compose.onNodeWithText("Streaming finished here.", substring = true).assertIsDisplayed()
    }

    @After
    fun cleanup() {
        compose.runOnUiThread {
            model.foreground(false)
            store.clear()
        }
        runBlocking { LocalStore(app).saveToken("") }
        server.shutdown()
        if (coverScreen || landscapeScreen || reportCoverOverride) {
            shell("wm size reset")
            shell("wm density reset")
        }
    }

    @Test
    fun coverScreenDestinationsRemainReachable() {
        Assume.assumeTrue("Run this test with scripts/emulator-test --cover", coverScreen)
        val configuration = compose.activity.resources.configuration
        assertTrue(configuration.screenWidthDp in 400..420)
        assertTrue(configuration.screenHeightDp in 470..500)

        compose.onNodeWithContentDescription("New chat").assertIsDisplayed()
        compose.onNodeWithText("Fixture task").assertIsDisplayed()
        demoPause(2000)
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Scan setup QR").assertIsDisplayed()
        compose.onNodeWithText("Choose default assistant").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Check for updates").performScrollTo().assertIsDisplayed()
        demoPause(2500)

        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithContentDescription("New chat").performClick()
        compose.waitUntil(5000) { model.state.value.page == "chat" }
        compose.onNodeWithText("What shall we work on?").assertIsDisplayed()
        compose.onNodeWithTag("composer").assertIsDisplayed()
        compose.onNodeWithTag("conversation-settings").assertIsDisplayed()
        demoPause(2500)
        compose.onNodeWithTag("add-menu").assertIsDisplayed().performClick()
        compose.onNodeWithText("Photos").assertIsDisplayed()
        compose.onNodeWithText("Files").assertIsDisplayed()
        compose.onNodeWithText("Camera").assertIsDisplayed()
        demoPause(2500)

        compose.runOnUiThread { model.openTask("task-test") }
        compose.waitUntil(10000) {
            model.state.value.thread == "task-test" && !model.state.value.busy
        }
        val density = compose.activity.resources.displayMetrics.density
        val timelineHeight =
            compose.onNodeWithTag("timeline").fetchSemanticsNode().boundsInRoot.height / density
        val composerHeight =
            compose.onNodeWithTag("composer-actions").fetchSemanticsNode().boundsInRoot.height / density
        assertTrue("Conversation timeline is only $timelineHeight dp high", timelineHeight >= 100f)
        assertTrue("Composer actions are $composerHeight dp high", composerHeight <= 90f)
        compose.onNodeWithTag("send").assertIsDisplayed()
        demoPause(3000)
    }

    @Test
    fun landscapeConversationLeavesRoomForMessagesAndDraft() {
        // Match the reported Razr window; setup installs the mock after resizing.
        compose.runOnUiThread { model.openTask("task-test") }
        compose.waitUntil(10000) {
            model.state.value.thread == "task-test" && !model.state.value.busy
        }
        compose.waitForIdle()
        val configuration = compose.activity.resources.configuration
        assertTrue(configuration.screenWidthDp > 900)
        assertTrue(configuration.screenHeightDp < 480)
        val timeline = compose.onNodeWithTag("timeline").getUnclippedBoundsInRoot()
        val height = timeline.bottom - timeline.top
        assertTrue("Landscape timeline is only $height", height >= 160.dp)
        compose.onNodeWithTag("composer").assertIsDisplayed()
        compose.onNodeWithTag("send").assertIsDisplayed()
        openConversationTray()
        compose.onNodeWithTag("model-selector").performScrollTo().performClick()
        compose.onNodeWithText("Fixture Fast").performClick()
        compose.onNodeWithTag("reasoning-selector").performScrollTo().performClick()
        compose.onNodeWithText("Medium").performClick()
        closeConversationTray()
        assertEquals("gpt-fixture-fast", model.state.value.newTaskOptions.model)
        assertEquals("medium", model.state.value.newTaskOptions.reasoningEffort)
        compose.onNodeWithTag("add-menu").performClick()
        compose.onNodeWithText("Photos").assertIsDisplayed()
        compose.onNodeWithText("Files").assertIsDisplayed()
        compose.onNodeWithText("Camera").assertIsDisplayed()
        shell("input keyevent KEYCODE_BACK")
        closeConversationTray()
        compose.onNodeWithTag("composer").performTextInput("A landscape draft\nwith several\nlines of text")
        compose.onNodeWithTag("send").assertIsDisplayed().assertIsEnabled()
        assertEquals("A landscape draft\nwith several\nlines of text", model.state.value.draft)
        compose.runOnUiThread { model.newChat() }
        compose.waitUntil(5000) {
            model.state.value.thread == null && !model.state.value.busy
        }
        openConversationTray()
        compose.onNodeWithTag("project-selector").performClick()
        compose.onNodeWithText("Remote Codex").performClick()
        openConversationTray()
        compose.onNodeWithTag("workspace-new-worktree").performScrollTo().performClick()
        assertEquals(ExecutionTarget.NewWorktree, model.state.value.newTaskOptions.executionTarget)
        closeConversationTray()
        compose.onNodeWithTag("composer").assertIsDisplayed()
        compose.onNodeWithTag("send").assertIsDisplayed()
    }

    @Test
    fun textChatStreamsAndCanReopen() {
        compose.onNodeWithContentDescription("New chat").performClick()
        compose.waitUntil(5000) { model.state.value.page == "chat" }
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
    fun existingThreadDeeplinkCanBeCopied() {
        compose.runOnUiThread { model.openTask("task-test") }
        compose.waitUntil(10000) {
            model.state.value.page == "chat" &&
                model.state.value.thread == "task-test" &&
                !model.state.value.busy
        }

        compose.onNodeWithContentDescription("Copy deeplink").performClick()

        val clipboard = app.getSystemService(ClipboardManager::class.java)
        compose.waitUntil(5000) {
            clipboard.primaryClip?.getItemAt(0)?.coerceToText(app)?.toString() ==
                "codex://threads/task-test"
        }
        assertEquals(
            "codex://threads/task-test",
            clipboard.primaryClip!!.getItemAt(0).coerceToText(app).toString(),
        )

        compose.runOnUiThread { model.newChat() }
        compose.waitUntil(5000) {
            model.state.value.page == "chat" && model.state.value.thread == null
        }
        compose.onNodeWithContentDescription("Copy deeplink").assertDoesNotExist()
    }

    private fun openRunningQueueFixture() {
        holdTurnOpen = true
        compose.runOnUiThread { model.openTask("task-test") }
        compose.waitUntil(10000) { model.state.value.thread == "task-test" && !model.state.value.busy && model.state.value.queueReady }
        compose.onNodeWithTag("composer").performTextInput("Start working")
        compose.onNodeWithTag("send").performClick()
        compose.waitUntil(10000) { model.state.value.activeTurn == "turn-test" && !model.state.value.busy }
    }

    private fun enqueueFixture(text: String = "Do this next") {
        compose.onNodeWithTag("composer").performTextInput(text)
        demoPause()
        assertComposerActionFullyVisible(compose.onNodeWithContentDescription("Queue message"))
        assertComposerActionFullyVisible(compose.onNodeWithContentDescription("Stop"))
        compose.onNodeWithContentDescription("Queue message").performClick()
        compose.waitUntil(10000) { !model.state.value.busy && model.state.value.queuedMessages.any { it.text == text } }
        demoPause()
    }

    // Compose test clicks reach nodes that a Row pushed past its clipped edge, so check the
    // unclipped bounds against the action row the user can actually see.
    private fun assertComposerActionFullyVisible(action: SemanticsNodeInteraction) {
        val row = compose.onNodeWithTag("composer-actions").fetchSemanticsNode()
        val node = action.assertIsDisplayed().fetchSemanticsNode()
        val rowRight = row.positionInRoot.x + row.size.width
        val nodeRight = node.positionInRoot.x + node.size.width
        assertTrue(
            "Composer action ends at $nodeRight px beyond the action row edge at $rowRight px",
            nodeRight <= rowRight + 0.5f,
        )
    }

    @Test
    fun planModeCanBeSelectedWhileWorkingAndSentWhenIdle() {
        openRunningQueueFixture()
        compose.onNodeWithTag("model-selector").assertDoesNotExist()
        compose.onNodeWithTag("add-menu").assertIsDisplayed().performClick()
        compose.onNodeWithTag("add-photos").assertIsDisplayed()
        compose.onNodeWithTag("add-files").assertIsDisplayed()
        compose.onNodeWithTag("add-camera").assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        openConversationTray()
        compose.onNodeWithTag("mode-plan").performScrollTo().performClick()
        closeConversationTray()
        compose.onNodeWithTag("conversation-settings").assertTextContains("Plan", substring = true)
        closeConversationTray()
        compose.onNodeWithTag("composer").performTextInput("Plan the next change")
        compose.onNodeWithTag("send").assertIsNotEnabled()
        compose.onNodeWithTag("composer-status")
            .assertTextContains("Plan selected · send when the task is idle")
        // The model must also reject a queue submission that would lose the mode.
        compose.runOnUiThread { model.send() }
        compose.waitForIdle()
        assertTrue(queueMutations.isEmpty())
        assertEquals("Plan the next change", model.state.value.draft)
        holdTurnOpen = false
        emit(peer!!, "turn/completed", obj("turn" to obj("id" to s("turn-test"), "status" to s("completed"))))
        compose.waitUntil(5000) { model.state.value.activeTurn == null }
        assertEquals(1, turnRequests.size)
        compose.onNodeWithTag("send").assertIsDisplayed().assertIsEnabled().performClick()
        compose.waitUntil(10000) { turnRequests.size == 2 && !model.state.value.busy }
        assertEquals("plan", turnRequests.last().map("collaborationMode").str("mode"))
        assertEquals("Plan the next change", turnRequests.last().list("input").single().str("text"))
        assertTrue(queueMutations.isEmpty())
    }

    @Test
    fun planDraftWaitsForExistingQueueAndCanReturnToTaskSettings() {
        openRunningQueueFixture()
        enqueueFixture()
        holdTurnOpen = false
        emit(peer!!, "turn/completed", obj("turn" to obj("id" to s("turn-test"), "status" to s("interrupted"))))
        compose.waitUntil(5000) { model.state.value.activeTurn == null }
        openConversationTray()
        compose.onNodeWithTag("mode-plan").performScrollTo().performClick()
        closeConversationTray()
        compose.onNodeWithTag("composer").performTextInput("Plan after the queue")
        compose.onNodeWithTag("send").assertIsNotEnabled()
        compose.runOnUiThread { model.send() }
        compose.waitForIdle()
        assertEquals(1, queueMutations.size)
        openConversationTray()
        compose.onNodeWithTag("mode-server-default").performScrollTo().performClick()
        closeConversationTray()
        compose.onNodeWithTag("send").assertIsEnabled().performClick()
        compose.waitUntil(10000) { !model.state.value.busy && model.state.value.queuedMessages.size == 2 }
        assertEquals(1, turnRequests.size)
        assertEquals(listOf("thread/queue/add", "thread/queue/add"), queueMutations.map { it.str("method") })
    }

    @Test
    fun normalSendQueuesAndCanSteerWithoutChangingNewDraft() {
        openRunningQueueFixture()
        enqueueFixture()
        enqueueFixture("Then review it")
        assertEquals(1, sent.get())
        assertTrue(steerRequests.isEmpty())
        assertEquals(listOf("Do this next", "Then review it"), model.state.value.queuedMessages.map { it.text })
        if (coverScreen) shell("settings put secure show_ime_with_hard_keyboard 1")
        compose.onNodeWithTag("composer").performTextInput("An unfinished thought")
        // Wait for the real IME transition before using the compact queue shortcut.
        if (coverScreen) {
            compose.waitUntil(5000) {
                compose.onAllNodesWithTag("show-queue").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("show-queue").assertIsDisplayed().performClick()
            compose.waitUntil(5000) {
                compose.onAllNodesWithTag("send-queued-queue-1").fetchSemanticsNodes().isNotEmpty()
            }
        }
        val queued = queueItems.first()
        demoPause(2500)
        compose.onNodeWithTag("send-queued-queue-1").performScrollTo().performClick()
        compose.waitUntil(10000) { !model.state.value.busy && model.state.value.queuedMessages.size == 1 }
        demoPause()
        assertEquals(1, steerRequests.size)
        assertEquals(queued["input"], steerRequests.single()["input"])
        assertEquals(queued["clientUserMessageId"], steerRequests.single()["clientUserMessageId"])
        assertEquals("turn-test", steerRequests.single().str("expectedTurnId"))
        assertFalse(steerRequests.single().containsKey("model"))
        assertEquals("An unfinished thought", model.state.value.draft)
        assertNull(model.state.value.journal)
        compose.onNodeWithTag("remove-queued-queue-2").performClick()
        compose.waitUntil(10000) { !model.state.value.busy && model.state.value.queuedMessages.isEmpty() }
        demoPause(2000)
        assertEquals(1, steerRequests.size)
        compose.onNodeWithTag("composer").assertTextContains("An unfinished thought")
    }

    @Test
    fun serverQueueSurvivesRecreationAndTracksOtherClients() {
        openRunningQueueFixture()
        enqueueFixture()
        compose.runOnUiThread {
            store.clear()
            model = ClientModel(app, "ws://127.0.0.1:${server.port}/rpc", "/fixture", true)
            store.put("fixture", model)
            compose.activity.setContent { RemoteTheme { App(model) } }
            model.foreground(true)
        }
        compose.waitUntil(15000) { model.state.value.ready }
        compose.runOnUiThread { model.openTask("task-test") }
        compose.waitUntil(15000) { model.state.value.ready && !model.state.value.busy && model.state.value.queueReady }
        assertEquals("Do this next", model.state.value.queuedMessages.single().text)
        assertEquals(1, queueMutations.count { it.str("method") == "thread/queue/add" })
        queueItems.add(obj("id" to s("desktop"), "clientUserMessageId" to s("desktop-message"), "input" to turnInput("From desktop", emptyList())))
        emit(peer!!, "thread/queue/changed", obj())
        compose.waitUntil(5000) { model.state.value.queuedMessages.size == 2 }
        compose.runOnUiThread { model.openTask("project-task") }
        compose.waitUntil(10000) { model.state.value.thread == "project-task" && !model.state.value.busy }
        assertTrue(model.state.value.queuedMessages.isEmpty())
        compose.runOnUiThread { model.openTask("task-test") }
        compose.waitUntil(10000) { model.state.value.thread == "task-test" && !model.state.value.busy && model.state.value.queuedMessages.size == 2 }
        // The server owns consumption. A completion and queue notification must never
        // cause the client to send a second start or steer for the consumed submission.
        queueItems.clear()
        emit(peer!!, "turn/completed", obj("turn" to obj("id" to s("turn-test"), "status" to s("completed"))))
        emit(peer!!, "thread/queue/changed", obj())
        compose.waitUntil(5000) { model.state.value.activeTurn == null && model.state.value.queuedMessages.isEmpty() }
        assertEquals(1, sent.get())
        assertTrue(steerRequests.isEmpty())
        assertEquals(1, queueMutations.size)
    }

    @Test
    fun queuedMessageCanStartWhenIdle() {
        openRunningQueueFixture()
        enqueueFixture()
        holdTurnOpen = false
        emit(peer!!, "turn/completed", obj("turn" to obj("id" to s("turn-test"), "status" to s("interrupted"))))
        compose.waitUntil(5000) { model.state.value.activeTurn == null }
        compose.onNodeWithText("Send now").performClick()
        compose.waitUntil(10000) { !model.state.value.busy && model.state.value.queuedMessages.isEmpty() }
        assertEquals(listOf("thread/queue/add", "thread/queue/start"), queueMutations.map { it.str("method") })
        assertTrue(steerRequests.isEmpty())
    }

    @Test
    fun uncertainQueueAddIsNotReplayed() {
        openRunningQueueFixture()
        dropQueueMethod = "thread/queue/add"
        compose.onNodeWithTag("composer").performTextInput("Queue exactly once")
        compose.onNodeWithTag("send").performClick()
        compose.waitUntil(10000) { !model.state.value.busy && model.state.value.journal != null }
        dropQueueMethod = null
        compose.runOnUiThread { model.connect() }
        compose.waitUntil(15000) { model.state.value.ready && !model.state.value.busy && model.state.value.queueReady }
        assertEquals(1, queueItems.size)
        assertEquals(1, queueMutations.size)
        assertNotNull(model.state.value.journal)
        assertEquals("Queue exactly once", model.state.value.draft)
        compose.onNodeWithTag("send-queued-queue-1").assertIsNotEnabled()
    }

    @Test
    fun uncertainQueueSteerIsNotReplayed() {
        openRunningQueueFixture()
        enqueueFixture()
        compose.onNodeWithTag("composer").performTextInput("Keep this draft")
        dropQueueMethod = "turn/steer"
        compose.onNodeWithText("Steer now").performClick()
        compose.waitUntil(10000) { !model.state.value.busy && model.state.value.journal != null }
        dropQueueMethod = null
        compose.runOnUiThread { model.connect() }
        compose.waitUntil(15000) { model.state.value.ready && !model.state.value.busy && model.state.value.queueReady }
        assertEquals(1, steerRequests.size)
        assertTrue(queueItems.isEmpty())
        assertEquals("steeringQueued", model.state.value.journal?.str("stage"))
        assertEquals("Do this next", model.state.value.journal?.str("text"))
        assertEquals("Keep this draft", model.state.value.draft)
        compose.onNodeWithTag("send").assertIsNotEnabled()
    }

    @Test
    fun rejectedQueueSteerPreservesInputForExplicitRetry() {
        openRunningQueueFixture()
        enqueueFixture()
        rejectSteer = true
        compose.onNodeWithText("Steer now").performClick()
        compose.waitUntil(10000) { !model.state.value.busy && model.state.value.journal?.str("stage") == "queuedSteerRejected" }
        assertTrue(queueItems.isEmpty())
        assertEquals(1, steerRequests.size)
        rejectSteer = false
        compose.onNodeWithText("Send saved message").performScrollTo().performClick()
        compose.waitUntil(10000) { !model.state.value.busy && model.state.value.journal == null }
        assertEquals(2, steerRequests.size)
        assertEquals(steerRequests[0], steerRequests[1])
        assertEquals(1, queueMutations.count { it.str("method") == "thread/queue/delete" })
    }

    @Test
    fun queueConsumptionRaceNeverSteersStaleCopy() {
        openRunningQueueFixture()
        enqueueFixture()
        queueItems.clear()
        compose.onNodeWithText("Steer now").performClick()
        compose.waitUntil(10000) { !model.state.value.busy && model.state.value.queuedMessages.isEmpty() }
        assertTrue(steerRequests.isEmpty())
        assertNull(model.state.value.journal)
        assertEquals(1, sent.get())
    }

    @Test
    fun queuedImageSteersWithOriginalUploadedInput() {
        openRunningQueueFixture()
        val image = fixtureImage("queued.png")
        compose.runOnUiThread { model.addAttachments(listOf(Uri.fromFile(image))) }
        compose.waitUntil(5000) { model.state.value.attachments.size == 1 }
        compose.onNodeWithTag("send").performClick()
        compose.waitUntil(10000) { !model.state.value.busy && model.state.value.queuedMessages.size == 1 }
        val queuedInput = queueItems.single()["input"] as JsonArray
        assertTrue(queuedInput.filterIsInstance<JsonObject>().any { it.str("type") == "localImage" })
        assertTrue(model.state.value.attachments.isEmpty())
        compose.onNodeWithText("Steer now").performClick()
        compose.waitUntil(10000) { !model.state.value.busy && model.state.value.queuedMessages.isEmpty() }
        assertEquals(queuedInput, steerRequests.single()["input"])
    }

    @Test
    fun unavailableQueueNeverFallsBackToDirectSend() {
        rejectQueueRead = true
        compose.runOnUiThread { model.openTask("task-test") }
        compose.waitUntil(10000) { !model.state.value.busy && model.state.value.queueError != null }
        closeConversationTray()
        compose.onNodeWithTag("composer").performTextInput("Wait for the queue")
        compose.onNodeWithTag("send").assertIsNotEnabled()
        compose.runOnUiThread { model.send() }
        compose.waitForIdle()
        assertEquals(0, sent.get())
        assertTrue(queueMutations.isEmpty())
        assertEquals("Wait for the queue", model.state.value.draft)
        rejectQueueRead = false
        compose.onNodeWithText("Refresh").performClick()
        compose.waitUntil(5000) { model.state.value.queueReady }
        compose.onNodeWithTag("send").assertIsEnabled().performClick()
        compose.waitUntil(10000) { !model.state.value.busy && model.state.value.draft.isEmpty() }
        assertEquals(1, sent.get())
        assertTrue(queueMutations.isEmpty())
    }

    private fun openConversationTray() {
        if (compose.onAllNodesWithTag("conversation-tray").fetchSemanticsNodes().isEmpty())
            compose.onNodeWithTag("conversation-settings").performClick()
    }

    private fun closeConversationTray() {
        if (compose.onAllNodesWithTag("conversation-tray").fetchSemanticsNodes().isNotEmpty())
            compose.onNodeWithContentDescription("Close conversation settings").performClick()
    }

    private fun captureComposer(name: String) {
        compose.waitForIdle()
        // UIAutomation captures the hardware surface, which can lag Compose semantics.
        if (name.endsWith("expanded.png")) compose.onNodeWithTag("conversation-tray").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        SystemClock.sleep(300)
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?: app.getExternalFilesDir(null)!!.absolutePath
        val bitmap = if (name.endsWith("expanded.png"))
            compose.onNodeWithTag("conversation-tray").captureToImage().asAndroidBitmap()
        else InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(output, name).apply { parentFile?.mkdirs() }.outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test
    fun commandTrayResolvesInheritanceAndPreservesDraftAndActions() {
        compose.runOnUiThread { compose.activity.setContent { RemoteTheme(darkTheme = true) { App(model) } } }
        compose.onNodeWithContentDescription("New chat").performClick()
        compose.waitUntil(5000) { model.state.value.inheritedSettings.status == ModelCatalogStatus.Ready && model.state.value.modelCatalogStatus == ModelCatalogStatus.Ready }
        compose.onNodeWithTag("conversation-settings").assertTextContains("Fixture Default · High", substring = true)
        compose.onNodeWithTag("send").assertIsNotEnabled()
        captureComposer("command-tray-resting.png")
        compose.onNodeWithTag("composer").performTextInput("Keep my draft")
        openConversationTray()
        compose.onNodeWithTag("model-selector").assertTextContains("Fixture Default")
        compose.onNodeWithTag("reasoning-selector").assertTextContains("High")
        compose.onNodeWithTag("send").assertIsDisplayed().assertIsEnabled()
        captureComposer("command-tray-expanded.png")
        compose.onNodeWithTag("project-selector").performClick()
        compose.onNodeWithText("Remote Codex").performClick()
        compose.waitUntil(5000) { model.state.value.inheritedSettings.cwd == "/fixture/remote-codex" }
        compose.onNodeWithTag("model-selector").assertTextContains("From project")
        compose.onNodeWithTag("model-selector").performScrollTo().performClick()
        compose.onNodeWithText("Fixture Fast").performClick()
        assertEquals("gpt-fixture-fast", model.state.value.newTaskOptions.model)
        compose.onNodeWithTag("model-selector").performScrollTo().performClick()
        compose.onNodeWithTag("model-automatic").performClick()
        assertNull(model.state.value.newTaskOptions.model)
        compose.onNodeWithTag("mode-plan").performScrollTo().performClick()
        compose.onNodeWithTag("reasoning-selector").performScrollTo().assertTextContains("From Plan mode")
        compose.onNodeWithTag("mode-server-default").performScrollTo().performClick()
        assertNull(model.state.value.newTaskOptions.collaborationMode)
        compose.onNodeWithTag("add-menu").performClick()
        compose.onNodeWithTag("add-photos").assertIsDisplayed()
        compose.onNodeWithTag("add-files").assertIsDisplayed()
        compose.onNodeWithTag("add-camera").assertIsDisplayed()
        shell("input keyevent KEYCODE_BACK")
        closeConversationTray()
        assertEquals("Keep my draft", model.state.value.draft)
        assertNull(model.state.value.newTaskOptions.reasoningEffort)
        compose.onNodeWithTag("send").performClick()
        compose.waitUntil(15000) { lastTurnStartParams != null }
        assertFalse(lastThreadStartParams!!.containsKey("model"))
        assertFalse(lastTurnStartParams!!.containsKey("model"))
        assertFalse(lastTurnStartParams!!.containsKey("effort"))
    }

    @Test
    fun commandTrayCoverLargeTextKeepsActionsReachable() {
        val density = compose.activity.resources.displayMetrics.density
        compose.runOnUiThread {
            compose.activity.setContent {
                RemoteTheme {
                    CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides
                        androidx.compose.ui.unit.Density(density, fontScale = 1.5f)) { App(model) }
                }
            }
        }
        compose.onNodeWithContentDescription("New chat").performClick()
        compose.waitUntil(5000) { model.state.value.inheritedSettings.status == ModelCatalogStatus.Ready && model.state.value.modelCatalogStatus == ModelCatalogStatus.Ready }
        compose.onNodeWithTag("composer").performTextInput("Cover draft")
        compose.onNodeWithTag("send").assertIsDisplayed().assertIsEnabled()
        captureComposer("command-tray-cover-keyboard.png")
        openConversationTray()
        compose.onNodeWithTag("send").assertIsDisplayed()
        compose.onNodeWithTag("add-menu").assertIsDisplayed()
        compose.onNodeWithTag("model-selector").performScrollTo().assertTextContains("Fixture Default")
        captureComposer("command-tray-cover-large-text.png")
        compose.onNodeWithTag("mode-plan").performScrollTo().performClick()
        compose.onNodeWithTag("send").assertIsDisplayed()
        closeConversationTray()
        assertEquals("Cover draft", model.state.value.draft)
    }

    @Test
    fun modelControlsUseCatalogAndReconcileOnReconnect() {
        compose.runOnUiThread { compose.activity.setContent { RemoteTheme(darkTheme = true) { App(model) } } }
        compose.onNodeWithContentDescription("New chat").performClick()
        compose.waitUntil(5000) { model.state.value.page == "chat" }
        demoPause()

        compose.waitUntil(5000) { model.state.value.inheritedSettings.status == ModelCatalogStatus.Ready }
        captureComposer("command-tray-resting.png")
        openConversationTray()
        captureComposer("command-tray-expanded.png")
        // Refresh is available inside settings, never in the resting composer.
        compose.onNodeWithContentDescription("Refresh models").assertDoesNotExist()
        compose.onNodeWithText("Refresh models").assertDoesNotExist()
        openConversationTray()
        compose.onNodeWithTag("model-selector").performScrollTo().performClick()
        compose.onNodeWithText("Refresh models").assertDoesNotExist()
        demoPause()
        compose.onNodeWithText("Fixture Fast").performClick()
        assertEquals("gpt-fixture-fast", model.state.value.newTaskOptions.model)
        demoPause()

        compose.onNodeWithTag("reasoning-selector").performScrollTo().performClick()
        compose.onNodeWithText("Medium").assertExists()
        compose.onNodeWithText("Low").assertDoesNotExist()
        demoPause()
        compose.onNodeWithText("Medium").performClick()
        assertEquals("medium", model.state.value.newTaskOptions.reasoningEffort)
        demoPause()

        closeConversationTray()
        compose.onNodeWithTag("composer").performTextInput("Use the selected model")
        demoPause()
        compose.onNodeWithTag("send").performClick()
        compose.waitUntil(15000) { model.state.value.entries.any { it.text == "Hello from Grace" } }
        assertEquals("gpt-fixture-fast", lastThreadStartParams!!.str("model"))
        assertEquals("gpt-fixture-fast", lastTurnStartParams!!.str("model"))
        assertEquals("medium", lastTurnStartParams!!.str("effort"))
        demoPause(2500)

        // Existing tasks reset per-turn overrides when resumed. Reconcile the
        // saved new-chat preferences so this checks catalog changes rather than
        // racing that independent reset during reconnect.
        compose.waitUntil(5000) { !model.state.value.busy }
        compose.runOnUiThread { model.newChat() }
        compose.waitUntil(5000) { model.state.value.thread == null && !model.state.value.busy }
        openConversationTray()
        compose.onNodeWithTag("model-selector").performScrollTo().performClick()
        compose.onNodeWithText("Fixture Fast").performClick()
        compose.onNodeWithTag("reasoning-selector").performScrollTo().performClick()
        compose.onNodeWithText("Medium").performClick()
        assertEquals("gpt-fixture-fast", model.state.value.newTaskOptions.model)
        assertEquals("medium", model.state.value.newTaskOptions.reasoningEffort)
        val previousLists = modelLists.get()
        fastModelAvailable = false
        compose.waitUntil(5000) { !model.state.value.busy }
        compose.runOnUiThread { model.connect() }
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
    fun modelCatalogFailureRecoversOnReconnect() {
        compose.onNodeWithContentDescription("New chat").performClick()
        compose.waitUntil(5000) { model.state.value.page == "chat" }
        closeConversationTray()
        compose.onNodeWithTag("composer").performTextInput("Keep this draft")
        rejectModelList = true
        compose.runOnUiThread { model.connect() }
        compose.waitUntil(5000) {
            model.state.value.modelCatalogStatus == ModelCatalogStatus.Error
        }
        openConversationTray()
        compose.onNodeWithTag("model-selector").assertIsNotEnabled()
        compose.onNodeWithText("Refresh models").assertDoesNotExist()
        compose.onNodeWithText("Refresh models to try again.", substring = true).assertIsDisplayed()
        val previousLists = modelLists.get()
        rejectModelList = false
        compose.waitUntil(5000) { !model.state.value.busy }
        compose.runOnUiThread { model.connect() }
        compose.waitUntil(5000) {
            modelLists.get() > previousLists &&
                model.state.value.modelCatalogStatus == ModelCatalogStatus.Ready
        }
        assertEquals(previousLists + 1, modelLists.get())
        compose.onNodeWithText("Refresh models").assertDoesNotExist()
        compose.onNodeWithTag("model-selector").assertIsEnabled()
        compose.onNodeWithTag("composer").assertTextContains("Keep this draft")
        assertEquals(0, sent.get())
        assertEquals(0, threadStarts.get())
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
    fun imageOnlyUploadsAndRendersThroughStockRpc() {
        compose.runOnUiThread { model.openTask("task-test") }
        compose.waitUntil(10000) {
            model.state.value.thread == "task-test" && !model.state.value.busy
        }
        val image = fixtureImage()
        compose.runOnUiThread { model.addAttachments(listOf(Uri.fromFile(image))) }
        compose.waitUntil(5000) { model.state.value.attachments.size == 1 }
        compose.onNodeWithTag("draft-attachment").assertIsDisplayed()
        compose.onNodeWithTag("send").assertIsEnabled().performClick()
        compose.waitUntil(15000) {
            sent.get() == 1 && model.state.value.entries.any { it.kind == "imageView" }
        }
        assertEquals(
            listOf("text", "localImage"),
            acceptedInput.map { it.jsonObject.str("type") },
        )
        assertTrue(acceptedText.startsWith("# Files mentioned by the user:"))
        assertTrue(acceptedText.contains("## My request for Codex:"))
        assertEquals(1, remoteFiles.size)
        compose.waitUntil(10000) {
            compose.onAllNodesWithTag("message-image").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodesWithTag("message-image")[0].performClick()
        compose.onNodeWithContentDescription("Expanded conversation image")
            .performTouchInput { doubleClick() }
        val viewport = compose.onNodeWithTag("image-viewport")
        viewport.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "300%"))
        viewport.performTouchInput { swipe(center, center + androidx.compose.ui.geometry.Offset(80f, 80f)) }
        viewport.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "300%"))
        viewport.performTouchInput { doubleClick() }
        viewport.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "100%"))
        viewport.performTouchInput {
            val span = width / 8f
            down(0, center - androidx.compose.ui.geometry.Offset(span, 0f))
            down(1, center + androidx.compose.ui.geometry.Offset(span, 0f))
            for (step in 1..12) {
                val distance = span * (1f + step / 12f)
                moveTo(0, center - androidx.compose.ui.geometry.Offset(distance, 0f), delayMillis = 16)
                moveTo(1, center + androidx.compose.ui.geometry.Offset(distance, 0f), delayMillis = 16)
            }
            up(0)
            up(1)
        }
        viewport.assert(SemanticsMatcher("Image is zoomed by pinch") {
            it.config[SemanticsProperties.StateDescription].removeSuffix("%").toInt() > 150
        })
        compose.onNodeWithText("Fit").performClick()
        viewport.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "100%"))
        compose.onNodeWithText("Zoom out").assertIsNotEnabled()
        repeat(4) { compose.onNodeWithText("Zoom in").performClick() }
        viewport.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "500%"))
        compose.onNodeWithText("Zoom in").assertIsNotEnabled()
        compose.onNodeWithTag("close-image").assertIsDisplayed().performClick()
        compose.onAllNodesWithTag("message-image")[0].performClick()
        viewport.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "100%"))
        shell("input keyevent KEYCODE_BACK")
        compose.onNodeWithTag("close-image").assertDoesNotExist()
    }

    @Test
    fun genericFileUploadsAsPathContextAndRendersAHistoryChip() {
        compose.runOnUiThread { model.openTask("task-test") }
        compose.waitUntil(10000) {
            model.state.value.thread == "task-test" && !model.state.value.busy
        }
        val file = File(app.cacheDir, "fixture-notes.txt").apply { writeText("fixture notes") }
        compose.runOnUiThread { model.addFiles(listOf(Uri.fromFile(file))) }
        compose.waitUntil(5000) { model.state.value.attachments.size == 1 }
        compose.onNodeWithTag("draft-file").assertIsDisplayed()
        compose.onNodeWithTag("composer").performTextInput("Review this")
        compose.onNodeWithTag("send").performClick()
        compose.waitUntil(15000) {
            sent.get() == 1 && model.state.value.entries.any { it.kind == "userMessage" }
        }
        assertEquals(listOf("text"), acceptedInput.map { it.jsonObject.str("type") })
        assertTrue(acceptedText.contains("## fixture-notes.txt:"))
        assertTrue(acceptedText.endsWith("Review this"))
        compose.onNodeWithTag("file-reference").assertIsDisplayed()
    }

    @Test
    fun visualizationLoadsFromHistoryExpandsAndShowsMissingFileRecovery() {
        val path = "/fixture/chart.html"
        remoteFiles[path] = Base64.getEncoder().encodeToString("<div id=\"chart\">Fixture visualization</div>".toByteArray())
        historyOverride = obj("data" to JsonArray(listOf(obj(
            "id" to s("visual-turn"), "status" to s("completed"),
            "items" to JsonArray(listOf(obj("id" to s("visual-reply"), "type" to s("agentMessage"),
                "text" to s("Before the chart.\n\nvisualize{\"path\":\"$path\",\"title\":\"Fixture chart\",\"mode\":\"wide\"}\n\nAfter the chart."))))
        ))))
        compose.runOnUiThread { model.openTask("task-test") }
        compose.waitUntil(15000) { compose.onAllNodesWithTag("visualization-webview").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Before the chart.").assertExists()
        compose.onNodeWithText("After the chart.").assertExists()
        compose.onNodeWithTag("expand-visualization").performClick()
        compose.onNodeWithTag("visualization-fullscreen").assertIsDisplayed()
        compose.onNodeWithTag("close-visualization").performClick()
        compose.onNodeWithTag("visualization-fullscreen").assertDoesNotExist()
        compose.runOnUiThread { model.home() }
        remoteFiles.remove(path)
        compose.runOnUiThread { model.openTask("task-test") }
        compose.waitUntil(10000) { compose.onAllNodesWithTag("visualization-error").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Retry").assertExists()
        assertEquals(0, sent.get())
    }

    @Test
    fun remoteTextFileUsesMetadataAndOpensAReadablePreview() {
        compose.runOnUiThread { model.openTask("task-test") }
        compose.waitUntil(10000) {
            model.state.value.thread == "task-test" && !model.state.value.busy
        }
        remoteFiles["/fixture/remote-codex/result.txt"] =
            Base64.getEncoder().encodeToString("hello from remote one".toByteArray())

        compose.runOnUiThread {
            model.inspectFile(FileRef("result", "result.txt", "result.txt"))
        }
        compose.waitUntil(10000) {
            model.state.value.filePreview?.let { !it.loading } == true
        }

        assertEquals(FilePreviewKind.TEXT, model.state.value.filePreview?.kind)
        assertEquals("hello from remote one", model.state.value.filePreview?.text)
        compose.onNodeWithTag("file-preview").assertIsDisplayed()
        compose.onNodeWithText("hello from remote one").assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithTag("file-preview").assertDoesNotExist()

        remoteFiles["/fixture/remote-codex/result.txt"] =
            Base64.getEncoder().encodeToString("hello from remote two".toByteArray())
        compose.runOnUiThread {
            model.inspectFile(FileRef("result", "result.txt", "result.txt"))
        }
        compose.waitUntil(10000) {
            model.state.value.filePreview?.let { !it.loading } == true
        }
        compose.onNodeWithText("hello from remote two").assertIsDisplayed()
    }

    @Test
    fun uncertainAttachmentWriteIsNotReplayed() {
        compose.runOnUiThread { model.openTask("task-test") }
        compose.waitUntil(10000) {
            model.state.value.thread == "task-test" && !model.state.value.busy
        }
        val image = fixtureImage("uncertain.png")
        compose.runOnUiThread { model.addAttachments(listOf(Uri.fromFile(image))) }
        compose.waitUntil(5000) { model.state.value.attachments.size == 1 }
        dropWrite = true
        compose.onNodeWithTag("send").performClick()
        compose.waitUntil(15000) {
            !model.state.value.busy && model.state.value.journal != null
        }
        assertEquals(0, sent.get())
        assertEquals("uploadingAttachment", model.state.value.journal?.str("stage"))
        compose.runOnUiThread { model.connect() }
        compose.waitUntil(15000) { model.state.value.ready && !model.state.value.busy }
        assertEquals(0, sent.get())
        compose.onNodeWithTag("send").assertIsNotEnabled()
    }

    @Test
    fun recentChatsIgnoreMetadataUpdatesAcrossPagesAndSearch() {
        // Keep exact page boundaries independent of the inbox's automatic pagination.
        compose.activity.setContent { androidx.compose.material3.Text("Model fixture") }
        browserResponse = { method, params ->
            if (method in listOf("thread/list", "thread/search")) {
                recencyRequests.add(obj("method" to s(method), "params" to params))
                recencyPage(params, method == "thread/search")
            } else null
        }
        compose.runOnUiThread { model.query(" ") }
        compose.waitUntil(5000) { !model.state.value.listLoading && model.state.value.tasks.firstOrNull()?.str("id") == "recent-chat" }
        compose.runOnUiThread { model.moreTasks() }
        compose.waitUntil(5000) { !model.state.value.listLoading && model.state.value.listCursor == null }
        assertEquals(listOf("recent-chat", "old-lampshades"), model.state.value.tasks.map { it.str("id") })

        compose.runOnUiThread { model.query("chat") }
        compose.waitUntil(5000) {
            !model.state.value.listLoading && model.state.value.tasks.size == 1 && model.state.value.listCursor != null
        }
        assertEquals("recent-chat", model.state.value.tasks.single().str("id"))
        compose.runOnUiThread { model.moreTasks() }
        compose.waitUntil(5000) { !model.state.value.listLoading && model.state.value.listCursor == null }
        assertEquals(listOf("recent-chat", "old-lampshades"), model.state.value.tasks.map { it.str("id") })
        assertEquals(listOf("thread/list", "thread/list", "thread/search", "thread/search"),
            recencyRequests.map { it.str("method") })
        recencyRequests.forEach {
            assertEquals("recency_at", it.map("params").str("sortKey"))
            assertEquals("desc", it.map("params").str("sortDirection"))
        }
        assertEquals(listOf("", "recency-page-2", "", "recency-page-2"),
            recencyRequests.map { it.map("params").str("cursor") })
    }

    @Test
    fun readingEarlierParagraphKeepsNewReplyUnread() {
        browserResponse = { method, params -> if (method == "thread/read")
            obj("thread" to obj("id" to params["threadId"], "status" to obj("type" to s("idle")))) else null }
        compose.runOnUiThread { model.foreground(true) }
        openLongHistory(tallLastMessage = true)
        val id = model.state.value.thread!!
        compose.waitUntil(5000) { model.state.value.chatActivity[id]?.unread == false }
        compose.onNodeWithTag("timeline").performTouchInput {
            swipe(center, center.copy(y = height * .85f), durationMillis = 1200)
        }
        compose.onNodeWithTag("jump-to-latest").assertExists()
        val delta = "\n\nA new ending that has not been viewed."
        val original = historyOverride!!
        val turns = original.list("data").mapIndexed { i, turn -> if (i != 0) turn else
            JsonObject(turn + ("items" to JsonArray(turn.list("items").map { item ->
                if (item.str("id") != "reply-20") item else JsonObject(item + ("text" to s(item.str("text") + delta)))
            }))) }
        historyOverride = obj("data" to JsonArray(turns))
        emit(peer!!, "item/agentMessage/delta", obj("threadId" to s(id), "turnId" to s("history-20"),
            "itemId" to s("reply-20"), "delta" to s(delta)))
        emit(peer!!, "thread/status/changed", obj("threadId" to s(id), "status" to obj("type" to s("idle"))))
        compose.waitUntil(10000) { model.state.value.chatActivity[id]?.unread == true }
        compose.onNodeWithTag("jump-to-latest").performClick()
        compose.waitUntil(5000) { model.state.value.chatActivity[id]?.unread == false }
        compose.onNodeWithText("A new ending that has not been viewed.").assertIsDisplayed()
    }

    @Test
    fun inboxRuntimeAndUnreadFollowServerAndVisibleReply() {
        val runtime = java.util.concurrent.atomic.AtomicReference(obj("type" to s("idle")))
        val text = java.util.concurrent.atomic.AtomicReference("An old reply")
        fun turn() = obj("id" to s("indicator-turn"), "status" to s("completed"), "items" to JsonArray(listOf(
            obj("id" to s("indicator-reply"), "type" to s("agentMessage"), "text" to s(text.get())))))
        browserResponse = { method, params -> when (method) {
            "thread/read" -> obj("thread" to obj("id" to params["threadId"], "status" to runtime.get()))
            "thread/turns/list" -> obj("data" to JsonArray(listOf(turn())))
            else -> null
        } }
        val id = model.state.value.tasks.first().str("id")
        compose.runOnUiThread { model.foreground(true); model.visibleChats(setOf(id)) }
        compose.waitUntil(10000) { model.state.value.chatActivity[id] != null }
        // Wait until the initial reply comparison has established its historical baseline.
        SystemClock.sleep(700)
        assertFalse(model.state.value.chatActivity.getValue(id).unread)
        fun announce(value: JsonObject) {
            runtime.set(value)
            emit(peer!!, "thread/status/changed", obj("threadId" to s(id), "status" to value))
        }
        announce(obj("type" to s("active")))
        compose.waitUntil(5000) { model.state.value.chatActivity[id]?.indicator == ChatIndicator.Working }
        assertNull(model.state.value.thread)
        announce(obj("type" to s("active"), "activeFlags" to JsonArray(listOf(s("waitingOnApproval")))))
        compose.waitUntil(5000) { model.state.value.chatActivity[id]?.indicator == ChatIndicator.Approval }
        announce(obj("type" to s("active"), "activeFlags" to JsonArray(listOf(s("waitingOnUserInput")))))
        compose.waitUntil(5000) { model.state.value.chatActivity[id]?.indicator == ChatIndicator.Input }
        text.set("A new reply to review")
        announce(obj("type" to s("idle")))
        compose.waitUntil(10000) { model.state.value.chatActivity[id]?.indicator == ChatIndicator.Unread }
        // An old remembered selection or backgrounded app must not count as reading.
        compose.runOnUiThread { model.viewedReply(id, replySignature("indicator-turn", turn().list("items"))!!) }
        assertTrue(model.state.value.chatActivity.getValue(id).unread)
        compose.runOnUiThread { model.openTask(id) }
        compose.waitUntil(10000) { !model.state.value.busy && model.state.value.entries.any { it.text == text.get() } }
        compose.waitUntil(5000) { model.state.value.chatActivity[id]?.unread == false }
        compose.runOnUiThread { model.home() }
        compose.waitUntil(5000) { model.state.value.page == "home" }
        announce(obj("type" to s("systemError")))
        compose.waitUntil(5000) { model.state.value.chatActivity[id]?.indicator == ChatIndicator.Error }
        announce(obj("type" to s("notLoaded")))
        compose.waitUntil(5000) { model.state.value.chatActivity[id]?.indicator == ChatIndicator.None }
    }

    @Test
    fun compactInboxVisualAndTyping() {
        browserResponse = { method, _ -> if (method != "thread/list") null else obj("data" to JsonArray(
            listOf("Printer connection", "Guardian review", "Linux printing setup", "Merge the auth bridge", "Printer calibration").mapIndexed { index, title ->
                obj("id" to s("visual-$index"), "name" to s(title), "projectId" to s("project-remote"),
                    "updatedAt" to JsonPrimitive(java.time.Instant.now().epochSecond - (index + 1) * 600))
            })) }
        compose.runOnUiThread { model.retryList() }
        compose.waitUntil(5000) { !model.state.value.listLoading && model.state.value.tasks.any { it.str("id") == "visual-0" } }
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?: app.getExternalFilesDir(null)!!.absolutePath
        fun capture(name: String) {
            compose.waitForIdle()
            val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(output, name).apply { parentFile?.mkdirs() }.outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        capture("compact-app-inbox.png")
        compose.onNodeWithTag("project-control").performClick()
        capture("compact-app-controls.png")
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.onNodeWithText("Search chats").performClick().performTextInput("p")
        compose.onNodeWithText("Search chats").assertIsFocused().performTextInput("rinter")
        assertEquals("printer", model.state.value.query)
        compose.onNodeWithContentDescription("Clear search").performClick()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Archived chats").assertIsDisplayed()
        capture("compact-app-settings.png")
    }

    @Test
    fun compactBrowserQueriesAndPagination() {
        // Remove the UI auto-scroll trigger while exercising exact model pagination boundaries.
        compose.activity.setContent { androidx.compose.material3.Text("Model fixture") }
        browserCalls.clear()
        ChatSort.entries.forEach { sort ->
            compose.runOnUiThread { model.applyListOptions(TaskProjectFilter.Projectless, sort) }
            compose.waitUntil(5000) { !model.state.value.listLoading }
            val call = browserCalls.last().second
            assertEquals(sort.key, call.str("sortKey"))
            assertEquals(sort.direction, call.str("sortDirection"))
            assertTrue(call.containsKey("projectId"))
            assertEquals(JsonNull, call["projectId"])
        }
        compose.runOnUiThread { model.applyListOptions(TaskProjectFilter.All, ChatSort.Recent) }
        compose.waitUntil(5000) { !model.state.value.listLoading }
        assertFalse(browserCalls.last().second.containsKey("projectId"))
        compose.runOnUiThread { model.applyListOptions(TaskProjectFilter.Project("project-remote"), ChatSort.Recent) }
        compose.waitUntil(5000) { !model.state.value.listLoading }
        assertEquals("project-remote", browserCalls.last().second.str("projectId"))
        browserCalls.clear()
        val visited = CopyOnWriteArrayList<String>()
        var failLast = true
        browserResponse = { method, params ->
            if (method != "thread/search") null else {
                val cursor = params.str("cursor")
                visited.add(cursor)
                val row = obj("id" to s(if (cursor == "p2") "match" else "other"),
                    "name" to s("Title does not contain the query"),
                    "projectId" to s(if (cursor == "p2") "project-remote" else "project-notes"))
                when {
                    cursor == "p3" && failLast -> {
                        failLast = false
                        obj("_fixtureError" to obj("code" to JsonPrimitive(-32000), "message" to s("Page unavailable")))
                    }
                    cursor == "p3" -> obj("data" to JsonArray(listOf(obj("thread" to obj("id" to s("match"), "projectId" to s("project-remote"))))))
                    else -> obj("data" to JsonArray(listOf(obj("thread" to row))),
                        "nextCursor" to s(when(cursor) { "" -> "p1"; "p1" -> "p2"; else -> "p3" }))
                }
            }
        }
        compose.runOnUiThread { model.query("body-only needle") }
        compose.waitUntil(5000) { model.state.value.listFailed }
        assertEquals(listOf("", "p1", "p2", "p3"), visited.toList())
        assertEquals(listOf("match"), model.state.value.tasks.map { it.str("id") })
        assertEquals("p3", model.state.value.listCursor)
        assertFalse(browserCalls.last().second.containsKey("projectId"))
        compose.runOnUiThread { model.moreTasks() }
        assertEquals(4, visited.size)
        compose.runOnUiThread { model.retryList(); model.retryList() }
        compose.waitUntil(5000) { !model.state.value.listLoading }
        assertEquals(5, visited.size)
        assertNull(model.state.value.listCursor)
        assertEquals(1, model.state.value.tasks.size)
    }

    @Test
    fun compactBrowserRepeatedCursorAndStaleSearch() {
        compose.activity.setContent { androidx.compose.material3.Text("Model fixture") }
        browserResponse = { method, params -> if (method != "thread/search") null else {
            if (params.str("searchTerm") == "slow") Thread.sleep(600)
            obj("data" to JsonArray(listOf(obj("thread" to obj("id" to s(params.str("searchTerm")),
                "name" to s("Search result"))))), "nextCursor" to s("repeat"))
        } }
        compose.runOnUiThread { model.query("slow") }
        compose.waitUntil(5000) { browserCalls.any { it.first == "thread/search" && it.second.str("searchTerm") == "slow" } }
        compose.runOnUiThread { model.query("new") }
        compose.waitUntil(5000) { !model.state.value.listLoading && model.state.value.tasks.any { it.str("id") == "new" } }
        assertEquals(listOf("new"), model.state.value.tasks.map { it.str("id") })
        compose.runOnUiThread { model.moreTasks(); model.moreTasks() }
        compose.waitUntil(5000) { model.state.value.listFailed }
        assertEquals("repeat", model.state.value.listCursor)
        assertEquals(1, model.state.value.tasks.size)
        assertEquals(1, browserCalls.count { it.first == "thread/search" && it.second.str("cursor") == "repeat" })
    }

    @Test
    fun compactArchivesRestoreAndNavigation() {
        archivedTaskIds.add("task-test")
        compose.activity.setContent { androidx.compose.material3.Text("Model fixture") }
        compose.runOnUiThread { model.query("inbox query") }
        compose.waitUntil(5000) { !model.state.value.listLoading }
        compose.runOnUiThread { model.listPosition(3, 12); model.settings(); model.openArchives() }
        compose.waitUntil(5000) { !model.state.value.listLoading }
        assertEquals("", model.state.value.query)
        assertTrue(model.state.value.archived)
        assertEquals(JsonPrimitive(true), browserCalls.last().second["archived"])
        compose.runOnUiThread { model.query("archive query") }
        compose.waitUntil(5000) { !model.state.value.listLoading }
        compose.runOnUiThread { model.openTask("task-test") }
        compose.waitUntil(5000) { model.state.value.page == "chat" && !model.state.value.busy }
        compose.runOnUiThread { model.back() }
        assertEquals("archives", model.state.value.page)
        assertEquals("archive query", model.state.value.query)
        compose.runOnUiThread { model.back(); model.back() }
        assertEquals("home", model.state.value.page)
        assertFalse(model.state.value.archived)
        assertEquals("inbox query", model.state.value.query)
        assertEquals(3, model.state.value.listIndex)
        assertEquals(12, model.state.value.listOffset)
        compose.runOnUiThread { model.settings(); model.openArchives() }
        compose.runOnUiThread { model.query("") }
        compose.waitUntil(5000) { !model.state.value.listLoading }
        rejectArchive = true
        compose.runOnUiThread { model.archiveTask("task-test", false); model.archiveTask("task-test", false) }
        compose.waitUntil(5000) { model.state.value.error?.contains("rejected") == true }
        assertTrue(model.state.value.tasks.any { it.str("id") == "task-test" })
        assertTrue(model.state.value.uncertainTaskActions.isEmpty())
        assertEquals(1, archiveMutations.size)
        rejectArchive = false
        compose.runOnUiThread { model.archiveTask("task-test", false) }
        compose.waitUntil(5000) { !model.state.value.listLoading && model.state.value.taskNotice?.message == "Task unarchived" }
        assertFalse(model.state.value.tasks.any { it.str("id") == "task-test" })
        compose.runOnUiThread { model.home() }
        compose.waitUntil(5000) { !model.state.value.listLoading && model.state.value.tasks.any { it.str("id") == "task-test" } }
        assertEquals("inbox query", model.state.value.query)
        assertEquals(2, archiveMutations.size)
    }

    @Test
    fun changingSearchImmediatelyInvalidatesLoadedTasks() {
        assertTrue(model.state.value.tasks.isNotEmpty())
        compose.runOnUiThread {
            model.query("fixture")
            assertTrue(model.state.value.tasks.isEmpty())
            assertNull(model.state.value.listCursor)
            assertTrue(model.state.value.listLoading)
        }
        compose.waitUntil(5000) { !model.state.value.listLoading }
        assertFalse(model.state.value.listFailed)
        compose.onNodeWithContentDescription("Clear search").performClick()
        compose.waitUntil(5000) {
            model.state.value.query.isEmpty() && !model.state.value.listLoading &&
                model.state.value.tasks.isNotEmpty()
        }
    }

    @Test
    fun taskBrowserExcludesInternalReviewers() {
        fun assertInteractiveTasks() {
            assertEquals(
                listOf("task-test", "project-task"),
                model.state.value.tasks.map { it.str("id") },
            )
            compose.onNodeWithText("Internal reviewer", substring = true).assertDoesNotExist()
            compose.onNodeWithText(fixtureTitle).assertIsDisplayed()
        }

        assertInteractiveTasks()
        for (search in listOf(false, true)) {
            for (archived in listOf(false, true)) {
                val count = browserRequests.size
                compose.runOnUiThread {
                    if (archived) model.openArchives() else model.home()
                    model.query(if (search) "fixture" else "")
                }
                compose.waitUntil(5000) { browserRequests.size > count && !model.state.value.listLoading }
                compose.waitForIdle()
                assertInteractiveTasks()
                val request = browserRequests.last()
                assertEquals(archived, request["archived"]?.jsonPrimitive?.boolean)
                assertEquals(if (search) "fixture" else "", request.str("searchTerm"))
                assertTrue((request["sourceKinds"] as? JsonArray).isNullOrEmpty())
            }
        }
    }

    @Test
    fun pullToRefreshUpdatesStaleTaskList() {
        compose.onNodeWithText(fixtureTitle).assertIsDisplayed()
        fixtureTitle = "Task submitted elsewhere"
        compose.onNodeWithText(fixtureTitle).assertDoesNotExist()
        compose.onNodeWithTag("chat-list").performTouchInput { swipeDown() }
        compose.waitUntil(5000) {
            model.state.value.tasks.any { it.str("name") == fixtureTitle }
        }
        compose.onNodeWithText(fixtureTitle).assertIsDisplayed()
        assertFalse(model.state.value.refreshingTasks)
    }

    @Test
    fun pullToRefreshEmptyFilteredListRecoversAfterFailure() {
        emptyTaskList = true
        compose.runOnUiThread {
            model.applyListOptions(TaskProjectFilter.Projectless, ChatSort.Recent)
        }
        compose.waitUntil(5000) { model.state.value.tasks.isEmpty() }
        compose.onNodeWithText("No matching chats").assertIsDisplayed()
        holdTaskList = true
        val list = compose.onNodeWithTag("chat-list")
        list.performTouchInput { swipeDown() }
        compose.waitUntil(5000) { heldTaskLists.size == 1 && model.state.value.refreshingTasks }
        compose.runOnUiThread { model.refreshTasks() }
        compose.waitForIdle()
        assertEquals(1, heldTaskLists.size)
        val request = heldTaskLists.single()
        assertEquals(JsonNull, request.map("params")["projectId"])
        assertNull(request.map("params")["cursor"])
        peer!!.send(obj("id" to request["id"], "error" to obj("code" to JsonPrimitive(-32000), "message" to s("Refresh unavailable"))).toString())
        compose.waitUntil(5000) { !model.state.value.refreshingTasks && model.state.value.listFailed }
        assertTrue(model.state.value.tasks.isEmpty())
        holdTaskList = false
        emptyTaskList = false
        list.performTouchInput { swipeDown() }
        compose.waitUntil(5000) { model.state.value.tasks.isNotEmpty() && !model.state.value.refreshingTasks }
        assertEquals(listOf("task-test"), model.state.value.tasks.map { it.str("id") })
        assertEquals(TaskProjectFilter.Projectless, model.state.value.projectFilter)
        assertFalse(model.state.value.listFailed)
        compose.onNodeWithText(fixtureTitle).assertIsDisplayed()
    }

    @Test
    fun projectsAndChatsFilterTaskBrowser() {
        assertEquals(listOf("Remote Codex", "Notes"), model.state.value.projects.map { it.name })
        demoPause(2000)

        compose.onNodeWithText("All projects").performClick()
        compose.onNode(hasText("Remote Codex") and isSelectable()).performClick()
        compose.onNodeWithText("Apply").performClick()
        compose.waitUntil(5000) {
            model.state.value.projectFilter == TaskProjectFilter.Project("project-remote") &&
                model.state.value.tasks.map { it.str("id") } == listOf("project-task")
        }
        compose.onNodeWithText("Remote Codex project task").assertIsDisplayed()
        compose.onNodeWithText(fixtureTitle).assertDoesNotExist()
        demoPause(2000)

        compose.onNodeWithTag("project-control").performClick()
        compose.onNodeWithText("No project").performClick()
        compose.onNodeWithText("Apply").performClick()
        compose.waitUntil(5000) {
            model.state.value.projectFilter == TaskProjectFilter.Projectless &&
                model.state.value.tasks.map { it.str("id") } == listOf("task-test")
        }
        compose.onNodeWithText(fixtureTitle).assertIsDisplayed()
        compose.onNodeWithText("Remote Codex project task").assertDoesNotExist()
        demoPause(2000)

        compose.onNodeWithContentDescription("New chat").performClick()
        compose.waitUntil(5000) { model.state.value.page == "chat" }
        demoPause(2000)
        compose.onNodeWithTag("project-selector").performClick()
        compose.onNodeWithText("Remote Codex").assertIsDisplayed()
        demoPause(2000)
        compose.onNodeWithText("Remote Codex").performClick()
        compose.waitUntil(5000) {
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
        compose.waitUntil(5000) { model.state.value.page == "chat" }
        compose.onNodeWithTag("project-selector").assertTextContains("No project").performClick()
        compose.onNodeWithText("Remote Codex").performClick()
        compose.waitUntil(5000) {
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
        compose.waitUntil(5000) { model.state.value.thread == null }
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
        compose.waitUntil(5000) { model.state.value.page == "chat" }
        demoPause()
        compose.onNodeWithTag("composer").performTextInput("Keep this idea")
        val image = fixtureImage("persist.png")
        compose.runOnUiThread { model.addAttachments(listOf(Uri.fromFile(image))) }
        compose.waitUntil(5000) {
            runBlocking { LocalStore(app).get("draft/new") } == "Keep this idea" &&
                model.state.value.attachments.size == 1
        }
        demoPause(2500)
        compose.runOnUiThread {
            store.clear()
            model = ClientModel(app, "ws://127.0.0.1:${server.port}/rpc", "/fixture", true)
            store.put("fixture", model)
            compose.activity.setContent { RemoteTheme { App(model) } }
            model.newChat()
        }
        compose.waitUntil(5000) {
            model.state.value.draft == "Keep this idea" &&
                model.state.value.attachments.size == 1
        }
        compose.onNodeWithTag("composer").assertTextContains("Keep this idea")
        compose.onNodeWithTag("draft-attachment").assertIsDisplayed()
        demoPause(3500)
    }

    private fun reportField(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("report-content").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag)
    }

    @Test
    fun researchReportReviewsEditsAndFreezesTheRequestAcrossRecreation() {
        compose.runOnUiThread { model.reports.open() }
        compose.waitUntil(10000) { model.reports.state.value.visible && !model.reports.state.value.capturing }
        val description = "Research swipe gestures for the task list. Don't build it; compare options."
        reportField("report-intent-Research").performClick()
        reportField("bug-description").performTextInput(description)
        reportField("report-intent-Plan").performClick()
        assertEquals(description, model.reports.state.value.draft!!.description)
        reportField("report-intent-Research").performClick()
        compose.onNodeWithTag("submit-bug-report").performClick()
        compose.waitUntil(10000) { model.reports.state.value.draft?.review?.isNotEmpty() == true }
        assertEquals(0, worktreeAdds.get())
        assertEquals(0, sent.get())
        compose.onNodeWithTag("edit-report-request").performClick()
        reportField("remove-report-context.txt").performClick()
        compose.waitUntil(5000) { model.reports.state.value.draft?.attachments?.none { it.id == "context.txt" } == true && !model.reports.state.value.busy }
        compose.onNodeWithTag("submit-bug-report").performClick()
        compose.waitUntil(10000) { model.reports.state.value.draft?.review?.isNotEmpty() == true }
        reportField("report-title").performTextReplacement("Research: Task-list gestures")
        compose.waitUntil(5000) { BugReportStore(File(app.filesDir, "bug-reports")).load()?.title == "Research: Task-list gestures" }
        val reviewed = model.reports.state.value.draft!!.review
        assertTrue(reviewed.str("prompt").contains(description))
        assertTrue(reviewed.str("prompt").contains("Do not implement changes."))
        assertFalse(reviewed.str("prompt").contains("This is an implementation task."))
        compose.runOnUiThread {
            store.clear()
            model = ClientModel(app, "ws://127.0.0.1:${server.port}/rpc", "/fixture", true, "/fixture/remote-codex")
            store.put("fixture", model)
            compose.activity.setContent { RemoteTheme { App(model) } }
            model.foreground(true)
        }
        compose.waitUntil(15000) { model.reports.state.value.loaded && model.state.value.ready }
        assertEquals(0, threadStarts.get())
        assertEquals(reviewed, model.reports.state.value.draft!!.review)
        compose.runOnUiThread { model.reports.open() }
        reportField("report-title").assertTextContains("Research: Task-list gestures")
        compose.onNodeWithTag("submit-bug-report").assertTextContains("Start research task")
        // Two calls in the same UI turn must still dispatch just one submission.
        compose.runOnUiThread { model.reports.submit(); model.reports.submit() }
        compose.waitUntil(20000) { model.reports.state.value.lastTask == "task-test" }
        assertEquals(1, threadStarts.get())
        assertEquals(1, sent.get())
        assertEquals("plan", lastTurnStartParams!!.map("collaborationMode").str("mode"))
        assertTrue(acceptedText.contains(reviewed.str("prompt")))
        assertFalse(remoteFiles.keys.any { it.contains("/report/") && it.endsWith("context.txt") })
    }

    @Test
    fun reportUnavailableModeKeepsDraftWithoutCreatingTask() {
        compose.runOnUiThread { model.reports.open() }
        compose.waitUntil(10000) { model.reports.state.value.visible && !model.reports.state.value.capturing }
        reportField("report-intent-Plan").performClick()
        reportField("bug-description").performTextInput("Plan better quick actions")
        browserResponse = { method, _ -> if (method == "collaborationMode/list") obj("data" to JsonArray(emptyList())) else null }
        compose.onNodeWithTag("submit-bug-report").performClick()
        compose.waitUntil(10000) { model.reports.state.value.error?.contains("requires an available") == true }
        assertTrue(model.reports.state.value.draft!!.review.isEmpty())
        assertEquals(ReportIntent.Plan, model.reports.state.value.draft!!.intent)
        assertEquals(0, worktreeAdds.get())
        assertEquals(0, threadStarts.get())
    }

    @Test
    fun bugReportCapturesScreenAndStartsIsolatedFixTask() {
        compose.runOnUiThread { model.newChat() }
        compose.waitUntil(5000) { model.state.value.page == "chat" }
        compose.onNodeWithTag("composer").performTextInput("Keep my original draft")
        compose.onNodeWithTag("app-menu").performClick()
        compose.onNodeWithTag("report-bug").performClick()
        compose.waitUntil(10000) { model.reports.state.value.visible && !model.reports.state.value.capturing }
        val draft = requireNotNull(model.reports.state.value.draft)
        assertEquals("chat", draft.context.str("screen"))
        assertEquals("Keep my original draft", draft.context.str("draft"))
        assertEquals("captured", draft.diagnostics.map("screenshot").str("status"))
        val screenshot = draft.attachments.single { it.id == "screenshot.png" }
        assertNotNull(android.graphics.BitmapFactory.decodeFile(screenshot.localPath))
        compose.onNodeWithTag("submit-bug-report").assertIsNotEnabled()
        reportField("bug-description").performTextInput("The queue button lost my message")
        compose.onNodeWithTag("submit-bug-report").assertIsNotEnabled()
        reportField("report-intent-Implement").performClick()
        compose.onNodeWithTag("submit-bug-report").performClick()
        compose.waitUntil(10000) { model.reports.state.value.draft?.review?.isNotEmpty() == true }
        assertEquals(0, threadStarts.get())
        assertEquals(0, worktreeAdds.get())
        compose.onNodeWithTag("submit-bug-report").assertTextContains("Start implementation task").performClick()
        compose.waitUntil(20000) { model.reports.state.value.lastTask == "task-test" && model.reports.state.value.draft == null }
        assertEquals("default", lastTurnStartParams!!.map("collaborationMode").str("mode"))
        assertEquals(1, environmentSetups.get())
        assertEquals(1, worktreeAdds.get())
        assertEquals(1, threadStarts.get())
        assertEquals("project-remote", threadStartParams!!.str("projectId"))
        assertTrue(remoteFiles.keys.any { it.contains("/report/") && it.contains("screenshot") })
        assertTrue(acceptedText.contains("The queue button lost my message"))
        assertEquals("Keep my original draft", runBlocking { LocalStore(app).get("draft/new") })
        assertFalse(File(screenshot.localPath).exists())
        compose.waitUntil(5000) { !model.reports.state.value.busy && !model.reports.state.value.visible }
        compose.waitForIdle()
        assertEquals("chat", model.state.value.page)
        assertNull(model.state.value.thread)
        compose.onNodeWithTag("composer").assertTextContains("Keep my original draft")
        // Opening the fix task remains an explicit action.
        compose.onNodeWithTag("app-menu").performClick()
        compose.onNodeWithText("Last report task").performClick()
        compose.waitUntil(10000) { model.state.value.thread == "task-test" && !model.state.value.busy }
    }

    @Test
    fun systemScreenshotOffersReportWithTheCapturedWindow() {
        compose.waitUntil(5000) { model.reports.state.value.loaded }
        compose.runOnUiThread { model.newChat() }
        compose.waitUntil(5000) { model.state.value.page == "chat" }
        compose.onNodeWithTag("composer").performTextInput("Screenshot this draft")
        shell("input keyevent KEYCODE_SYSRQ")
        compose.waitUntil(10000) { compose.onAllNodesWithText("Report or request").fetchSemanticsNodes().isNotEmpty() }
        assertFalse(model.reports.state.value.visible)
        val actionBounds = compose.onNodeWithText("Report or request").fetchSemanticsNode().boundsInRoot
        val messageBounds = compose.onNodeWithText("Screenshot taken").fetchSemanticsNode().boundsInRoot
        assertTrue("Report action should be left of the screenshot message", actionBounds.right <= messageBounds.left)
        compose.onNodeWithText("Report or request").performClick()
        compose.waitUntil(10000) { model.reports.state.value.visible && !model.reports.state.value.capturing }
        val draft = requireNotNull(model.reports.state.value.draft)
        assertEquals("Screenshot this draft", draft.context.str("draft"))
        assertEquals("captured", draft.diagnostics.map("screenshot").str("status"))
        val screenshot = draft.attachments.single { it.id == "screenshot.png" }
        assertNotNull(android.graphics.BitmapFactory.decodeFile(screenshot.localPath))
    }

    @Test
    fun bugReportScreenshotSurvivesOfflineRecreationAndCanBeRemoved() {
        compose.runOnUiThread {
            model.reports.open {
                assertFalse(model.reports.state.value.visible)
                assertTrue(model.reports.state.value.capturing)
                captureBugReportScreenshot(compose.activity)
            }
        }
        compose.waitUntil(10000) { model.reports.state.value.visible && !model.reports.state.value.capturing }
        val original = requireNotNull(model.reports.state.value.draft)
        val screenshot = original.attachments.single { it.id == "screenshot.png" }
        val originalBytes = File(screenshot.localPath).readBytes()
        reportField("report-intent-Research").performClick()
        reportField("bug-description").performTextInput("Remember this offline")
        compose.waitUntil(5000) { BugReportStore(File(app.filesDir, "bug-reports")).load()?.description == "Remember this offline" }
        compose.runOnUiThread {
            store.clear()
            model = ClientModel(app, "ws://127.0.0.1:${server.port}/rpc", "/fixture", true, "/fixture/remote-codex")
            store.put("fixture", model)
            compose.activity.setContent { RemoteTheme { App(model) } }
        }
        compose.waitUntil(5000) { model.reports.state.value.loaded }
        var recaptured = false
        compose.runOnUiThread { model.reports.open { recaptured = true; byteArrayOf() } }
        reportField("bug-description").assertTextContains("Remember this offline")
        assertEquals(ReportIntent.Research, model.reports.state.value.draft!!.intent)
        assertFalse(recaptured)
        assertEquals(original.capturedAt, model.reports.state.value.draft!!.capturedAt)
        assertArrayEquals(originalBytes, File(screenshot.localPath).readBytes())
        compose.onNodeWithTag("submit-bug-report").assertTextContains("Save report")
        compose.onNodeWithTag("submit-bug-report").performClick()
        compose.waitUntil(5000) { !model.reports.state.value.visible }
        assertEquals(0, threadStarts.get())
        compose.runOnUiThread { model.reports.open() }
        compose.runOnUiThread { model.reports.removeAttachment("screenshot.png") }
        compose.waitUntil(5000) { model.reports.state.value.draft!!.attachments.none { it.id == "screenshot.png" } }
        assertFalse(File(screenshot.localPath).exists())
        reportField("discard-bug-report").performClick()
        compose.waitUntil(5000) { model.reports.state.value.draft == null }
        assertFalse(File(app.filesDir, "bug-reports/${original.id}").exists())
    }

    @Test
    fun bugReportSettingsExcludesScreenshotAndPersistsShakePreference() {
        assertFalse(model.reports.state.value.shakeEnabled)
        compose.runOnUiThread { model.settings(); model.reports.shakeEnabled(false) }
        compose.onNodeWithTag("app-menu").performClick()
        compose.onNodeWithTag("report-bug").performClick()
        compose.waitUntil(10000) { model.reports.state.value.visible && !model.reports.state.value.capturing }
        val report = requireNotNull(model.reports.state.value.draft)
        assertEquals("omitted", report.diagnostics.map("screenshot").str("status"))
        assertTrue(report.attachments.none { it.id == "screenshot.png" })
        assertFalse(report.context.toString().contains("fixture-credential"))
        assertEquals("false", runBlocking { LocalStore(app).get("bug-report/shake") })
        compose.onNodeWithTag("close-bug-report").performClick()
        assertEquals(report.id, model.reports.state.value.draft!!.id)
    }

    @Test
    fun bugReportCaptureFailureStillAllowsSubmissionOnCoverDisplay() {
        reportCoverOverride = true
        shell("wm size 1080x1272")
        shell("wm density 420")
        compose.waitForIdle()
        compose.runOnUiThread { model.reports.open { error("Fixture capture failure") } }
        compose.waitUntil(10000) { model.reports.state.value.visible && !model.reports.state.value.capturing }
        assertEquals("unavailable", model.reports.state.value.draft!!.diagnostics.map("screenshot").str("status"))
        reportField("report-intent-Investigate").performClick()
        reportField("bug-description").performTextInput("A cover-screen bug")
        compose.onNodeWithTag("submit-bug-report").assertIsDisplayed().performClick()
        compose.waitUntil(10000) { model.reports.state.value.draft?.review?.isNotEmpty() == true }
        reportField("report-title").assertIsDisplayed()
        compose.onNodeWithTag("submit-bug-report").assertTextContains("Start investigation").assertIsDisplayed().performClick()
        compose.waitUntil(20000) { model.reports.state.value.lastTask == "task-test" }
        assertEquals("plan", lastTurnStartParams!!.map("collaborationMode").str("mode"))
        assertEquals(1, sent.get())
    }

    @Test
    fun selectedProjectCanRunInANewIsolatedWorktree() {
        compose.onNodeWithContentDescription("New chat").performClick()
        compose.waitUntil(5000) { model.state.value.page == "chat" }
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
        openConversationTray()
        compose.onNodeWithTag("workspace-current").assertIsSelected()
        demoPause(2200)
        openConversationTray()
        compose.onNodeWithTag("workspace-new-worktree").performScrollTo().performClick()
        openConversationTray()
        compose.onNodeWithTag("workspace-new-worktree").assertIsSelected()
        demoPause(1800)
        closeConversationTray()
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
        assertTrue(workspaceMetadataReads.get() > 0)
        assertEquals(0, invalidDirectoryProbes.get())
        demoPause(3500)
    }

    @Test
    fun selectedProjectCanUseItsCurrentWorkspaceWithoutGitMutation() {
        compose.onNodeWithContentDescription("New chat").performClick()
        compose.waitUntil(5000) { model.state.value.page == "chat" }
        compose.runOnUiThread {
            model.updateNewTaskOptions(
                NewTaskOptions(
                    projectId = "project-remote",
                    workingDirectory = "/fixture/remote-codex",
                    executionTarget = ExecutionTarget.CurrentWorkspace,
                )
            )
        }
        openConversationTray()
        compose.onNodeWithTag("workspace-current").assertIsSelected()
        closeConversationTray()
        compose.onNodeWithTag("composer").performTextInput("Use the selected checkout")
        compose.onNodeWithTag("send").performClick()

        compose.waitUntil(15000) { sent.get() == 1 && model.state.value.journal == null }
        assertEquals(0, worktreeAdds.get())
        assertEquals("project-remote", threadStartParams!!.str("projectId"))
        assertEquals("/fixture/remote-codex", threadStartParams!!.str("cwd"))
        assertTrue(workspaceMetadataReads.get() > 0)
        assertEquals(0, invalidDirectoryProbes.get())
    }

    @Test
    fun rejectedWorkspaceValidationPreservesDraftAndAllowsRetry() {
        rejectWorkspaceMetadata = true
        compose.onNodeWithContentDescription("New chat").performClick()
        compose.waitUntil(5000) { model.state.value.page == "chat" }
        compose.runOnUiThread {
            model.updateNewTaskOptions(
                NewTaskOptions(
                    projectId = "project-remote",
                    workingDirectory = "/fixture/remote-codex",
                    executionTarget = ExecutionTarget.CurrentWorkspace,
                )
            )
        }
        compose.onNodeWithTag("composer").performTextInput("Keep this project draft")
        val image = fixtureImage("validation-retry.png")
        compose.runOnUiThread { model.addAttachments(listOf(Uri.fromFile(image))) }
        compose.waitUntil(5000) { model.state.value.attachments.size == 1 }
        compose.onNodeWithTag("send").assertIsEnabled().performClick()

        compose.waitUntil(5000) { workspaceMetadataRejections.get() == 1 }
        compose.waitUntil(10000) {
            !model.state.value.busy &&
                model.state.value.journal == null &&
                model.state.value.error != null
        }
        assertEquals(
            "The host could not inspect the selected workspace. Refresh projects and try again.",
            model.state.value.error,
        )
        assertEquals(0, threadStarts.get())
        assertEquals(0, invalidDirectoryProbes.get())
        assertEquals(
            "Keep this project draft",
            runBlocking { LocalStore(app).get("draft/new") },
        )
        compose.onNodeWithTag("composer").assertTextContains("Keep this project draft")
        assertEquals(1, model.state.value.attachments.size)
        compose.onNodeWithTag("draft-attachment").assertIsDisplayed()

        rejectWorkspaceMetadata = false
        compose.onNodeWithTag("send").performClick()
        compose.waitUntil(15000) { sent.get() == 1 && model.state.value.journal == null }
        assertEquals(1, threadStarts.get())
    }

    @Test
    fun selectedProjectPathDoesNotExpandComposerFooter() {
        compose.onNodeWithContentDescription("New chat").performClick()
        compose.waitUntil(5000) { model.state.value.page == "chat" }
        compose.runOnUiThread {
            model.updateNewTaskOptions(
                NewTaskOptions(
                    projectId = "project-remote",
                    workingDirectory =
                        "/home/agent/workspaces/remote-codex/with-an-intentionally-long-path",
                    executionTarget = ExecutionTarget.CurrentWorkspace,
                )
            )
        }

        val statusBounds = compose.onNodeWithTag("composer-status").getUnclippedBoundsInRoot()
        assertTrue(
            "Composer status should stay within its two-line limit",
            statusBounds.bottom - statusBounds.top <= 40.dp,
        )
        compose.onNodeWithTag("send").assertIsDisplayed()
    }

    @Test
    fun uncertainWorktreeCreationIsInspectedAndNotRepeated() {
        dropWorktreeReply = true
        compose.onNodeWithContentDescription("New chat").performClick()
        compose.waitUntil(5000) { model.state.value.page == "chat" }
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
        compose.waitUntil(5000) { model.state.value.page == "chat" }
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

    @Test
    fun longPlanHeadingsStayReadableInlineAndFullscreen() {
        val title = "Prevent socket fixes from being lost during deployments"
        planText = "# $title\n\n## Summary\n\nKeep the actual clients working against the actual server.\n\n" +
            "### Validation\n\n1. Inspect the code\n2. Verify the change with `checks`."
        compose.onNodeWithContentDescription("New chat").performClick()
        compose.waitUntil(5000) {
            model.state.value.page == "chat" && model.state.value.collaborationModes.size == 2
        }
        openConversationTray()
        compose.onNodeWithTag("mode-plan").performScrollTo().performClick()
        closeConversationTray()
        compose.onNodeWithTag("composer").performTextInput("Propose a safe change")
        compose.onNodeWithTag("send").performClick()
        compose.waitUntil(10000) {
            model.state.value.entries.any { it.kind == "plan" && it.completed }
        }

        fun assertCompactTitle(container: String) {
            val inContainer = hasAnyAncestor(hasTestTag(container))
            val titleMatcher = hasText(title) and inContainer
            compose.waitUntil(5000) {
                compose.onAllNodes(titleMatcher).fetchSemanticsNodes().isNotEmpty()
            }
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            compose.onNode(titleMatcher).performSemanticsAction(
                androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult
            ) { it(layouts) }
            assertTrue("Title should wrap naturally within three lines", layouts.single().lineCount <= 3)
            assertTrue("Plan title must use reading typography", layouts.single().layoutInput.style.fontSize.value <= 24f)
            compose.onNode(titleMatcher).assertIsDisplayed()
            compose.onNode(hasText("Summary") and inContainer).assertIsDisplayed()
        }

        compose.onNodeWithTag("open-plan-fullscreen").performScrollTo()
        assertCompactTitle("plan-card")
        compose.onNodeWithTag("open-plan-fullscreen").performClick()
        compose.onNodeWithTag("plan-fullscreen").assertIsDisplayed()
        assertCompactTitle("plan-fullscreen")
        compose.onNodeWithTag("implement-plan-fullscreen").assertIsDisplayed()
        compose.onNodeWithTag("close-plan-fullscreen").performClick()
        compose.onNodeWithTag("plan-fullscreen").assertDoesNotExist()
    }

    @Test
    fun advertisedPlanModeRendersFullscreenPlanAndImplementsWithDefaultMode() {
        compose.onNodeWithContentDescription("New chat").performClick()
        compose.waitUntil(5000) {
            model.state.value.page == "chat" && model.state.value.collaborationModes.size == 2
        }
        openConversationTray()
        compose.onNodeWithTag("mode-plan").performScrollTo().performClick()
        closeConversationTray()
        compose.onNodeWithTag("conversation-settings").assertTextContains("Plan", substring = true)
        closeConversationTray()
        compose.onNodeWithTag("composer").performTextInput("Propose a safe change")
        compose.onNodeWithTag("send").performClick()

        compose.waitUntil(10000) {
            model.state.value.entries.any { it.kind == "plan" && it.completed }
        }
        compose.onNodeWithTag("plan-card").assertIsDisplayed()
        assertEquals(
            "1. Inspect the code\n2. Make the change",
            model.state.value.entries.single { it.kind == "plan" }.text,
        )
        compose.onNodeWithTag("implement-plan").assertIsDisplayed()
        demoPause(2200)
        compose.onNodeWithTag("open-plan-fullscreen").performClick()
        compose.onNodeWithTag("plan-fullscreen").assertIsDisplayed()
        compose.onNodeWithTag("implement-plan-fullscreen").assertIsDisplayed()
        demoPause(3500)

        val planRequest = turnRequests.single()
        assertFalse(threadStartParams!!.containsKey("collaborationMode"))
        assertEquals("plan", planRequest.map("collaborationMode").str("mode"))
        assertEquals(
            "gpt-fixture",
            planRequest.map("collaborationMode").map("settings").str("model"),
        )
        assertEquals(
            "high",
            planRequest.map("collaborationMode").map("settings").str("reasoning_effort"),
        )

        compose.onNodeWithTag("implement-plan-fullscreen").performClick()
        compose.onNodeWithTag("plan-fullscreen").assertDoesNotExist()
        compose.waitUntil(10000) { turnRequests.size == 2 }
        demoPause(1800)
        val implementRequest = turnRequests.last()
        assertEquals(
            "Implement the proposed plan.",
            implementRequest.list("input").single().str("text"),
        )
        assertEquals("default", implementRequest.map("collaborationMode").str("mode"))
        assertEquals(
            "medium",
            implementRequest.map("collaborationMode").map("settings").str("reasoning_effort"),
        )
    }

    @Test
    fun planModeQuestionCanBeAnswered() {
        askPlanQuestion = true
        compose.onNodeWithContentDescription("New chat").performClick()
        compose.waitUntil(5000) {
            model.state.value.page == "chat" && model.state.value.collaborationModes.size == 2
        }
        openConversationTray()
        compose.onNodeWithTag("mode-plan").performScrollTo().performClick()
        closeConversationTray()
        compose.onNodeWithTag("composer").performTextInput("Plan after clarifying the scope")
        compose.onNodeWithTag("send").performClick()

        compose.waitUntil(10000) { model.state.value.decisions.size == 1 }
        if (coverScreen) {
            compose.onNodeWithTag("composer").assertDoesNotExist()
        } else {
            compose.onNodeWithTag("composer-status")
                .assertTextContains("Follow-up guides the active turn")
            val actionHeight =
                compose.onNodeWithTag("composer-actions").fetchSemanticsNode().boundsInRoot.height
            val maxActionHeight = 64 * compose.activity.resources.displayMetrics.density
            assertTrue(
                "Active-turn composer actions expanded to $actionHeight px",
                actionHeight <= maxActionHeight,
            )
        }
        compose.onNodeWithText("Where should the plan focus?").assertIsDisplayed()
        compose.onNodeWithText("Current workspace").performClick()
        compose.onNodeWithText("Submit answers").performClick()

        compose.waitUntil(10000) { userInputResponses.size == 1 }
        val answerValues =
            userInputResponses.single().map("answers").map("scope")["answers"] as JsonArray
        assertEquals("Current workspace", answerValues.single().jsonPrimitive.content)
        assertTrue(model.state.value.decisions.isEmpty())
        assertEquals("plan", turnRequests.single().map("collaborationMode").str("mode"))
    }
}
