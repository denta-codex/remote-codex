package dev.codexops.core

import kotlinx.serialization.json.*

enum class MediaLocation {
    CACHE,
    DATA_URL,
    HOST_PATH,
    BASE64,
    EXTERNAL_URL,
}

data class MediaRef(
    val key: String,
    val location: MediaLocation,
    val value: String,
    val status: String = "",
)

data class FileRef(
    val key: String,
    val displayName: String,
    val path: String,
)

data class ParsedAttachmentContext(val request: String, val files: List<Pair<String, String>>)

fun parseAttachmentContext(value: String): ParsedAttachmentContext? {
    val header = "# Files mentioned by the user:"
    if (!value.startsWith("$header\n\n")) return null
    val marker = "\n\n## My request for Codex:"
    val markerIndex = value.indexOf(marker, header.length)
    if (markerIndex < 0) return null
    val references = value.substring(header.length + 2, markerIndex)
    val files =
        references.split("\n\n").mapNotNull { section ->
            if (!section.startsWith("## ")) return null
            val separator = section.lastIndexOf(": ")
            if (separator <= 3 || separator + 2 >= section.length) return null
            section.substring(3, separator) to section.substring(separator + 2)
        }
    if (files.isEmpty()) return null
    val requestStart = markerIndex + marker.length
    val request = value.substring(requestStart).removePrefix("\n\n")
    return ParsedAttachmentContext(request, files)
}

data class Entry(
    val turn: String,
    val raw: JsonObject,
    // Session-only presentation metadata; never added to RPC payloads or operational logs.
    val progressMessage: String = "",
    val summaryFinal: Boolean = false,
) {
    val id
        get() = raw.str("id")

    val kind
        get() = raw.str("type")

    val key
        get() = "$turn/$id"

    val summaries: List<String>
        get() = (raw["summary"] as? JsonArray).orEmpty().map {
            (it as? JsonPrimitive)?.contentOrNull.orEmpty()
        }

    val media: List<MediaRef>
        get() =
            when (kind) {
                "userMessage" ->
                    raw.list("content").mapIndexedNotNull { index, item ->
                        when (item.str("type")) {
                            "image" ->
                                (item.str("cacheKey").takeIf(String::isNotBlank)?.let {
                                    MediaRef("$key/image/$index", MediaLocation.CACHE, it)
                                }) ?: item.str("url").takeIf { it.isNotBlank() }?.let {
                                    MediaRef(
                                        "$key/image/$index",
                                        if (it.startsWith("data:image/")) MediaLocation.DATA_URL
                                        else MediaLocation.EXTERNAL_URL,
                                        it,
                                    )
                                }
                            "localImage" ->
                                item.str("path").takeIf { it.isNotBlank() }?.let {
                                    MediaRef("$key/local/$index", MediaLocation.HOST_PATH, it)
                                }
                            else -> null
                        }
                    }
                "imageView" ->
                    raw.str("path").takeIf { it.isNotBlank() }?.let {
                        listOf(MediaRef("$key/view", MediaLocation.HOST_PATH, it))
                    } ?: emptyList()
                "imageGeneration" -> {
                    val status = raw.str("status")
                    val result = raw.str("result")
                    val saved = raw.str("savedPath")
                    when {
                        raw.str("cacheKey").isNotBlank() -> listOf(MediaRef("$key/generated", MediaLocation.CACHE, raw.str("cacheKey"), status))
                        result.isNotBlank() ->
                            listOf(
                                MediaRef(
                                    "$key/generated",
                                    if (result.startsWith("data:image/")) MediaLocation.DATA_URL
                                    else MediaLocation.BASE64,
                                    result,
                                    status,
                                )
                            )
                        saved.isNotBlank() ->
                            listOf(
                                MediaRef(
                                    "$key/generated",
                                    MediaLocation.HOST_PATH,
                                    saved,
                                    status,
                                )
                            )
                        else -> emptyList()
                    }
                }
                else -> emptyList()
            }

    val files: List<FileRef>
        get() =
            when (kind) {
                "userMessage" -> {
                    val localImages =
                        raw.list("content")
                            .filter { it.str("type") == "localImage" }
                            .map { it.str("path") }
                            .toSet()
                    raw.list("content")
                        .firstOrNull { it.str("type") == "text" }
                        ?.str("text")
                        ?.let(::parseAttachmentContext)
                        ?.files
                        .orEmpty()
                        .filterNot { it.second in localImages }
                        .mapIndexed { index, (name, path) ->
                            FileRef("$key/file/$index", name, path)
                        }
                }
                "fileChange" ->
                    raw.list("changes").mapIndexedNotNull { index, change ->
                        change.str("path").takeIf(String::isNotBlank)?.let { path ->
                            FileRef("$key/change/$index", path.substringAfterLast('/'), path)
                        }
                    }
                else -> emptyList()
            }

    val completed
        get() = (raw["_completed"] as? JsonPrimitive)?.booleanOrNull == true

    val text: String
        get() =
            when (kind) {
                "userMessage" ->
                    raw.list("content")
                        .mapNotNull {
                            when (it.str("type")) {
                                "text" ->
                                    parseAttachmentContext(it.str("text"))?.request
                                        ?: it.str("text")
                                "image" -> if (it["_mediaUnavailable"] == JsonPrimitive(true)) "[Image unavailable; reopen the conversation to retry.]" else null
                                "localImage" -> null
                                else -> "[${it.str("type")}]"
                            }
                        }
                        .joinToString("\n")
                "agentMessage",
                "plan" -> raw.str("text")
                "imageView" -> ""
                "imageGeneration" ->
                    if (media.isNotEmpty()) ""
                    else raw.str("status").ifBlank { "Image generation did not produce an image" }
                "commandExecution" ->
                    listOf(raw.str("command"), raw.str("aggregatedOutput"), raw.str("status"))
                        .filter { it.isNotEmpty() }
                        .joinToString("\n\n")
                "fileChange" ->
                    raw.list("changes").joinToString("\n\n") {
                        it.str("path") + "\n" + it.str("diff")
                    }
                "reasoning" -> "Working…"
                else -> raw.str("text").ifEmpty { raw.toString() }
            }
}

/** Retain a bounded tool preview, never the entire output or unknown tool payload. */
const val TOOL_PREVIEW_CHARS = 65536
fun boundedToolItem(item: JsonObject, cursor: String? = null): JsonObject {
    if (item.str("type") in setOf("userMessage", "agentMessage", "plan", "imageView", "imageGeneration")) return item
    var remaining = TOOL_PREVIEW_CHARS
    var omitted = false
    fun bounded(value: JsonElement): JsonElement = when (value) {
        is JsonPrimitive -> if (value.isString) {
            val text = value.content
            val take = minOf(text.length, remaining)
            remaining -= take
            if (take < text.length) omitted = true
            s(text.take(take))
        } else value
        is JsonArray -> JsonArray(value.map(::bounded))
        is JsonObject -> JsonObject(value.mapValues { bounded(it.value) })
    }
    // Identities must remain intact even when an output exhausts the preview budget.
    val fields = item.filterKeys { it !in setOf("id", "type", "status", "_detailsCursor", "_detailsOmitted") }
    val safe = bounded(JsonObject(fields)).jsonObject
    return JsonObject(safe + item.filterKeys { it in setOf("id", "type", "status") } +
        if (omitted || item["_detailsOmitted"] == JsonPrimitive(true)) obj("_detailsOmitted" to JsonPrimitive(true), "_detailsCursor" to (cursor?.let(::s) ?: item["_detailsCursor"])) else emptyMap())
}

/** Updated only on the owning UI coroutine. Snapshots replace, deltas append. */
class Timeline {
    private val entries = linkedMapOf<String, Entry>()
    private val statuses = linkedMapOf<String, String>()
    private val progress = mutableMapOf<String, String>()
    val turnStatuses: Map<String, String>
        get() = statuses.toMap()
    var activeTurn: String? = null
        private set

    fun values(): List<Entry> = entries.values.toList()

    fun progressMessages(): Map<String, String> = progress.toMap()

    /** Restore only observed progress for a reconnect to the same selected thread. */
    fun restoreProgress(messages: Map<String, String>) {
        progress.putAll(messages)
        entries.replaceAll { key, entry -> entry.copy(progressMessage = progress[key].orEmpty()) }
    }

    fun clear() {
        entries.clear()
        statuses.clear()
        progress.clear()
        activeTurn = null
    }

    fun snapshot(turns: List<JsonObject>, prepend: Boolean = false) {
        val page = linkedMapOf<String, Entry>()
        turns.forEach { turn ->
            // Older pages must not overwrite the outcome of a turn already updated live.
            if (!prepend || turn.str("id") !in statuses)
                statuses[turn.str("id")] = turn.str("status")
            if (statuses[turn.str("id")] == "inProgress") activeTurn = turn.str("id")
            else if (activeTurn == turn.str("id")) activeTurn = null
            turn.list("items").forEach { item ->
                // History items are authoritative completed snapshots.
                val e =
                    Entry(
                        turn.str("id"),
                        JsonObject(item + ("_completed" to JsonPrimitive(true))),
                        progressMessage = progress["${turn.str("id")}/${item.str("id")}"].orEmpty(),
                        summaryFinal = item.str("type") == "reasoning" && turn.str("status") != "inProgress",
                    )
                page[e.key] = e
            }
        }
        if (prepend) {
            val current = entries.toMap()
            entries.clear()
            entries.putAll(page)
            entries.putAll(current)
        } else entries.putAll(page)
    }

    fun hydrate(turns: List<JsonObject>, events: List<JsonObject>) {
        snapshot(turns)
        val live = Timeline()
        val completed = mutableSetOf<String>()
        events.forEach { e ->
            val p = e.map("params")
            if (e.str("method") == "item/completed")
                completed.add(p.str("turnId") + "/" + p.map("item").str("id"))
            live.event(e.str("method"), p)
        }
        live.values().forEach { e ->
            val old = entries[e.key]
            val message = e.progressMessage.ifBlank { progress[e.key].orEmpty() }
            if (message.isNotBlank()) progress[e.key] = message
            if (old == null || e.key in completed) entries[e.key] = e.copy(progressMessage = message)
            else if (old.kind == "plan" && old.completed) Unit
            else {
                val merged = old.raw.toMutableMap()
                e.raw.forEach { (k, v) ->
                    if (k !in setOf("text", "aggregatedOutput", "summary")) merged[k] = v
                }
                if (old.kind == "reasoning") {
                    // A terminal history snapshot supersedes buffered partial summaries.
                    if (!old.summaryFinal) merged["summary"] = JsonArray(
                        List(maxOf(old.summaries.size, e.summaries.size)) { index ->
                            s(mergeStream(old.summaries.getOrElse(index) { "" }, e.summaries.getOrElse(index) { "" }))
                        },
                    )
                    if (old.summaryFinal) merged["_completed"] = JsonPrimitive(true)
                }
                for (field in listOf("text", "aggregatedOutput")) {
                    val a = old.raw.str(field)
                    val b = e.raw.str(field)
                    val text = mergeStream(a, b)
                    if (text.isNotEmpty()) merged[field] = s(text)
                }
                entries[e.key] = Entry(e.turn, JsonObject(merged), message, old.summaryFinal || e.summaryFinal)
            }
        }
        events
            .filter { it.str("method").startsWith("turn/") }
            .forEach { event(it.str("method"), it.map("params")) }
    }

    fun event(method: String, p: JsonObject) {
        val turn = p.str("turnId")
        when (method) {
            "turn/started" -> {
                activeTurn = p.map("turn").str("id")
                statuses[p.map("turn").str("id")] = "inProgress"
            }
            "turn/completed" -> {
                val completedTurn = p.map("turn")
                statuses[completedTurn.str("id")] = completedTurn.str("status")
                if (activeTurn == completedTurn.str("id")) activeTurn = null
            }
            "item/started",
            "item/completed" -> {
                val item =
                    JsonObject(
                        p.map("item") +
                            ("_completed" to JsonPrimitive(method == "item/completed"))
                    )
                val key = "$turn/${item.str("id")}"
                val old = entries[key]
                if (method == "item/started" && item.str("type") == "reasoning" && old?.summaryFinal == true) return
                val raw = if (method == "item/started" && item.str("type") == "reasoning" && old != null) {
                    JsonObject(item + ("summary" to JsonArray(
                        List(maxOf(old.summaries.size, (item["summary"] as? JsonArray).orEmpty().size)) { index ->
                            val part = ((item["summary"] as? JsonArray)?.getOrNull(index) as? JsonPrimitive)?.contentOrNull.orEmpty()
                            s(mergeStream(part, old.summaries.getOrElse(index) { "" }))
                        },
                    )))
                } else item
                val e = Entry(turn, boundedToolItem(raw), progress[key].orEmpty(), item.str("type") == "reasoning" && method == "item/completed")
                entries[e.key] = e
            }
            "item/reasoning/summaryPartAdded",
            "item/reasoning/summaryTextDelta" -> {
                val id = p.str("itemId")
                val index = (p["summaryIndex"] as? JsonPrimitive)?.intOrNull ?: return
                if (turn.isBlank() || id.isBlank() || index !in 0..255) return
                val key = "$turn/$id"
                val old = entries[key] ?: Entry(turn, obj("id" to s(id), "type" to s("reasoning")))
                if (old.summaryFinal || statuses[turn] in setOf("completed", "failed", "interrupted")) return
                val parts = old.summaries.toMutableList()
                while (parts.size <= index) parts.add("")
                if (method.endsWith("summaryTextDelta")) parts[index] = (parts[index] + p.str("delta")).takeLast(100000)
                entries[key] = old.copy(raw = JsonObject(old.raw + ("summary" to JsonArray(parts.map(::s)))))
            }
            "item/mcpToolCall/progress" -> {
                val id = p.str("itemId")
                val message = p.str("message").takeLast(100000)
                if (turn.isBlank() || id.isBlank() || message.isBlank()) return
                val key = "$turn/$id"
                progress[key] = message
                val old = entries[key] ?: Entry(turn, obj("id" to s(id), "type" to s("mcpToolCall")))
                entries[key] = old.copy(progressMessage = message)
            }
            "item/agentMessage/delta",
            "item/commandExecution/outputDelta",
            "item/plan/delta" -> {
                val key = "$turn/${p.str("itemId")}"
                val field = if (method.contains("outputDelta")) "aggregatedOutput" else "text"
                val kind =
                    if (field == "aggregatedOutput") "commandExecution"
                    else if (method.contains("/plan/")) "plan" else "agentMessage"
                val old = entries[key]?.raw ?: obj("id" to s(p.str("itemId")), "type" to s(kind))
                if (kind == "plan" && (old["_completed"] as? JsonPrimitive)?.booleanOrNull == true)
                    return
                entries[key] =
                    Entry(
                        turn,
                        JsonObject(
                            if (kind == "commandExecution") boundedToolItem(JsonObject(old +
                                (field to s(old.str(field) + p.str("delta")))))
                            else old + (field to s((old.str(field) + p.str("delta")).takeLast(100000)))
                        ),
                    )
            }
        }
    }

    private fun mergeStream(snapshot: String, stream: String): String = when {
        stream.isEmpty() -> snapshot
        snapshot.isEmpty() || stream.startsWith(snapshot) -> stream
        snapshot.startsWith(stream) || snapshot.endsWith(stream) -> snapshot
        else -> {
            val overlap = (minOf(snapshot.length, stream.length) downTo 1).firstOrNull {
                snapshot.endsWith(stream.take(it))
            } ?: 0
            snapshot + stream.drop(overlap)
        }
    }
}

data class Decision(
    val id: JsonElement,
    val method: String,
    val params: JsonObject,
    val epoch: Long,
) {
    val blocksUser
        get() = method != "item/tool/requestUserInput" || params["isBlocking"] != JsonPrimitive(false)

    val key
        get() = id.toString()

    val thread
        get() = params.str("threadId")
}

object Decisions {
    const val OTHER_ANSWER = "None of the above"

    /** Stock answers contain the selected label first, then optional notes. */
    fun questionAnswers(questions: List<JsonObject>, selections: Map<String, String>, notes: Map<String, String>) =
        obj("answers" to JsonObject(questions.associate { question ->
            val id = question.str("id")
            val values = buildList {
                selections[id]?.let { add(s(it)) }
                notes[id]?.takeIf { it.isNotBlank() }?.let {
                    add(s(if (question["isSecret"] == JsonPrimitive(true)) it else it.trim()))
                }
            }
            id to obj("answers" to JsonArray(values))
        }))

    fun answers(answers: Map<String, String>) =
        obj(
            "answers" to
                JsonObject(answers.mapValues { obj("answers" to JsonArray(listOf(s(it.value)))) })
        )
}
