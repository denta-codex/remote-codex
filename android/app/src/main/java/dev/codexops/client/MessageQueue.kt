package dev.codexops.client

import dev.codexops.core.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** A submission owned by the stock app-server, including input from other clients. */
data class QueuedMessage(
    val id: String,
    val clientUserMessageId: String,
    val input: JsonArray,
) {
    val text: String
        get() = input.filterIsInstance<JsonObject>()
            .filter { it.str("type") == "text" }
            .joinToString("\n") { it.str("text") }

    val preview: String
        get() {
            val context = parseAttachmentContext(text)
            return if (context == null) text.ifBlank { "Attachment message" }
            else listOf(context.request, context.files.joinToString { it.first })
                .filter(String::isNotBlank).joinToString("\n")
        }

    fun json() = obj(
        "id" to s(id),
        "clientUserMessageId" to s(clientUserMessageId),
        "input" to input,
    )

    companion object {
        fun parse(value: JsonObject): QueuedMessage {
            val id = value.str("id")
            val clientId = value.str("clientUserMessageId")
            require(id.isNotBlank() && clientId.isNotBlank())
            return QueuedMessage(id, clientId, value["input"] as JsonArray)
        }
    }
}

internal fun ScreenState.willQueueMessage() = activeTurn != null || queuedMessages.isNotEmpty()

internal fun queueItemParams(threadId: String, queuedSubmissionId: String) = obj(
    "threadId" to s(threadId),
    "queuedSubmissionId" to s(queuedSubmissionId),
)

internal fun queueAddParams(threadId: String, input: JsonArray, clientUserMessageId: String) = obj(
    "threadId" to s(threadId),
    "input" to input,
    "clientUserMessageId" to s(clientUserMessageId),
)
