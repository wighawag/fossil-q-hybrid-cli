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
    fun `auto-stop is finite so a pocketed phone cannot ring forever`() {
        assertTrue("auto-stop must be positive", RingPolicy.AUTO_STOP_MS > 0)
        assertTrue("auto-stop should be bounded (<= 2min)", RingPolicy.AUTO_STOP_MS <= 120_000L)
    }

    @Test
    fun `vibration waveform loops`() {
        // repeat index 0 = loop the whole pattern (an un-looped one-shot would be -1).
        assertEquals(0, RingPolicy.VIBRATION_REPEAT)
        assertTrue("waveform must have entries", RingPolicy.VIBRATION_PATTERN.isNotEmpty())
    }
}
