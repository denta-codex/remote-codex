package dev.codexops.client

import dev.codexops.core.*
import java.nio.file.Paths
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

data class ProjectAdditionState(
    val visible: Boolean = false,
    val path: String = "/home/agent/workspaces",
    val loadedPath: String? = null,
    val folders: List<String> = emptyList(),
    val loading: Boolean = false,
    val working: Boolean = false,
    val confirming: Boolean = false,
    val name: String = "",
    val matches: List<CodexProject> = emptyList(),
    val pending: JsonObject? = null,
    val error: String? = null,
)

internal class ProjectPathUnavailable : Exception()

/** Stock host operations only. No desktop settings or workspace files are written. */
internal class ProjectRepository(private val rpc: RemoteSession) {
    suspend fun list(): List<CodexProject> {
        val projects = linkedMapOf<String, CodexProject>()
        val seen = mutableSetOf<String>()
        var cursor: String? = null
        var requests = 0
        do {
            check(requests++ < 100) { "Project loading exceeded its request limit" }
            val page = rpc.call("project/list", obj("cursor" to cursor?.let(::s),
                "limit" to JsonPrimitive(50), "sortKey" to s("position"), "sortDirection" to s("asc")))
            require(page["data"] is JsonArray) { "Project catalog response is incomplete" }
            page.list("data").forEach { row ->
                val project = parseProject(row)
                projects[project.id] = project
            }
            check(projects.size <= 5000) { "Project loading exceeded its catalog limit" }
            cursor = page.cursor()
            check(cursor == null || seen.add(cursor!!)) { "Project loading repeated a page" }
        } while (cursor != null)
        return projects.values.toList()
    }

    suspend fun canonicalDirectory(raw: String): String {
        val path = absolute(raw)
        // The path is an argv element, never interpolated into shell source.
        val canonical = rpc.call("command/exec", obj(
            "command" to JsonArray(listOf("realpath", "-e", "--", path).map(::s)),
            "sandboxPolicy" to obj("type" to s("readOnly")),
            "timeoutMs" to JsonPrimitive(10000), "outputBytesCap" to JsonPrimitive(16384)))
        if (canonical.str("exitCode") != "0") throw ProjectPathUnavailable()
        val resolved = absolute(canonical.str("stdout").removeSuffix("\n"))
        val result = rpc.getMetadata(resolved)
        val metadata = result["metadata"] as? JsonObject ?: result
        if (metadata.str("type").ifBlank { metadata.str("kind") } != "directory" &&
            (metadata["isDirectory"] as? JsonPrimitive)?.booleanOrNull != true) throw ProjectPathUnavailable()
        return resolved
    }

    suspend fun folders(path: String): List<String> =
        rpc.call("fs/readDirectory", obj("path" to s(absolute(path)))).list("entries").mapNotNull { entry ->
            val name = entry.str("fileName")
            require(name.isNotEmpty() && name !in setOf(".", "..") && '/' !in name && '\u0000' !in name)
            name.takeIf { (entry["isDirectory"] as? JsonPrimitive)?.booleanOrNull == true }
        }.distinct().sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it })

    suspend fun matches(path: String, projects: List<CodexProject>): List<CodexProject> {
        val canonicalRoots = mutableMapOf<String, String?>()
        return projects.filter { project ->
            project.roots.any { root ->
                if (root == path) true
                else {
                    if (!canonicalRoots.containsKey(root)) {
                        canonicalRoots[root] = try { canonicalDirectory(root) }
                        catch (_: ProjectPathUnavailable) { null }
                        catch (_: RpcRejected) { null }
                    }
                    canonicalRoots[root] == path
                }
            }
        }
    }

    suspend fun create(record: JsonObject): CodexProject = parseProject(
        rpc.call("project/create", obj("idempotencyKey" to s(record.str("operation")),
            "name" to s(record.str("name")),
            "roots" to JsonArray(listOf(obj("path" to s(record.str("path"))))))).map("project"))

    private fun parseProject(row: JsonObject): CodexProject {
        require(row.str("id").isNotBlank()) { "Project response is missing its ID" }
        return CodexProject(row.str("id"), row.str("name").ifBlank { "Untitled project" },
            row.list("roots").map { it.str("path") }.filter(String::isNotBlank))
    }

    companion object {
        fun absolute(raw: String): String {
            require(raw.startsWith('/') && '\u0000' !in raw && '\n' !in raw && '\r' !in raw) {
                "Enter an absolute folder path on the host."
            }
            // Do not collapse '..' before the host resolves symlinks.
            return raw.trimEnd('/').ifEmpty { "/" }
        }
    }
}

/** Registration has its own durable record and never sends a task or retries a mutation. */
internal class ProjectAdditionController(
    private val scope: CoroutineScope,
    private val store: ClientStore,
    private val rpc: RemoteSession,
    private val screen: () -> ScreenState,
    private val update: (ProjectAdditionState) -> Unit,
    private val select: suspend (List<CodexProject>, CodexProject, String, () -> Boolean) -> Boolean,
) {
    private val repository = ProjectRepository(rpc)
    private val key = "project-add/${screen().host.endpoint}/${screen().host.expectedCodexHome}"
    private var navigation: Job? = null
    private var revision = 0
    private var running = false
    private fun state() = screen().projectAddition
    private fun eligible() = screen().let { it.page == "chat" && it.thread == null && !it.busy && it.journal == null }

    fun open() {
        if (!eligible() || running) return
        ++revision
        running = true
        update(ProjectAdditionState(visible = true, loading = true))
        scope.launch {
            try {
                val saved = store.get(key)
                if (!state().visible) return@launch
                if (saved.isNotBlank()) {
                    val pending = runCatching { wire.parseToJsonElement(saved).jsonObject }.getOrNull() ?: obj()
                    update(state().copy(loading = false, pending = pending, path = pending.str("path"),
                        error = "A previous registration needs a state check. It will not be sent again."))
                } else update(state().copy(loading = false))
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) {
                if (!state().visible) return@launch
                update(state().copy(loading = false, pending = obj(), error = "Could not read the saved registration. Close and reopen to check again."))
            } finally { running = false }
            if (state().visible && state().pending == null && screen().ready) browse(state().path)
        }
    }

    fun dismiss() {
        revision++
        navigation?.cancel()
        update(state().copy(visible = false, loading = false))
    }

    fun disconnected() {
        revision++
        navigation?.cancel()
        update(state().copy(loading = false, loadedPath = null, folders = emptyList(), confirming = false, matches = emptyList()))
    }

    fun editPath(path: String) {
        if (running || state().pending != null) return
        revision++
        navigation?.cancel()
        update(state().copy(path = path, loadedPath = null, folders = emptyList(), loading = false,
            confirming = false, matches = emptyList(), error = null))
    }

    fun browse(raw: String) {
        if (running || state().pending != null || !screen().ready) return
        val ticket = ++revision
        val generation = rpc.generation
        navigation?.cancel()
        update(state().copy(path = raw, loading = true, loadedPath = null, folders = emptyList(),
            confirming = false, matches = emptyList(), error = null))
        navigation = scope.launch {
            try {
                val path = repository.canonicalDirectory(raw)
                val folders = repository.folders(path)
                if (ticket == revision && generation == rpc.generation && screen().ready)
                    update(state().copy(path = path, loadedPath = path, folders = folders, loading = false))
            } catch (e: Exception) {
                if (e is CancellationException && e !is TimeoutCancellationException) throw e
                if (ticket == revision && generation == rpc.generation)
                    update(state().copy(loading = false, error = "Cannot open this folder. Check the absolute path and host permissions."))
            }
        }
    }

    fun confirmFolder() {
        if (running || !screen().ready || state().loading || state().loadedPath == null || state().pending != null) return
        update(state().copy(confirming = true, name = Paths.get(state().loadedPath!!).fileName?.toString() ?: "Project", error = null))
    }

    fun name(value: String) {
        if (!running && state().pending == null) update(state().copy(name = value))
    }

    fun add() {
        if (running || !eligible() || !screen().ready || state().pending != null || !state().confirming || state().name.isBlank()) return
        val path = state().loadedPath ?: return
        runOperation { ticket, generation ->
            val canonical = repository.canonicalDirectory(path)
            val projects = repository.list()
            val matches = repository.matches(canonical, projects)
            if (!current(ticket, generation)) return@runOperation
            when {
                matches.size == 1 -> finish(projects, matches.single(), canonical, ticket, generation)
                matches.size > 1 -> update(state().copy(matches = matches, error = "Several projects use this folder. Choose one."))
                else -> {
                    var record = obj("operation" to s(UUID.randomUUID().toString()), "path" to s(canonical), "name" to s(state().name.trim()))
                    store.put(key, record.toString())
                    update(state().copy(pending = record))
                    if (!current(ticket, generation)) {
                        // No request was sent; safe to remove the prepared record.
                        store.remove(key)
                        update(state().copy(pending = null))
                        return@runOperation
                    }
                    val created = try { repository.create(record) }
                    catch (e: RpcRejected) {
                        store.remove(key)
                        update(state().copy(pending = null))
                        throw e
                    }
                    record = JsonObject(record + ("projectId" to s(created.id)))
                    store.put(key, record.toString())
                    update(state().copy(pending = record))
                    val refreshed = repository.list()
                    val confirmed = refreshed.firstOrNull { it.id == created.id }
                        ?: error("Created project was not returned by the catalog")
                    finish(refreshed, confirmed, canonical, ticket, generation)
                }
            }
        }
    }

    fun checkAgain() {
        if (running || !eligible() || !screen().ready || state().pending == null) return
        val record = state().pending!!
        if (record.str("path").isBlank() || record.str("operation").isBlank()) {
            update(state().copy(error = "The saved registration is incomplete. Inspect the host before attempting another registration."))
            return
        }
        runOperation { ticket, generation ->
            val projects = repository.list()
            val id = record.str("projectId")
            val matches = if (id.isNotBlank()) projects.filter { it.id == id }
                else repository.matches(record.str("path"), projects)
            if (!current(ticket, generation)) return@runOperation
            when (matches.size) {
                0 -> update(state().copy(error = "Registration is not confirmed. Check again after reconnecting; it will not be sent again."))
                1 -> finish(projects, matches.single(), record.str("path"), ticket, generation)
                else -> update(state().copy(matches = matches, error = "Several projects use this folder. Choose the project to use."))
            }
        }
    }

    fun choose(id: String) {
        if (running || !eligible() || !screen().ready || state().matches.none { it.id == id }) return
        val path = state().pending?.str("path") ?: state().loadedPath ?: return
        runOperation { ticket, generation ->
            val projects = repository.list()
            val match = repository.matches(path, projects).firstOrNull { it.id == id }
                ?: error("Project no longer matches this folder")
            finish(projects, match, path, ticket, generation)
        }
    }

    private fun current(ticket: Int, generation: Long) =
        ticket == revision && generation == rpc.generation && screen().ready && eligible() && state().visible

    private fun runOperation(block: suspend (Int, Long) -> Unit) {
        running = true
        navigation?.cancel()
        val ticket = ++revision
        val generation = rpc.generation
        update(state().copy(working = true, loading = false, error = null))
        scope.launch {
            try { block(ticket, generation) }
            catch (e: Exception) {
                if (e is CancellationException && e !is TimeoutCancellationException) throw e
                update(state().copy(error = if (state().pending != null)
                    "Registration needs a state check. Check again; it will not be sent again."
                    else "Could not add this project. Check the folder and connection, then try again."))
            } finally {
                running = false
                update(state().copy(working = false))
            }
        }
    }

    private suspend fun finish(projects: List<CodexProject>, project: CodexProject, path: String, ticket: Int, generation: Long) {
        if (!current(ticket, generation)) return
        // Use the saved server root so subsequent project/read validation agrees, including aliases.
        val root = project.roots.firstOrNull { it == path } ?: project.roots.firstOrNull {
            try { repository.canonicalDirectory(it) == path }
            catch (_: ProjectPathUnavailable) { false }
            catch (_: RpcRejected) { false }
        } ?: error("The project no longer contains this folder")
        if (!current(ticket, generation)) return
        if (!select(projects, project, root) { current(ticket, generation) }) return
        store.remove(key)
        update(state().copy(visible = false, pending = null, matches = emptyList(), error = null))
    }
}
