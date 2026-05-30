package qhybrid.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.regex.Pattern

/**
 * Pure unit test for [CompanionManager.FOSSIL_NAME_PATTERN] — the advertised-name regex the CDM
 * chooser uses to list ONLY Fossil watches when adding by scan (no MAC).
 *
 * Fossil Q hybrids advertise as `Fossil` / `FossilQ Hybrid` (FINDINGS #6), and the advertised name
 * CHANGES after pairing — so the pattern matches `fossil` ANYWHERE in the name (case-insensitively)
 * to be forgiving, while still excluding clearly-unrelated BLE devices (headphones / trackers /
 * phones). A device that merely contains the substring is rare enough that the small false-positive
 * risk is worth not missing a real watch; the "Show all devices" fallback covers anything missed.
 */
class CompanionNamePatternTest {

    private val pattern = Pattern.compile(CompanionManager.FOSSIL_NAME_PATTERN)

    private fun matches(name: String) = pattern.matcher(name).matches()

    @Test
    fun matchesFossilAdvertisedNames() {
        assertTrue(matches("Fossil"))
        assertTrue(matches("FossilQ Hybrid"))
        assertTrue(matches("Fossil Q Commuter"))
        assertTrue(matches("Fossil Hybrid HR"))
    }

    @Test
    fun matchesCaseInsensitively() {
        assertTrue(matches("fossil"))
        assertTrue(matches("FOSSIL Q"))
        assertTrue(matches("fOsSiL hybrid"))
    }

    @Test
    fun doesNotMatchNonFossilDevices() {
        assertFalse(matches("Galaxy Buds"))
        assertFalse(matches("Mi Band 7"))
        assertFalse(matches("Pixel 8"))
        assertFalse(matches("Garmin"))
    }

    @Test
    fun matchesFossilAnywhereInName() {
        // The advertised name changes after pairing, so we match 'fossil' as a substring (forgiving).
        assertTrue(matches("My Fossil Watch"))
        assertTrue(matches("FossilQ-1234"))
    }
}
