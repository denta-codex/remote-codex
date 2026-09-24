package dev.codexops.client

import dev.codexops.core.*
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.*
import org.junit.Test

class CollaborationModeTest {
    @Test
    fun onlyCompleteStockPresetsBecomeTurnSettings() {
        val modes =
            CollaborationModePreset.parse(
                obj(
                    "data" to
                        JsonArray(
                            listOf(
                                obj(
                                    "name" to s("Plan"),
                                    "mode" to s("plan"),
                                    "model" to s("gpt-test"),
                                    "reasoning_effort" to s("high"),
                                ),
                                obj("name" to s("Default"), "mode" to s("default")),
                                obj(
                                    "name" to s("Unknown"),
                                    "mode" to s("future"),
                                    "model" to s("gpt-test"),
                                ),
                            )
                        )
                )
            )

        assertEquals(listOf("plan", "default"), modes.map { it.mode })
        val setting = modes.first().turnSetting("gpt-fallback")!!
        assertEquals("plan", setting.str("mode"))
        assertEquals("gpt-test", setting.map("settings").str("model"))
        assertEquals("high", setting.map("settings").str("reasoning_effort"))
        assertNull(modes.last().turnSetting(null))
        val defaultSetting = modes.last().turnSetting("gpt-fallback")!!
        assertEquals("gpt-fallback", defaultSetting.map("settings").str("model"))
    }
}
