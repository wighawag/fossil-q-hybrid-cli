package qhybrid.android.sync

import android.content.Context
import qhybrid.android.WatchConnectionService

/**
 * WP-BUZZTEST — the app-side "vibrate the watch now" trigger (mirrors [ServiceSaveToWatch]).
 *
 * **Immediate feedback:** it publishes [SyncState] = SYNCING *synchronously* the instant the user
 * taps a "Vibrate" button — BEFORE poking the service — so the blocking "Buzzing…" modal appears
 * immediately, even while the watch is still disconnected and the link is being established (the
 * buzz does a connect-then-do, exactly like a Save).
 *
 * **Honest result:** it then pokes [WatchConnectionService.buzzNow], which does a
 * **connect-then-buzz** when the link is down and publishes SUCCESS once the buzz wire sequence
 * (NOTIFICATION_FILTER + NOTIFICATION_PLAY) runs — or an honest ERROR (e.g. "Watch not reachable")
 * if the connect/buzz fails. No new wire bytes (reuses [qhybrid.protocol.FossilController.buzz]).
 *
 * The clock is injected so this stays unit-testable; production uses [System.currentTimeMillis].
 */
object ServiceBuzz {

    /**
     * Mark a buzz in flight ([SyncState] SYNCING) and poke the WP3 service to vibrate the watch
     * with [pattern] (e.g. 5 = strong single, 1 = triple). Returns true (the buzz pipeline is
     * wired); the terminal SUCCESS/ERROR phase is published by the service once the
     * connect-then-buzz resolves.
     */
    fun trigger(
        context: Context,
        pattern: Int,
        now: () -> Long = System::currentTimeMillis,
    ): Boolean {
        SyncState.publish(SyncState.SyncPhase.SYNCING, nowMillis = now())
        WatchConnectionService.buzzNow(context.applicationContext, pattern)
        return true
    }
}
