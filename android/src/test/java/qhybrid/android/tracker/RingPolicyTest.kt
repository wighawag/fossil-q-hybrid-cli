package qhybrid.android.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WP-TRACKER (§4) — unit tests for the PURE ring policy ([RingPolicy]): ring at max alarm volume,
 * a finite auto-stop, and a looping vibration waveform. The actual MediaPlayer/Vibrator calls in
 * [SystemPhoneRinger] are on-device-verified only; this pins the decision (loud + bounded) so the
 * "ring forever" / "ring quietly" regressions are caught off-device. Pure JVM — no Robolectric.
 */
class RingPolicyTest {

    @Test
    fun `ring volume is the max available`() {
        assertEquals(7, RingPolicy.ringVolume(7))
        assertEquals(15, RingPolicy.ringVolume(15))
    }

    @Test
    fun `ring volume never goes negative`() {
        assertEquals(0, RingPolicy.ringVolume(-1))
        assertEquals(0, RingPolicy.ringVolume(0))
    }

    @Test
    fun `auto-stop converts the configured seconds to millis`() {
        // Default 1 minute.
        assertEquals(60_000L, RingPolicy.autoStopMillis(60))
        assertEquals(5_000L, RingPolicy.autoStopMillis(5))
        assertEquals(300_000L, RingPolicy.autoStopMillis(300))
    }

    @Test
    fun `auto-stop clamps out-of-range durations so a pocketed phone cannot ring forever`() {
        // Below min (5s) clamps up; above max (300s) clamps down — never 0 / never unbounded.
        assertEquals(5_000L, RingPolicy.autoStopMillis(0))
        assertEquals(5_000L, RingPolicy.autoStopMillis(-10))
        assertEquals(300_000L, RingPolicy.autoStopMillis(99_999))
        assertTrue(RingPolicy.autoStopMillis(0) > 0)
    }

    @Test
    fun `vibration waveform loops`() {
        // repeat index 0 = loop the whole pattern (an un-looped one-shot would be -1).
        assertEquals(0, RingPolicy.VIBRATION_REPEAT)
        assertTrue("waveform must have entries", RingPolicy.VIBRATION_PATTERN.isNotEmpty())
    }
}
