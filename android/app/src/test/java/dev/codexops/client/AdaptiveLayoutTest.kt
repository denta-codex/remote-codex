package dev.codexops.client

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveLayoutTest {
    @Test
    fun coverLayoutRequiresCompactWidthAndHeight() {
        assertTrue(classifyWindow(411.dp, 485.dp).coverScreen)
        assertFalse(classifyWindow(411.dp, 800.dp).coverScreen)
        assertFalse(classifyWindow(700.dp, 411.dp).coverScreen)
    }

    @Test
    fun androidWindowClassBreakpointsAreAppliedAtTheirBoundaries() {
        assertTrue(classifyWindow(599.dp, 479.dp).coverScreen)
        assertFalse(classifyWindow(600.dp, 479.dp).compactWidth)
        assertFalse(classifyWindow(599.dp, 480.dp).compactHeight)
    }
}
