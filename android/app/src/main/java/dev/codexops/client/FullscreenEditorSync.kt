package dev.codexops.client

import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.PlatformTextInputMethodRequest

/** Keeps an IME's full-screen text copy in sync after accepted keyboard edits. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun FullscreenEditorSync(
    text: String,
    bridge: FullscreenEditorBridge = rememberFullscreenEditorBridge(),
    content: @Composable () -> Unit,
) {
    // Compose's value-based field can acknowledge an IME edit with updateSelection alone.
    // Wait until its input connection has received the new value, then also refresh the
    // extracted text requested by full-screen keyboards. Remove when the composer moves
    // to an input implementation with verified extracted-text monitoring support.
    LaunchedEffect(text, bridge) {
        withFrameNanos { }
        bridge.refresh()
    }
    DisposableEffect(bridge) { onDispose { bridge.clear() } }
    InterceptPlatformTextInput(
        interceptor = { request, next ->
            next.startInputMethod(PlatformTextInputMethodRequest { info ->
                bridge.wrap(request.createInputConnection(info))
            })
        },
        content = content,
    )
}

@Composable
private fun rememberFullscreenEditorBridge(): FullscreenEditorBridge {
    val view = LocalView.current
    return remember(view) {
        val manager = view.context.getSystemService(InputMethodManager::class.java)
        FullscreenEditorBridge { token, text -> manager.updateExtractedText(view, token, text) }
    }
}

internal class FullscreenEditorBridge(private val update: (Int, ExtractedText) -> Unit) {
    private val connections = mutableSetOf<MonitoredConnection>()

    fun wrap(connection: InputConnection): InputConnection =
        MonitoredConnection(connection).also { connections.add(it) }

    fun refresh() { connections.toList().forEach { it.refresh() } }

    fun clear() { connections.clear() }

    private inner class MonitoredConnection(connection: InputConnection) :
        InputConnectionWrapper(connection, false) {
        private var monitor: ExtractedTextRequest? = null

        override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? {
            if (flags and InputConnection.GET_EXTRACTED_TEXT_MONITOR != 0) {
                monitor = ExtractedTextRequest().apply { token = request?.token ?: 0 }
            }
            return super.getExtractedText(request, flags)
        }

        fun refresh() {
            val request = monitor ?: return
            val text = super.getExtractedText(request, InputConnection.GET_EXTRACTED_TEXT_MONITOR) ?: return
            update(request.token, text)
        }

        override fun closeConnection() {
            connections.remove(this)
            super.closeConnection()
        }
    }
}
