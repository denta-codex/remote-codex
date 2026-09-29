package dev.codexops.client

import dev.codexops.core.*
import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

internal data class WorkspacePlan(
    val target: ExecutionTarget,
    val projectId: String?,
    val sourceDirectory: String?,
    val workingDirectory: String,
    val worktreeRoot: String?,
)

internal object WorkspacePlans {
    fun create(options: NewTaskOptions, operation: String, codexHome: String): WorkspacePlan {
        require(operation.matches(Regex("[0-9a-f-]{36}"))) { "Invalid operation identity" }
        if (options.projectId == null) {
            require(options.executionTarget == ExecutionTarget.Projectless) {
                "Select a project before choosing a workspace"
            }
            return WorkspacePlan(
                target = ExecutionTarget.Projectless,
                projectId = null,
                sourceDirectory = null,
                workingDirectory = "/home/agent/Documents/RemoteCodex/$operation",
                worktreeRoot = null,
            )
        }

        require(options.projectId.isNotBlank()) { "Select a valid project" }
        require(options.executionTarget != ExecutionTarget.Projectless) {
            "Choose the current workspace or a new worktree"
        }
        val source = normalizedAbsolute(options.workingDirectory, "project workspace")
        if (options.executionTarget == ExecutionTarget.CurrentWorkspace) {
            return WorkspacePlan(
                target = options.executionTarget,
                projectId = options.projectId,
                sourceDirectory = source,
                workingDirectory = source,
                worktreeRoot = null,
            )
        }

        val home = normalizedAbsolute(codexHome, "Codex home")
        val root = "$home/worktrees/remote-codex-$operation"
        return WorkspacePlan(
            target = options.executionTarget,
            projectId = options.projectId,
            sourceDirectory = source,
            workingDirectory = "$root/workspace",
            worktreeRoot = root,
        )
    }

    private fun normalizedAbsolute(value: String?, label: String): String {
        require(!value.isNullOrBlank()) { "The $label is unavailable" }
        require('\u0000' !in value && '\n' !in value && '\r' !in value) {
            "The $label is invalid"
        }
        val file = File(value)
        require(file.isAbsolute) { "The $label must be an absolute path" }
        val normalized = file.toPath().normalize().toString()
        require(normalized == value.trimEnd('/')) { "The $label must not contain relative segments" }
        return normalized
    }
}

internal class WorkspaceSetupFailure(
    val userMessage: String,
    val uncertain: Boolean = false,
) : Exception(userMessage)

/** Narrow stock-RPC adapter for destination preparation. It does not discover projects. */
internal class StockWorkspaceAdapter(private val rpc: RemoteSession) {
    suspend fun validateSelectedProject(plan: WorkspacePlan) {
        val projectId = plan.projectId ?: return
        val source = plan.sourceDirectory ?: throw WorkspaceSetupFailure("Project workspace is missing")
        val response = rpc.call("project/read", obj("projectId" to s(projectId)))
        val project = response.map("project")
        if (project.str("id") != projectId) {
            throw WorkspaceSetupFailure("The selected project is no longer available on this host")
        }
        if (project.list("roots").none { it.str("path").trimEnd('/') == source }) {
            throw WorkspaceSetupFailure("The selected workspace is no longer a root of this project")
        }
        if (!directoryExists(source)) {
            throw WorkspaceSetupFailure("The selected workspace is not an accessible directory")
        }
    }

    suspend fun resolveDefaultCommit(source: String): String {
        val ref =
            command(
                listOf("git", "-C", source, "symbolic-ref", "refs/remotes/origin/HEAD"),
                "The project's default Git branch is unavailable. Use the current workspace instead.",
            ).str("stdout").trim()
        if (ref.isEmpty()) {
            throw WorkspaceSetupFailure(
                "The project's default Git branch is unavailable. Use the current workspace instead."
            )
        }
        val commit =
            command(
                listOf(
                    "git",
                    "-C",
                    source,
                    "rev-parse",
                    "--verify",
                    "--end-of-options",
                    "$ref^{commit}",
                ),
                "The project's default Git revision could not be resolved. Use the current workspace instead.",
            ).str("stdout").trim()
        if (!commit.matches(Regex("[0-9a-fA-F]{40,64}"))) {
            throw WorkspaceSetupFailure("The host returned an invalid Git revision")
        }
        return commit
    }

    suspend fun createDirectory(path: String) {
        command(
            listOf("mkdir", "-p", "-m", "0700", "--", path),
            "The workspace directory could not be prepared. Check its parent permissions.",
        )
    }

    suspend fun createProjectlessDirectory(path: String) {
        val root = "/home/agent/Documents/RemoteCodex"
        if (!path.startsWith("$root/")) {
            throw WorkspaceSetupFailure("The conversation directory is outside its allowed root")
        }
        val result =
            rpc.call(
                "command/exec",
                obj(
                    "command" to
                        JsonArray(
                            listOf("mkdir", "-p", "-m", "0700", "--", path).map(::s)
                        ),
                    "cwd" to s(root),
                    "sandboxPolicy" to
                        obj(
                            "type" to s("workspaceWrite"),
                            "writableRoots" to JsonArray(listOf(s(root))),
                            "networkAccess" to JsonPrimitive(false),
                        ),
                    "timeoutMs" to JsonPrimitive(10000),
                    "outputBytesCap" to JsonPrimitive(2048),
                ),
            )
        if (result.str("exitCode") != "0") {
            throw WorkspaceSetupFailure(
                "The conversation directory could not be prepared. Check its parent permissions."
            )
        }
    }

    suspend fun createDetachedWorktree(source: String, destination: String, commit: String) {
        command(
            listOf(
                "git",
                "-C",
                source,
                "worktree",
                "add",
                "--detach",
                "--",
                destination,
                commit,
            ),
            "Git could not create the isolated worktree. The retained path is shown below.",
        )
    }

    suspend fun directoryExists(path: String): Boolean {
        val response = rpc.getMetadata(path)
        val metadata = response["metadata"] as? JsonObject ?: response
        val type = metadata.str("type").ifBlank { metadata.str("kind") }
        return type.equals("directory", ignoreCase = true) ||
            (metadata["isDirectory"] as? JsonPrimitive)?.booleanOrNull == true
    }

    suspend fun worktreeExists(source: String, destination: String): Boolean {
        val result =
            command(
                listOf("git", "-C", source, "worktree", "list", "--porcelain"),
                "The host could not inspect the project's Git worktrees.",
            )
        return result.str("stdout").lineSequence().any { it == "worktree $destination" }
    }

    private suspend fun command(command: List<String>, failure: String): JsonObject {
        val result = commandResult(command)
        if (result.str("exitCode") != "0") throw WorkspaceSetupFailure(failure)
        return result
    }

    private suspend fun commandResult(command: List<String>): JsonObject =
        rpc.call(
            "command/exec",
            obj(
                "command" to JsonArray(command.map(::s)),
                "sandboxPolicy" to obj("type" to s("dangerFullAccess")),
                "env" to obj("LC_ALL" to s("C")),
                "timeoutMs" to JsonPrimitive(30000),
                "outputBytesCap" to JsonPrimitive(65536),
            ),
        )

    companion object {
        fun progress(stage: String): String =
            when (stage) {
                "validatingWorkspace" -> "Checking project workspace…"
                "creatingDirectory" -> "Preparing conversation directory…"
                "creatingWorktreeRoot" -> "Preparing worktree destination…"
                "worktreeRootReady", "creatingWorktree" -> "Creating isolated worktree…"
                "workspaceReady", "creatingTask" -> "Creating task…"
                "taskReady", "creatingAttachmentDirectory" -> "Preparing images…"
                "attachmentDirectoryReady", "uploadingAttachment", "attachmentUploaded" ->
                    "Uploading images…"
                "attachmentsReady", "sending" -> "Sending message…"
                "removingQueued" -> "Removing queued message…"
                "queuedRemoved", "steeringQueued" -> "Steering active turn…"
                "startingQueued" -> "Starting queued message…"
                else -> "Updating…"
            }
    }
}
