package qhybrid.android.settings

import android.content.Context
import qhybrid.android.sync.ServiceSaveToWatch
import qhybrid.android.sync.SyncSection

/**
 * WP-PULLSYNC — narrow, injectable seam for the manual "Sync all" button on the Settings screen
 * (mirrors [VibrationSync] / [SettingsSync]) so [SettingsViewModel] is unit-testable with a fake
 * (no Android service, no BLE).
 *
 * Sync is user-initiated now (connecting no longer auto-pushes the full config). "Sync all" is the
 * explicit escape hatch to push the WHOLE saved config (alarms / notification rules / buttons /
 * settings) to the active watch in one pass — e.g. after restoring a backup, or to force a
 * reconcile. The per-screen "Save to watch" remains the normal path (and the blocking modal tells
 * the user when a save happened, so this is rarely needed). No new wire bytes — reuses the
 * golden-tested [qhybrid.android.sync.SyncOrchestrator] full reconcile.
 */
interface FullSync {
    /**
     * Push the entire saved config to the active watch (connect-then-sync if the link is down,
     * with an honest error if unreachable). Returns whether the pipeline is wired (`true`).
     */
    fun syncAll(): Boolean
}

/**
 * Production [FullSync] — publishes [qhybrid.android.sync.SyncState] = SYNCING immediately (so the
 * blocking "Saving…" modal appears on tap) and pokes the WP3 service for a FULL reconcile
 * ([SyncSection.ALL]). Holds the application context so it never leaks an Activity.
 */
class ServiceFullSync(context: Context) : FullSync {
    private val appContext = context.applicationContext

    override fun syncAll(): Boolean =
        ServiceSaveToWatch.trigger(appContext, SyncSection.ALL)
}

/**
 * A no-op [FullSync] used as the [SettingsViewModel] constructor default so callers that don't
 * exercise the sync-all path never poke the service. Returns `false`.
 */
object NoopFullSync : FullSync {
    override fun syncAll(): Boolean = false
}
