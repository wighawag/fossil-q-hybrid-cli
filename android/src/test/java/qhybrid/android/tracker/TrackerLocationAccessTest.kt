package qhybrid.android.tracker

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * WP-TRACKER — unit tests for the PURE grant logic of [TrackerLocationAccess] (the foreground /
 * background result checks + the permission arrays). The Context-based checks + the runtime request
 * flow are on-device / instrumented; this pins the boolean logic. `@Config(sdk=33)` so the API 31+
 * branch (FINE + COARSE) is exercised.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TrackerLocationAccessTest {

    @Test
    fun `foreground permissions include fine and coarse on api 31+`() {
        val perms = TrackerLocationAccess.foregroundPermissions().toList()
        assertTrue(perms.contains(Manifest.permission.ACCESS_FINE_LOCATION))
        assertTrue(perms.contains(Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    @Test
    fun `isForegroundGranted true only when fine granted`() {
        assertTrue(
            TrackerLocationAccess.isForegroundGranted(
                mapOf(
                    Manifest.permission.ACCESS_FINE_LOCATION to true,
                    Manifest.permission.ACCESS_COARSE_LOCATION to true,
                )
            )
        )
        // Coarse-only (the user picked "Approximate") is NOT enough for the fine GPS fix.
        assertFalse(
            TrackerLocationAccess.isForegroundGranted(
                mapOf(
                    Manifest.permission.ACCESS_FINE_LOCATION to false,
                    Manifest.permission.ACCESS_COARSE_LOCATION to true,
                )
            )
        )
        assertFalse(TrackerLocationAccess.isForegroundGranted(emptyMap()))
    }

    @Test
    fun `isBackgroundGranted passes the boolean through`() {
        assertTrue(TrackerLocationAccess.isBackgroundGranted(true))
        assertFalse(TrackerLocationAccess.isBackgroundGranted(false))
    }

    @Test
    fun `background permission constant is access background location`() {
        assertEquals(
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            TrackerLocationAccess.BACKGROUND_PERMISSION,
        )
    }
}
