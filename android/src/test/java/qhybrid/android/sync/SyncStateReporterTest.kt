package qhybrid.android.sync

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * WP-PROGRESS (sub-part 2) — headless tests for the pure [SyncStateReporter], the SYNCING →
 * SUCCESS/ERROR choreography the WP3 service delegates to. Proves the SyncState lifecycle WITHOUT
 * the Android service or any BLE (the live BLE effect remains on-device-pending).
 *
 * A `phases` recorder observes [SyncState.status] transitions to assert that SYNCING is published
 * BEFORE the sync block runs and the terminal phase AFTER.
 */
class SyncStateReporterTest {

    @Before fun resetBefore() = SyncState.reset()
    @After fun resetAfter() = SyncState.reset()

    private val clock = { 42L }

    private fun result(errors: List<SyncError> = emptyList()) =
        SyncResult("AA:00:00:00:00:01", listOf(SyncSection.BUTTONS), emptyList(), errors)

    @Test
    fun publishesSyncingBeforePassAndSuccessAfter() {
        var phaseDuringPass: SyncState.SyncPhase? = null
        val r = result()
        val returned = SyncStateReporter.reportAround(clock) {
            // The holder must already be SYNCING while the pass runs.
            phaseDuringPass = SyncState.status.value.phase
            r
        }
        assertEquals(SyncState.SyncPhase.SYNCING, phaseDuringPass)
        assertSame(r, returned)
        val s = SyncState.status.value
        assertEquals(SyncState.SyncPhase.SUCCESS, s.phase)
        assertSame(r, s.lastResult)
        assertEquals(42L, s.lastUpdatedMillis)
    }

    @Test
    fun passThatThrows_publishesErrorAndReturnsNull() {
        val returned = SyncStateReporter.reportAround(clock) {
            throw IllegalStateException("link lost mid-write")
        }
        assertNull(returned) // exception swallowed; caller logs
        val s = SyncState.status.value
        assertEquals(SyncState.SyncPhase.ERROR, s.phase)
        assertEquals("link lost mid-write", s.errorMessage)
        assertEquals(42L, s.lastUpdatedMillis)
    }

    @Test
    fun successCarriesSectionErrorsHonestly() {
        // A completed pass with a per-section failure is still SUCCESS, but hadSectionErrors true.
        val r = result(errors = listOf(SyncError(SyncSection.ALARMS, "too many alarms")))
        SyncStateReporter.reportAround(clock) { r }
        val s = SyncState.status.value
        assertEquals(SyncState.SyncPhase.SUCCESS, s.phase)
        assertTrue(s.hadSectionErrors)
    }

    @Test
    fun errorPhaseDoesNotLeaveStaleSyncing() {
        // Regression: a thrown pass must not leave the UI stuck spinning on SYNCING.
        SyncStateReporter.reportAround(clock) { throw RuntimeException("boom") }
        assertEquals(SyncState.SyncPhase.ERROR, SyncState.status.value.phase)
    }
}
