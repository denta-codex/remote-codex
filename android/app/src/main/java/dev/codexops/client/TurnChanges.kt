package dev.codexops.client

import dev.codexops.core.*
import kotlinx.serialization.json.JsonObject

internal data class RecordedFileChanges(val path: String, val patches: List<JsonObject>)

internal fun Entry.isRecordedFileChange(): Boolean =
    kind == "fileChange" && completed && raw.str("status") == "completed" &&
        raw.list("changes").any { it.str("path").isNotBlank() }

/** Recorded edits, not a net Git diff. History and live items use the same projection. */
internal fun completedTurnChanges(entries: List<Entry>, statuses: Map<String, String>): Map<String, ConversationRow.Changes> =
    entries.filter { statuses[it.turn] == "completed" && it.isRecordedFileChange() }
        .distinctBy { it.key }
        .groupBy { it.turn }
        .mapValues { (turn, edits) ->
            val files = edits.flatMap { it.raw.list("changes") }.filter { it.str("path").isNotBlank() }
                .groupBy { it.str("path") }.map { (path, patches) -> RecordedFileChanges(path, patches) }
            ConversationRow.Changes(turn, files)
        }
