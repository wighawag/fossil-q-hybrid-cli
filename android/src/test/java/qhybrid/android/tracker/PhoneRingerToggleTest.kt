package qhybrid.android.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WP-TRACKER (§4) — unit tests for the [PhoneRinger.toggle] default: a repeated trigger (a second
 * LONG gesture / RING_PHONE press) must STOP the ring rather than be ignored. The actual audio is
 * on-device-only ([SystemPhoneRinger]); this pins the toggle decision with a tiny fake ringer.
 * Pure JVM — no Robolectric.
 */
class PhoneRingerToggleTest {

    /** A fake [PhoneRinger] that just tracks ringing state + counts start/stop calls. */
    private class FakeRinger : PhoneRinger {
        var starts = 0
        var stops = 0
        private var on = false
        override fun start() { on = true; starts++ }
        override fun stop() { on = false; stops++ }
        override fun isRinging(): Boolean = on
    }

    @Test
    fun `toggle starts when idle then stops when ringing`() {
        val r = FakeRinger()
        assertFalse(r.isRinging())

        // First trigger: starts ringing, returns true (now ringing).
        assertTrue(r.toggle())
        assertTrue(r.isRinging())
        assertEquals(1, r.starts)
        assertEquals(0, r.stops)

        // Second trigger (same gesture again): stops, returns false (now silent).
        assertFalse(r.toggle())
        assertFalse(r.isRinging())
        assertEquals(1, r.starts)
        assertEquals(1, r.stops)

        // Third trigger: starts again.
        assertTrue(r.toggle())
        assertTrue(r.isRinging())
        assertEquals(2, r.starts)
        assertEquals(1, r.stops)
    }

    @Test
    fun `noop ringer never reports ringing and toggling it stays silent`() {
        // NoopPhoneRinger's start() is a no-op, so it never becomes "ringing"; toggle keeps trying
        // to start (returns true) but isRinging stays false — the safe default for tests.
        assertFalse(NoopPhoneRinger.isRinging())
        assertTrue(NoopPhoneRinger.toggle())
        assertFalse(NoopPhoneRinger.isRinging())
    }
}
