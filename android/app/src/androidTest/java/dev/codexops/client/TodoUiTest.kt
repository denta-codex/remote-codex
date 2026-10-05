package dev.codexops.client

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

class TodoUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val state = mutableStateOf(ScreenState(page = "todo", ready = true))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val tasks = mutableListOf(TodoItem(1, "A readable task title that wraps on the cover display", "To Do", 1, "- [ ] First step"))
    private var saves = 0
    private var loseReply = false
    private val store = object : ClientStore {
        val data = mutableMapOf<String, String>()
        override suspend fun get(id: String) = data[id].orEmpty()
        override suspend fun put(id: String, value: String) { data[id] = value }
        override suspend fun remove(id: String) { data.remove(id) }
        override suspend fun token() = ""
        override suspend fun saveToken(value: String) {}
    }
    private val operations = object : TodoOperations {
        override suspend fun list() = tasks.toList()
        override suspend fun show(id: Long) = tasks.single { it.id == id }
        override suspend fun save(editor: TodoEditor): TodoItem {
            saves++
            val old = editor.original
            val saved = TodoItem(old?.id ?: 2, editor.title, old?.status ?: editor.creationStatus, (old?.revision ?: 0) + 1, editor.description)
            tasks.removeAll { it.id == saved.id }; tasks += saved
            check(!loseReply)
            return saved
        }
        override suspend fun move(task: TodoItem, status: String) = task.copy(status = status, revision = task.revision + 1).also { next ->
            tasks.removeAll { it.id == task.id }; tasks += next
        }
    }
    private val controller = TodoController(scope, store, operations, { state.value }, { state.value = state.value.copy(todo = it) })
    private val actions = object : TodoActions {
        override fun openTodo() = controller.refresh()
        override fun refreshTodo() = controller.refresh()
        override fun selectTodoStatus(status: String) = controller.select(status)
        override fun newTodo() = controller.new()
        override fun openTodoTask(id: Long) = controller.open(id)
        override fun todoTitle(value: String) = controller.title(value)
        override fun todoDescription(value: String) = controller.description(value)
        override fun saveTodo() = controller.save()
        override fun moveTodoTask(id: Long, status: String) = controller.moveTask(id, status)
        override fun reorderTodo(id: Long, target: Long, after: Boolean) = controller.reorder(id, target, after)
        override fun moveTodo(status: String) = controller.move(status)
        override fun closeTodoEditor() = controller.close()
        override fun discardTodoEditor() = controller.discard()
        override fun keepTodoEditor() = controller.keep()
        override fun acknowledgeTodoOutcome() = controller.acknowledge()
    }

    private fun show(cover: Boolean = false) {
        compose.runOnUiThread { compose.activity.viewModelStore.clear(); controller.refresh() }
        compose.activity.setContent {
            RemoteTheme {
                if (cover) {
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f),
                        LocalAppWindowClass provides classifyWindow(360.dp, 360.dp)) {
                        Box(Modifier.size(360.dp)) { Surface { TodoScreen(state.value, actions) } }
                    }
                } else Surface { TodoScreen(state.value, actions) }
            }
        }
    }

    @After fun cleanup() { scope.cancel() }

    @Test fun swipesMoveBothDirectionsAndLongPressReorders() {
        tasks += TodoItem(2, "Second priority", "To Do", 1)
        tasks += TodoItem(3, "Last priority", "To Do", 1)
        show()
        compose.onNodeWithTag("todo-task-1").performTouchInput { swipeRight() }
        compose.waitUntil(5000) { state.value.todo.status == "In Progress" && !state.value.todo.busy }
        compose.onNodeWithTag("todo-task-1").performTouchInput { swipeRight() }
        compose.waitUntil(5000) { state.value.todo.status == "Done" && !state.value.todo.busy }
        compose.onNodeWithTag("todo-task-1").performTouchInput { swipeRight() }
        assertEquals("Done", state.value.todo.status)
        compose.onNodeWithTag("todo-task-1").performTouchInput { swipeLeft() }
        compose.waitUntil(5000) { state.value.todo.status == "In Progress" && !state.value.todo.busy }
        compose.onNodeWithTag("todo-task-1").performTouchInput { swipeLeft() }
        compose.waitUntil(5000) { state.value.todo.status == "To Do" && !state.value.todo.busy }
        compose.onNodeWithTag("todo-task-1").assertIsDisplayed()
        val destination = compose.onNodeWithTag("todo-task-2").fetchSemanticsNode().boundsInRoot.center
        val source = compose.onNodeWithTag("todo-task-3").fetchSemanticsNode().boundsInRoot.center
        compose.onNodeWithTag("todo-task-3").performTouchInput {
            down(center); advanceEventTime(700)
            moveTo(center + androidx.compose.ui.geometry.Offset(0f, destination.y - source.y - 10f), 500)
            up()
        }
        compose.waitUntil(5000) { state.value.todo.items.filter { it.status == "To Do" }.first().id == 3L }
        compose.runOnUiThread { controller.refresh() }
        compose.waitUntil(5000) { !state.value.todo.busy }
        assertEquals(3L, state.value.todo.items.first().id)
        assertEquals(0, saves)
    }

    @Test fun longDragScrollsPastTheSourceRowAndSavesPriority() {
        tasks.clear()
        (1L..20L).forEach { tasks += TodoItem(it, "Priority $it", "To Do", 1) }
        show()
        val list = compose.onNodeWithTag("todo-list-0")
        list.performScrollToNode(hasTestTag("todo-task-20"))
        val bounds = list.fetchSemanticsNode().boundsInRoot
        val source = compose.onNodeWithTag("todo-task-20").fetchSemanticsNode().boundsInRoot.center
        list.performTouchInput {
            down(androidx.compose.ui.geometry.Offset(source.x - bounds.left, source.y - bounds.top))
        }
        compose.mainClock.autoAdvance = false
        try {
            compose.mainClock.advanceTimeBy(700)
            list.performTouchInput { moveTo(androidx.compose.ui.geometry.Offset(center.x, 10f), 500) }
            repeat(140) { compose.mainClock.advanceTimeBy(32) }
            list.performTouchInput { up() }
        } finally { compose.mainClock.autoAdvance = true }
        compose.waitUntil(5000) { state.value.todo.items.first().id == 20L && !state.value.todo.busy }
        assertEquals(0, saves)
    }

    @Test fun addUsesTheVisibleColumnAfterTabNavigation() {
        show(cover = true)
        compose.onNodeWithContentDescription("Refresh Todo").assertIsDisplayed()
        compose.onNodeWithTag("todo-tab-1").performClick()
        compose.waitUntil(5000) { state.value.todo.status == "In Progress" }
        compose.onNodeWithText("Add").performClick()
        compose.onNodeWithTag("todo-title").performTextInput("Started task")
        compose.onNodeWithTag("todo-save").performClick()
        compose.onNodeWithTag("todo-tab-1").assertIsSelected()
        compose.onNodeWithText("Started task").assertIsDisplayed()
        assertEquals("In Progress", tasks.single { it.title == "Started task" }.status)
        compose.onNodeWithTag("todo-tab-2").performClick()
        compose.waitUntil(5000) { state.value.todo.status == "Done" }
        compose.onNodeWithText("Add").performClick()
        compose.onNodeWithTag("todo-title").performTextInput("Finished task")
        compose.onNodeWithTag("todo-save").performClick()
        compose.onNodeWithTag("todo-tab-2").assertIsSelected()
        compose.onNodeWithText("Finished task").assertIsDisplayed()
        assertEquals("Done", tasks.single { it.title == "Finished task" }.status)
    }

    @Test fun addsEditsAndMovesATaskThroughNativeControls() {
        show()
        compose.onNodeWithText("Add").performClick()
        compose.onNodeWithTag("todo-title").performTextInput("Buy coffee")
        compose.onNodeWithTag("todo-description").performTextInput("- [ ] Decaf")
        compose.onNodeWithTag("todo-save").performClick()
        compose.onNodeWithText("Buy coffee").assertIsDisplayed().performClick()
        compose.onNodeWithTag("todo-description").assertTextContains("- [ ] Decaf")
        compose.onNodeWithTag("todo-title").performTextReplacement("Buy good coffee")
        compose.onNodeWithTag("todo-save").performClick()
        compose.onNodeWithText("Buy good coffee").performClick()
        compose.onNodeWithTag("todo-status").performClick()
        compose.onNodeWithText("In Progress").performClick()
        compose.waitUntil(5000) { state.value.todo.status == "In Progress" && state.value.todo.editor == null }
        compose.onNodeWithTag("todo-tab-1").assertIsSelected()
        compose.onNodeWithText("Buy good coffee").assertIsDisplayed()
        assertEquals(2, saves)
    }

    @Test fun coverKeepsTabsEditingAndDiscardReachableWithLargeText() {
        show(cover = true)
        compose.onNodeWithTag("todo-tab-0").assertIsDisplayed()
        compose.onNodeWithTag("todo-tab-2").assertIsDisplayed()
        compose.onNodeWithTag("todo-task-1").assertIsDisplayed().performClick()
        compose.onNodeWithTag("todo-title").performTextReplacement("Unsaved cover edit")
        compose.onNodeWithTag("todo-save").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("todo-editor").performScrollToNode(hasText("Close"))
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText("Keep editing").assertIsDisplayed().performClick()
        compose.onNodeWithTag("todo-editor").performScrollToNode(hasText("Close"))
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText("Discard").performClick()
        compose.onNodeWithTag("todo-task-1").assertIsDisplayed()
        compose.onNodeWithTag("todo-tab-2").performClick()
        compose.onNodeWithText("No tasks done yet.").assertIsDisplayed()
        assertEquals(0, saves)
    }

    @Test fun offlineAndUncertainSaveDoNotOfferSilentRetry() {
        show()
        compose.onNodeWithText("Add").performClick()
        compose.onNodeWithTag("todo-title").performTextInput("A saved task")
        loseReply = true
        compose.onNodeWithTag("todo-save").performClick()
        compose.onNodeWithText("Check the previous save").assertIsDisplayed()
        compose.onNodeWithTag("todo-save").assertIsNotEnabled()
        compose.onNodeWithTag("todo-acknowledge").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Refresh Todo").performClick()
        compose.onNodeWithTag("todo-acknowledge").performClick()
        compose.onNodeWithText("A saved task").assertIsDisplayed()
        assertEquals(1, saves)
        compose.runOnUiThread { state.value = state.value.copy(ready = false); controller.disconnected() }
        compose.onNodeWithText("Connect to Grace to use Todo.").assertIsDisplayed()
        compose.onNodeWithText("Add").assertIsNotEnabled()
        compose.onNodeWithText("A saved task").assertDoesNotExist()
    }
}
