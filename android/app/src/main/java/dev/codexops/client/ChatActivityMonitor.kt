package dev.codexops.client

import dev.codexops.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.security.MessageDigest

/** Observes stock read APIs only; never resumes background chats or retries a mutation. */
internal class ChatActivityMonitor(
    private val scope: CoroutineScope,
    private val store: ClientStore,
    private val rpc: RemoteSession,
    namespace: String,
    private val publish: (String, ChatActivity) -> Unit,
) {
    private val prefix = "chat-read/" + MessageDigest.getInstance("SHA-256")
        .digest(namespace.toByteArray()).joinToString("") { "%02x".format(it) } + "/"
    private val markers = mutableMapOf<String, ReplyReadState>()
    private val runtime = mutableMapOf<String, ChatIndicator>()
    private val versions = mutableMapOf<String, Long>()
    private val pending = mutableMapOf<String, Pair<String, ChatIndicator>>()
    private val lock = Mutex()
    private val wake = MutableStateFlow(0L)
    private val targets = MutableStateFlow(emptySet<String>())
    private val enabled = MutableStateFlow(false)

    init {
        scope.launch {
            combine(targets, enabled, wake) { ids, active, _ -> if (active) ids else emptySet() }
                .collectLatest { ids ->
                    if (ids.isEmpty()) return@collectLatest
                    while (currentCoroutineContext().isActive) {
                        for (id in ids) {
                            try { refresh(id) } catch (e: CancellationException) { throw e }
                            catch (_: Exception) { /* A failed status read is not a task error. */ }
                        }
                        delay(5000)
                    }
                }
        }
    }

    fun watch(ids: Set<String>, active: Boolean) {
        targets.value = ids
        enabled.value = active
    }

    private fun version(id: String) = versions[id] ?: 0L
    private fun changed(id: String) { versions[id] = version(id) + 1 }
    private fun emit(id: String) {
        publish(id, ChatActivity(if (runtime[id] == ChatIndicator.Error) ChatIndicator.Error
            else pending.values.firstOrNull { it.first == id }?.second ?: runtime[id] ?: ChatIndicator.None,
            markers[id]?.unread == true))
    }
    private suspend fun marker(id: String) = markers.getOrPut(id) { ReplyReadState.decode(store.get(prefix + id)) }
    private suspend fun save(id: String, value: ReplyReadState) {
        val old = markers[id]
        withContext(NonCancellable) {
            if (old != value) store.put(prefix + id, value.encode())
            markers[id] = value
            emit(id)
        }
    }

    fun read(id: String, signature: String) {
        changed(id)
        persist { lock.withLock { save(id, marker(id).seen(signature)) } }
    }

    private fun persist(block: suspend () -> Unit) {
        scope.launch {
            try { block() } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* A storage failure must not stop chat execution. */ }
        }
    }

    fun disconnected() {
        val ids = (runtime.keys + markers.keys + pending.values.map { it.first }).toSet()
        runtime.clear()
        pending.clear()
        ids.forEach(::changed)
        ids.forEach(::emit)
    }

    /** Called before timeline hydration/filtering, so another chat's events are not lost. */
    fun event(event: JsonObject) {
        val method = event.str("method")
        val p = event.map("params")
        if (method == "serverRequest/resolved") {
            pending.remove(p["requestId"].toString())?.first?.let { id ->
                changed(id)
                runtime[id] = ChatIndicator.Working
                emit(id)
                wake.value++
            }
            return
        }
        val id = p.str("threadId").ifBlank { p.map("thread").str("id") }
        if (id.isBlank()) return
        val value = when {
            event.containsKey("id") && method.endsWith("requestApproval") -> ChatIndicator.Approval
            event.containsKey("id") && method == "item/tool/requestUserInput" -> ChatIndicator.Input
            method == "thread/status/changed" -> runtimeIndicator(p.map("status"))
            method == "turn/started" -> ChatIndicator.Working
            method == "turn/completed" -> if (p.map("turn").str("status") == "failed") ChatIndicator.Error else ChatIndicator.None
            method == "error" && p["willRetry"] == JsonPrimitive(false) -> ChatIndicator.Error
            else -> return
        }
        changed(id)
        if (method == "turn/completed" || method == "thread/status/changed" && value !in setOf(ChatIndicator.Approval, ChatIndicator.Input))
            pending.entries.removeAll { it.value.first == id }
        runtime[id] = value
        if (event.containsKey("id")) pending[event["id"].toString()] = id to value
        emit(id)
        if (method == "turn/started" || method == "turn/completed") persist {
            lock.withLock {
                val old = marker(id)
                // A new live turn is evidence of new work, including chats first seen in this session.
                if (old.observed == null && old.read == null) save(id, ReplyReadState(read = ""))
            }
        }
        wake.value++
    }

    internal suspend fun refresh(id: String) {
        val version = version(id)
        val epoch = rpc.generation
        val thread = rpc.call("thread/read", obj("threadId" to s(id), "includeTurns" to JsonPrimitive(false))).map("thread")
        if (thread.str("id") != id || epoch != rpc.generation || version != version(id)) return
        runtime[id] = runtimeIndicator(thread.map("status"))
        // Reconcile approvals answered elsewhere even if their resolution notification was missed.
        if (runtime[id] !in setOf(ChatIndicator.Approval, ChatIndicator.Input))
            pending.entries.removeAll { it.value.first == id }
        val active = runtime[id] in setOf(ChatIndicator.Working, ChatIndicator.Approval, ChatIndicator.Input)
        lock.withLock {
            val old = marker(id)
            if (active && old.observed == null && old.read == null) save(id, ReplyReadState(read = "")) else emit(id)
        }
        if (active) return
        // The latest turn is enough to compare assistant output; title/project edits never create unread.
        val history = rpc.call("thread/turns/list", obj("threadId" to s(id), "limit" to JsonPrimitive(1),
            "itemsView" to s("full"), "sortDirection" to s("desc")))
        if (epoch != rpc.generation || version != version(id)) return
        val turn = history.list("data").firstOrNull()
        if (turn?.str("status") == "failed" && runtime[id] == ChatIndicator.None) runtime[id] = ChatIndicator.Error
        val signature = turn?.let { replySignature(it.str("id"), it.list("items")) }
        lock.withLock {
            if (epoch == rpc.generation && version == version(id)) save(id, marker(id).observe(signature))
        }
    }
}
