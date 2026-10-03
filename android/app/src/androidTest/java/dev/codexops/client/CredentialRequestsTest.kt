package dev.codexops.client

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
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

@RunWith(AndroidJUnit4::class)
class CredentialRequestsTest {
    private fun request(state: String = "pending") = obj("id" to s("fixture-request"), "state" to s(state),
        "account" to s("test.1password.com"), "host" to s("fixture"), "caller" to s("agent"),
        "vault" to s("Test"), "item" to s("Fake token"), "field" to s("password"),
        "deadline" to JsonPrimitive(System.currentTimeMillis() + 60000))
    private fun button(view: View, name: String): Button? {
        if (view is Button && view.text.toString() == name) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) button(view.getChildAt(i), name)?.let { return it }
        return null
    }
    private fun fixture(server: MockWebServer): Intent {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return Intent(context, CredentialRequestsActivity::class.java).putExtra("fixture_endpoint", "ws://127.0.0.1:${server.port}/codex/rpc")
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
                    it.selectRequest(request()); it.secret.setText("FAKE_PICKER_VALUE")
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
