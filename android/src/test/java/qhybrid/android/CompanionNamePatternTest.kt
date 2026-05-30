package qhybrid.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.regex.Pattern

/**
 * Pure unit test for [CompanionManager.FOSSIL_NAME_PATTERN] — the advertised-name regex the CDM
 * chooser uses to list ONLY Fossil watches when adding by scan (no MAC).
 *
 * Fossil Q hybrids advertise as `Fossil` / `FossilQ Hybrid` (FINDINGS #6); the pattern must match
 * those (case-insensitively) and must NOT match unrelated BLE devices, so the user isn't shown a
 * chooser full of headphones / trackers / phones.
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
        // A name that merely CONTAINS 'fossil' mid-string is not an advertised Fossil watch name.
        assertFalse(matches("MyFossilReplica"))
    }
}
