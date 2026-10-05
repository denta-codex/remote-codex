package dev.codexops.client

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import dev.codexops.core.obj
import dev.codexops.core.s
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class HomeScreenTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val state = mutableStateOf(ScreenState(ready = true))
    private var moreRequests = 0
    private var retries = 0
    private val actions = object : HomeActions {
        override fun refreshTasks() = Unit
        override fun query(value: String) { state.value = state.value.copy(query = value) }
        override fun applyListOptions(project: TaskProjectFilter, sort: ChatSort) {
            state.value = state.value.copy(projectFilter = project, chatSort = sort)
        }
        override fun listPosition(index: Int, offset: Int) = Unit
        override fun archiveTask(id: String, archived: Boolean) = Unit
        override fun toggleTaskUnread(id: String) = Unit
        override fun undoTaskAction(noticeId: String) = Unit
        override fun dismissTaskNotice(noticeId: String) = Unit
        override fun retryList() { retries++ }
        override fun moreTasks() { moreRequests++; state.value = state.value.copy(listLoading = true) }
        override fun newChat() = Unit
        override fun openTask(id: String) = Unit
    }
    private fun show(value: ScreenState, cover: Boolean = false, dark: Boolean = false, large: Boolean = false) {
        state.value = value
        compose.runOnUiThread { compose.activity.viewModelStore.clear() }
        compose.activity.setContent {
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, if (large) 1.5f else 1f)) {
                    if (cover) Box(Modifier.size(360.dp)) {
                        CompositionLocalProvider(LocalAppWindowClass provides classifyWindow(360.dp, 360.dp)) {
                            androidx.compose.material3.Surface { HomeScreen(state.value, actions) }
                        }
                    } else androidx.compose.material3.Surface { HomeScreen(state.value, actions) }
                }
            }
        }
    }
    private fun fixture() = ScreenState(ready = true, listInitialized = true,
        projects = listOf(CodexProject("printing", "3d_printing", emptyList())),
        tasks = listOf(obj("id" to s("task"), "name" to s("Printer connection"), "projectId" to s("printing"))))

    @Test fun recentAgeUsesRecencyInsteadOfMetadataUpdate() {
        val now = java.time.Instant.now().epochSecond
        show(fixture().copy(tasks = listOf(obj(
            "id" to s("old-chat"), "name" to s("Old chat"),
            "updatedAt" to s(now.toString()),
            "recencyAt" to s((now - 7200).toString()),
            "createdAt" to s((now - 10800).toString()),
        ))))
        compose.onNodeWithText("No project · 2 hr ago").assertIsDisplayed()
        compose.runOnUiThread { state.value = state.value.copy(chatSort = ChatSort.Newest) }
        compose.onNodeWithText("No project · 3 hr ago").assertIsDisplayed()
    }

    @Test fun infiniteScrollAndRetryAreExplicit() {
        show(fixture().copy(listCursor = "next"))
        compose.waitUntil(5000) { moreRequests == 1 }
        compose.onNodeWithText("Loading more chats…").assertIsDisplayed()
        compose.runOnUiThread { state.value = state.value.copy(listLoading = false, listFailed = true) }
        compose.onNodeWithText("Could not load chats.").assertIsDisplayed()
        compose.onNodeWithText("All matching chats shown").assertDoesNotExist()
        compose.onNodeWithText("Retry").performClick()
        assertEquals(1, retries)
        assertEquals(1, moreRequests)
    }

    @Test fun sheetAppliesAtomicallyAndDismissesWithoutChanges() {
        show(fixture())
        compose.onNodeWithText("All projects").performClick()
        compose.onNodeWithText("No project").performClick()
        compose.onNodeWithText("Oldest created").performScrollTo().performClick()
        assertEquals(TaskProjectFilter.All, state.value.projectFilter)
        assertEquals(ChatSort.Recent, state.value.chatSort)
        compose.onNodeWithText("Apply").performClick()
        assertEquals(TaskProjectFilter.Projectless, state.value.projectFilter)
        assertEquals(ChatSort.Oldest, state.value.chatSort)
        compose.onNodeWithText("Oldest created").performClick()
        compose.onNodeWithText("Reset").performClick()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
        assertEquals(ChatSort.Oldest, state.value.chatSort)
        compose.onNodeWithText("Oldest created").performClick()
        compose.onNodeWithText("Reset").performClick()
        compose.onNodeWithText("Apply").performClick()
        assertEquals(TaskProjectFilter.All, state.value.projectFilter)
        assertEquals(ChatSort.Recent, state.value.chatSort)
        compose.onNodeWithText("Archived").assertDoesNotExist()
    }

    @Test fun coverScreenLargeTextKeepsSheetReachable() {
        fun shell(command: String) {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(
                InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
            ).use { it.readBytes() }
        }
        shell("wm size 1080x1200")
        shell("wm density 480")
        try {
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        show(fixture(), cover = true, dark = true, large = true)
        compose.onNodeWithText("Recent activity").performClick()
        compose.onNodeWithText("Oldest created").performScrollTo().performClick()
        compose.onNodeWithText("Apply").assertIsDisplayed().performClick()
        compose.onNodeWithTag("chat-list").performScrollToNode(hasText("Printer connection"))
        compose.onNodeWithText("Printer connection").assertIsDisplayed()
        } finally { shell("wm size reset"); shell("wm density reset") }
    }

    @Test fun loadingEmptyAndSearchClear() {
        show(fixture().copy(tasks = emptyList(), query = "printer", listLoading = true,
            projectFilter = TaskProjectFilter.Projectless))
        compose.onNodeWithText("Searching this project…").assertIsDisplayed()
        compose.onNodeWithText("No matching chats").assertDoesNotExist()
        compose.onNodeWithContentDescription("Clear search").performClick()
        assertEquals("", state.value.query)
        compose.runOnUiThread { state.value = state.value.copy(listLoading = false) }
        compose.onNodeWithText("No matching chats").assertIsDisplayed()
    }

    @Test fun chatIndicatorsAndScreenshots() {
        val indicators = listOf(ChatIndicator.Working, ChatIndicator.Unread, ChatIndicator.Error,
            ChatIndicator.Approval, ChatIndicator.Input, ChatIndicator.None)
        val titles = listOf("Printer connection", "Guardian review", "Linux printing setup",
            "Merge the auth bridge", "Printer calibration", "Finished reading")
        val fixture = ScreenState(ready = true, listInitialized = true,
            tasks = indicators.mapIndexed { i, _ -> obj("id" to s("status-$i"), "name" to s(titles[i])) },
            chatActivity = indicators.mapIndexed { i, indicator -> "status-$i" to ChatActivity(indicator) }.toMap())
        show(fixture)
        for (indicator in indicators.filter { it != ChatIndicator.None })
            compose.onNodeWithContentDescription(indicator.label, useUnmergedTree = true).assertExists()
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?: InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)!!.absolutePath
        fun capture(name: String) {
            compose.waitForIdle()
            val bitmap = compose.onNodeWithTag("chat-list").captureToImage().asAndroidBitmap()
            File(output, name).apply { parentFile?.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        capture("chat-status-light.png")
        show(fixture, cover = true, dark = true, large = true)
        compose.onNodeWithTag("chat-list").performScrollToNode(hasContentDescription("Approval needed", substring = true))
        compose.onNodeWithContentDescription("Approval needed", useUnmergedTree = true).assertIsDisplayed()
        capture("chat-status-cover-dark.png")
    }

    @Test fun captureCompactDesign() {
        show(fixture())
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?: InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)!!.absolutePath
        fun capture(name: String) {
            compose.waitForIdle()
            val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(output, name).apply { parentFile?.mkdirs() }.outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        capture("compact-inbox.png")
        compose.onNodeWithText("All projects").performClick()
        capture("compact-project-sort.png")
    }
}
