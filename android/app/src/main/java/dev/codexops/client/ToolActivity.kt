package dev.codexops.client

import dev.codexops.core.*
import kotlinx.serialization.json.*

/** UI-only projection. The original entries remain authoritative and retain their identities. */
internal sealed interface ConversationRow {
    val key: String

    data class Message(val entry: Entry) : ConversationRow {
        override val key = entry.key
    }

    data class Activity(val calls: List<ToolCall>, val summary: String, val working: Boolean,
        val representsActiveTurn: Boolean) : ConversationRow {
        override val key = "tools/${calls.first().entry.key}"
    }
}

internal enum class ToolState(val label: String) {
    Running("Running"), Completed("Finished"), Failed("Failed"), Interrupted("Interrupted"),
    Waiting("Waiting for you"), Disconnected("Connection lost"), Unknown("Status unavailable"),
}

internal enum class ToolCategory(val activity: String, val singular: String, val plural: String, val title: String) {
    Read("Reading files", "file read", "file reads", "File read"),
    SearchFiles("Searching files", "file search", "file searches", "File search"),
    ListFiles("Listing files", "directory listing", "directory listings", "Directory listing"),
    Command("Running commands", "command", "commands", "Command"),
    WebSearch("Searching the web", "web search", "web searches", "Web search"),
    OpenPage("Opening pages", "page opened", "pages opened", "Open page"),
    FindInPage("Searching a page", "page search", "page searches", "Find in page"),
    WebOther("Browsing the web", "web action", "web actions", "Web activity"),
    ChangeFiles("Editing files", "file change", "file changes", "File changes"),
    Tool("Using tools", "tool call", "tool calls", "Tool"),
    Agent("Working with agents", "agent action", "agent actions", "Agent activity"),
    Other("Working", "activity", "activities", "Activity"),
}

internal data class ToolDetail(val label: String, val value: String, val code: Boolean = false)
internal data class ToolCall(val entry: Entry, val category: ToolCategory, val title: String,
    val state: ToolState) {
    // Large output and opaque results are formatted only when their disclosure is opened.
    val details: List<ToolDetail> by lazy { toolDetails(entry) }
    val technicalDetails: String by lazy { JsonObject(entry.raw.filterKeys { it != "_completed" }).display() }
}

private val detailJson = Json { prettyPrint = true }
private fun JsonElement?.display(): String = when (this) {
    null, JsonNull -> ""
    is JsonPrimitive -> contentOrNull.orEmpty()
    else -> detailJson.encodeToString(JsonElement.serializer(), this)
}

private val messageKinds = setOf("userMessage", "agentMessage", "plan", "imageView", "imageGeneration")

internal fun conversationRows(entries: List<Entry>, activeTurn: String?, connected: Boolean,
    turnStatuses: Map<String, String> = emptyMap(), waitingForUser: Boolean = false): List<ConversationRow> {
    val visible = entries.filter { it.kind != "reasoning" }
    val rows = mutableListOf<ConversationRow>()
    var group = mutableListOf<Entry>()
    fun flush(trailing: Boolean) {
        if (group.isEmpty()) return
        val turn = group.first().turn
        val active = turn == activeTurn && turnStatuses[turn] !in setOf("completed", "failed", "interrupted")
        val calls = group.map { toolCall(it, active, connected, turnStatuses[turn], active && waitingForUser) }
        val running = calls.lastOrNull { it.state == ToolState.Running }
        val waiting = waitingForUser || calls.any { it.state == ToolState.Waiting }
        val pending = calls.any { it.state in setOf(ToolState.Running, ToolState.Waiting, ToolState.Disconnected) }
        val representsActive = active && (trailing || pending)
        val working = representsActive && connected && !waiting
        val failures = calls.count { it.state == ToolState.Failed }
        val interrupted = turnStatuses[turn] == "interrupted" || calls.any { it.state == ToolState.Interrupted }
        val failedTurn = turnStatuses[turn] == "failed"
        val counts = calls.groupingBy { it.category }.eachCount().entries.joinToString(" · ") { (type, count) ->
            "$count ${if (count == 1) type.singular else type.plural}"
        }
        val summary = when {
            representsActive && !connected -> "Connection lost"
            representsActive && waiting -> "Waiting for you"
            running != null -> running.category.activity
            working -> "Working…"
            interrupted -> "Interrupted · $counts"
            failedTurn -> "Failed · $counts"
            calls.any { it.state == ToolState.Unknown } -> "Status unavailable · $counts"
            else -> counts.replaceFirstChar { it.uppercase() }
        } + if (failures > 0 && !failedTurn) " · $failures failed" else ""
        rows += ConversationRow.Activity(calls, summary, working, representsActive)
        group = mutableListOf()
    }
    visible.forEach { entry ->
        if (entry.kind in messageKinds) {
            flush(false)
            rows += ConversationRow.Message(entry)
        } else {
            if (group.isNotEmpty() && group.first().turn != entry.turn) flush(false)
            group += entry
        }
    }
    flush(true)
    return rows
}

private fun toolState(entry: Entry, active: Boolean, connected: Boolean, turnStatus: String?, waiting: Boolean): ToolState {
    val raw = entry.raw
    val status = raw.str("status")
    val error = raw["error"]
    if (status in setOf("failed", "error", "declined") ||
        (error != null && error != JsonNull && error != JsonObject(emptyMap())) ||
        (raw["success"] as? JsonPrimitive)?.booleanOrNull == false ||
        (raw.map("result")["isError"] as? JsonPrimitive)?.booleanOrNull == true ||
        ((raw["exitCode"] as? JsonPrimitive)?.intOrNull?.let { it != 0 } == true)) return ToolState.Failed
    if (status in setOf("interrupted", "cancelled", "canceled")) return ToolState.Interrupted
    if (status == "completed" || (entry.completed && status !in setOf("inProgress", "awaitingApproval")))
        return ToolState.Completed
    if (turnStatus == "interrupted") return ToolState.Interrupted
    if (turnStatus == "failed") return ToolState.Failed
    if (!active) return ToolState.Unknown
    if (!connected) return ToolState.Disconnected
    if (waiting || status == "awaitingApproval") return ToolState.Waiting
    return ToolState.Running
}

internal fun toolCall(entry: Entry, active: Boolean = false, connected: Boolean = true,
    turnStatus: String? = null, waiting: Boolean = false): ToolCall {
    val raw = entry.raw
    val action = raw.map("action")
    val commandActions = raw.list("commandActions")
    val commandType = commandActions.map { it.str("type") }.distinct().singleOrNull()
    val category = when (entry.kind) {
        "commandExecution" -> when (commandType) {
            "read" -> ToolCategory.Read
            "search" -> ToolCategory.SearchFiles
            "listFiles" -> ToolCategory.ListFiles
            else -> ToolCategory.Command
        }
        "webSearch" -> when (action.str("type")) {
            "openPage" -> ToolCategory.OpenPage
            "findInPage" -> ToolCategory.FindInPage
            "search" -> ToolCategory.WebSearch
            else -> if (raw.str("query").isNotBlank()) ToolCategory.WebSearch else ToolCategory.WebOther
        }
        "fileChange" -> ToolCategory.ChangeFiles
        "mcpToolCall", "dynamicToolCall" -> ToolCategory.Tool
        "collabAgentToolCall", "subAgentActivity" -> ToolCategory.Agent
        else -> ToolCategory.Other
    }
    val target = when (category) {
        ToolCategory.Read -> commandActions.firstOrNull()?.let { it.str("name").ifBlank { it.str("path").substringAfterLast('/') } }.orEmpty()
        ToolCategory.SearchFiles -> commandActions.firstOrNull()?.str("query").orEmpty()
        ToolCategory.ListFiles -> commandActions.firstOrNull()?.str("path").orEmpty()
        ToolCategory.WebSearch -> action.str("query").ifBlank { raw.str("query") }
        ToolCategory.OpenPage, ToolCategory.FindInPage -> action.str("url")
        ToolCategory.Tool, ToolCategory.Agent -> raw.str("tool").ifBlank { raw.str("kind") }
        ToolCategory.Command -> raw.str("command")
        else -> ""
    }
    val title = if (target.isBlank()) category.title else "${category.title} · $target"
    return ToolCall(entry, category, title, toolState(entry, active, connected, turnStatus, waiting))
}

private fun toolDetails(entry: Entry): List<ToolDetail> {
    val raw = entry.raw
    val action = raw.map("action")
    return buildList {
        fun field(label: String, value: String, code: Boolean = false) {
            if (value.isNotBlank()) add(ToolDetail(label, value, code))
        }
        when (entry.kind) {
            "commandExecution" -> {
                field("Command", raw.str("command"), true)
                field("Working directory", raw.str("cwd"))
                field("Output", raw.str("aggregatedOutput"), true)
                field("Exit code", raw["exitCode"].display())
            }
            "webSearch" -> {
                val queries = (action["queries"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
                field("Queries", queries.joinToString("\n").ifBlank { action.str("query").ifBlank { raw.str("query") } })
                field("URL", action.str("url"))
                field("Find", action.str("pattern"))
                field("Results", raw["results"].display(), true)
            }
            "fileChange" -> raw.list("changes").forEach {
                field(it.str("path").ifBlank { "Changes" }, it.str("diff"), true)
            }
            "mcpToolCall", "dynamicToolCall" -> {
                field("Tool", listOf(raw.str("server"), raw.str("namespace"), raw.str("tool")).filter { it.isNotBlank() }.joinToString(" / "))
                field("Arguments", raw["arguments"].display(), true)
                field("Result", raw["result"].display(), true)
                field("Output", raw["contentItems"].display(), true)
            }
            "collabAgentToolCall", "subAgentActivity" -> {
                field("Action", raw.str("tool").ifBlank { raw.str("kind") })
                field("Instructions", raw.str("prompt"))
                field("Agents", raw["agentsStates"].display(), true)
            }
            else -> field("Details", raw.str("text"))
        }
        field("Error", raw["error"].display(), true)
    }
}
