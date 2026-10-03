package dev.codexops.core

import kotlinx.serialization.json.JsonArray
import org.junit.Assert.*
import org.junit.Test

class TimelineStatusesTest {
    @Test fun tracksTurnOutcomesAcrossHistoryLiveUpdatesAndClear() {
        val timeline = Timeline()
        fun turn(status: String) = obj("id" to s("t"), "status" to s(status), "items" to JsonArray(emptyList()))
        timeline.snapshot(listOf(turn("inProgress")))
        assertEquals("inProgress", timeline.turnStatuses["t"])
        timeline.event("turn/completed", obj("turn" to turn("interrupted")))
        assertNull(timeline.activeTurn)
        timeline.snapshot(listOf(turn("inProgress")), prepend = true)
        assertEquals("interrupted", timeline.turnStatuses["t"])
        assertNull(timeline.activeTurn)
        timeline.hydrate(listOf(turn("inProgress")), listOf(
            obj("method" to s("turn/completed"), "params" to obj("turn" to turn("failed")))))
        assertEquals("failed", timeline.turnStatuses["t"])
        timeline.clear()
        assertTrue(timeline.turnStatuses.isEmpty())
    }
}
