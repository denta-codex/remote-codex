package dev.codexops.client

import dev.codexops.core.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class WorkspaceExecutionTest {
    private val operation = "12345678-1234-1234-1234-123456789abc"

    @Test
    fun plansKeepProjectIdentitySeparateFromExecutionDirectory() {
        val current =
            WorkspacePlans.create(
                NewTaskOptions(
                    projectId = "project-1",
                    workingDirectory = "/srv/repo",
                    executionTarget = ExecutionTarget.CurrentWorkspace,
                ),
                operation,
                "/srv/codex",
            )
        assertEquals("project-1", current.projectId)
        assertEquals("/srv/repo", current.workingDirectory)
        assertNull(current.worktreeRoot)

        val isolated =
            WorkspacePlans.create(
                NewTaskOptions(
                    projectId = "project-1",
                    workingDirectory = "/srv/repo",
                    executionTarget = ExecutionTarget.NewWorktree,
                ),
                operation,
                "/srv/codex",
            )
        assertEquals("project-1", isolated.projectId)
        assertEquals("/srv/repo", isolated.sourceDirectory)
        assertEquals(
            "/srv/codex/worktrees/remote-codex-$operation/workspace",
            isolated.workingDirectory,
        )
        assertEquals(
            "/srv/codex/worktrees/remote-codex-$operation",
            isolated.worktreeRoot,
        )
    }

    @Test
    fun projectlessPlanPreservesExistingDestinationPolicy() {
        val plan = WorkspacePlans.create(NewTaskOptions(), operation, "/srv/codex")
        assertEquals(ExecutionTarget.Projectless, plan.target)
        assertEquals("/home/agent/Documents/RemoteCodex/$operation", plan.workingDirectory)
        assertNull(plan.projectId)
    }

    @Test
    fun invalidOrIncompleteSelectionsAreRejectedBeforeRpc() {
        assertThrows(IllegalArgumentException::class.java) {
            WorkspacePlans.create(
                NewTaskOptions(
                    projectId = "project-1",
                    workingDirectory = "relative/repo",
                    executionTarget = ExecutionTarget.NewWorktree,
                ),
                operation,
                "/srv/codex",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorkspacePlans.create(
                NewTaskOptions(projectId = "project-1", workingDirectory = "/srv/repo"),
                operation,
                "/srv/codex",
            )
        }
    }

    @Test
    fun stockAdapterValidatesOnlySelectedProjectAndUsesDetachedGitArgv() = runBlocking {
        val calls = mutableListOf<Pair<String, JsonObject>>()
        val destination = "/srv/codex/worktrees/remote-codex-$operation/workspace"
        val rpc =
            FakeSession { method, params ->
                calls += method to params
                if (method == "project/read") {
                    obj(
                        "project" to
                            obj(
                                "id" to s("project-1"),
                                "roots" to
                                    kotlinx.serialization.json.JsonArray(
                                        listOf(obj("path" to s("/srv/repo")))
                                    ),
                            )
                    )
                } else {
                    val command = params["command"]!!.toString()
                    when {
                        "symbolic-ref" in command ->
                            obj("exitCode" to JsonPrimitive(0), "stdout" to s("refs/remotes/origin/main\n"))
                        "rev-parse" in command ->
                            obj("exitCode" to JsonPrimitive(0), "stdout" to s("a".repeat(40) + "\n"))
                        "worktree\",\"list" in command ->
                            obj(
                                "exitCode" to JsonPrimitive(0),
                                "stdout" to s("worktree /srv/repo\nHEAD ${"b".repeat(40)}\n\nworktree $destination\nHEAD ${"a".repeat(40)}\ndetached\n"),
                            )
                        else -> obj("exitCode" to JsonPrimitive(0))
                    }
                }
            }
        val adapter = StockWorkspaceAdapter(rpc)
        val plan =
            WorkspacePlan(
                ExecutionTarget.NewWorktree,
                "project-1",
                "/srv/repo",
                destination,
                destination.substringBeforeLast('/'),
            )

        adapter.validateSelectedProject(plan)
        val commit = adapter.resolveDefaultCommit("/srv/repo")
        adapter.createDetachedWorktree("/srv/repo", destination, commit)
        assertTrue(adapter.worktreeExists("/srv/repo", destination))

        assertEquals("a".repeat(40), commit)
        assertFalse(calls.any { it.first == "project/list" })
        assertEquals("project-1", calls.first().second.str("projectId"))
        val add =
            calls.map { it.second }.first {
                it["command"].toString().contains("worktree\",\"add")
            }
        assertTrue(add["command"].toString().contains("\"--detach\""))
        assertTrue(add["command"].toString().contains("\"$destination\""))
        assertEquals("dangerFullAccess", add.map("sandboxPolicy").str("type"))
    }

    @Test
    fun projectlessDirectoryRetainsNarrowWorkspacePolicy() = runBlocking {
        var params: JsonObject? = null
        val adapter =
            StockWorkspaceAdapter(
                FakeSession { method, value ->
                    assertEquals("command/exec", method)
                    params = value
                    obj("exitCode" to JsonPrimitive(0))
                }
            )
        adapter.createProjectlessDirectory("/home/agent/Documents/RemoteCodex/$operation")
        assertEquals("workspaceWrite", params!!.map("sandboxPolicy").str("type"))
        assertEquals("/home/agent/Documents/RemoteCodex", params!!.str("cwd"))
    }

    private class FakeSession(
        private val handler: suspend (String, JsonObject) -> JsonObject
    ) : RemoteSession {
        private val channel = Channel<JsonObject>()
        override val events: ReceiveChannel<JsonObject> = channel
        override val generation: Long = 0

        override suspend fun connect(url: String, token: String) = obj()

        override suspend fun call(method: String, params: JsonObject) = handler(method, params)

        override fun respond(id: JsonElement, result: JsonObject, epoch: Long) = Unit

        override fun close() = Unit

        override fun dispose() {
            channel.close()
        }
    }
}
