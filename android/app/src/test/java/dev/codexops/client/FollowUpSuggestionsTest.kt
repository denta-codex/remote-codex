package dev.codexops.client

import dev.codexops.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class FollowUpSuggestionsTest {
    private val messages = listOf("Show me a layout", "What about speed?", "How will we preserve my draft?")

    private fun entry(text: String, kind: String = "agentMessage") = Entry("turn", obj(
        "id" to s(text.take(12)), "type" to s(kind), "text" to s(text), "_completed" to JsonPrimitive(true),
    ))

    private inner class Fixture : FollowUpSuggestions, AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var epoch = 1L
        var screen = ScreenState(page = "chat", thread = "thread", ready = true, queueReady = true,
            entries = listOf(entry("We can add contextual messages.")), draft = "Keep this draft")
        var calls = 0
        var fail = false
        var gate: CompletableDeferred<Unit>? = null
        var ignoreCancellation = false
        val controller = FollowUpController(scope, this, { epoch }, { screen }, { screen = screen.copy(followUps = it) })
        override suspend fun generate(context: String): List<String> {
            calls++
            val wait = gate
            if (ignoreCancellation) withContext(NonCancellable) { wait?.await() } else wait?.await()
            check(!fail)
            return messages
        }
        override fun close() { scope.cancel() }
    }

    @Test fun reopeningUnchangedContextUsesCacheAndCompletionOnlyInvalidates() = Fixture().use { f ->
        f.controller.opened()
        assertEquals(messages, f.screen.visibleFollowUps())
        f.screen = f.screen.copy(page = "home")
        f.controller.changed()
        assertTrue(f.screen.followUps.messages.isEmpty())
        f.screen = f.screen.copy(page = "chat")
        f.controller.opened()
        assertEquals(1, f.calls)
        assertEquals(messages, f.screen.visibleFollowUps())
        f.screen = f.screen.copy(entries = listOf(entry("A newer completed response.")))
        assertTrue(f.screen.visibleFollowUps().isEmpty())
        f.controller.changed()
        assertTrue(f.screen.followUps.messages.isEmpty())
        assertEquals(1, f.calls)
        f.controller.opened()
        assertEquals(2, f.calls)
        assertEquals("Keep this draft", f.screen.draft)
    }

    @Test fun staleResponseCannotPopulateAnotherConversationOrConnection() = Fixture().use { f ->
        f.gate = CompletableDeferred()
        f.ignoreCancellation = true
        f.controller.opened()
        f.screen = f.screen.copy(thread = "another")
        f.controller.changed()
        f.gate!!.complete(Unit)
        assertTrue(f.screen.followUps.messages.isEmpty())
        f.gate = null
        f.controller.opened()
        assertEquals(messages, f.screen.visibleFollowUps())
        f.epoch++
        f.controller.changed()
        assertTrue(f.screen.followUps.messages.isEmpty())
        f.controller.opened()
        assertEquals(3, f.calls)
    }

    @Test fun failedGenerationNeverRetriesUntilReopeningAndActiveTurnsSuppressIt() = Fixture().use { f ->
        f.fail = true
        f.controller.opened()
        f.controller.changed()
        assertEquals(1, f.calls)
        assertTrue(f.screen.visibleFollowUps().isEmpty())
        assertNull(f.screen.error)
        f.screen = f.screen.copy(activeTurn = "active")
        f.controller.opened()
        assertEquals(1, f.calls)
        f.screen = f.screen.copy(activeTurn = null)
        f.controller.changed()
        assertEquals(1, f.calls)
        f.fail = false
        f.controller.opened()
        assertEquals(messages, f.screen.visibleFollowUps())
        f.controller.consumed()
        assertTrue(f.screen.visibleFollowUps().isEmpty())
    }

    @Test fun contextOmitsAttachmentsToolOutputAndReasoningAndBoundsText() {
        val user = Entry("turn", obj("id" to s("u"), "type" to s("userMessage"), "content" to JsonArray(listOf(
            obj("type" to s("text"), "text" to s("Review this screen")),
            obj("type" to s("localImage"), "path" to s("/private/image.png")),
            obj("type" to s("unknown"), "secret" to s("private metadata")),
        ))))
        val screen = ScreenState(page = "chat", ready = true, queueReady = true, thread = "thread", entries = listOf(
            user, entry("private tool output", "commandExecution"), entry("private reasoning", "reasoning"),
            entry("x".repeat(7_000) + " newest context"),
        ))
        val context = FollowUpContext.from(screen)!!
        assertEquals(6_000, context.text.length)
        assertTrue(context.text.endsWith("newest context"))
        assertFalse(context.text.contains("private"))
        assertFalse(context.fingerprint.contains("newest"))
        assertNull(FollowUpContext.from(screen.copy(queueReady = false)))
        assertNull(FollowUpContext.from(screen.copy(attention = true)))
    }

    @Test fun deadlineLeavesComposerUsableAndAllowsANewRequestOnReopen() = runBlocking {
        Fixture().use { f ->
            f.gate = CompletableDeferred()
            f.controller.opened()
            delay(2_100)
            assertTrue(f.screen.followUps.messages.isEmpty())
            assertEquals("Keep this draft", f.screen.draft)
            assertNull(f.screen.error)
            assertEquals(1, f.calls)
            f.gate = null
            f.controller.opened()
            assertEquals(messages, f.screen.visibleFollowUps())
            assertEquals(2, f.calls)
        }
    }

    private fun response(content: JsonElement, finish: String = "stop") = obj("choices" to JsonArray(listOf(obj(
        "finish_reason" to s(finish), "message" to obj("content" to s(obj("suggestions" to content).toString())),
    )))).toString()

    @Test fun malformedDuplicateTruncatedOrLongSuggestionsAreRejected() {
        assertEquals(messages, StockFollowUpSuggestions.parse(response(JsonArray(messages.map(::s)))))
        val invalid = listOf(
            JsonArray(listOf(s("same"), s("Same"), s("other"))),
            JsonArray(listOf(s("only one"))),
            JsonArray(listOf(s(""), s("other"), s("last"))),
            JsonArray(listOf(s("new\nline"), s("other"), s("last"))),
            JsonArray(listOf(s((1..11).joinToString(" ") { "word" }), s("other"), s("last"))),
            JsonArray(listOf(JsonPrimitive(7), s("other"), s("last"))),
        )
        invalid.forEach { value -> assertThrows(Exception::class.java) { StockFollowUpSuggestions.parse(response(value)) } }
        assertThrows(Exception::class.java) { StockFollowUpSuggestions.parse(response(JsonArray(messages.map(::s)), "length")) }
    }

    @Test fun stockTransportPassesContextAsDataWithBoundedReadOnlyExecution() = runBlocking {
        val transcript = "Quoted \" text; $(touch /tmp/do-not-run) `echo nope`"
        var request: JsonObject? = null
        val rpc = object : RemoteSession {
            override val events = Channel<JsonObject>()
            override val generation = 3L
            override suspend fun connect(url: String, token: String) = obj()
            override suspend fun call(method: String, params: JsonObject): JsonObject {
                assertEquals("command/exec", method)
                request = params
                return obj("exitCode" to JsonPrimitive(0), "stdout" to s(response(JsonArray(messages.map(::s)))))
            }
            override fun respond(id: JsonElement, result: JsonObject, epoch: Long) {}
            override fun close() {}
            override fun dispose() {}
        }
        assertEquals(messages, StockFollowUpSuggestions(rpc).generate(transcript))
        val args = request!!.getValue("command").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(StockFollowUpSuggestions.COMMAND, args[2])
        assertFalse(args[2].contains(transcript))
        assertFalse(args[2].contains("CEREBRAS_API_KEY"))
        val body = wire.parseToJsonElement(args.last()).jsonObject
        assertEquals(transcript, body.list("messages").last().str("content"))
        assertEquals("none", body.str("reasoning_effort"))
        assertEquals("0", body.str("num_retries"))
        assertEquals("readOnly", request!!.map("sandboxPolicy").str("type"))
        assertEquals("2000", request!!.str("timeoutMs"))
    }
}
