package dev.codexops.client

import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebChromeClient
import android.webkit.ConsoleMessage
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.codexops.core.*
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class VisualizationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun sandboxRendersInteractsRestoresStateAndBlocksEscapes() {
        val snapshots = CopyOnWriteArrayList<JsonObject>()
        val failures = CopyOnWriteArrayList<String>()
        val followUps = CopyOnWriteArrayList<JsonObject>()
        val resources = CopyOnWriteArrayList<String>()
        var generation by mutableIntStateOf(0)
        var saved = "null"
        val fragment = """
            <div id="fixture">
              <button type="button" style="width:240px;height:60px" id="counter">Increment</button>
              <button type="button" style="display:block;width:240px;height:60px" id="follow">Ask about selection</button>
              <div id="status" aria-live="polite"></div>
            </div>
            <script>
            (() => {
              let n = window.openai.widgetState?.modelContent?.n ?? 0;
              const report = {n, ready:true, parentBlocked:false, storageBlocked:false, fetchBlocked:false, noNative: typeof Android === 'undefined', styled:!!getComputedStyle(document.documentElement).getPropertyValue('--viz-series-1')};
              try { parent.document.body; } catch { report.parentBlocked = true; }
              try { localStorage.setItem('escape','1'); } catch { report.storageBlocked = true; }
              fetch('https://example.invalid/escape').catch(() => { report.fetchBlocked = true; });
              document.getElementById('counter').onclick = () => {
                report.n = ++n;
                document.getElementById('status').textContent = 'Count ' + n;
                window.openai.setWidgetState({modelContent:report, privateContent:null}).catch(() => {});
              };
              document.getElementById('follow').onclick = () => window.openai.sendFollowUpMessage({prompt:'Explain count ' + n,title:'Explain selection'});
              // Fixture-only readiness receipt, also queued until the native channel is ready.
              window.openai.setWidgetState({modelContent:report,privateContent:null}).catch(() => {});
            })();
            </script>
            <img src="file:///data/data/dev.codexops.client.debug/shared_prefs/connection.xml">
            <img src="https://example.invalid/unapproved.png">
        """.trimIndent()
        compose.runOnUiThread {
            compose.activity.viewModelStore.clear()
            compose.activity.setContent {
                RemoteTheme {
                    Column(Modifier.fillMaxSize()) {
                        key(generation) {
                            VisualizationWebView(fragment, saved, false,
                                onState = { value, receipt ->
                                    saved = value
                                    snapshots += wire.parseToJsonElement(value).jsonObject.map("modelContent")
                                    receipt(true)
                                },
                                onFollowUp = { followUps += it }, onExternal = { failures += it },
                                onFailure = { failures += "renderer failure" },
                                resourceLoader = { url ->
                                    resources += url
                                    // No network in this test: the bundled runtime must work offline.
                                    WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArray(0).inputStream())
                                },
                            )
                        }
                    }
                }
            }
        }
        fun findWeb(view: View): WebView? = if (view is WebView) view else
            (view as? ViewGroup)?.let { group -> (0 until group.childCount).firstNotNullOfOrNull { findWeb(group.getChildAt(it)) } }
        val console = CopyOnWriteArrayList<String>()
        fun awaitPaint() {
            var painted = false
            compose.runOnUiThread {
                findWeb(compose.activity.window.decorView)!!.postVisualStateCallback(1, object : WebView.VisualStateCallback() {
                    override fun onComplete(requestId: Long) { painted = true }
                })
            }
            compose.waitUntil(5000) { painted }
            compose.waitForIdle()
        }
        compose.runOnUiThread {
            findWeb(compose.activity.window.decorView)?.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    console += message.message()
                    return true
                }
            }
        }
        try {
            compose.waitUntil(15000) { snapshots.isNotEmpty() }
        } catch (failure: ComposeTimeoutException) {
            var diagnostics: String? = null
            compose.runOnUiThread {
                findWeb(compose.activity.window.decorView)?.evaluateJavascript("JSON.stringify({url:location.href,stage:document.documentElement.dataset,frame:!!document.querySelector('iframe')?.srcdoc})") { diagnostics = it }
            }
            compose.waitUntil(2000) { diagnostics != null }
            throw AssertionError("Viewer bootstrap: $diagnostics; fixture console: $console; resource count: ${resources.size}", failure)
        }
        awaitPaint()
        compose.onNodeWithTag("visualization-webview").performTouchInput { click(Offset(90f, 30f) * density) }
        compose.waitUntil(5000) { snapshots.last().str("n") == "1" }
        val result = snapshots.last()
        listOf("parentBlocked", "storageBlocked", "fetchBlocked", "noNative", "styled").forEach { assertEquals(it, "true", result.str(it)) }
        assertTrue(resources.none { it.contains("example.invalid") || it.startsWith("file:") })
        compose.onNodeWithTag("visualization-webview").performTouchInput { click(Offset(90f, 90f) * density) }
        compose.waitUntil(5000) { followUps.size == 1 }
        assertEquals("Explain count 1", followUps.single().str("prompt"))
        val before = snapshots.size
        compose.runOnUiThread { generation++ }
        compose.waitUntil(15000) { snapshots.size > before }
        assertEquals("1", snapshots.last().str("n"))
        awaitPaint()
        compose.onNodeWithTag("visualization-webview").performTouchInput { click(Offset(90f, 30f) * density) }
        compose.waitUntil(5000) { snapshots.last().str("n") == "2" }
        assertTrue(failures.isEmpty())
    }
}
