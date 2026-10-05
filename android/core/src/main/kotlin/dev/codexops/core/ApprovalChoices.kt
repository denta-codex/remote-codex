package dev.codexops.core

import kotlinx.serialization.json.*

data class ApprovalChoice(
    val value: JsonElement,
    val label: String,
    val consequence: String = "",
    val grantsAccess: Boolean = true,
) {
    val result: JsonObject get() = obj("decision" to value)
}

/** Choices remain bound to the stock request; amendments are never authored by the client. */
object ApprovalChoices {
    fun choices(request: Decision): List<ApprovalChoice> {
        val file = request.method == "item/fileChange/requestApproval"
        if (!file && request.method != "item/commandExecution/requestApproval") return emptyList()
        val advertised = request.params["availableDecisions"]
        val values = when {
            advertised is JsonArray -> advertised.toList()
            advertised == null || advertised == JsonNull ->
                (if (file) listOf("accept", "acceptForSession", "decline", "cancel")
                else listOf("accept", "decline")).map(::s)
            else -> emptyList()
        }
        return values.mapNotNull { value ->
            if (value is JsonPrimitive && value.isString) {
                when (value.content) {
                    "accept" -> ApprovalChoice(value, "Approve once")
                    "decline" -> ApprovalChoice(value, "Decline", grantsAccess = false)
                    "cancel" -> ApprovalChoice(value, "Deny and stop turn", grantsAccess = false)
                    "acceptForSession" -> ApprovalChoice(value, "Approve for session",
                        if (file) "Future changes to the same files can run without prompting for this session."
                        else "Future matching approvals can run without prompting for this session.")
                    else -> null
                }
            } else if (!file && value is JsonObject && value.size == 1) {
                val exec = value["acceptWithExecpolicyAmendment"] as? JsonObject
                val prefix = exec?.get("execpolicy_amendment") as? JsonArray
                val net = value["applyNetworkPolicyAmendment"] as? JsonObject
                val amendment = net?.get("network_policy_amendment") as? JsonObject
                when {
                    exec?.keys == setOf("execpolicy_amendment") && prefix != null &&
                        prefix.all { it is JsonPrimitive && it.isString } ->
                        ApprovalChoice(value, "Approve and save command rule",
                            "Future commands matching this prefix can run without prompting. This rule persists beyond this turn:\n$prefix" +
                                if (prefix.isEmpty()) "\nAn empty prefix can match broadly." else "")
                    net?.keys == setOf("network_policy_amendment") &&
                        amendment?.keys == setOf("host", "action") &&
                        amendment["host"] is JsonPrimitive && amendment["host"]!!.jsonPrimitive.isString &&
                        amendment.str("host").isNotBlank() &&
                        amendment["action"] is JsonPrimitive && amendment["action"]!!.jsonPrimitive.isString &&
                        amendment.str("action") in setOf("allow", "deny") -> {
                        val allow = amendment.str("action") == "allow"
                        ApprovalChoice(value, if (allow) "Save network allow rule" else "Save network deny rule",
                            "${if (allow) "Allow" else "Deny"} future network requests for ${amendment.str("host")}. This rule persists beyond this turn.", allow)
                    }
                    else -> null
                }
            } else null
        }.distinctBy { it.value }
    }

    fun validResult(request: Decision, result: JsonObject): Boolean =
        if (request.method == "item/permissions/requestApproval") PermissionSelection(request).validResult(result)
        else choices(request).any { it.result == result }
}

data class PermissionOption(val id: String, val label: String, val value: JsonElement)

/** Builds a subset by copying requested entries, retaining filesystem restrictions and scan limits. */
class PermissionSelection(request: Decision) {
    private val profile = request.params.map("permissions")
    private val fs = profile["fileSystem"] as? JsonObject
    private val knownFileSystem = fs != null && fs.keys.all { it in setOf("entries", "read", "write", "globScanMaxDepth") } &&
        listOf("entries", "read", "write").all { field ->
            val entries = fs[field]
            entries == null || entries == JsonNull || entries is JsonArray && entries.all { entry ->
                if (field == "entries") entry is JsonObject && entry.keys == setOf("access", "path") &&
                    entry.str("access") in setOf("read", "write", "deny") && knownPath(entry["path"])
                else entry is JsonPrimitive && entry.isString
            }
        }

    private fun knownPath(value: JsonElement?): Boolean {
        val path = value as? JsonObject ?: return false
        return when (path.str("type")) {
            "path" -> path.keys == setOf("type", "path") && path["path"] is JsonPrimitive && path["path"]!!.jsonPrimitive.isString
            "glob_pattern" -> path.keys == setOf("type", "pattern") && path["pattern"] is JsonPrimitive && path["pattern"]!!.jsonPrimitive.isString
            "special" -> (path["value"] as? JsonObject)?.str("kind") in setOf("root", "minimal", "project_roots", "tmpdir", "slash_tmp", "unknown")
            else -> false
        }
    }

    private fun pathLabel(path: JsonObject): String = when (path.str("type")) {
        "path" -> path.str("path")
        "glob_pattern" -> "Files matching ${path.str("pattern")}"
        else -> {
            val special = path.map("value")
            val root = when (special.str("kind")) {
                "root" -> "Filesystem root"
                "minimal" -> "Minimal sandbox paths"
                "project_roots" -> "Project roots"
                "tmpdir" -> "Temporary directory"
                "slash_tmp" -> "/tmp"
                else -> special.str("path")
            }
            root + special.str("subpath").takeIf { it.isNotEmpty() }?.let { "/$it" }.orEmpty()
        }
    }

    val options: List<PermissionOption> = buildList {
        val network = profile["network"] as? JsonObject
        if (network?.keys == setOf("enabled") && network["enabled"] == JsonPrimitive(true))
            add(PermissionOption("network", "Network access", network))
        listOf("entries", "read", "write").forEach { field ->
            (fs?.get(field) as? JsonArray)?.takeIf { knownFileSystem }?.forEachIndexed { index, entry ->
                if (field == "entries") {
                    val e = entry as? JsonObject
                    if (e?.str("access") in setOf("read", "write") && e?.get("path") is JsonObject)
                        add(PermissionOption("$field/$index", "${e!!.str("access")}: ${pathLabel(e.map("path"))}", entry))
                } else if (entry is JsonPrimitive && entry.isString)
                    add(PermissionOption("$field/$index", "$field: ${entry.content}", entry))
            }
        }
    }

    fun result(selected: Set<String>, scope: String = "turn"): JsonObject {
        require(scope in setOf("turn", "session"))
        require(selected.all { id -> options.any { it.id == id } })
        val grant = mutableMapOf<String, JsonElement>()
        options.firstOrNull { it.id == "network" && it.id in selected }?.let { grant["network"] = it.value }
        if (selected.any { it != "network" } && fs != null) {
            val subset = mutableMapOf<String, JsonElement>()
            fs["globScanMaxDepth"]?.let { subset["globScanMaxDepth"] = it }
            listOf("entries", "read", "write").forEach { field ->
                val entries = (fs[field] as? JsonArray)?.filterIndexed { index, entry ->
                    "$field/$index" in selected || field == "entries" &&
                        (entry as? JsonObject)?.str("access") == "deny"
                }.orEmpty()
                if (entries.isNotEmpty()) subset[field] = JsonArray(entries)
            }
            grant["fileSystem"] = JsonObject(subset)
        }
        return obj("permissions" to JsonObject(grant), "scope" to s(if (grant.isEmpty()) "turn" else scope))
    }

    fun validResult(response: JsonObject): Boolean {
        if (response.keys != setOf("permissions", "scope") || response.str("scope") !in setOf("turn", "session")) return false
        val granted = response["permissions"] as? JsonObject ?: return false
        val selected = mutableSetOf<String>()
        options.firstOrNull { it.id == "network" && granted["network"] == it.value }?.let { selected.add(it.id) }
        listOf("entries", "read", "write").forEach { field ->
            val requested = (fs?.get(field) as? JsonArray).orEmpty()
            var next = 0
            (granted.map("fileSystem")[field] as? JsonArray)?.forEach { value ->
                while (next < requested.size && requested[next] != value) next++
                if (next < requested.size) {
                    val id = "$field/$next"
                    if (options.any { it.id == id }) selected.add(id)
                    next++
                }
            }
        }
        return result(selected, response.str("scope")) == response
    }
}
