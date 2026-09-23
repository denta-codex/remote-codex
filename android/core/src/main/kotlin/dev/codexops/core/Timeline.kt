package dev.codexops.core

import kotlinx.serialization.json.*

data class Entry(val turn: String, val raw: JsonObject) {
    val id
        get() = raw.str("id")

    val kind
        get() = raw.str("type")

    val key
        get() = "$turn/$id"

    val text: String
        get() =
            when (kind) {
                "userMessage" ->
                    raw.list("content").joinToString("\n") {
                        if (it.str("type") == "text") it.str("text") else "[${it.str("type")}]"
                    }
                "agentMessage",
                "plan" -> raw.str("text")
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
    var activeTurn: String? = null
        private set

    fun values(): List<Entry> = entries.values.toList()

    fun clear() {
        entries.clear()
        activeTurn = null
    }

    fun snapshot(turns: List<JsonObject>, prepend: Boolean = false) {
        val page = linkedMapOf<String, Entry>()
        turns.forEach { turn ->
            if (turn.str("status") == "inProgress") activeTurn = turn.str("id")
            else if (activeTurn == turn.str("id")) activeTurn = null
            turn.list("items").forEach { item ->
                val e = Entry(turn.str("id"), item)
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
            "turn/started" -> activeTurn = p.map("turn").str("id")
            "turn/completed" -> if (activeTurn == p.map("turn").str("id")) activeTurn = null
            "item/started",
            "item/completed" -> {
                val e = Entry(turn, p.map("item"))
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
    fun result(decision: Decision, accept: Boolean): JsonObject =
        when (decision.method) {
            "item/permissions/requestApproval" ->
                obj(
                    "permissions" to if (accept) decision.params.map("permissions") else obj(),
                    "scope" to s("turn"),
                )
            else -> obj("decision" to s(if (accept) "accept" else "decline"))
        }

    fun answers(answers: Map<String, String>) =
        obj(
            "answers" to
                JsonObject(answers.mapValues { obj("answers" to JsonArray(listOf(s(it.value)))) })
        )
}
