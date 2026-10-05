package dev.codexops.client

import dev.codexops.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ChatActivityTest {
    @Test fun unreadTogglePersistsAndAcknowledgesAutomaticUnread() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val store = MemoryStore()
            val rpc = Session()
            var output = ChatActivity()
            fun monitor() = ChatActivityMonitor(scope, store, rpc, "host") { _, state -> output = state }
            val first = monitor()
            first.toggleUnread("chat")
            first.refresh("chat")
            assertTrue(output.unread)
            first.toggleUnread("chat")
            assertFalse(output.unread)
            first.toggleUnread("chat")
            val restarted = monitor()
            restarted.refresh("chat")
            assertTrue(output.unread)
            restarted.opened("chat")
            assertFalse(output.unread)
            rpc.text = "Another reply"
            restarted.refresh("chat")
            assertTrue(output.unread)
            restarted.toggleUnread("chat")
            assertFalse(output.unread)
            restarted.refresh("chat")
            assertFalse(output.unread)
            rpc.text = "Newest reply"
            restarted.refresh("chat")
            assertTrue(output.unread)
            restarted.opened("chat")
            assertTrue("Opening must not clear an unseen automatic reply", output.unread)
            restarted.read("chat", replySignature("turn", listOf(reply(rpc.text)))!!)
            assertFalse(output.unread)
            assertTrue(rpc.calls.all { it.first in setOf("thread/read", "thread/turns/list") })
        } finally { scope.cancel() }
    }

    private fun status(type: String, vararg flags: String) = obj("type" to s(type), "activeFlags" to JsonArray(flags.map(::s)))
    private fun reply(text: String) = obj("id" to s("reply"), "type" to s("agentMessage"), "text" to s(text))

    @Test fun runtimeAndUnreadAreIndependent() {
        assertEquals(ChatIndicator.Working, runtimeIndicator(status("active")))
        assertEquals(ChatIndicator.Approval, runtimeIndicator(status("active", "waitingOnApproval")))
        assertEquals(ChatIndicator.Input, runtimeIndicator(status("active", "waitingOnUserInput")))
        assertEquals(ChatIndicator.Error, runtimeIndicator(status("systemError")))
        for (type in listOf("idle", "notLoaded", "unknown")) assertEquals(ChatIndicator.None, runtimeIndicator(status(type)))
        assertEquals(ChatIndicator.Unread, ChatActivity(unread = true).indicator)
        assertEquals(ChatIndicator.Input, ChatActivity(ChatIndicator.Input, true).indicator)
        assertEquals(ChatIndicator.None, ChatActivity().indicator)
    }

    @Test fun historicalBaselineAndReadMarkersRoundTripWithoutContent() {
        val a = replySignature("turn1", listOf(reply("Old answer")))!!
        val b = replySignature("turn2", listOf(reply("New answer")))!!
        val baseline = ReplyReadState().observe(a)
        assertFalse(baseline.unread)
        val unread = ReplyReadState.decode(baseline.encode()).observe(b)
        assertTrue(unread.unread)
        assertFalse(unread.encode().contains("answer"))
        assertTrue(unread.observe(null).unread)
        assertFalse(unread.seen(b).observe(b).unread)
        assertEquals(a, replySignature("turn1", listOf(reply("Old answer"), obj("type" to s("userMessage")))))
        assertNull(replySignature("turn", listOf(obj("type" to s("commandExecution")))))
    }

    @Test fun monitorPersistsUnreadReconcilesFailuresAndIsolatesHosts() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val store = MemoryStore()
            val rpc = Session()
            val output = mutableMapOf<String, ChatActivity>()
            fun monitor(host: String) = ChatActivityMonitor(scope, store, rpc, host) { id, state -> output[id] = state }
            val first = monitor("host-a")
            first.refresh("chat")
            assertFalse(output.getValue("chat").unread)
            rpc.text = "new reply"
            first.refresh("chat")
            assertTrue(output.getValue("chat").unread)
            val restarted = monitor("host-a")
            restarted.refresh("chat")
            assertTrue(output.getValue("chat").unread)
            monitor("host-b").refresh("chat")
            assertFalse(output.getValue("chat").unread)
            restarted.read("chat", replySignature("turn", listOf(reply(rpc.text)))!!)
            restarted.refresh("chat")
            assertFalse(output.getValue("chat").unread)
            rpc.failed = true
            restarted.refresh("chat")
            assertEquals(ChatIndicator.Error, output.getValue("chat").indicator)
            rpc.runtime = status("active", "waitingOnUserInput")
            restarted.refresh("chat")
            assertEquals(ChatIndicator.Input, output.getValue("chat").indicator)
            rpc.runtime = status("active")
            restarted.refresh("chat")
            assertEquals(ChatIndicator.Working, output.getValue("chat").indicator)
            assertTrue(rpc.calls.all { it.first in setOf("thread/read", "thread/turns/list") })
            assertTrue(rpc.calls.filter { it.first == "thread/read" }.all { it.second["includeTurns"] == JsonPrimitive(false) })
            assertTrue(rpc.calls.filter { it.first == "thread/turns/list" }.all { it.second.str("sortDirection") == "desc" && it.second["limit"] == JsonPrimitive(1) })
        } finally { scope.cancel() }
    }

    @Test fun eventsOverrideLateReadsAndResolvedRequestsStopPulsing() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val rpc = Session()
            var result = ChatActivity()
            val monitor = ChatActivityMonitor(scope, MemoryStore(), rpc, "host") { _, state -> result = state }
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            rpc.beforeHistory = { entered.complete(Unit); release.await() }
            val stale = async { monitor.refresh("chat") }
            entered.await()
            monitor.event(obj("method" to s("thread/status/changed"), "params" to obj("threadId" to s("chat"), "status" to status("active"))))
            release.complete(Unit)
            stale.await()
            assertEquals(ChatIndicator.Working, result.indicator)
            monitor.event(obj("id" to JsonPrimitive(7), "method" to s("item/tool/requestUserInput"), "params" to obj("threadId" to s("chat"))))
            assertEquals(ChatIndicator.Input, result.indicator)
            monitor.event(obj("method" to s("serverRequest/resolved"), "params" to obj("requestId" to JsonPrimitive(7))))
            assertEquals(ChatIndicator.Working, result.indicator)
            monitor.event(obj("id" to JsonPrimitive(8), "method" to s("item/commandExecution/requestApproval"), "params" to obj("threadId" to s("chat"))))
            assertEquals(ChatIndicator.Approval, result.indicator)
            rpc.beforeHistory = {}
            monitor.refresh("chat")
            assertEquals(ChatIndicator.None, result.indicator)
            monitor.disconnected()
            assertEquals(ChatIndicator.None, result.indicator)
        } finally { scope.cancel() }
    }

    @Test fun firstObservedRunningChatBecomesUnreadWhenItFinishes() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val rpc = Session()
            var result = ChatActivity()
            val monitor = ChatActivityMonitor(scope, MemoryStore(), rpc, "host") { _, state -> result = state }
            rpc.runtime = status("active")
            monitor.refresh("chat")
            assertEquals(ChatIndicator.Working, result.indicator)
            rpc.runtime = status("idle")
            monitor.refresh("chat")
            assertEquals(ChatIndicator.Unread, result.indicator)
        } finally { scope.cancel() }
    }

    private class MemoryStore : ClientStore {
        val values = mutableMapOf<String, String>()
        override suspend fun get(id: String) = values[id].orEmpty()
        override suspend fun put(id: String, value: String) { values[id] = value }
        override suspend fun remove(id: String) { values.remove(id) }
        override suspend fun token() = ""
        override suspend fun saveToken(value: String) = Unit
    }
    private inner class Session : RemoteSession {
        override val events = Channel<JsonObject>()
        override val generation = 1L
        var text = "old reply"
        var failed = false
        var runtime = status("idle")
        var beforeHistory: suspend () -> Unit = {}
        val calls = mutableListOf<Pair<String, JsonObject>>()
        override suspend fun connect(url: String, token: String) = obj()
        override suspend fun call(method: String, params: JsonObject): JsonObject {
            calls += method to params
            return when (method) {
                "thread/read" -> obj("thread" to obj("id" to params["threadId"], "status" to runtime))
                "thread/turns/list" -> {
                    beforeHistory()
                    obj("data" to JsonArray(listOf(obj("id" to s("turn"), "status" to s(if (failed) "failed" else "completed"), "items" to JsonArray(listOf(reply(text)))))))
                }
                else -> error("Unexpected mutation")
            }
        }
        override fun respond(id: JsonElement, result: JsonObject, epoch: Long) = Unit
        override fun close() = Unit
        override fun dispose() = Unit
    }
}
