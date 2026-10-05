package dev.codexops.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

sealed interface ServerRequestRoute {
    data object Interactive : ServerRequestRoute
    data class Result(val result: JsonObject) : ServerRequestRoute
    data class Error(val code: Int, val message: String) : ServerRequestRoute
}

/** Stock server requests have one destination; unsupported requests never enter the UI. */
object ServerRequests {
    private val unsupported = ServerRequestRoute.Error(-32601, "This client does not support this server request.")
    private val routes = mapOf(
        "item/commandExecution/requestApproval" to ServerRequestRoute.Interactive,
        "item/fileChange/requestApproval" to ServerRequestRoute.Interactive,
        "item/permissions/requestApproval" to ServerRequestRoute.Interactive,
        "item/tool/requestUserInput" to ServerRequestRoute.Interactive,
        "item/tool/call" to ServerRequestRoute.Result(obj(
            "success" to JsonPrimitive(false),
            "contentItems" to JsonArray(listOf(obj(
                "type" to s("inputText"),
                "text" to s("This client does not support client-executed tools. The tool was not executed."),
            ))),
        )),
        "mcpServer/elicitation/request" to ServerRequestRoute.Result(obj("action" to s("cancel"))),
        "currentTime/read" to unsupported,
        "applyPatchApproval" to unsupported,
        "execCommandApproval" to unsupported,
        "account/chatgptAuthTokens/refresh" to unsupported,
        "attestation/generate" to unsupported,
    )

    fun route(method: String): ServerRequestRoute = routes[method] ?: unsupported
}
