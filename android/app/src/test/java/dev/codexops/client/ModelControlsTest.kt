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
}
