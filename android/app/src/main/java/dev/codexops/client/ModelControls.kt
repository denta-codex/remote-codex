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
    else null
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
            model?.supportedReasoningEfforts?.none { it.id == options.reasoningEffort } == true
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

/** Read-only config/read snapshot. Values never become outgoing overrides. */
data class InheritedSettings(
    val cwd: String? = null,
    val status: ModelCatalogStatus = ModelCatalogStatus.Unavailable,
    val model: String? = null,
    val effort: String? = null,
    val modelSource: String = "From server",
    val effortSource: String = "From server",
)

internal fun parseInheritedSettings(result: JsonObject, cwd: String): InheritedSettings {
    val config = result.map("config")
    fun source(key: String) =
        if (result.map("origins").map(key).map("name").str("type") == "project")
            "From project" else "From server"
    return InheritedSettings(
        cwd, ModelCatalogStatus.Ready,
        config.str("model").takeIf(String::isNotBlank),
        config.str("model_reasoning_effort").takeIf(String::isNotBlank),
        source("model"), source("model_reasoning_effort"),
    )
}

internal fun ScreenState.settingsCwd(): String? = when {
    thread != null -> threadCwd
    newTaskOptions.executionTarget == ExecutionTarget.NewWorktree -> null // Destination not created yet.
    newTaskOptions.projectId != null -> newTaskOptions.workingDirectory
    else -> "/home/agent/Documents/RemoteCodex"
}

internal data class ComposerSettings(
    val modelId: String?, val model: String, val modelSource: String,
    val effort: String, val effortSource: String, val mode: String,
)

internal fun ScreenState.composerSettings(): ComposerSettings {
    val inherited = inheritedSettings.takeIf { it.cwd == settingsCwd() }
    val options = newTaskOptions
    val preset = collaborationModes.firstOrNull { it.mode == options.collaborationMode }
    val queued = willQueueMessage() && !waitingToSendMode()
    val modelId = if (queued) threadModel else
        preset?.model ?: options.model ?: if (thread != null) threadModel else inherited?.model
    val catalogModel = models.firstOrNull { it.id == modelId }
    val effort = if (queued) threadReasoningEffort else when {
        preset != null -> preset.reasoningEffort // Mode settings take precedence over turn effort.
        options.reasoningEffort != null -> options.reasoningEffort
        thread != null -> threadReasoningEffort
        else -> inherited?.effort ?: catalogModel?.defaultReasoningEffort
            .takeIf { inherited?.status == ModelCatalogStatus.Ready }
    }
    val unknown = when {
        !ready -> "Offline"
        settingsCwd() == null && thread == null -> "Not resolved"
        inherited == null || inherited.status == ModelCatalogStatus.Loading -> "Loading…"
        else -> "Unavailable"
    }
    return ComposerSettings(
        modelId, catalogModel?.displayName ?: modelId ?: unknown,
        when {
            queued || thread != null && options.model == null && preset?.model == null -> "This chat"
            preset?.model != null -> "From ${preset.name} mode"
            options.model != null -> "This chat"
            settingsCwd() == null -> "After workspace creation"
            else -> inherited?.modelSource ?: "From server"
        },
        effort?.replaceFirstChar { it.uppercase() } ?: unknown,
        when {
            queued -> "This chat"
            preset != null -> "From ${preset.name} mode"
            options.reasoningEffort != null || thread != null -> "This chat"
            settingsCwd() == null -> "After workspace creation"
            else -> inherited?.effortSource ?: "From server"
        },
        preset?.name ?: threadMode?.replaceFirstChar { it.uppercase() }
            ?: if (thread == null) "Default" else "Mode unavailable",
    )
}
