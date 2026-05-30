package qhybrid.android.settings

import android.content.Context
import android.util.Log
import qhybrid.android.CompanionManager
import qhybrid.android.WatchConnectionService

/**
 * WP-WATCHADMIN — narrow, injectable seam for watch-administration actions the Settings screen can
 * perform (mirrors [VibrationSync] / [FullSync] / WP16b's `AlarmSync` etc.) so [SettingsViewModel]
 * stays unit-testable with a fake (no Android service, no CDM, no BLE).
 *
 * Currently the one action is **remove this watch**: the "re-provision" / clean-slate operation the
 * user previously had to do via the Debug Menu wipe. Removing a watch makes the next connect look
 * brand-new, so the one-time provisioning sync runs again (which uploads the notification filter
 * with the reserved buzz entries folded in — see WP-BUZZ-PLAYONLY-SIMPLIFY).
 *
 * **What "remove" does (and does NOT do):**
 *  - DELETEs the WP4 Room row + its CASCADE children (alarms / rules / buttons) — the app's
 *    knowledge of the watch. This is what makes the next connect "new" → provisioning.
 *  - Clears the CDM association ([CompanionManager.disassociate]) + stops presence observation +
 *    clears the persisted associated-MAC pointer, so the OS no longer auto-wakes us for it. (The
 *    pre-S [qhybrid.android.ReconnectFallback] is a self-cancelling one-shot scan, nothing to undo.)
 *  - Disconnects the live link if connected.
 *  - Does NOT remove the OS Bluetooth bond — that is the user's "Forget" in Android Settings. We
 *    surface that as guidance in the UI; the app cannot/should not silently unpair at the OS level.
 *
 * The DB delete is suspending; the seam exposes a fire-and-forget call and reports whether the
 * pipeline is wired ([WATCHADMIN_WIRED]).
 */
interface WatchAdminSync {
    /**
     * Remove [mac] from the app: delete its DB row (+ children), clear its CDM association /
     * presence / reconnect pointer, and disconnect the link. Returns whether the action pipeline is
     * wired (`true`). The DB delete runs asynchronously off the caller's thread.
     */
    fun removeWatch(mac: String): Boolean

    companion object {
        /** WP-WATCHADMIN: the remove pipeline is wired. */
        const val WATCHADMIN_WIRED = true
    }
}

/**
 * Production [WatchAdminSync] — performs the full removal (DB delete + CDM disassociate + presence
 * stop + pointer clear + disconnect). Holds the application context so it never leaks an Activity.
 * The DB delete is dispatched on IO via the injected [removeFromDb] hook (production passes the WP4
 * repository delete); CDM/service effects are best-effort and tolerant.
 *
 * Adds NO new BLE/protocol behavior — disconnect reuses the existing [WatchConnectionService]
 * action; the rest is CDM/DB housekeeping.
 */
class ServiceWatchAdminSync(
    context: Context,
    /** Suspend hook that deletes the watch row (+ CASCADE children). Production = WP4 repo delete. */
    private val removeFromDb: suspend (String) -> Unit,
    /** Launches [removeFromDb]; production = an IO coroutine. Injected so tests stay synchronous. */
    private val launchDbRemoval: (suspend () -> Unit) -> Unit,
) : WatchAdminSync {
    private val appContext = context.applicationContext

    override fun removeWatch(mac: String): Boolean {
        val normalized = mac.uppercase()
        Log.i(TAG, "removeWatch($normalized): clearing CDM/presence/pointer, disconnecting, deleting DB row")
        // 1. Stop the OS waking us for this watch + clear the reconnect pointer.
        runCatching { CompanionManager.stopObserving(appContext, normalized) }
        runCatching { CompanionManager.disassociate(appContext, normalized) }
        runCatching { CompanionManager.setAssociatedMac(appContext, null) }
        // 2. Drop the live link if any — FORGET (not plain disconnect) so the service suppresses
        // auto-reconnect; otherwise the just-removed watch immediately reconnects and looks un-removed.
        runCatching { WatchConnectionService.forget(appContext) }
        // 3. Delete the app's knowledge of the watch (→ next connect provisions as new).
        launchDbRemoval { removeFromDb(normalized) }
        return WatchAdminSync.WATCHADMIN_WIRED
    }

    companion object {
        private const val TAG = "FossilQ-WatchAdmin"
    }
}

/**
 * A no-op [WatchAdminSync] used as the [SettingsViewModel] constructor default so callers / tests
 * that do not exercise removal never touch the service or CDM. Returns `false` (nothing done).
 */
object NoopWatchAdminSync : WatchAdminSync {
    override fun removeWatch(mac: String): Boolean = false
}
