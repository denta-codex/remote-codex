package dev.codexops.client

import dev.codexops.core.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

enum class WorktreeStatus { Loading, Ready, NotRepository, Error }

data class WorktreeFile(val path: String, val added: Long?, val removed: Long?, val diff: String)

data class WorktreeChanges(
    val cwd: String? = null,
    val root: String? = null,
    val status: WorktreeStatus = WorktreeStatus.Loading,
    val files: List<WorktreeFile> = emptyList(),
    val stale: Boolean = false,
) {
    val added: Long get() = files.sumOf { it.added ?: 0 }
    val removed: Long get() = files.sumOf { it.removed ?: 0 }
}

/** NUL framing preserves tabs/newlines in Git filenames and detects truncated RPC output. */
internal fun parseWorktreeChanges(stdout: String, cwd: String): WorktreeChanges {
    val fields = stdout.split('\u0000')
    require(fields.firstOrNull() == "remote-codex-worktree-v1" && fields.takeLast(2) == listOf("end", "")) {
        "Incomplete worktree snapshot"
    }
    if (fields.getOrNull(1) == "not-repository") {
        require(fields.size == 4)
        return WorktreeChanges(cwd = cwd, status = WorktreeStatus.NotRepository)
    }
    require(fields.getOrNull(1) == "ready" && fields.size >= 5 && (fields.size - 5) % 4 == 0)
    val root = fields[2]
    require(root.startsWith('/'))
    val files = fields.subList(3, fields.size - 2).chunked(4).map { row ->
        fun count(value: String): Long? = if (value == "-") null else
            requireNotNull(value.toLongOrNull()?.takeIf { it >= 0 }) { "Invalid diff count" }
        require(row[0].isNotEmpty())
        WorktreeFile(row[0], count(row[1]), count(row[2]), row[3])
    }
    require(files.map { it.path }.distinct().size == files.size)
    // Reject overflow rather than showing a negative total.
    files.fold(0L) { total, file -> Math.addExact(total, file.added ?: 0) }
    files.fold(0L) { total, file -> Math.addExact(total, file.removed ?: 0) }
    return WorktreeChanges(cwd, root, WorktreeStatus.Ready, files)
}

/** Uses stock command/exec. No Git writes, external diff helpers, or text conversion. */
internal suspend fun readWorktreeChanges(rpc: RemoteSession, cwd: String): WorktreeChanges {
    val response = rpc.callWithTimeout("command/exec", obj(
        "command" to JsonArray(listOf("bash", "-c", worktreeReadScript, "remote-codex-worktree", cwd).map(::s)),
        "cwd" to s(cwd),
        "sandboxPolicy" to obj("type" to s("readOnly")),
        "env" to obj("LC_ALL" to s("C"), "GIT_OPTIONAL_LOCKS" to s("0"), "GIT_LITERAL_PATHSPECS" to s("1")),
        "timeoutMs" to JsonPrimitive(8_000),
        "outputBytesCap" to JsonPrimitive(2 * 1024 * 1024),
    ), 10_000)
    require(response.str("exitCode") == "0") { "Worktree inspection failed" }
    return parseWorktreeChanges(response.str("stdout"), cwd)
}

internal val worktreeReadScript = """
    set -euo pipefail
    printf 'remote-codex-worktree-v1\0'
    command -v git >/dev/null
    [[ -d "${'$'}1" ]] || exit 2
    if ! root=${'$'}(git -C "${'$'}1" rev-parse --show-toplevel 2>/dev/null); then
        printf 'not-repository\0end\0'
        exit 0
    fi
    cd -- "${'$'}root"
    printf 'ready\0%s\0' "${'$'}root"
    if ! base=${'$'}(git rev-parse --verify HEAD 2>/dev/null); then
        base=${'$'}(git hash-object -t tree /dev/null)
    fi
    # --no-renames expresses renames as deletion/addition, including partial renames.
    # A slash cannot be a relative Git filename or numstat row. Each producer
    # emits it only after success, since process substitution hides exit status.
    tracked_complete=false
    while IFS= read -r -d '' row; do
        if [[ "${'$'}row" == / ]]; then tracked_complete=true; break; fi
        added=${'$'}{row%%${'$'}'\t'*}
        rest=${'$'}{row#*${'$'}'\t'}
        removed=${'$'}{rest%%${'$'}'\t'*}
        path=${'$'}{rest#*${'$'}'\t'}
        patch=${'$'}(git diff --no-ext-diff --no-textconv --no-renames --no-color "${'$'}base" -- "${'$'}path")
        printf '%s\0%s\0%s\0%s\0' "${'$'}path" "${'$'}added" "${'$'}removed" "${'$'}patch"
    done < <(git diff --no-ext-diff --no-textconv --no-renames --numstat -z "${'$'}base" -- && printf '/\0')
    [[ "${'$'}tracked_complete" == true ]] || exit 2
    untracked_complete=false
    while IFS= read -r -d '' path; do
        if [[ "${'$'}path" == / ]]; then untracked_complete=true; break; fi
        code=0
        stat=${'$'}(git diff --no-ext-diff --no-textconv --no-color --no-index --numstat -- /dev/null "${'$'}path") || code=${'$'}?
        (( code <= 1 )) || exit "${'$'}code"
        added=0; removed=0
        if [[ -n "${'$'}stat" ]]; then
            added=${'$'}{stat%%${'$'}'\t'*}
            rest=${'$'}{stat#*${'$'}'\t'}
            removed=${'$'}{rest%%${'$'}'\t'*}
        fi
        code=0
        patch=${'$'}(git diff --no-ext-diff --no-textconv --no-color --no-index -- /dev/null "${'$'}path") || code=${'$'}?
        (( code <= 1 )) || exit "${'$'}code"
        printf '%s\0%s\0%s\0%s\0' "${'$'}path" "${'$'}added" "${'$'}removed" "${'$'}patch"
    done < <(git ls-files --others --exclude-standard -z && printf '/\0')
    [[ "${'$'}untracked_complete" == true ]] || exit 2
    printf 'end\0'
""".trimIndent()
