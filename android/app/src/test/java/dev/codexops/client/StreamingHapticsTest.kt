package dev.codexops.client

import org.junit.Assert.*
import org.junit.Test

class StreamingHapticsTest {
    private var time = 1_000L
    private val stream = StreamingHaptics { time }
    private var revision = 0L

    private fun update(turn: String = "turn", item: String = "answer", eligible: Boolean = true) =
        LiveAssistantText("chat", turn, item, ++revision).also { stream.live(it, eligible) }

    @Test fun fadeStartsAtLayoutAndEndsAfterEightSeconds() {
        val first = update()
        time += 5_000 // Thinking/parsing before the first layout must not spend the fade.
        assertEquals(0.30f, stream.rendered(first, true)!!, 0.00001f)
        time += 4_000
        assertEquals(0.15f, stream.rendered(update(), true)!!, 0.00001f)
        time += 3_999
        val last = stream.rendered(update(), true)!!
        assertTrue(last > 0f && last < 0.001f)
        time += 1
        assertNull(stream.rendered(update(), true))
        time += 20_000
        assertNull(stream.rendered(update(), true))
    }

    @Test fun latestLayoutCoalescesUpdatesAndNeverReplaysSuppressedTicks() {
        val obsolete = update()
        val current = update()
        assertNull(stream.rendered(obsolete, true))
        assertEquals(0.30f, stream.rendered(current, true)!!, 0.00001f)
        assertNull(stream.rendered(current, true)) // Recomposition/layout of the same text.
        time += 59
        val suppressed = update()
        assertNull(stream.rendered(suppressed, true))
        time += 1
        assertNull(stream.rendered(suppressed, true)) // No delayed catch-up.
        assertNotNull(stream.rendered(update(), true))
    }

    @Test fun pausesAndAdditionalMessagesDoNotResetTheTurn() {
        assertEquals(0.30f, stream.rendered(update(), true)!!, 0.00001f)
        time += 2_000
        assertEquals(0.225f, stream.rendered(update(item = "commentary"), true)!!, 0.00001f)
        time += 4_000
        assertEquals(0.075f, stream.rendered(update(item = "final"), true)!!, 0.00001f)
        time += 2_000
        assertNull(stream.rendered(update(item = "final"), true))
        assertEquals(0.30f, stream.rendered(update(turn = "next"), true)!!, 0.00001f)
    }

    @Test fun scrollingAwayConsumesUpdatesWithoutRestartingTheFade() {
        assertNull(stream.rendered(update(), false))
        time += 4_000
        assertEquals(0.15f, stream.rendered(update(), true)!!, 0.00001f)
    }

    @Test fun cancellationAndHistoryBaselineStaySilentUntilANewTurn() {
        val beforePause = update()
        stream.cancel()
        assertNull(stream.rendered(beforePause, true))
        assertNull(stream.rendered(update(), true))
        stream.begin("chat", "turn", true) // Duplicate turn/started cannot re-arm it.
        assertNull(stream.rendered(update(), true))
        assertNotNull(stream.rendered(update(turn = "next"), true))
        stream.baseline("chat", "restored")
        assertNull(stream.rendered(update(turn = "restored"), true))
        assertNotNull(stream.rendered(update(turn = "fresh"), true))
    }

    @Test fun turnsStartedOffscreenOrWithFeedbackDisabledCannotCatchUp() {
        stream.begin("chat", "hidden", false)
        assertNull(stream.rendered(update(turn = "hidden", eligible = false), true))
        assertNull(stream.rendered(update(turn = "hidden"), true))
        assertNotNull(stream.rendered(update(turn = "fresh"), true))
    }
}
