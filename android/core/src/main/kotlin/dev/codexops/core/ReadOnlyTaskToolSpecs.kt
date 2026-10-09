package dev.codexops.core

import kotlinx.serialization.json.*

/** Stock 0.159.2 thread/start DynamicToolSpec namespace; only implemented tools are advertised. */
object ReadOnlyTaskToolSpecs {
    val definitions = JsonArray(listOf(obj(
        "type" to s("namespace"), "name" to s("codex_app"),
        "description" to s("Read-only task access on the active connected host. Tasks are never opened or changed."),
        "tools" to JsonArray(listOf(
            tool("list_threads",
                "Return a bounded recent snapshot of active user tasks on the connected host. " +
                    "Use codex-tasks for query-based finding, archives, or exhaustive inventory.",
                obj("hostId" to text("Optional active host ID; omit to select the connected host. Desktop local is unavailable."),
                    "limit" to integer(10, 1, 50)), emptyList()),
            tool("read_thread",
                "Read task metadata and recent turn history without opening or resuming it. " +
                    "Pages contain at most 20 items and may contain only part of a turn. Follow the returned opaque cursor until exhausted. MCP and dynamic-tool result payloads are omitted.",
                obj("threadId" to text("Task ID returned by list_threads."),
                    "hostId" to text("Optional active host ID returned by list_threads."),
                    "cursor" to text("Opaque continuation cursor from an earlier read_thread page."),
                    "turnLimit" to integer(1, 1, 10),
                    "includeOutputs" to obj("type" to s("boolean"), "default" to JsonPrimitive(false),
                        "description" to s("Include bounded command output, reasoning content, and file diffs. Default false.")),
                    "maxOutputCharsPerItem" to integer(2000, 0, 20000)), listOf("threadId")),
        )),
    )))

    private fun tool(name: String, description: String, properties: JsonObject, required: List<String>) = obj(
        "type" to s("function"), "name" to s(name), "description" to s(description),
        "deferLoading" to JsonPrimitive(false),
        "inputSchema" to obj("type" to s("object"), "properties" to properties,
            "required" to JsonArray(required.map(::s)), "additionalProperties" to JsonPrimitive(false)),
    )

    private fun text(description: String) = obj("type" to s("string"),
        "minLength" to JsonPrimitive(1), "description" to s(description))

    // Stock normalizes model-facing schemas and drops numeric constraints/defaults;
    // descriptions must also state the contract. The handler enforces it independently.
    private fun integer(default: Int, minimum: Int, maximum: Int) = obj("type" to s("integer"),
        "description" to s("Optional integer, default $default. Allowed range $minimum–$maximum inclusive."),
        "default" to JsonPrimitive(default), "minimum" to JsonPrimitive(minimum), "maximum" to JsonPrimitive(maximum))
}
