package dev.codexops.client

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.codexops.core.*
import java.io.File
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
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
    @Volatile private var dropWrite = false
    @Volatile private var peer: WebSocket? = null
    @Volatile private var acceptedText = ""
    @Volatile private var acceptedInput = JsonArray(emptyList())
    private val remoteFiles = ConcurrentHashMap<String, String>()
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
                                    if (method.isEmpty() || method == "initialized") return
                                    val result =
                                        when (method) {
                                            "initialize" -> obj("codexHome" to s("/fixture"))
                                            "thread/list" ->
                                                obj(
                                                    "data" to
                                                        JsonArray(
                                                            listOf(
                                                                obj(
                                                                    "id" to s("task-test"),
                                                                    "name" to s("Fixture task"),
                                                                    "cwd" to s("/fixture"),
                                                                )
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
                                                                                s("Fixture task"),
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
                                                            "name" to s("Fixture task"),
                                                        )
                                                )
                                            "thread/start" ->
                                                obj(
                                                    "thread" to
                                                        obj(
                                                            "id" to s("task-test"),
                                                            "projectId" to JsonNull,
                                                        )
                                                )
                                            "command/exec" -> obj("exitCode" to JsonPrimitive(0))
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
                                            "thread/turns/list" -> history()
                                            "turn/start",
                                            "turn/steer" -> {
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
                    "attachments/new",
                    "draft/task-test",
                    "journal/task-test",
                    "attachments/task-test",
                )
                .forEach {
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
                "status" to s("completed"),
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
        compose.onNodeWithText("＋  New chat").performClick()
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
    fun imageOnlyUploadsAndRendersThroughStockRpc() {
        compose.runOnUiThread { model.openTask("task-test") }
        compose.waitUntil(10000) { model.state.value.thread == "task-test" && !model.state.value.busy }
        val image = fixtureImage()
        compose.runOnUiThread { model.addAttachments(listOf(Uri.fromFile(image))) }
        compose.waitUntil(5000) { model.state.value.attachments.size == 1 }
        compose.onNodeWithTag("draft-attachment").assertIsDisplayed()
        compose.onNodeWithTag("send").assertIsEnabled().performClick()
        compose.waitUntil(15000) {
            sent.get() == 1 && model.state.value.entries.any { it.kind == "imageView" }
        }
        assertEquals(listOf("localImage"), acceptedInput.map { it.jsonObject.str("type") })
        assertEquals(1, remoteFiles.size)
        compose.waitUntil(10000) {
            compose.onAllNodesWithTag("message-image").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodesWithTag("message-image")[0].performClick()
        compose.onNodeWithTag("close-image").assertIsDisplayed().performClick()
    }

    @Test
    fun uncertainAttachmentWriteIsNotReplayed() {
        compose.runOnUiThread { model.openTask("task-test") }
        compose.waitUntil(10000) { model.state.value.thread == "task-test" && !model.state.value.busy }
        val image = fixtureImage("uncertain.png")
        compose.runOnUiThread { model.addAttachments(listOf(Uri.fromFile(image))) }
        compose.waitUntil(5000) { model.state.value.attachments.size == 1 }
        dropWrite = true
        compose.onNodeWithTag("send").performClick()
        compose.waitUntil(15000) { !model.state.value.busy && model.state.value.journal != null }
        assertEquals(0, sent.get())
        assertEquals("uploadingAttachment", model.state.value.journal?.str("stage"))
        compose.runOnUiThread { model.connect() }
        compose.waitUntil(15000) { model.state.value.ready && !model.state.value.busy }
        assertEquals(0, sent.get())
        compose.onNodeWithTag("send").assertIsNotEnabled()
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
        compose.waitUntil {
            model.state.value.draft == "Keep this idea" && model.state.value.attachments.size == 1
        }
        compose.onNodeWithTag("composer").assertTextContains("Keep this idea")
        compose.onNodeWithTag("draft-attachment").assertIsDisplayed()
        demoPause(3500)
    }
}
