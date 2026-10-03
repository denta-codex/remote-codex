package dev.codexops.client

import dev.codexops.core.*
import java.security.MessageDigest
import kotlinx.serialization.json.*

enum class ChatIndicator(val label: String) {
    Working("Working"), Approval("Approval needed"), Input("Input needed"),
    Error("Task error"), Unread("Unread reply"), None("");
}

data class ChatActivity(val runtime: ChatIndicator = ChatIndicator.None, val unread: Boolean = false) {
    val indicator: ChatIndicator get() = runtime.takeUnless { it == ChatIndicator.None }
        ?: if (unread) ChatIndicator.Unread else ChatIndicator.None
}

internal fun runtimeIndicator(status: JsonObject): ChatIndicator = when (status.str("type")) {
    "systemError" -> ChatIndicator.Error
    "active" -> {
        val flags = (status["activeFlags"] as? JsonArray).orEmpty().map { (it as? JsonPrimitive)?.content }
        when {
            "waitingOnApproval" in flags -> ChatIndicator.Approval
            "waitingOnUserInput" in flags -> ChatIndicator.Input
            else -> ChatIndicator.Working
        }
    }
    else -> ChatIndicator.None
}

/** Store only a digest of assistant output, never message text. Metadata changes do not count. */
internal fun replySignature(turn: String, items: List<JsonObject>): String? {
    val replies = items.filter { it.str("type") in setOf("agentMessage", "plan") }
    if (replies.isEmpty()) return null
    val content = JsonArray(replies.map { obj("id" to it["id"], "type" to it["type"], "text" to it["text"]) })
    return MessageDigest.getInstance("SHA-256").digest((turn + content.toString()).toByteArray())
        .joinToString("") { "%02x".format(it) }
}

/** First observation establishes a baseline, so upgrading does not mark old history unread. */
internal data class ReplyReadState(val observed: String? = null, val read: String? = null) {
    val unread get() = observed != null && observed != read
    fun observe(signature: String?): ReplyReadState = when {
        signature == null -> this
        observed == null && read == null -> ReplyReadState(signature, signature)
        else -> copy(observed = signature)
    }
    fun seen(signature: String) = ReplyReadState(signature, signature)
    fun encode() = obj("observed" to observed?.let(::s), "read" to read?.let(::s)).toString()
    companion object {
        fun decode(value: String): ReplyReadState = runCatching {
            val json = wire.parseToJsonElement(value).jsonObject
            ReplyReadState(json.str("observed").ifBlank { null }, json.str("read").ifBlank { null })
        }.getOrDefault(ReplyReadState())
    }
}
