package qhybrid.android.notifications

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

/**
 * WP10/WP11 — helpers for the **Notification Access** special permission (the "notification
 * listener" access; NOT a runtime permission, so it can't be requested via the permissions API —
 * the user grants it in system Settings).
 *
 * Grant-state detection delegates to [NotificationManagerCompat.getEnabledListenerPackages] (which
 * reads `Settings.Secure.enabled_notification_listeners`), and the grant deep-link is the standard
 * [Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS] intent. The membership check is factored out as a
 * pure function so it is unit-testable without launching Settings.
 */
object NotificationAccess {

    /** True if THIS app's package is in the OS's enabled-notification-listeners set. */
    fun isGranted(context: Context): Boolean =
        isGranted(NotificationManagerCompat.getEnabledListenerPackages(context), context.packageName)

    /**
     * Pure membership check: is [packageName] in the OS-reported [enabledPackages] set of enabled
     * notification listeners? (Split out so the grant logic is testable with a plain set.)
     */
    fun isGranted(enabledPackages: Set<String>, packageName: String): Boolean =
        packageName in enabledPackages

    /**
     * The deep-link Intent to the system "Notification access" screen where the user toggles the
     * listener on. `FLAG_ACTIVITY_NEW_TASK` so it can be launched from a non-Activity context.
     */
    fun settingsIntent(): Intent =
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
