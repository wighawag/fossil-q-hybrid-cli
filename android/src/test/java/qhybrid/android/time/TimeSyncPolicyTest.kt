package qhybrid.android.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.util.TimeZone

/** WP-TZSYNC: when the watch's time/offset must be re-pushed while the link stays up. */
class TimeSyncPolicyTest {

    @Test
    fun neverPushed_pushes() {
        assertTrue(TimeSyncPolicy.shouldPush(force = false, lastPushedOffsetMinutes = null, currentOffsetMinutes = 60))
    }

    @Test
    fun unchangedOffset_doesNotPush() {
        assertFalse(TimeSyncPolicy.shouldPush(force = false, lastPushedOffsetMinutes = 60, currentOffsetMinutes = 60))
    }

    @Test
    fun dstFlip_pushes() {
        assertTrue(TimeSyncPolicy.shouldPush(force = false, lastPushedOffsetMinutes = 120, currentOffsetMinutes = 60))
    }

    @Test
    fun force_pushesEvenWhenUnchanged() {
        assertTrue(TimeSyncPolicy.shouldPush(force = true, lastPushedOffsetMinutes = 60, currentOffsetMinutes = 60))
    }

    @Test
    fun timeSet_debounced() {
        assertTrue(TimeSyncPolicy.timeSetForces(null))
        assertFalse(TimeSyncPolicy.timeSetForces(1_000L))
        assertTrue(TimeSyncPolicy.timeSetForces(TimeSyncPolicy.TIME_SET_DEBOUNCE_MS))
    }

    @Test
    fun nextOffsetChange_isNextDstTransitionPlusGrace() {
        // From 2026-01-15T12:00Z in Paris the next transition is DST start 2026-03-29T01:00Z.
        val next = TimeSyncPolicy.nextOffsetChangeMillis(ZoneId.of("Europe/Paris"), 1_768_478_400_000L)
        assertEquals(1_774_746_000_000L + TimeSyncPolicy.TRANSITION_GRACE_MS, next)
        // From 2026-07-15T12:00Z: DST end 2026-10-25T01:00Z.
        val next2 = TimeSyncPolicy.nextOffsetChangeMillis(ZoneId.of("Europe/Paris"), 1_784_116_800_000L)
        assertEquals(1_792_890_000_000L + TimeSyncPolicy.TRANSITION_GRACE_MS, next2)
    }

    @Test
    fun nextOffsetChange_nullForFixedZone() {
        assertNull(TimeSyncPolicy.nextOffsetChangeMillis(ZoneId.of("UTC"), 1_768_478_400_000L))
    }

    @Test
    fun currentOffset_followsDefaultZoneIncludingDst() {
        val saved = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Paris"))
            // 2026-01-15T12:00Z (CET, +60) and 2026-07-15T12:00Z (CEST, +120).
            assertEquals(60, TimeSyncPolicy.currentOffsetMinutes(1_768_478_400_000L))
            assertEquals(120, TimeSyncPolicy.currentOffsetMinutes(1_784_116_800_000L))
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"))
            assertEquals(330, TimeSyncPolicy.currentOffsetMinutes(1_768_478_400_000L))
        } finally {
            TimeZone.setDefault(saved)
        }
    }
}
