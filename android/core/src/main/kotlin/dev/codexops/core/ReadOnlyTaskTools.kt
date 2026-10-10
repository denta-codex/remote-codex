package dev.codexops.core

import kotlinx.serialization.json.*

/** A subset of the stock Android coordinator tools, on one verified host/account. */
class ReadOnlyTaskTools(
    private val hostId: String,
    private val call: suspend (String, JsonObject) -> JsonObject,
) {
    suspend fun execute(params: JsonObject): JsonObject {
        val args = params["arguments"] as? JsonObject ?: invalid("Tool arguments must be an object.")
        return when (params.str("tool")) {
            "list_threads" -> list(args)
            "read_thread" -> read(args)
            else -> invalid("Unsupported task tool.")
        }
    }

    private fun validate(args: JsonObject, allowed: Set<String>) {
        if (args.keys.any { it !in allowed })
            invalid("Unsupported arguments. Use codex-tasks for query-based finding, archives, or exhaustive inventory.")
        val requestedHost = args.string("hostId")
        if (requestedHost != null && requestedHost != hostId)
            invalid("This task tool can access only the active host. Use its returned hostId; desktop local and other hosts are unavailable.")
    }

    private suspend fun list(args: JsonObject): JsonObject {
        validate(args, setOf("hostId", "limit"))
        val limit = args.integer("limit", 10, 1..50)
        val page = call("thread/list", obj(
            "limit" to JsonPrimitive(limit), "archived" to JsonPrimitive(false),
            "sortKey" to s("updated_at"), "sortDirection" to s("desc"),
            "modelProviders" to JsonArray(emptyList()), "useStateDbOnly" to JsonPrimitive(true),
            // Omitted sourceKinds uses stock interactive sources, excluding internal agents.
        ))
        val threads = page.objects("data")
        if (threads.size > limit) invalid("The server exceeded the requested task limit.")
        return obj("schemaVersion" to JsonPrimitive(1),
            "threads" to JsonArray(threads.map { thread(it, detailed = false) }))
    }

    private suspend fun read(args: JsonObject): JsonObject {
        validate(args, setOf("hostId", "threadId", "cursor", "turnLimit", "includeOutputs", "maxOutputCharsPerItem"))
        val id = args.string("threadId") ?: invalid("threadId is required.")
        val cursor = args.string("cursor")
        val limit = args.integer("turnLimit", 1, 1..10)
        val outputs = args.boolean("includeOutputs", false)
        val maxChars = args.integer("maxOutputCharsPerItem", 2000, 0..20000)
        val metadata = call("thread/read", obj("threadId" to s(id), "includeTurns" to JsonPrimitive(false)))
        val info = metadata["thread"] as? JsonObject ?: invalid("The server omitted task metadata.")
        if (info.str("id") != id) invalid("The server returned a different task.")
        val page = call("thread/turns/list", obj("threadId" to s(id), "cursor" to cursor?.let(::s),
            "limit" to JsonPrimitive(limit), "sortDirection" to s("desc"), "itemsView" to s("full")))
        val turns = page.objects("data")
        if (turns.size > limit || turns.any { it["itemsView"] != null && it["itemsView"] != s("full") })
            invalid("The server returned incomplete task history.")
        val next = page.string("nextCursor", nullable = true)
        if (next != null && next == cursor) invalid("The server repeated the history cursor.")
        val budget = OutputBudget(if (outputs) 20000 else 0)
        return obj("schemaVersion" to JsonPrimitive(1), "thread" to thread(info, detailed = true),
            "page" to obj("order" to s("newest_first"), "limit" to JsonPrimitive(limit),
                "nextCursor" to next?.let(::s), "hasMore" to JsonPrimitive(next != null)),
            "turns" to JsonArray(turns.map { turn ->
                if (turn.str("id").isBlank()) invalid("The server omitted a turn identity.")
                JsonObject(turn.pick("id", "status", "error", "startedAt", "completedAt", "durationMs") +
                    ("items" to JsonArray(turn.objects("items").map { item(it, outputs, maxChars, budget) })))
            }))
    }

    private fun thread(value: JsonObject, detailed: Boolean): JsonObject {
        val id = value.string("id") ?: invalid("The server omitted a task identity.")
        val status = value["status"]
        return JsonObject(value.pick("preview", "projectId", "cwd", "createdAt", "updatedAt") +
            obj("id" to s(id), "threadId" to s(id), "hostId" to s(hostId), "title" to value["name"],
                "status" to if (detailed) status else (status as? JsonObject)?.get("type") ?: status))
    }

    private fun item(value: JsonObject, outputs: Boolean, maxChars: Int, totalBudget: OutputBudget): JsonObject {
        val allowance = minOf(maxChars, totalBudget.remaining)
        val budget = OutputBudget(allowance)
        val base = value.pick("id", "type")
        val fields = when (value.str("type")) {
            "userMessage" -> obj("content" to value["content"])
            "agentMessage" -> value.pick("text", "phase")
            "plan" -> value.pick("text")
            "hookPrompt" -> obj("fragmentCount" to JsonPrimitive((value["fragments"] as? JsonArray)?.size ?: 0))
            "reasoning" -> JsonObject(value.pick("summary") + if (outputs) obj(
                "content" to JsonArray((value["content"] as? JsonArray).orEmpty().map {
                    budget.bounded(it.jsonPrimitive.content, maxChars)
                })) else obj())
            "commandExecution" -> JsonObject(value.pick("command", "cwd", "status", "exitCode", "durationMs") +
                if (outputs && value["aggregatedOutput"] is JsonPrimitive && value["aggregatedOutput"] != JsonNull)
                    obj("output" to budget.bounded(value.str("aggregatedOutput"), maxChars)) else obj())
            "fileChange" -> JsonObject(value.pick("status") + obj("changes" to JsonArray(value.objects("changes").map { change ->
                val kind = (change["kind"] as? JsonObject)?.get("type") ?: change["kind"]
                JsonObject(change.pick("path") + obj("kind" to kind) +
                    if (outputs && change["diff"] is JsonPrimitive && change["diff"] != JsonNull)
                        obj("diff" to budget.bounded(change.str("diff"), maxChars)) else obj())
            })))
            "mcpToolCall" -> value.pick("server", "tool", "arguments", "status", "durationMs")
            "dynamicToolCall" -> value.pick("namespace", "tool", "arguments", "status", "success", "durationMs")
            "collabAgentToolCall" -> value.pick("tool", "status", "senderThreadId", "receiverThreadIds", "model", "prompt", "reasoningEffort")
            "subAgentActivity" -> value.pick("agentPath", "agentThreadId", "kind")
            "webSearch" -> value.pick("query", "action")
            "imageView" -> value.pick("path")
            "sleep" -> value.pick("durationMs")
            "imageGeneration" -> value.pick("status")
            "enteredReviewMode", "exitedReviewMode" -> value.pick("review")
            // Like Android, unknown/function result items retain only their identity/type.
            else -> obj()
        }
        totalBudget.remaining -= allowance - budget.remaining
        return JsonObject(base + fields)
    }

    private class OutputBudget(var remaining: Int) {
        fun bounded(text: String, maxChars: Int): JsonObject {
            var count = minOf(text.length, maxChars, remaining)
            if (count > 0 && count < text.length && text[count - 1].isHighSurrogate() && text[count].isLowSurrogate()) count--
            remaining -= count
            val truncated = count < text.length
            return obj("text" to s(text.take(count)), "truncated" to JsonPrimitive(truncated),
                "originalChars" to if (truncated) JsonPrimitive(text.length) else null)
        }
    }

    private fun JsonObject.pick(vararg keys: String) = JsonObject(filterKeys { it in keys })
    private fun JsonObject.objects(key: String): List<JsonObject> {
        val values = get(key) as? JsonArray ?: invalid("The server omitted task history data.")
        return values.map { it as? JsonObject ?: invalid("The server returned invalid task history data.") }
    }
    private fun JsonObject.string(key: String, nullable: Boolean = false): String? {
        val value = get(key) ?: return null
        if (nullable && value == JsonNull) return null
        val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (text.isNullOrBlank()) invalid("$key must be a nonempty string.")
        return text
    }
    private fun JsonObject.integer(key: String, default: Int, range: IntRange): Int {
        val value = get(key) ?: return default
        val number = (value as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
        if (number == null || number !in range) invalid("$key must be an integer in ${range.first}–${range.last}.")
        return number
    }
    private fun JsonObject.boolean(key: String, default: Boolean): Boolean {
        val value = get(key) ?: return default
        return (value as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
            ?: invalid("$key must be a boolean.")
    }
    private fun invalid(message: String): Nothing = throw TaskToolFailure(message)
}

class TaskToolFailure(message: String) : Exception(message)
