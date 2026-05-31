package qhybrid.android.tracker

import android.location.Location
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * WP-TRACKER — unit tests for the PURE part of [SystemLocationSource]: the
 * [SystemLocationSource.toFix] `Location` → [LocationSource.Fix] mapping. The live `LocationManager`
 * call itself is on-device-verified only (it can't be meaningfully exercised on the JVM); this
 * pins the mapping (lat/lon/timestamp pass-through + the accuracy null-when-absent rule).
 * Robolectric only because `android.location.Location` is an Android framework type.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SystemLocationSourceTest {

    @Test
    fun `toFix maps lat lon timestamp and accuracy`() {
        val loc = Location("gps").apply {
            latitude = 48.8566
            longitude = 2.3522
            accuracy = 12.5f
            time = 1_700_000_000_000L
        }
        val fix = SystemLocationSource.toFix(loc)
        assertEquals(48.8566, fix.lat, 0.0)
        assertEquals(2.3522, fix.lon, 0.0)
        assertEquals(12.5f, fix.accuracyM)
        assertEquals(1_700_000_000_000L, fix.timestamp)
    }

    @Test
    fun `toFix reports null accuracy when location has none`() {
        val loc = Location("network").apply {
            latitude = 1.0
            longitude = 2.0
            time = 42L
            // no setAccuracy() → hasAccuracy() is false
        }
        val fix = SystemLocationSource.toFix(loc)
        assertNull(fix.accuracyM)
    }
}
