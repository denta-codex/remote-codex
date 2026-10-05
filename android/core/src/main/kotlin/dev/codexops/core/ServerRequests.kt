package dev.codexops.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

sealed interface ServerRequestRoute {
    data object Interactive : ServerRequestRoute
    data class Result(val result: JsonObject) : ServerRequestRoute
    data class Error(val code: Int, val message: String) : ServerRequestRoute
}

/** Stock server requests have one destination; unsupported requests never enter the UI. */
object ServerRequests {
    private val readThreadUnavailable = toolFailure(
        "This client does not execute codex_app.read_thread. The tool was not executed. " +
            "Use the available codex-tasks skill to read or find tasks on an explicitly configured Grace endpoint. " +
            "Desktop hostId values are not CLI target selectors.",
    )
    private val unsupported = ServerRequestRoute.Error(-32601, "This client does not support this server request.")
    private val routes = mapOf(
        "item/commandExecution/requestApproval" to ServerRequestRoute.Interactive,
        "item/fileChange/requestApproval" to ServerRequestRoute.Interactive,
        "item/permissions/requestApproval" to ServerRequestRoute.Interactive,
        "item/tool/requestUserInput" to ServerRequestRoute.Interactive,
        "item/tool/call" to toolFailure("This client does not support client-executed tools. The tool was not executed."),
        "mcpServer/elicitation/request" to ServerRequestRoute.Interactive,
        "applyPatchApproval" to unsupported,
        "execCommandApproval" to unsupported,
        "account/chatgptAuthTokens/refresh" to unsupported,
        "attestation/generate" to unsupported,
    )

    fun route(
        method: String,
        params: JsonElement? = null,
        clockMillis: () -> Long = System::currentTimeMillis,
    ): ServerRequestRoute {
        if (method == "currentTime/read") {
            return ServerRequestRoute.Result(obj(
                "currentTimeAt" to JsonPrimitive(Math.floorDiv(clockMillis(), 1000L)),
            ))
        }
        if (method == "item/tool/call" && params is JsonObject &&
            params["namespace"] == s("codex_app") && params["tool"] == s("read_thread"))
            return readThreadUnavailable
        return routes[method] ?: unsupported
    }

    private fun toolFailure(text: String) = ServerRequestRoute.Result(obj(
        "success" to JsonPrimitive(false),
        "contentItems" to JsonArray(listOf(obj("type" to s("inputText"), "text" to s(text)))),
    ))
}
