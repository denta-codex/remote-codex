package dev.codexops.client

import dev.codexops.core.obj
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CredentialAlertsTest {
    private fun pending(vararg entries: Pair<String, Long>) = obj("requests" to JsonArray(entries.map { (id, deadline) ->
        obj("id" to JsonPrimitive(id), "state" to JsonPrimitive("pending"), "deadline" to JsonPrimitive(deadline))
    }))

    @Test fun duplicateHintsCoalesceAndDismissedRequestsDoNotReappear() = runTest {
        var notice: CredentialNotice? = null
        var reads = 0
        val gate = CompletableDeferred<Unit>()
        val alerts = CredentialAlertController(backgroundScope, { 1 }, { true }, {
            reads++; gate.await(); pending("first" to 60000)
        }, { notice = it }, { testScheduler.currentTime })
        alerts.refresh(); runCurrent()
        repeat(50) { alerts.refresh() }
        gate.complete(Unit); runCurrent()
        assertEquals(2, reads)
        assertEquals(listOf("first"), notice!!.requestIds)
        alerts.dismiss(notice!!.id)
        alerts.refresh(); runCurrent()
        assertNull(notice)
        alerts.stopped()
    }

    @Test fun expiryRetainsOtherLiveRequestsAndClearsTheLastOne() = runTest {
        var notice: CredentialNotice? = null
        val alerts = CredentialAlertController(backgroundScope, { 1 }, { true }, {
            pending("early" to 1000, "late" to 3000)
        }, { notice = it }, { testScheduler.currentTime })
        alerts.refresh(); runCurrent()
        assertEquals(listOf("early", "late"), notice!!.requestIds)
        advanceTimeBy(1000); runCurrent()
        assertEquals(listOf("late"), notice!!.requestIds)
        advanceTimeBy(2000); runCurrent()
        assertNull(notice)
        alerts.stopped()
    }

    @Test fun resultsFromOldConnectionAndStoppedDiscoveryStaySilent() = runTest {
        var notice: CredentialNotice? = null
        var epoch = 1L
        var gate = CompletableDeferred<Unit>()
        val alerts = CredentialAlertController(backgroundScope, { epoch }, { true }, {
            gate.await(); pending("first" to 60000)
        }, { notice = it }, { testScheduler.currentTime })
        alerts.refresh(); runCurrent()
        epoch++; gate.complete(Unit); runCurrent()
        assertNull(notice)
        gate = CompletableDeferred()
        alerts.refresh(); runCurrent(); alerts.stopped()
        gate.complete(Unit); runCurrent()
        assertNull(notice)
    }

    @Test fun reconnectCanDiscoverWhileAnOldReadIsWaiting() = runTest {
        var notice: CredentialNotice? = null
        var epoch = 1L
        val gate = CompletableDeferred<Unit>()
        val alerts = CredentialAlertController(backgroundScope, { epoch }, { true }, {
            if (epoch == 1L) gate.await()
            pending("fresh" to 60000)
        }, { notice = it }, { testScheduler.currentTime })
        alerts.refresh(); runCurrent()
        epoch++
        alerts.refresh(); runCurrent()
        assertEquals(listOf("fresh"), notice!!.requestIds)
        alerts.stopped()
    }

    @Test fun failureAndEmptyAuthoritativeListClearAlerts() = runTest {
        var notice: CredentialNotice? = null
        var fail = false
        var response = pending("first" to 60000)
        val alerts = CredentialAlertController(backgroundScope, { 1 }, { true }, {
            if (fail) error("fixture unavailable") else response
        }, { notice = it }, { testScheduler.currentTime })
        alerts.refresh(); runCurrent(); assertNotNull(notice)
        response = pending(); alerts.refresh(); runCurrent(); assertNull(notice)
        response = pending("second" to 60000); alerts.refresh(); runCurrent(); assertNotNull(notice)
        fail = true; alerts.refresh(); runCurrent(); assertNull(notice)
        alerts.stopped()
    }
}
