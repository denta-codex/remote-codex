package dev.codexops.client

import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import androidx.activity.compose.setContent
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalComposeUiApi::class)
class FullscreenEditorTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun backspaceRefreshesFullscreenTextAndSelection() {
        var draft by mutableStateOf("hello world")
        var connection: InputConnection? = null
        val updates = mutableListOf<Triple<Int, String, Int>>()
        val bridge = FullscreenEditorBridge { token, text ->
            updates.add(Triple(token, text.text.toString(), text.selectionStart))
        }
        compose.activity.setContent {
            RemoteTheme {
                InterceptPlatformTextInput(interceptor = { request, _ ->
                    connection = request.createInputConnection(EditorInfo())
                    awaitCancellation()
                }) {
                    FullscreenEditorSync(draft, bridge) {
                        TextField(draft, { draft = it }, Modifier.testTag("editor"))
                    }
                }
            }
        }
        compose.onNodeWithTag("editor").performClick()
        compose.waitUntil(5000) { connection != null }
        compose.runOnIdle {
            val input = requireNotNull(connection)
            val initial = input.getExtractedText(ExtractedTextRequest().apply { token = 42 },
                InputConnection.GET_EXTRACTED_TEXT_MONITOR)
            assertEquals("hello world", initial?.text.toString())
            input.setSelection(11, 11)
        }
        compose.waitForIdle()
        compose.runOnIdle { requireNotNull(connection).deleteSurroundingText(1, 0) }
        compose.waitUntil(5000) { updates.lastOrNull() == Triple(42, "hello worl", 10) }
        assertEquals("hello worl", draft)

        // Batched, repeated deletion must refresh the whole copy, including the suffix.
        compose.runOnIdle {
            requireNotNull(connection).apply {
                beginBatchEdit()
                setSelection(5, 5)
                deleteSurroundingText(1, 0)
                deleteSurroundingText(1, 0)
                endBatchEdit()
            }
        }
        compose.waitUntil(5000) { updates.lastOrNull() == Triple(42, "hel worl", 3) }
        assertEquals("hel worl", draft)

        // Deleting a selection and then clearing externally must also refresh the IME.
        compose.runOnIdle {
            requireNotNull(connection).apply {
                beginBatchEdit()
                setSelection(0, 4)
                commitText("", 1)
                endBatchEdit()
            }
        }
        compose.waitUntil(5000) { updates.lastOrNull() == Triple(42, "worl", 0) }
        compose.runOnIdle { draft = "" }
        compose.waitUntil(5000) { updates.lastOrNull()?.second == "" }

        compose.runOnIdle {
            requireNotNull(connection).closeConnection()
            updates.clear()
            bridge.refresh()
            assertTrue("A closed connection must not notify the keyboard", updates.isEmpty())
        }
    }

    @Test
    fun normalKeyboardDoesNotReceiveUnrequestedExtractedText() {
        var draft by mutableStateOf("hello")
        var connection: InputConnection? = null
        var updates = 0
        val bridge = FullscreenEditorBridge { _, _ -> updates++ }
        compose.activity.setContent {
            RemoteTheme {
                InterceptPlatformTextInput(interceptor = { request, _ ->
                    connection = request.createInputConnection(EditorInfo())
                    awaitCancellation()
                }) {
                    FullscreenEditorSync(draft, bridge) {
                        TextField(draft, { draft = it }, Modifier.testTag("editor"))
                    }
                }
            }
        }
        compose.onNodeWithTag("editor").performClick()
        compose.waitUntil(5000) { connection != null }
        compose.runOnIdle {
            requireNotNull(connection).apply {
                getExtractedText(ExtractedTextRequest(), 0)
                beginBatchEdit()
                setSelection(5, 5)
                deleteSurroundingText(1, 0)
                endBatchEdit()
            }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            bridge.refresh()
            assertEquals("hell", draft)
            assertEquals(0, updates)
        }
    }
}
