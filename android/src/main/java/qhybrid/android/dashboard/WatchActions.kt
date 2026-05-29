package qhybrid.android.dashboard

import android.content.Context
import qhybrid.android.WatchConnectionService

/**
 * WP16a — narrow, injectable seam over the WP3 [WatchConnectionService] static entry
 * points so [DashboardViewModel] is unit-testable with a fake (no Android service, no BLE).
 *
 * The production impl ([ServiceWatchActions]) simply forwards to the existing static
 * entry points — it adds NO new BLE/protocol behavior (WP16a constraint). "Find Watch"
 * (phone→watch) is routed here as a stub for now; the real watch-side choreography
 * (ANDROID-PLAN §4.J) is flagged on-device-pending and lives in a later WP.
 */
interface WatchActions {
    /** Connect+init to [mac] (or the associated mac if null). */
    fun connect(mac: String?)

    /** Tear down the current link. */
    fun disconnect()

    /** Re-run the sync-on-connect operations. */
    fun sync()

    /**
     * Find Watch (phone→watch). STUB for WP16a — does NOT add new BLE/protocol behavior.
     * The real hand-choreography / repeating-vibe trigger is on-device-pending (WP later).
     */
    fun findWatch()
}

/**
 * Production [WatchActions] — forwards to the WP3 service's existing static entry points.
 * Holds the application context so it never leaks an Activity.
 */
class ServiceWatchActions(context: Context) : WatchActions {
    private val appContext = context.applicationContext

    override fun connect(mac: String?) = WatchConnectionService.connectNow(appContext, mac)
    override fun disconnect() = WatchConnectionService.disconnect(appContext)
    override fun sync() = WatchConnectionService.syncNow(appContext)

    /**
     * On-device-pending: WP16a routes Find Watch through this seam but the production
     * trigger (a repeating call-notification / hand choreography on the watch) is NOT
     * implemented yet — doing so would add new protocol behavior, out of scope for WP16a.
     * For now it is a no-op so the UI button is wired end-to-end without changing wire bytes.
     */
    override fun findWatch() {
        // No-op placeholder. Real choreography is on-device-pending (ANDROID-PLAN §4.J).
    }
}
