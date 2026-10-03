package dev.codexops.client

import dev.codexops.core.*
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

internal class BugReportSubmission(private val rpc: RemoteSession, private val host: HostIdentity) {
    private val workspaces = StockWorkspaceAdapter(rpc)

    suspend fun run(initial: BugReportDraft, save: suspend (BugReportDraft) -> Unit): BugReportDraft {
        var draft = initial
        suspend fun record(stage: String, vararg fields: Pair<String, JsonElement>) {
            val next = draft.copy(journal = JsonObject(draft.journal + fields.toMap() + ("stage" to s(stage))))
            save(next) // No mutation is dispatched until its journal entry is durable.
            draft = next
        }
        if (draft.journal.isEmpty()) {
            require(draft.description.isNotBlank()) { "Describe what went wrong." }
            val projects = mutableListOf<JsonObject>()
            var cursor: String? = null
            val seen = mutableSetOf<String>()
            do {
                val page = rpc.call("project/list", obj("limit" to JsonPrimitive(100), "cursor" to cursor?.let(::s)))
                projects += page.list("data")
                cursor = page.cursor()
                check(projects.size <= 5000 && (cursor == null || seen.add(cursor))) { "Project listing did not complete." }
            } while (cursor != null)
            val matches = projects.filter { it.list("roots").any { root -> root.str("path").trimEnd('/') == host.bugReportRepository } }
            require(matches.size == 1) { "The configured remote-codex checkout must belong to exactly one project on ${host.displayName}." }
            val plan = WorkspacePlans.create(NewTaskOptions(projectId = matches.single().str("id"),
                workingDirectory = host.bugReportRepository, executionTarget = ExecutionTarget.NewWorktree), draft.id, host.expectedCodexHome)
            workspaces.validateSelectedProject(plan)
            val origin = command(listOf("git", "-C", host.bugReportRepository, "remote", "get-url", "origin")).str("stdout").trim()
            require(validBugReportOrigin(origin)) { "The reporting checkout does not identify denta-codex/remote-codex." }
            val commit = workspaces.resolveDefaultCommit(host.bugReportRepository)
            record("validated", "projectId" to s(requireNotNull(plan.projectId)), "cwd" to s(plan.workingDirectory),
                "root" to s(requireNotNull(plan.worktreeRoot)), "commit" to s(commit),
                "name" to s("Bug: " + draft.description.lineSequence().first().take(100)))
        }

        val root = draft.journal.str("root")
        val cwd = draft.journal.str("cwd")
        val evidence = "$root/report"
        val receipt = "$root/setup-complete"
        val receiptValue = "${draft.id}\n${draft.journal.str("commit")}\n"

        // In-flight stages are inspection-only. Absence is never proof that a mutation failed.
        when (draft.journal.str("stage")) {
            "creatingRoot" -> {
                requireConfirmed(workspaces.directoryExists(root), "worktree directory")
                record("rootReady")
            }
            "creatingWorktree" -> {
                requireConfirmed(workspaces.worktreeExists(host.bugReportRepository, cwd), "worktree")
                val revision = command(listOf("git", "-C", cwd, "rev-parse", "HEAD")).str("stdout").trim()
                requireConfirmed(revision == draft.journal.str("commit"), "worktree revision")
                record("workspaceReady")
            }
            "settingUp" -> {
                requireConfirmed(readMatches(receipt, receiptValue.toByteArray()), "environment setup completion")
                record("environmentReady")
            }
            "creatingEvidence" -> {
                requireConfirmed(workspaces.directoryExists(evidence), "evidence directory")
                record("evidenceReady")
            }
            "uploading" -> {
                val index = draft.journal.str("uploaded").toInt()
                val file = draft.attachments[index]
                val bytes = withContext(Dispatchers.IO) { File(file.localPath).readBytes() }
                requireConfirmed(readMatches(remotePath(evidence, index, file), bytes), "artifact upload")
                record("evidenceReady", "uploaded" to JsonPrimitive(index + 1))
            }
            "creatingTask" -> {
                val found = findTask(draft.journal)
                requireConfirmed(found != null, "task creation")
                record("taskReady", "threadId" to s(requireNotNull(found)))
            }
            "namingTask" -> {
                val task = rpc.call("thread/read", obj("threadId" to s(draft.journal.str("threadId")))).map("thread")
                requireConfirmed(task.str("name") == draft.journal.str("name"), "task title")
                record("named")
            }
            "sending" -> {
                requireConfirmed(findSubmission(draft.journal.str("threadId"), draft.id), "first message submission")
                record("accepted")
            }
        }
        if (draft.journal.str("stage") == "validated") {
            record("creatingRoot")
            workspaces.createDirectory(root)
            record("rootReady")
        }
        if (draft.journal.str("stage") == "rootReady") {
            record("creatingWorktree")
            workspaces.createDetachedWorktree(host.bugReportRepository, cwd, draft.journal.str("commit"))
            record("workspaceReady")
        }
        if (draft.journal.str("stage") == "workspaceReady") {
            record("settingUp")
            workspaces.prepareEnvironment(cwd, "./scripts/setup-worktree", receipt, draft.id, draft.journal.str("commit"))
            record("environmentReady")
        }
        if (draft.journal.str("stage") == "environmentReady") {
            record("creatingEvidence")
            workspaces.createDirectory(evidence)
            record("evidenceReady", "uploaded" to JsonPrimitive(0))
        }
        if (draft.journal.str("stage") == "evidenceReady") {
            for (index in (draft.journal.str("uploaded").toIntOrNull() ?: 0) until draft.attachments.size) {
                val file = draft.attachments[index]
                val bytes = withContext(Dispatchers.IO) { File(file.localPath).readBytes() }
                require(bytes.size.toLong() == file.byteSize) { "A saved report attachment changed." }
                record("uploading", "uploaded" to JsonPrimitive(index))
                rpc.writeFile(remotePath(evidence, index, file), bytes)
                record("evidenceReady", "uploaded" to JsonPrimitive(index + 1))
            }
            record("uploaded")
        }
        if (draft.journal.str("stage") == "uploaded") {
            record("creatingTask")
            val task = rpc.call("thread/start", obj("cwd" to s(cwd), "projectId" to s(draft.journal.str("projectId")),
                "ephemeral" to JsonPrimitive(false), "historyMode" to s("paginated"), "threadSource" to s("agent_created_thread"))).map("thread")
            require(task.str("id").isNotBlank()) { "The task response omitted its identity. Check and continue to inspect the host." }
            require(task.str("projectId") == draft.journal.str("projectId")) { "The new task did not retain the reporting project. Inspect the retained workspace." }
            record("taskReady", "threadId" to s(task.str("id")))
        }
        val threadId = draft.journal.str("threadId")
        if (draft.journal.str("stage") == "taskReady") {
            record("namingTask")
            rpc.call("thread/name/set", obj("threadId" to s(threadId), "name" to s(draft.journal.str("name"))))
            record("named")
        }
        if (draft.journal.str("stage") == "named") {
            val prompt = bugReportPrompt(draft, evidence)
            val attachments = draft.attachments.mapIndexed { index, file -> TurnAttachment(file.kind, file.displayName, remotePath(evidence, index, file)) }
            record("sending")
            // A newly created thread uses the server's default implementation mode and model.
            rpc.call("turn/start", turnStartParams(threadId, turnInput(prompt, attachments), draft.id, NewTaskOptions()))
            record("accepted")
        }
        return draft
    }

    private suspend fun command(argv: List<String>, timeout: Long = 30_000): JsonObject {
        val result = rpc.callWithTimeout("command/exec", obj("command" to JsonArray(argv.map(::s)),
            "sandboxPolicy" to obj("type" to s("dangerFullAccess")), "env" to obj("LC_ALL" to s("C")),
            "timeoutMs" to JsonPrimitive(timeout), "outputBytesCap" to JsonPrimitive(8192)), timeout + 10_000)
        check(result.str("exitCode") == "0") { "Host preparation failed. Inspect the retained workspace; the operation was not replayed." }
        return result
    }

    private suspend fun readMatches(path: String, expected: ByteArray): Boolean =
        MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(rpc.readFile(path)), MessageDigest.getInstance("SHA-256").digest(expected))

    private fun requireConfirmed(confirmed: Boolean, operation: String) {
        check(confirmed) { "Could not confirm $operation. Nothing was retried. Inspect the retained workspace/task, then check again." }
    }

    private suspend fun findTask(journal: JsonObject): String? {
        var cursor: String? = null
        val found = mutableSetOf<String>()
        val seen = mutableSetOf<String>()
        repeat(50) {
            val page = rpc.call("thread/list", obj("cwd" to s(journal.str("cwd")), "projectId" to s(journal.str("projectId")),
                "limit" to JsonPrimitive(100), "cursor" to cursor?.let(::s)))
            page.list("data").filter { it.str("cwd") == journal.str("cwd") && it.str("projectId") == journal.str("projectId") }
                .map { it.str("id") }.filter(String::isNotBlank).forEach(found::add)
            cursor = page.cursor() ?: return found.singleOrNull()
            if (!seen.add(requireNotNull(cursor))) return null
        }
        return null
    }

    private suspend fun findSubmission(threadId: String, operation: String): Boolean {
        var cursor: String? = null
        val seen = mutableSetOf<String>()
        repeat(50) {
            val page = rpc.call("thread/turns/list", obj("threadId" to s(threadId), "limit" to JsonPrimitive(100),
                "itemsView" to s("full"), "sortDirection" to s("asc"), "cursor" to cursor?.let(::s)))
            if (page.list("data").any { turn -> turn.list("items").any { item ->
                    item.str("type") == "userMessage" && (item.str("id") == operation || item.str("clientUserMessageId") == operation)
                } }) return true
            cursor = page.cursor() ?: return false
            if (!seen.add(requireNotNull(cursor))) return false
        }
        return false
    }

    private fun remotePath(directory: String, index: Int, file: DraftAttachment): String =
        "$directory/${safeAttachmentName(index + 1, file.displayName, file.kind, if (file.kind == AttachmentKind.IMAGE) ImagePolicy.inspect(File(file.localPath)) else null)}"
}

internal fun validBugReportOrigin(value: String): Boolean = value in setOf(
    "https://github.com/denta-codex/remote-codex.git", "https://github.com/denta-codex/remote-codex",
    "git@github.com:denta-codex/remote-codex.git", "git@github.com:denta-codex/remote-codex",
    "ssh://git@github.com/denta-codex/remote-codex.git", "ssh://git@github.com/denta-codex/remote-codex",
)
