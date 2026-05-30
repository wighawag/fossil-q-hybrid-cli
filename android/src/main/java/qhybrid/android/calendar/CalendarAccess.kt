package qhybrid.android.calendar

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * WP13/WP10 — helper for the **calendar read** runtime permission (`READ_CALENDAR`). Unlike the
 * WP11 Notification-Access special permission ([qhybrid.android.notifications.NotificationAccess]),
 * this IS a normal runtime permission, so it is requested via the permissions API (the existing
 * `RequestMultiplePermissions` launcher in `MainActivity`) — no Settings deep-link needed.
 *
 * The grant logic is a pure function of the granted flag so it is unit-testable without a Context;
 * the thin [isGranted] wrapper resolves it against [ContextCompat.checkSelfPermission].
 */
object CalendarAccess {

    /** The runtime permission this app needs to read the user's calendar (WP13 slots 16–31). */
    const val PERMISSION: String = Manifest.permission.READ_CALENDAR

    /** True if `READ_CALENDAR` is granted to this app. */
    fun isGranted(context: Context): Boolean =
        isGranted(ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED)

    /**
     * Pure check: is the calendar read permission [granted]? (Split out so the grant logic is
     * testable with a plain boolean, mirroring [qhybrid.android.notifications.NotificationAccess].)
     */
    fun isGranted(granted: Boolean): Boolean = granted
}
