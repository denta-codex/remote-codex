package dev.codexops.core

import kotlinx.serialization.json.*

enum class MediaLocation {
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

data class Entry(val turn: String, val raw: JsonObject) {
    val id
        get() = raw.str("id")

    val kind
        get() = raw.str("type")

    val key
        get() = "$turn/$id"

    val media: List<MediaRef>
        get() =
            when (kind) {
                "userMessage" ->
                    raw.list("content").mapIndexedNotNull { index, item ->
                        when (item.str("type")) {
                            "image" ->
                                item.str("url").takeIf { it.isNotBlank() }?.let {
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
                                "image", "localImage" -> null
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

/** Updated only on the owning UI coroutine. Snapshots replace, deltas append. */
class Timeline {
    private val entries = linkedMapOf<String, Entry>()
    private val statuses = linkedMapOf<String, String>()
    val turnStatuses: Map<String, String>
        get() = statuses.toMap()
    var activeTurn: String? = null
        private set

    fun values(): List<Entry> = entries.values.toList()

    fun clear() {
        entries.clear()
        statuses.clear()
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
            if (old == null || e.key in completed) entries[e.key] = e
            else if (old.kind == "plan" && old.completed) Unit
            else {
                val merged = old.raw.toMutableMap()
                e.raw.forEach { (k, v) ->
                    if (k !in setOf("text", "aggregatedOutput")) merged[k] = v
                }
                for (field in listOf("text", "aggregatedOutput")) {
                    val a = old.raw.str(field)
                    val b = e.raw.str(field)
                    val text =
                        when {
                            b.isEmpty() -> a
                            a.isEmpty() || b.startsWith(a) -> b
                            a.startsWith(b) || a.endsWith(b) -> a
                            else -> {
                                val overlap =
                                    (minOf(a.length, b.length) downTo 1).firstOrNull {
                                        a.endsWith(b.take(it))
                                    } ?: 0
                                a + b.drop(overlap)
                            }
                        }
                    if (text.isNotEmpty()) merged[field] = s(text)
                }
                entries[e.key] = Entry(e.turn, JsonObject(merged))
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
                val e = Entry(turn, item)
                entries[e.key] = e
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
                            old + (field to s((old.str(field) + p.str("delta")).takeLast(100000)))
                        ),
                    )
            }
        }
    }
}

data class Decision(
    val id: JsonElement,
    val method: String,
    val params: JsonObject,
    val epoch: Long,
) {
    val key
        get() = id.toString()

    val thread
        get() = params.str("threadId")
}

object Decisions {
    fun answers(answers: Map<String, String>) =
        obj(
            "answers" to
                JsonObject(answers.mapValues { obj("answers" to JsonArray(listOf(s(it.value)))) })
        )
}
