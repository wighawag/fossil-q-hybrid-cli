package qhybrid.android.notifications

import android.content.Context
import qhybrid.android.WatchConnectionService

/**
 * WP11 — narrow, injectable seam for "play a matched notification on the watch" (mirrors
 * [qhybrid.android.settings.VibrationSync] / [qhybrid.android.settings.ClearAlarmsSync]) so the
 * [FossilNotificationListenerService] is unit-testable with a fake (no Android service, no BLE).
 *
 * The runtime play is **play-only by package**: the per-app vibration pattern + precise hand
 * degrees (WP4 rule) are already on the watch in its `NOTIFICATION_FILTER` (written at
 * init/provisioning and re-pushed when the user edits the app's rule via the WP14 sync). So this
 * seam only names the package; the watch matches the play file's package CRC against that on-watch
 * filter and applies the configured vibe + hands itself. **Invents NO new wire bytes** — the
 * production impl reuses [qhybrid.protocol.FossilController.playNotification] via the WP3 service.
 */
interface NotificationPlay {
    /**
     * Play the notification for [packageName] on the watch (connect-then-play if the link is down,
     * with a stale-drop so a passive notification never buzzes late). Returns whether the play
     * pipeline is wired (`true` for the production impl — see [NotificationPlay.PLAY_WIRED]).
     */
    fun play(packageName: String): Boolean

    companion object {
        /** WP11: the notification play is wired (playNotificationNow → FossilController.playNotification). */
        const val PLAY_WIRED = true
    }
}

/**
 * Production [NotificationPlay] — pokes the WP3 [WatchConnectionService] to play [packageName] on
 * its ble-worker (a single play-only put; connect-then-play if the link is down; 30 s stale-drop).
 * Holds the application context so it never leaks an Activity. Publishes NO `SyncState` — a posted
 * notification is a silent background effect, not a user-initiated foreground action. Adds NO new
 * BLE/protocol behavior — reuses the golden play path.
 */
class ServiceNotificationPlay(context: Context) : NotificationPlay {
    private val appContext = context.applicationContext

    override fun play(packageName: String): Boolean {
        WatchConnectionService.playNotificationNow(appContext, packageName)
        return NotificationPlay.PLAY_WIRED
    }
}

/**
 * A no-op [NotificationPlay] used as the listener's default so callers / tests that don't exercise
 * the play path never poke the service. Returns `false` (nothing was triggered).
 */
object NoopNotificationPlay : NotificationPlay {
    override fun play(packageName: String): Boolean = false
}
