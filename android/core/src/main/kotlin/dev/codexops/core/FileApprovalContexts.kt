package dev.codexops.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class FileApprovalContext(val text: String = "", val loading: Boolean = false)

/** Details are connection-scoped evidence, never evidence that a request is still pending. */
class FileApprovalContexts {
    private var thread: String? = null
    private var epoch: Long = -1
    private val items = linkedMapOf<Pair<String, String>, String>()

    fun select(thread: String?, epoch: Long) {
        this.thread = thread
        this.epoch = epoch
        items.clear()
    }

    fun ensureSelected(thread: String?, epoch: Long) {
        if (thread != this.thread || epoch != this.epoch) select(thread, epoch)
    }

    fun text(decision: Decision): String =
        if (decision.method == "item/fileChange/requestApproval" && decision.thread == thread && decision.epoch == epoch)
            items[decision.params.str("turnId") to decision.params.str("itemId")].orEmpty()
        else ""

    fun event(method: String, params: JsonObject, epoch: Long) {
        if (epoch != this.epoch || params.str("threadId") != thread) return
        val item = when (method) {
            "item/started", "item/completed" -> params.map("item")
            "item/fileChange/patchUpdated" -> obj(
                "id" to params["itemId"], "type" to s("fileChange"), "changes" to params["changes"],
            )
            else -> return
        }
        record(params.str("turnId"), item, replace = true)
    }

    fun snapshot(thread: String, turns: List<JsonObject>, epoch: Long) {
        if (epoch != this.epoch || thread != this.thread) return
        // Live events delivered during the read are newer than its snapshot.
        turns.forEach { turn -> turn.list("items").forEach { record(turn.str("id"), it, replace = false) } }
    }

    fun resumed(response: JsonObject, epoch: Long) {
        snapshot(response.map("thread").str("id"), response.map("initialTurnsPage").list("data"), epoch)
    }

    private fun record(turn: String, item: JsonObject, replace: Boolean) {
        if (turn.isBlank() || item.str("id").isBlank() || item.str("type") != "fileChange") return
        val key = turn to item.str("id")
        val changes = item.list("changes")
        val text = if (changes.isNotEmpty() && changes.all { it.str("path").isNotBlank() })
            Entry(turn, item).text else ""
        if (replace) items[key] = text else items.putIfAbsent(key, text)
    }

    companion object {
        /** Stock resume overlays the live turn; persisted item reads omit unfinished patches. */
        fun resumeParams(thread: String) = obj(
            "threadId" to s(thread), "excludeTurns" to JsonPrimitive(true),
            "initialTurnsPage" to obj("limit" to JsonPrimitive(1), "itemsView" to s("full"), "sortDirection" to s("desc")),
        )
    }
}
