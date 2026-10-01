package dev.codexops.client

import android.app.Application
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
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
import org.junit.Assert.*

class AppTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var server: MockWebServer
    private lateinit var model: ClientModel
    private val store = ViewModelStore()
    private val sent = AtomicInteger()
    private val prepared = AtomicInteger()
    private val modelLists = AtomicInteger()
    private val workspaceMetadataReads = AtomicInteger()
    private val workspaceMetadataRejections = AtomicInteger()
    private val invalidDirectoryProbes = AtomicInteger()
    @Volatile private var dropSend = false
    @Volatile private var dropWrite = false
    @Volatile private var fastModelAvailable = true
    @Volatile private var peer: WebSocket? = null
    @Volatile private var acceptedText = ""
    @Volatile private var acceptedInput = JsonArray(emptyList())
    private val remoteFiles = ConcurrentHashMap<String, String>()
    @Volatile private var lastThreadStartParams: JsonObject? = null
    @Volatile private var lastTurnStartParams: JsonObject? = null
    @Volatile private var historyOverride: JsonObject? = null
    @Volatile private var fixtureTitle = "Fixture task"
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
        if (coverScreen) {
            shell("wm size 1080x1272")
            shell("wm density 420")
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
                                    if (method.startsWith("thread/queue/") && method != "thread/queue/list")
                                        queueMutations.add(m)
                                    val result =
                                        when (method) {
                                            "initialize" -> obj("codexHome" to s("/fixture"))
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
                                                                s("1. Inspect the code\n2. Make the change"),
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
        compose.runOnUiThread { model.home() }
        compose.waitUntil(5000) { model.state.value.tasks.first().str("name") == fixtureTitle }
        demoPause(2000)
        compose.onNodeWithText(fixtureTitle).performClick()
        compose.waitUntil(10000) { !model.state.value.busy && model.state.value.entries.size >= 40 }
        compose.waitForIdle()
        compose.waitUntil(5000) { latestReply().isDisplayed() }
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
        compose.onNodeWithContentDescription("Tasks").performClick()
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
        if (coverScreen || reportCoverOverride) {
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

        compose.onNodeWithContentDescription("Tasks").performClick()
        compose.onNodeWithContentDescription("New chat").performClick()
        compose.waitUntil(5000) { model.state.value.page == "chat" }
        compose.onNodeWithText("What shall we work on?").assertIsDisplayed()
        compose.onNodeWithTag("composer").assertIsDisplayed()
        compose.onNodeWithTag("model-selector").assertIsDisplayed()
        compose.onNodeWithTag("reasoning-selector").assertIsDisplayed()
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
        assertTrue("Composer actions are $composerHeight dp high", composerHeight <= 56f)
        compose.onNodeWithTag("send").assertIsDisplayed()
        demoPause(3000)
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
        compose.onNodeWithContentDescription("Queue message").performClick()
        compose.waitUntil(10000) { !model.state.value.busy && model.state.value.queuedMessages.any { it.text == text } }
    }

    @Test
    fun normalSendQueuesAndCanSteerWithoutChangingNewDraft() {
        openRunningQueueFixture()
        enqueueFixture()
        enqueueFixture("Then review it")
        assertEquals(1, sent.get())
        assertTrue(steerRequests.isEmpty())
        assertEquals(listOf("Do this next", "Then review it"), model.state.value.queuedMessages.map { it.text })
        compose.onNodeWithTag("composer").performTextInput("An unfinished thought")
        if (coverScreen) {
            compose.onNodeWithTag("show-queue").assertIsDisplayed().performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithTag("message-queue").fetchSemanticsNodes().isNotEmpty() }
        }
        val queued = queueItems.first()
        compose.onNodeWithTag("send-queued-queue-1").performScrollTo().performClick()
        compose.waitUntil(10000) { !model.state.value.busy && model.state.value.queuedMessages.size == 1 }
        assertEquals(1, steerRequests.size)
        assertEquals(queued["input"], steerRequests.single()["input"])
        assertEquals(queued["clientUserMessageId"], steerRequests.single()["clientUserMessageId"])
        assertEquals("turn-test", steerRequests.single().str("expectedTurnId"))
        assertFalse(steerRequests.single().containsKey("model"))
        assertEquals("An unfinished thought", model.state.value.draft)
        assertNull(model.state.value.journal)
        compose.onNodeWithTag("remove-queued-queue-2").performClick()
        compose.waitUntil(10000) { !model.state.value.busy && model.state.value.queuedMessages.isEmpty() }
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

    @Test
    fun modelControlsUseCatalogAndRefreshUnsupportedSelection() {
        compose.onNodeWithContentDescription("New chat").performClick()
        compose.waitUntil(5000) { model.state.value.page == "chat" }
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
        compose.onNodeWithTag("close-image").assertIsDisplayed().performClick()
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
        compose.onNodeWithTag("bug-description").performTextInput("The queue button lost my message")
        compose.onNodeWithTag("submit-bug-report").performClick()
        compose.waitUntil(20000) { model.reports.state.value.lastTask == "task-test" && model.reports.state.value.draft == null }
        assertEquals(1, environmentSetups.get())
        assertEquals(1, worktreeAdds.get())
        assertEquals(1, threadStarts.get())
        assertEquals("project-remote", threadStartParams!!.str("projectId"))
        assertTrue(remoteFiles.keys.any { it.contains("/report/") && it.contains("screenshot") })
        assertTrue(acceptedText.contains("The queue button lost my message"))
        assertEquals("Keep my original draft", runBlocking { LocalStore(app).get("draft/new") })
        assertFalse(File(screenshot.localPath).exists())
        compose.waitUntil(10000) { model.state.value.thread == "task-test" && !model.state.value.busy }
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
        compose.onNodeWithTag("bug-description").performTextInput("Remember this offline")
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
        compose.onNodeWithTag("bug-description").assertTextContains("Remember this offline")
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
        compose.onNodeWithTag("discard-bug-report").performClick()
        compose.waitUntil(5000) { model.reports.state.value.draft == null }
        assertFalse(File(app.filesDir, "bug-reports/${original.id}").exists())
    }

    @Test
    fun bugReportSettingsExcludesScreenshotAndPersistsShakePreference() {
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
        compose.onNodeWithTag("bug-description").performTextInput("A cover-screen bug")
        compose.onNodeWithTag("submit-bug-report").assertIsDisplayed().performClick()
        compose.waitUntil(20000) { model.reports.state.value.lastTask == "task-test" }
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
        compose.onNodeWithTag("workspace-current").assertIsSelected()
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
    fun advertisedPlanModeRendersFullscreenPlanAndImplementsWithDefaultMode() {
        compose.onNodeWithContentDescription("New chat").performClick()
        compose.waitUntil(5000) {
            model.state.value.page == "chat" && model.state.value.collaborationModes.size == 2
        }
        compose.onNodeWithTag("mode-selector").assertTextContains("Server default").performClick()
        compose.onNodeWithTag("mode-plan").performClick()
        compose.onNodeWithTag("mode-selector").assertTextContains("Plan")
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
        compose.onNodeWithTag("mode-selector").performClick()
        compose.onNodeWithTag("mode-plan").performClick()
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
