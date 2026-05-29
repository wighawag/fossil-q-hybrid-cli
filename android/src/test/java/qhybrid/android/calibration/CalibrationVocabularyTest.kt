package qhybrid.android.calibration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WP16e — constants sanity + degree-normalization round-trip & out-of-range/negative tolerance.
 * Pure JVM (no Android deps), so no Robolectric needed here.
 */
class CalibrationVocabularyTest {

    // ---- CalibrationHands ----------------------------------------------------

    @Test
    fun handConstantsAndLabels() {
        assertEquals("HOUR", CalibrationHands.HOUR)
        assertEquals("MINUTE", CalibrationHands.MINUTE)
        assertEquals("SUB", CalibrationHands.SUB)
        assertEquals(listOf("HOUR", "MINUTE", "SUB"), CalibrationHands.ALL)
        assertEquals(CalibrationHands.HOUR, CalibrationHands.DEFAULT)
        assertTrue(CalibrationHands.isKnown(CalibrationHands.MINUTE))
        assertFalse(CalibrationHands.isKnown("BOGUS"))
        // Unknown id renders raw (graceful fallback for the label).
        assertEquals("BOGUS", CalibrationHands.label("BOGUS"))
        assertEquals("Sub-eye", CalibrationHands.label(CalibrationHands.SUB))
    }

    @Test
    fun normalizeHandDefaultsBlankAndUnknownToDefault() {
        assertEquals(CalibrationHands.DEFAULT, CalibrationHands.normalize(null))
        assertEquals(CalibrationHands.DEFAULT, CalibrationHands.normalize("   "))
        assertEquals(CalibrationHands.DEFAULT, CalibrationHands.normalize("BOGUS"))
        assertEquals(CalibrationHands.MINUTE, CalibrationHands.normalize("  MINUTE "))
    }

    // ---- HandDegrees: constants ----------------------------------------------

    @Test
    fun degreeConstantsSanity() {
        assertEquals(360, HandDegrees.FULL)
        assertEquals(6, HandDegrees.COARSE)
        assertEquals(1, HandDegrees.FINE)
        assertEquals(0, HandDegrees.NEUTRAL)
    }

    // ---- HandDegrees: normalize (wrap, negative, out-of-range) ----------------

    @Test
    fun normalizeWrapsOntoRing() {
        assertEquals(0, HandDegrees.normalize(0))
        assertEquals(359, HandDegrees.normalize(359))
        // 360 wraps to 0.
        assertEquals(0, HandDegrees.normalize(360))
        // Past 359.
        assertEquals(5, HandDegrees.normalize(365))
        assertEquals(0, HandDegrees.normalize(720))
        // Negative wraps from the top.
        assertEquals(359, HandDegrees.normalize(-1))
        assertEquals(350, HandDegrees.normalize(-10))
        // Far out-of-range, both directions, never throws.
        assertEquals(350, HandDegrees.normalize(-370))
        assertEquals(5, HandDegrees.normalize(725))
    }

    @Test
    fun normalizeRoundTripIsIdempotent() {
        for (d in -800..800) {
            val n = HandDegrees.normalize(d)
            assertTrue("normalized $d -> $n out of range", n in 0..359)
            assertEquals("re-normalize not idempotent for $d", n, HandDegrees.normalize(n))
        }
    }

    // ---- HandDegrees: nudge (apply delta + wrap) ------------------------------

    @Test
    fun nudgeAppliesDeltaAndWraps() {
        assertEquals(6, HandDegrees.nudge(0, 6))
        assertEquals(0, HandDegrees.nudge(354, 6))   // wrap past 359
        assertEquals(354, HandDegrees.nudge(0, -6))  // wrap past 0
        assertEquals(359, HandDegrees.nudge(0, -1))  // fine step past 0
        assertEquals(0, HandDegrees.nudge(359, 1))   // fine step past 359
        // Large delta still wraps cleanly.
        assertEquals(0, HandDegrees.nudge(0, 360))
        assertEquals(6, HandDegrees.nudge(0, 366))
    }
}
