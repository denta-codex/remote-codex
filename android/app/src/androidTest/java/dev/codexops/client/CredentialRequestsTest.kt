package dev.codexops.client

import android.content.Intent
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.codexops.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class CredentialRequestsTest {
    private val fixtureDeadline = System.currentTimeMillis() + 60000
    private fun request(state: String = "pending") = obj("id" to s("fixture-request"), "state" to s(state),
        "account" to s("test.1password.com"), "host" to s("fixture"), "caller" to s("agent"),
        "vault" to s("Test"), "item" to s("Fake token"), "field" to s("password"),
        "deadline" to JsonPrimitive(fixtureDeadline))
    private fun button(view: View, name: String): Button? {
        if (view is Button && view.text.toString() == name) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) button(view.getChildAt(i), name)?.let { return it }
        return null
    }
    private fun fixture(server: MockWebServer): Intent {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return Intent(context, CredentialRequestsActivity::class.java).putExtra("fixture_endpoint", "ws://127.0.0.1:${server.port}/codex/rpc")
    }
    private fun buttons(view: View, name: String): List<Button> = buildList {
        if (view is Button && view.text.toString() == name) add(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) addAll(buttons(view.getChildAt(i), name))
    }
    private fun text(view: View, value: String): TextView? {
        if (view is TextView && view.text.toString() == value && view.isShown) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) text(view.getChildAt(i), value)?.let { return it }
        return null
    }

    @Test fun soleRequestOpensFocusedCardWithoutAnotherSelection() {
        MockWebServer().use { server ->
            val reads = AtomicInteger()
            val writes = AtomicInteger()
            val listener = object : WebSocketListener() {
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, "") }
                override fun onMessage(webSocket: WebSocket, value: String) {
                    val message = wire.parseToJsonElement(value).jsonObject
                    if (message.str("method") in listOf("get", "list")) reads.incrementAndGet() else writes.incrementAndGet()
                    webSocket.send(obj("version" to JsonPrimitive(1), "id" to message["id"], "requests" to JsonArray(listOf(request()))).toString())
                }
            }
            repeat(6) { server.enqueue(MockResponse().withWebSocketUpgrade(listener)) }
            server.start()
            ActivityScenario.launch<CredentialRequestsActivity>(fixture(server)).use { scenario ->
                awaitUi(scenario) { it.secret.isEnabled && reads.get() >= 2 }
                scenario.onActivity { activity ->
                    val root = activity.window.decorView
                    assertNotNull(text(root, "Credential request"))
                    assertNotNull(text(root, "Fake token"))
                    assertNotNull(text(root, "Requested field"))
                    assertNotNull(text(root, "agent on fixture"))
                    assertNotNull(text(root, "test.1password.com"))
                    assertNotNull(text(root, "Expires in"))
                    assertTrue(root.findViewWithTag<View>("credential-request-card").isShown)
                    assertFalse(button(root, "Release once")!!.isEnabled)
                    assertNull(button(root, "Choose in 1Password"))
                    activity.secret.requestFocus()
                    assertTrue(activity.secret.hasFocus())
                    activity.secret.autofill(android.view.autofill.AutofillValue.forText("HARMLESS_CARD_VALUE"))
                    assertTrue(activity.secret.transformationMethod is PasswordTransformationMethod)
                    val peek = (activity.secret.parent as ViewGroup).getChildAt(1)
                    assertEquals("Show password", peek.contentDescription)
                    peek.performClick()
                    assertNull(activity.secret.transformationMethod)
                    assertEquals("Hide password", peek.contentDescription)
                    peek.performClick()
                    assertTrue(activity.secret.transformationMethod is PasswordTransformationMethod)
                    peek.performClick()
                    activity.secret.text.clear()
                    assertTrue(activity.secret.transformationMethod is PasswordTransformationMethod)
                    assertEquals("Show password", peek.contentDescription)
                    activity.secret.setText("HARMLESS_CARD_VALUE")
                    assertTrue(button(root, "Release once")!!.isEnabled)
                    assertEquals(0, writes.get()) // Selecting a value is never approval.
                }
            }
        }
    }

    @Test fun multipleRequestsKeepSelectionAndBackNavigationClearsValues() {
        MockWebServer().use { server ->
            val first = request()
            val second = JsonObject(first + ("id" to s("second-request")) + ("item" to s("Second token")))
            val listener = object : WebSocketListener() {
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, "") }
                override fun onMessage(webSocket: WebSocket, value: String) {
                    val message = wire.parseToJsonElement(value).jsonObject
                    val entries = if (message.str("method") == "get") listOf(if (message.str("request_id") == "second-request") second else first)
                        else listOf(first, second)
                    webSocket.send(obj("version" to JsonPrimitive(1), "id" to message["id"], "requests" to JsonArray(entries)).toString())
                }
            }
            repeat(6) { server.enqueue(MockResponse().withWebSocketUpgrade(listener)) }
            server.start()
            ActivityScenario.launch<CredentialRequestsActivity>(fixture(server)).use { scenario ->
                awaitUi(scenario) { button(it.window.decorView, "Second token / password")?.isShown == true }
                scenario.onActivity {
                    assertFalse(it.secret.isEnabled)
                    button(it.window.decorView, "Second token / password")!!.performClick()
                    assertNotNull(text(it.window.decorView, "Second token"))
                    it.secret.setText("TRANSIENT_CARD_VALUE")
                    button(it.window.decorView, "Back to requests")!!.performClick()
                    assertTrue(it.secret.text.isNullOrEmpty())
                }
                awaitUi(scenario) { button(it.window.decorView, "Fake token / password")?.isShown == true }
                scenario.onActivity { assertFalse(it.secret.isEnabled) }
            }
        }
    }
    private class BatchFixture : AutoCloseable {
        val server = MockWebServer()
        val connected = CountDownLatch(1)
        val released = CountDownLatch(1)
        val releases = AtomicInteger()
        val reads = AtomicInteger()
        val correctValues = AtomicBoolean()
        val capabilitySeen = AtomicBoolean()
        @Volatile var state = "pending"
        @Volatile var deadline = System.currentTimeMillis() + 60000
        @Volatile var dropRelease = false
        @Volatile var rejectRelease = false
        @Volatile var peer: WebSocket? = null
        fun request(full: Boolean = true) = obj(
            "id" to s("fixture-batch"), "kind" to s("inject"), "state" to s(state),
            "account" to s("test.1password.com"), "host" to s("fixture"), "caller" to s("agent"),
            "deadline" to JsonPrimitive(deadline), "unique_count" to JsonPrimitive(2), "occurrence_count" to JsonPrimitive(3),
            "fields" to if (full) JsonArray(listOf(
                obj("id" to s("f1"), "vault" to s("Test"), "item" to s("Fake token"), "field" to s("password"), "occurrences" to JsonPrimitive(2)),
                obj("id" to s("f2"), "vault" to s("Test"), "item" to s("Fake token"), "field" to s("username"), "occurrences" to JsonPrimitive(1)),
            )) else null,
        )
        init {
            val listener = object : WebSocketListener() {
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, "") }
                override fun onOpen(webSocket: WebSocket, response: Response) { peer = webSocket; connected.countDown() }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val message = wire.parseToJsonElement(text).jsonObject
                    val method = message.str("method")
                    if (method == "release_batch") {
                        releases.incrementAndGet()
                        val values = message.list("values")
                        correctValues.set(values.size == 2 && values[0].str("id") == "f1" && values[0].str("value") == "HARMLESS_BATCH_VALUE" &&
                            values[1].str("id") == "f2" && values[1]["value"] == s(""))
                        if (!rejectRelease) state = "completed"
                        released.countDown()
                        if (dropRelease) { webSocket.close(1000, ""); return }
                    } else {
                        reads.incrementAndGet()
                        if (method == "list") capabilitySeen.set((message["capabilities"] as? JsonArray)?.contains(s(APPROVAL_BATCH_CAPABILITY)) == true)
                    }
                    webSocket.send(obj("version" to JsonPrimitive(1), "id" to message["id"],
                        "capabilities" to JsonArray(listOf(s(APPROVAL_BATCH_CAPABILITY))),
                        "error" to if (method == "release_batch" && rejectRelease) s("invalid_batch") else null,
                        "requests" to JsonArray(if (method == "list" && state != "pending") emptyList() else listOf(request(method != "list")))).toString())
                }
            }
            repeat(16) { server.enqueue(MockResponse().withWebSocketUpgrade(listener)) }
            server.start()
        }
        override fun close() { peer?.close(1000, ""); server.close() }
    }
    private fun awaitUi(scenario: ActivityScenario<CredentialRequestsActivity>, predicate: (CredentialRequestsActivity) -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12)
        while (System.nanoTime() < deadline) {
            var ready = false
            scenario.onActivity { ready = predicate(it) }
            if (ready) return
            Thread.sleep(25)
        }
        fail("Fixture UI did not reach expected state")
    }
    @Test fun completeBatchGroupsDuplicatesUsesExplicitEmptyAndNeverReplays() {
        BatchFixture().use { backend ->
            backend.dropRelease = true
            ActivityScenario.launch<CredentialRequestsActivity>(fixture(backend.server)).use { scenario ->
                assertTrue(backend.connected.await(10, TimeUnit.SECONDS))
                scenario.onActivity { it.selectRequest(backend.request(full = false)) }
                awaitUi(scenario) { it.batchSecrets.size == 2 }
                scenario.onActivity {
                    assertEquals(2, it.batchSecrets.size) // Three occurrences require two selections.
                    val submit = button(it.window.decorView, "Release all once")!!
                    assertFalse(submit.isEnabled)
                    it.batchSecrets.getValue("f1").autofill(android.view.autofill.AutofillValue.forText("HARMLESS_BATCH_VALUE"))
                    assertFalse(submit.isEnabled)
                    buttons(it.window.decorView, "Use empty value").last().performClick()
                    assertTrue(submit.isEnabled)
                    assertTrue(it.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                    assertTrue(buttons(it.window.decorView, "Choose in 1Password").isEmpty())
                    it.batchSecrets.values.forEach { input ->
                        assertFalse(input.isSaveEnabled); assertFalse(input.isSaveFromParentEnabled)
                        assertTrue(input.transformationMethod is PasswordTransformationMethod)
                    }
                    submit.performClick(); submit.performClick()
                    assertTrue(it.batchSecrets.values.all { input -> input.text.isNullOrEmpty() })
                    assertFalse(submit.isEnabled)
                }
                assertTrue(backend.released.await(10, TimeUnit.SECONDS))
                scenario.recreate()
                awaitUi(scenario) { button(it.window.decorView, "Release all once")?.isEnabled != true && backend.reads.get() >= 3 }
                scenario.onActivity { button(it.window.decorView, "Refresh")!!.performClick() }
                assertEquals(1, backend.releases.get())
                assertTrue(backend.correctValues.get())
                assertTrue(backend.capabilitySeen.get())
                assertEquals("/remote-codex/v1/credentials", backend.server.takeRequest(5, TimeUnit.SECONDS)!!.path)
            }
        }
    }
    @Test fun batchPickerHandoffKeepsTransientFieldsButRecreationClearsSelections() {
        BatchFixture().use { backend ->
            ActivityScenario.launch<CredentialRequestsActivity>(fixture(backend.server)).use { scenario ->
                assertTrue(backend.connected.await(10, TimeUnit.SECONDS))
                scenario.onActivity {
                    it.selectRequest(backend.request())
                    it.batchSecrets.getValue("f2").requestFocus()
                    assertTrue(it.batchSecrets.getValue("f2").hasFocus())
                    assertFalse(it.batchSecrets.getValue("f2").showSoftInputOnFocus)
                    it.batchSecrets.getValue("f2").setText("PICKER_FIXTURE")
                    (it.batchSecrets.getValue("f2").parent as ViewGroup).getChildAt(1).performClick()
                    assertNull(it.batchSecrets.getValue("f2").transformationMethod)
                    buttons(it.window.decorView, "Use empty value").first().performClick()
                    assertTrue(button(it.window.decorView, "Release all once")!!.isEnabled)
                    runBlocking {
                        try { captureBugReportScreenshot(it); fail("Protected batch activity captured") } catch (_: IllegalStateException) { }
                    }
                }
                scenario.moveToState(Lifecycle.State.CREATED)
                scenario.moveToState(Lifecycle.State.RESUMED)
                awaitUi(scenario) { button(it.window.decorView, "Release all once")?.isEnabled == true }
                scenario.onActivity {
                    assertEquals("PICKER_FIXTURE", it.batchSecrets.getValue("f2").text.toString())
                    assertTrue(it.batchSecrets.getValue("f2").transformationMethod is PasswordTransformationMethod)
                    assertEquals("Show password", (it.batchSecrets.getValue("f2").parent as ViewGroup).getChildAt(1).contentDescription)
                }
                scenario.recreate()
                scenario.onActivity {
                    it.selectRequest(backend.request())
                    assertTrue(it.batchSecrets.values.all { input -> input.text.isNullOrEmpty() })
                    assertFalse(button(it.window.decorView, "Release all once")!!.isEnabled)
                }
                assertEquals(0, backend.releases.get())
            }
        }
    }
    @Test fun incompleteBatchDisconnectExpiryAndCancellationClearValues() {
        BatchFixture().use { backend ->
            ActivityScenario.launch<CredentialRequestsActivity>(fixture(backend.server)).use { scenario ->
                assertTrue(backend.connected.await(10, TimeUnit.SECONDS))
                scenario.onActivity {
                    it.selectRequest(backend.request()); it.batchSecrets.getValue("f1").setText("TRANSIENT_FIXTURE")
                    assertFalse(button(it.window.decorView, "Release all once")!!.isEnabled)
                }
                backend.peer!!.close(1000, "")
                awaitUi(scenario) { it.batchSecrets.values.all { input -> input.text.isNullOrEmpty() } }
                scenario.onActivity { button(it.window.decorView, "Refresh")!!.performClick() }
                awaitUi(scenario) { it.batchSecrets.getValue("f1").isEnabled }
                scenario.onActivity { it.batchSecrets.getValue("f1").setText("TRANSIENT_FIXTURE") }
                backend.state = "cancelled"
                awaitUi(scenario) { it.batchSecrets.values.all { input -> input.text.isNullOrEmpty() } && !it.batchSecrets.getValue("f1").isEnabled }
                backend.state = "pending"; backend.deadline = System.currentTimeMillis() + 1500
                scenario.onActivity { it.selectRequest(backend.request()); it.batchSecrets.getValue("f1").setText("TRANSIENT_FIXTURE") }
                awaitUi(scenario) { it.batchSecrets.values.all { input -> input.text.isNullOrEmpty() } && !it.batchSecrets.getValue("f1").isEnabled }
                assertEquals(0, backend.releases.get())
            }
        }
    }
    @Test fun batchLimitsIncludeEscapingAndMalformedMetadataDisablesRelease() {
        BatchFixture().use { backend ->
            ActivityScenario.launch<CredentialRequestsActivity>(fixture(backend.server)).use { scenario ->
                assertTrue(backend.connected.await(10, TimeUnit.SECONDS))
                scenario.onActivity {
                    it.selectRequest(backend.request())
                    it.batchSecrets.getValue("f1").setText("x".repeat(APPROVAL_VALUE_LIMIT + 1))
                    it.batchSecrets.getValue("f2").setText("small")
                    assertFalse(button(it.window.decorView, "Release all once")!!.isEnabled)
                    it.batchSecrets.getValue("f1").setText("\u0001".repeat(APPROVAL_VALUE_LIMIT))
                    assertTrue(button(it.window.decorView, "Release all once")!!.isEnabled)
                    it.batchSecrets.getValue("f2").setText("\u0001".repeat(APPROVAL_VALUE_LIMIT))
                    assertFalse(button(it.window.decorView, "Release all once")!!.isEnabled)
                    val malformed = JsonObject(backend.request() + ("unique_count" to JsonPrimitive(3)))
                    it.selectRequest(malformed)
                    assertTrue(it.batchSecrets.isEmpty())
                    assertFalse(button(it.window.decorView, "Release all once")!!.isEnabled)
                }
                assertEquals(0, backend.releases.get())
            }
        }
    }
    @Test fun rejectedBatchRemainsBlockedAcrossRefreshAndRecreation() {
        BatchFixture().use { backend ->
            backend.rejectRelease = true
            ActivityScenario.launch<CredentialRequestsActivity>(fixture(backend.server)).use { scenario ->
                assertTrue(backend.connected.await(10, TimeUnit.SECONDS))
                scenario.onActivity {
                    it.selectRequest(backend.request())
                    it.batchSecrets.getValue("f1").setText("HARMLESS_BATCH_VALUE")
                    buttons(it.window.decorView, "Use empty value").last().performClick()
                    button(it.window.decorView, "Release all once")!!.performClick()
                }
                assertTrue(backend.released.await(10, TimeUnit.SECONDS))
                scenario.recreate()
                awaitUi(scenario) { backend.reads.get() >= 3 }
                scenario.onActivity {
                    it.selectRequest(backend.request())
                    assertFalse(button(it.window.decorView, "Release all once")!!.isEnabled)
                    assertTrue(it.batchSecrets.values.all { input -> input.text.isNullOrEmpty() })
                    button(it.window.decorView, "Refresh")!!.performClick()
                }
                assertEquals(1, backend.releases.get())
            }
        }
    }
    @Test
    fun releaseUsesSeparateRouteClearsValueAndNeverReplays() {
        MockWebServer().use { server ->
            val connected = CountDownLatch(1)
            val released = CountDownLatch(1)
            val releases = AtomicInteger()
            var state = "pending"
            val listener = object : WebSocketListener() {
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, "") }
                override fun onOpen(webSocket: WebSocket, response: Response) { connected.countDown() }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val message = wire.parseToJsonElement(text).jsonObject
                    val method = message.str("method")
                    if (method == "release") {
                        if (message.str("value") == "HARMLESS_AUTOFILL_FIXTURE" && message.str("request_id") == "fixture-request") releases.incrementAndGet()
                        state = "completed"
                        // Lose the response after accepting release; client must only query status.
                        webSocket.close(1000, "")
                        released.countDown()
                        return
                    }
                    webSocket.send(obj("version" to JsonPrimitive(1), "id" to message["id"],
                        "requests" to JsonArray(if (method == "list" && state != "pending") emptyList() else listOf(request(state)))).toString())
                }
            }
            repeat(6) { server.enqueue(MockResponse().withWebSocketUpgrade(listener)) }
            server.start()
            ActivityScenario.launch<CredentialRequestsActivity>(fixture(server)).use { scenario ->
                assertTrue(connected.await(10, TimeUnit.SECONDS))
                scenario.onActivity {
                    val content = it.findViewById<ViewGroup>(android.R.id.content)
                    val root = (content.getChildAt(0) as ViewGroup).getChildAt(0)
                    val insets = androidx.core.view.WindowInsetsCompat.Builder()
                        .setInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars(), androidx.core.graphics.Insets.of(0, 24, 0, 48))
                        .setInsets(androidx.core.view.WindowInsetsCompat.Type.ime(), androidx.core.graphics.Insets.of(0, 0, 0, 240))
                        .setDisplayCutout(androidx.core.view.DisplayCutoutCompat(
                            android.graphics.Rect(0, 96, 0, 160),
                            listOf(android.graphics.Rect(0, 0, 100, 96)),
                        ))
                        .build()
                    androidx.core.view.ViewCompat.dispatchApplyWindowInsets(root, insets)
                    val padding = (16 * it.resources.displayMetrics.density).toInt()
                    assertEquals(padding + 96, root.paddingTop)
                    assertEquals(padding + 240, root.paddingBottom)
                    androidx.core.view.ViewCompat.requestApplyInsets(root)
                    it.selectRequest(request())
                    it.secret.setText("HARMLESS_AUTOFILL_FIXTURE")
                    val submit = button(it.window.decorView, "Release once")!!
                    assertTrue(submit.isEnabled)
                    submit.performClick()
                    assertTrue(it.secret.text.isNullOrEmpty())
                    assertFalse(submit.isEnabled)
                }
                assertTrue(released.await(10, TimeUnit.SECONDS))
                scenario.recreate()
                scenario.onActivity { assertTrue(it.secret.text.isNullOrEmpty()) }
                assertEquals(1, releases.get())
                val networkRequest = server.takeRequest(5, TimeUnit.SECONDS)!!
                assertEquals("/remote-codex/v1/credentials", networkRequest.path)
                assertEquals("Bearer fixture-approval-token", networkRequest.getHeader("Authorization"))
            }
        }
    }
    @Test
    fun pickerHandoffRecreationAndProtectedWindow() {
        MockWebServer().use { server ->
            val connected = CountDownLatch(1)
            val listener = object : WebSocketListener() {
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, "") }
                override fun onOpen(webSocket: WebSocket, response: Response) { connected.countDown() }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val message = wire.parseToJsonElement(text).jsonObject
                    webSocket.send(obj("version" to JsonPrimitive(1), "id" to message["id"], "requests" to JsonArray(listOf(request()))).toString())
                }
            }
            repeat(6) { server.enqueue(MockResponse().withWebSocketUpgrade(listener)) }; server.start()
            ActivityScenario.launch<CredentialRequestsActivity>(fixture(server)).use { scenario ->
                assertTrue(connected.await(10, TimeUnit.SECONDS))
                scenario.onActivity {
                    it.selectRequest(request())
                    button(it.window.decorView, "Choose in 1Password")!!.performClick()
                    assertTrue(it.secret.hasFocus())
                    assertFalse(it.secret.showSoftInputOnFocus)
                    it.secret.autofill(android.view.autofill.AutofillValue.forText("FAKE_PICKER_VALUE"))
                    assertEquals("FAKE_PICKER_VALUE", it.secret.text.toString())
                    assertFalse(it.secret.isSaveEnabled)
                    assertFalse(it.secret.isSaveFromParentEnabled)
                    assertTrue(it.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                    runBlocking {
                        try { captureBugReportScreenshot(it); fail("Protected activity was captured") } catch (_: IllegalStateException) { }
                    }
                }
                scenario.moveToState(Lifecycle.State.CREATED)
                scenario.moveToState(Lifecycle.State.RESUMED)
                scenario.onActivity { assertEquals("FAKE_PICKER_VALUE", it.secret.text.toString()) }
                scenario.recreate()
                scenario.onActivity { assertTrue(it.secret.text.isNullOrEmpty()) }
            }
        }
    }
}
