package qhybrid.android.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WP14 sub-part 5 — headless tests for the pure periodic-sync decision logic. No WorkManager,
 * no Android scheduler. Verifies the conservative "no scanning / no forced connect" rules.
 */
class SyncScheduleDeciderTest {

    @Test
    fun noAssociatedWatchSkips() {
        val d = SyncScheduleDecider.decide(
            SyncScheduleDecider.State(hasAssociatedWatch = false, linkUp = false),
        )
        assertFalse(d.shouldSync)
    }

    @Test
    fun associatedButLinkDownSkips() {
        // The periodic job must NEVER force a connect / scan — CDM owns reconnection.
        val d = SyncScheduleDecider.decide(
            SyncScheduleDecider.State(hasAssociatedWatch = true, linkUp = false),
        )
        assertFalse(d.shouldSync)
    }

    @Test
    fun associatedAndLinkUpSyncs() {
        val d = SyncScheduleDecider.decide(
            SyncScheduleDecider.State(hasAssociatedWatch = true, linkUp = true),
        )
        assertTrue(d.shouldSync)
    }

    @Test
    fun periodIsClampedToWorkManagerFloor() {
        assertEquals(15L, SyncScheduleDecider.normalizePeriodMinutes(1))
        assertEquals(15L, SyncScheduleDecider.normalizePeriodMinutes(15))
        assertEquals(360L, SyncScheduleDecider.normalizePeriodMinutes(360))
    }

    @Test
    fun defaultsAreSane() {
        assertEquals(15L, SyncScheduleDecider.MIN_PERIOD_MINUTES)
        assertTrue(SyncScheduleDecider.DEFAULT_PERIOD_MINUTES >= SyncScheduleDecider.MIN_PERIOD_MINUTES)
    }
}
