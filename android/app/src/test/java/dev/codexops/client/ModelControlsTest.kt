package dev.codexops.client

import dev.codexops.core.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ModelControlsTest {
    private val catalogResult =
        obj(
            "data" to
                JsonArray(
                    listOf(
                        obj(
                            "model" to s("gpt-fixture"),
                            "displayName" to s("Fixture"),
                            "description" to s("Server description"),
                            "serviceTiers" to JsonArray(listOf(obj("id" to s("priority"), "name" to s("Fast")))),
                            "defaultReasoningEffort" to s("low"),
                            "supportedReasoningEfforts" to
                                JsonArray(
                                    listOf(
                                        obj(
                                            "reasoningEffort" to s("low"),
                                            "description" to s("Quick"),
                                        ),
                                        obj(
                                            "reasoningEffort" to s("high"),
                                            "description" to s("Thorough"),
                                        ),
                                    )
                                ),
                            "isDefault" to JsonPrimitive(true),
                        )
                    )
                )
        )

    @Test
    fun catalogFieldsAndSupportedEffortsComeOnlyFromServerPayload() {
        val model = parseModelCatalog(catalogResult).single()

        assertEquals("gpt-fixture", model.id)
        assertEquals("Fixture", model.displayName)
        assertEquals("Server description", model.description)
        assertEquals("low", model.defaultReasoningEffort)
        assertEquals(listOf("low", "high"), model.supportedReasoningEfforts.map { it.id })
        assertTrue(model.isDefault)
        assertEquals(listOf("priority"), model.serviceTiers)
    }

    @Test
    fun unsupportedSelectionsAreRemovedOnCatalogRefresh() {
        val options = NewTaskOptions(model = "gone", reasoningEffort = "invented")
        val reconciled = reconcileModelOptions(options, parseModelCatalog(catalogResult), null)

        assertTrue(reconciled.removedUnsupportedChoice)
        assertNull(reconciled.options.model)
        assertNull(reconciled.options.reasoningEffort)
    }

    @Test
    fun unsupportedEffortIsClearedWithoutDiscardingSupportedModel() {
        val options = NewTaskOptions(model = "gpt-fixture", reasoningEffort = "invented")
        val reconciled = reconcileModelOptions(options, parseModelCatalog(catalogResult), null)

        assertTrue(reconciled.removedUnsupportedChoice)
        assertEquals("gpt-fixture", reconciled.options.model)
        assertNull(reconciled.options.reasoningEffort)
    }

    @Test
    fun omittedOverridesStayAbsentAndExplicitOverridesUseStockFieldNames() {
        val input = JsonArray(listOf(obj("type" to s("text"), "text" to s("hello"))))
        val defaults = NewTaskOptions()
        assertFalse(newThreadParams("/fixture", defaults).containsKey("model"))
        val defaultTurn = turnStartParams("thread", input, "operation", defaults)
        assertFalse(defaultTurn.containsKey("model"))
        assertFalse(defaultTurn.containsKey("effort"))

        val overrides = NewTaskOptions(model = "gpt-fixture", reasoningEffort = "high")
        assertEquals("gpt-fixture", newThreadParams("/fixture", overrides).str("model"))
        val turn = turnStartParams("thread", input, "operation", overrides)
        assertEquals("gpt-fixture", turn.str("model"))
        assertEquals("high", turn.str("effort"))

        val steer = turnSteerParams("thread", input, "operation", "active")
        assertFalse(steer.containsKey("model"))
        assertFalse(steer.containsKey("effort"))
    }

    private fun config(cwd: String = "/project", model: String = "gpt-fixture", effort: String? = "high", project: Boolean = true) =
        parseInheritedSettings(obj("config" to obj("model" to s(model), "model_reasoning_effort" to effort?.let(::s)),
            "origins" to obj("model" to obj("name" to obj("type" to s(if (project) "project" else "user"))),
                "model_reasoning_effort" to obj("name" to obj("type" to s("user"))))), cwd)

    private fun resolvedState() = ScreenState(ready = true, models = parseModelCatalog(catalogResult),
        newTaskOptions = NewTaskOptions(projectId = "project", workingDirectory = "/project", executionTarget = ExecutionTarget.CurrentWorkspace),
        inheritedSettings = config(), modelCatalogStatus = ModelCatalogStatus.Ready)

    @Test fun projectConfigOverridesCatalogDefaultWithoutWritingOverrides() {
        val state = resolvedState().copy(inheritedSettings = config(model = "project-model"))
        assertEquals("project-model", state.composerSettings().model)
        assertEquals("From project", state.composerSettings().modelSource)
        assertEquals("High", state.composerSettings().effort)
        assertEquals("From server", state.composerSettings().effortSource)
        assertNull(state.newTaskOptions.model)
        assertNull(state.newTaskOptions.reasoningEffort)
        assertFalse(turnStartParams("thread", JsonArray(emptyList()), "op", state.newTaskOptions).containsKey("model"))
    }

    @Test fun staleProjectConfigAndMissingValuesNeverFallBackToCatalogModel() {
        val state = resolvedState()
        assertEquals("Loading…", state.copy(inheritedSettings = config(cwd = "/old")).composerSettings().model)
        assertEquals("Unavailable", state.copy(inheritedSettings = config(model = "")).composerSettings().model)
        assertEquals("Offline", state.copy(ready = false, inheritedSettings = InheritedSettings()).composerSettings().model)
        assertNull(effectiveModel(NewTaskOptions(), state.models, null))
    }

    @Test fun overridesThreadAndModeUseTheSamePrecedenceAsStockTurnRequests() {
        val state = resolvedState().copy(thread = "thread", threadCwd = "/project", threadModel = "thread-model",
            threadReasoningEffort = "low", threadMode = "default")
        assertEquals("thread-model", state.composerSettings().model)
        assertEquals("Low", state.composerSettings().effort)
        val override = state.copy(newTaskOptions = state.newTaskOptions.copy(model = "gpt-fixture", reasoningEffort = "high"))
        assertEquals("Fixture", override.composerSettings().model)
        assertEquals("High", override.composerSettings().effort)
        val mode = override.copy(collaborationModes = listOf(CollaborationModePreset("plan", "Plan", "mode-model", "medium")),
            newTaskOptions = override.newTaskOptions.copy(collaborationMode = "plan"))
        assertEquals("mode-model", mode.composerSettings().model)
        assertEquals("Medium", mode.composerSettings().effort)
        assertEquals("From Plan mode", mode.composerSettings().effortSource)
        assertEquals("thread-model", override.copy(activeTurn = "active").composerSettings().model)
        assertEquals("Low", override.copy(activeTurn = "active").composerSettings().effort)
    }

    @Test fun unknownInheritedModelDoesNotDiscardAnEffortUntilItCanBeValidated() {
        val options = NewTaskOptions(reasoningEffort = "high")
        assertEquals(options, reconcileModelOptions(options, parseModelCatalog(catalogResult), null).options)
        assertNull(reconcileModelOptions(options.copy(reasoningEffort = "medium"), parseModelCatalog(catalogResult), "gpt-fixture").options.reasoningEffort)
    }

    @Test fun existingThreadNeverBorrowsTheCurrentProjectModelWhenItsModelIsUnknown() {
        val state = resolvedState().copy(thread = "thread", threadCwd = "/project")
        assertEquals("Unavailable", state.composerSettings().model)
        assertNull(state.collaborationModel())
    }

    @Test fun worktreeSettingsStayUnresolvedUntilTheActualDestinationExists() {
        val state = resolvedState().copy(newTaskOptions = resolvedState().newTaskOptions.copy(executionTarget = ExecutionTarget.NewWorktree))
        assertNull(state.settingsCwd())
        assertEquals("Not resolved", state.composerSettings().model)
        assertEquals("After workspace creation", state.composerSettings().modelSource)
    }

    @Test fun pendingModeDraftShowsModeSettingsRatherThanTheRunningTurnsSettings() {
        val state = resolvedState().copy(thread = "thread", threadCwd = "/project", threadModel = "gpt-fixture",
            threadReasoningEffort = "low", activeTurn = "active",
            collaborationModes = listOf(CollaborationModePreset("plan", "Plan", reasoningEffort = "high")),
            newTaskOptions = resolvedState().newTaskOptions.copy(collaborationMode = "plan"))
        assertTrue(state.waitingToSendMode())
        assertEquals("High", state.composerSettings().effort)
        assertEquals("Plan", state.composerSettings().mode)
    }
    @Test fun speedDistinguishesInheritanceStandardFastAndUnknown() {
        val inherited = parseInheritedSettings(obj("config" to obj("service_tier" to s("priority"))), "/fixture")
        assertEquals("priority", inherited.serviceTier)
        assertFalse(newThreadParams("/fixture", NewTaskOptions()).containsKey("serviceTier"))
        assertEquals("default", newThreadParams("/fixture", NewTaskOptions(serviceTier = "default")).str("serviceTier"))
        assertEquals("priority", newThreadParams("/fixture", NewTaskOptions(serviceTier = "priority")).str("serviceTier"))
        assertEquals(JsonNull, speedUpdateParams("thread", false)["serviceTier"])
        assertEquals("priority", speedUpdateParams("thread", true).str("serviceTier"))
        assertEquals("Standard", ComposerSpeed(null, true).label)
        assertEquals("Unavailable", ComposerSpeed(null, false).label)
        assertTrue(ComposerSpeed("priority", true).fast)
        assertTrue(ComposerSpeed("fast", true).fast)
        assertEquals("Other (flex)", ComposerSpeed("flex", true).label)
        assertFalse(ComposerSpeed("priority", false).fast)
    }

    @Test fun speedUsesChatAuthorityAndOnlyAdvertisedFastCapability() {
        val catalog = parseModelCatalog(catalogResult).map { it.copy(serviceTiers = listOf("priority")) }
        val state = resolvedState().copy(models = catalog, fastModeAllowed = true,
            inheritedSettings = config().copy(serviceTier = "priority"))
        assertTrue(state.composerSpeed().fast)
        assertTrue(state.canSelectFast())
        assertFalse(state.copy(fastModeAllowed = false).canSelectFast())
        assertFalse(state.copy(models = catalog.map { it.copy(serviceTiers = emptyList()) }).canSelectFast())
        assertEquals("Standard", state.copy(newTaskOptions = state.newTaskOptions.copy(serviceTier = "default")).composerSpeed().label)
        val thread = state.copy(thread = "thread", threadServiceTierKnown = true)
        assertEquals("Standard", thread.composerSpeed().label)
        assertFalse(thread.copy(threadServiceTier = "priority", speedUncertain = true).composerSpeed().fast)
        assertTrue(thread.copy(threadServiceTier = "priority", activeTurn = "working").composerSpeed().fast)
    }

}
