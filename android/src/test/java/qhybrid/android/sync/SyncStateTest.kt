package qhybrid.android.sync

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * WP-PROGRESS (sub-part 1) — headless tests for the process-wide in-memory holder [SyncState]
 * (the decision: in-memory, mirroring WP3 `WatchState` / WP-ACTIVITY `ActivityState`; NO Room
 * schema). Verifies the publish/observe contract the WP3 service writes (SYNCING → SUCCESS/ERROR)
 * and the Save buttons read.
 */
class SyncStateTest {

    @Before fun resetBefore() = SyncState.reset()
    @After fun resetAfter() = SyncState.reset()

    private fun result(
        mac: String? = "AA:00:00:00:00:01",
        performed: List<SyncSection> = listOf(SyncSection.BUTTONS),
        skipped: List<SyncSection> = emptyList(),
        errors: List<SyncError> = emptyList(),
    ) = SyncResult(mac, performed, skipped, errors)

    @Test
    fun pristineState_isIdleNeverSynced() {
        val s = SyncState.status.value
        assertEquals(SyncState.SyncPhase.IDLE, s.phase)
        assertFalse(s.isSyncing)
        assertNull(s.lastResult)
        assertNull(s.errorMessage)
        assertEquals(0L, s.lastUpdatedMillis)
        assertFalse(s.hadSectionErrors)
    }

    @Test
    fun publishSyncing_flipsPhaseAndStamps() {
        SyncState.publish(SyncState.SyncPhase.SYNCING, nowMillis = 100L)
        val s = SyncState.status.value
        assertEquals(SyncState.SyncPhase.SYNCING, s.phase)
        assertTrue(s.isSyncing)
        assertEquals(100L, s.lastUpdatedMillis)
        // No result yet for the first sync.
        assertNull(s.lastResult)
    }

    @Test
    fun syncingThenSuccess_carriesResult() {
        SyncState.publish(SyncState.SyncPhase.SYNCING, nowMillis = 1L)
        val r = result(performed = listOf(SyncSection.ALARMS, SyncSection.BUTTONS))
        SyncState.publish(SyncState.SyncPhase.SUCCESS, result = r, nowMillis = 2L)

        val s = SyncState.status.value
        assertEquals(SyncState.SyncPhase.SUCCESS, s.phase)
        assertFalse(s.isSyncing)
        assertSame(r, s.lastResult)
        assertEquals(2L, s.lastUpdatedMillis)
        assertNull(s.errorMessage)
        assertFalse(s.hadSectionErrors)
    }

    @Test
    fun success_withSectionErrors_isFlaggedHonestly() {
        // A pass that ran to completion but had a per-section failure: SUCCESS phase, but
        // hadSectionErrors true so the UI can avoid a blanket "Saved to watch".
        val r = result(
            performed = listOf(SyncSection.BUTTONS),
            errors = listOf(SyncError(SyncSection.ALARMS, "too many alarms")),
        )
        SyncState.publish(SyncState.SyncPhase.SUCCESS, result = r, nowMillis = 5L)
        val s = SyncState.status.value
        assertEquals(SyncState.SyncPhase.SUCCESS, s.phase)
        assertTrue(s.hadSectionErrors)
        assertEquals(1, s.lastResult?.errors?.size)
    }

    @Test
    fun error_carriesMessageNoResult() {
        SyncState.publish(SyncState.SyncPhase.SYNCING, nowMillis = 1L)
        SyncState.publish(SyncState.SyncPhase.ERROR, errorMessage = "link lost", nowMillis = 9L)
        val s = SyncState.status.value
        assertEquals(SyncState.SyncPhase.ERROR, s.phase)
        assertFalse(s.isSyncing)
        assertEquals("link lost", s.errorMessage)
        assertEquals(9L, s.lastUpdatedMillis)
    }

    @Test
    fun syncing_keepsPreviousResultForDisplay() {
        // After one good sync, starting another keeps the last summary visible while SYNCING.
        val r = result(performed = listOf(SyncSection.BUTTONS))
        SyncState.publish(SyncState.SyncPhase.SUCCESS, result = r, nowMillis = 1L)
        SyncState.publish(SyncState.SyncPhase.SYNCING, nowMillis = 2L)
        val s = SyncState.status.value
        assertTrue(s.isSyncing)
        assertSame(r, s.lastResult) // previous result retained
    }

    @Test
    fun error_clearsErrorMessageOnNextNonErrorPhase() {
        SyncState.publish(SyncState.SyncPhase.ERROR, errorMessage = "boom", nowMillis = 1L)
        assertEquals("boom", SyncState.status.value.errorMessage)
        SyncState.publish(SyncState.SyncPhase.SYNCING, nowMillis = 2L)
        assertNull(SyncState.status.value.errorMessage)
    }

    @Test
    fun reset_returnsToPristine() {
        SyncState.publish(SyncState.SyncPhase.SUCCESS, result = result(), nowMillis = 5L)
        SyncState.reset()
        val s = SyncState.status.value
        assertEquals(SyncState.SyncPhase.IDLE, s.phase)
        assertNull(s.lastResult)
        assertEquals(0L, s.lastUpdatedMillis)
    }
}
