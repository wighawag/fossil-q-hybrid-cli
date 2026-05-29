package qhybrid.android.sync

import android.content.Context
import qhybrid.android.WatchConnectionService

/**
 * WP-SYNCFIX — the single app-side "Save to watch" trigger shared by every feature seam
 * (`ServiceAlarmSync` / `ServiceNotificationSync` / `ServiceButtonSync` / `ServiceSettingsSync`).
 *
 * **Immediate feedback:** it publishes [SyncState] = SYNCING *synchronously* the instant the user
 * taps Save — BEFORE poking the service — so the Save button shows its spinner immediately, even
 * while the watch is still disconnected and the link is being established. (Previously the spinner
 * only appeared once the service confirmed a live link, which never happened when disconnected, so
 * Save looked like it did nothing.)
 *
 * **Honest result:** it then pokes [WatchConnectionService.syncNow], which does a
 * **connect-then-sync** when the link is down and publishes SUCCESS from the sync result — or an
 * honest ERROR (e.g. "Watch not reachable") if the connect/sync fails. The config is NOT reported
 * as saved-to-watch when the write didn't happen (it remains persisted in Room either way).
 *
 * The clock is injected so this stays unit-testable; production uses [System.currentTimeMillis].
 */
object ServiceSaveToWatch {

    /**
     * Mark a sync in flight ([SyncState] SYNCING) and poke the WP3 service to perform it. Returns
     * true (the upload pipeline is wired); the terminal SUCCESS/ERROR phase is published by the
     * service once the connect-then-sync resolves.
     */
    fun trigger(context: Context, now: () -> Long = System::currentTimeMillis): Boolean {
        SyncState.publish(SyncState.SyncPhase.SYNCING, nowMillis = now())
        WatchConnectionService.syncNow(context.applicationContext)
        return true
    }
}
