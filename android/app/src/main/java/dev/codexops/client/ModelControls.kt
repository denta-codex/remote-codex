package dev.codexops.client

import dev.codexops.core.*
import kotlinx.serialization.json.*

data class ReasoningEffortOption(
    val id: String,
    val description: String? = null,
)

data class ServerModelOption(
    val id: String,
    val displayName: String?,
    val description: String?,
    val defaultReasoningEffort: String?,
    val supportedReasoningEfforts: List<ReasoningEffortOption>,
    val isDefault: Boolean,
)

enum class ModelCatalogStatus {
    Unavailable,
    Loading,
    Ready,
    Error,
}

internal fun parseModelCatalog(result: JsonObject): List<ServerModelOption> =
    result.list("data")
        .mapNotNull { row ->
            val id = row.str("model").takeIf(String::isNotBlank) ?: return@mapNotNull null
            ServerModelOption(
                id = id,
                displayName = row.str("displayName").takeIf(String::isNotBlank),
                description = row.str("description").takeIf(String::isNotBlank),
                defaultReasoningEffort =
                    row.str("defaultReasoningEffort").takeIf(String::isNotBlank),
                supportedReasoningEfforts =
                    row.list("supportedReasoningEfforts").mapNotNull { effort ->
                        effort.str("reasoningEffort").takeIf(String::isNotBlank)?.let {
                            ReasoningEffortOption(
                                id = it,
                                description = effort.str("description").takeIf(String::isNotBlank),
                            )
                        }
                    },
                isDefault =
                    (row["isDefault"] as? JsonPrimitive)?.booleanOrNull == true,
            )
        }
        .distinctBy(ServerModelOption::id)

internal fun effectiveModel(
    options: NewTaskOptions,
    catalog: List<ServerModelOption>,
    threadModel: String?,
): ServerModelOption? {
    val id = options.model ?: threadModel
    return if (id != null) catalog.firstOrNull { it.id == id }
    else catalog.firstOrNull(ServerModelOption::isDefault)
}

internal data class ReconciledModelOptions(
    val options: NewTaskOptions,
    val removedUnsupportedChoice: Boolean,
)

internal fun reconcileModelOptions(
    options: NewTaskOptions,
    catalog: List<ServerModelOption>,
    threadModel: String?,
): ReconciledModelOptions {
    if (options.model != null && catalog.none { it.id == options.model }) {
        return ReconciledModelOptions(
            options.copy(model = null, reasoningEffort = null),
            removedUnsupportedChoice = true,
        )
    }
    val model = effectiveModel(options, catalog, threadModel)
    if (
        options.reasoningEffort != null &&
            model?.supportedReasoningEfforts?.none { it.id == options.reasoningEffort } != false
    ) {
        return ReconciledModelOptions(
            options.copy(reasoningEffort = null),
            removedUnsupportedChoice = true,
        )
    }
    return ReconciledModelOptions(options, removedUnsupportedChoice = false)
}

internal fun newThreadParams(cwd: String, options: NewTaskOptions) =
    obj(
        "cwd" to s(cwd),
        "historyMode" to s("paginated"),
        "ephemeral" to JsonPrimitive(false),
        "threadSource" to s("agent_created_thread"),
        "projectId" to JsonNull,
        "model" to options.model?.let(::s),
    )

internal fun turnStartParams(
    threadId: String,
    input: JsonArray,
    operation: String,
    options: NewTaskOptions,
    collaborationMode: JsonObject? = null,
) =
    obj(
        "threadId" to s(threadId),
        "input" to input,
        "clientUserMessageId" to s(operation),
        "model" to options.model?.let(::s),
        "effort" to options.reasoningEffort?.let(::s),
        "collaborationMode" to collaborationMode,
    )

internal fun turnSteerParams(
    threadId: String,
    input: JsonArray,
    operation: String,
    expectedTurnId: String,
) =
    obj(
        "threadId" to s(threadId),
        "input" to input,
        "clientUserMessageId" to s(operation),
        "expectedTurnId" to s(expectedTurnId),
    )
