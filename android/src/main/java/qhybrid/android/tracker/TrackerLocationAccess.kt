package qhybrid.android.tracker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * WP-TRACKER — helper for the **location** runtime permissions the GPS-waypoint tracker needs. The
 * grant is a two-step flow enforced by the OS on API 30+:
 *
 *   1. **Foreground location** ([foregroundPermissions]): `ACCESS_FINE_LOCATION` (+
 *      `ACCESS_COARSE_LOCATION` on API 31+ so the system shows the Precise/Approximate toggle).
 *      Requested via the normal `RequestMultiplePermissions` launcher.
 *   2. **Background location** ([BACKGROUND_PERMISSION]): `ACCESS_BACKGROUND_LOCATION`, a SEPARATE
 *      second request that can ONLY be asked AFTER foreground location is already granted. On API
 *      30+ it typically routes the user to the app's settings page to pick "Allow all the time".
 *      Needed because logging happens while the phone is pocketed under the foreground service.
 *
 * The pure grant logic ([isForegroundGranted]/[isBackgroundGranted] boolean overloads) is split out
 * so it stays unit-testable without a Context, mirroring [qhybrid.android.calendar.CalendarAccess].
 *
 * **Play-sensitive disclosure copy** ([BACKGROUND_DISCLOSURE]) lives here so the prominent in-app
 * disclosure that Google Play requires for `ACCESS_BACKGROUND_LOCATION` is in one place.
 */
object TrackerLocationAccess {

    /** Foreground location permission(s): FINE on all API levels; + COARSE on API 31+. */
    fun foregroundPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    /** The separate background-location permission (requested only AFTER foreground is granted). */
    const val BACKGROUND_PERMISSION: String = Manifest.permission.ACCESS_BACKGROUND_LOCATION

    /** True iff foreground location (FINE) is granted — the minimum to capture a fix. */
    fun isForegroundGranted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Pure check: are the [results] of a foreground-location request all granted? */
    fun isForegroundGranted(results: Map<String, Boolean>): Boolean =
        results[Manifest.permission.ACCESS_FINE_LOCATION] == true

    /** True iff background location is granted (always true below API 29, where it's implicit). */
    fun isBackgroundGranted(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(context, BACKGROUND_PERMISSION) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    /** Pure check: is the background-location request [granted]? */
    fun isBackgroundGranted(granted: Boolean): Boolean = granted

    /**
     * Prominent in-app disclosure for the background-location request (Google Play requirement).
     * Show this BEFORE the system background-location prompt.
     */
    const val BACKGROUND_DISCLOSURE: String =
        "Fossil Q logs a GPS waypoint when you press a watch button or use a tracker gesture. To " +
            "do this while the app runs in the background (your phone pocketed, under the " +
            "foreground service), it needs background location access (\"Allow all the time\"). " +
            "Location is stored only on your device and never shared."
}
