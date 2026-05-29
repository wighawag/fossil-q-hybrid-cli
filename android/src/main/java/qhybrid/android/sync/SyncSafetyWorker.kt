package qhybrid.android.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import qhybrid.android.CompanionManager
import qhybrid.android.WatchConnectionService
import qhybrid.android.WatchState

/**
 * WP14 — the periodic **safety-sync** worker (WorkManager). A Doze-aware background net on top of
 * the primary triggers (sync-on-connect in the WP3 service, the WP13 calendar push later).
 *
 * It does NOT scan and does NOT force a connect (that is the CompanionDeviceManager's job): it
 * applies the pure [SyncScheduleDecider] to the current [WatchState] + CDM association and, only
 * when the link is already up, pokes [WatchConnectionService.syncNow] to reconcile any config drift.
 * The actual upload then runs on the service's ble-worker via the SyncOrchestrator (sub-parts 1–4).
 *
 * Scheduling lives in [SyncScheduler]; the *decision* lives in [SyncScheduleDecider] (unit-tested).
 */
class SyncSafetyWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val state = SyncScheduleDecider.State(
            hasAssociatedWatch = CompanionManager.getAssociatedMac(applicationContext) != null,
            linkUp = WatchState.status.value.link == WatchState.LinkState.INITIALIZED,
        )
        val decision = SyncScheduleDecider.decide(state)
        Log.i(TAG, "periodic safety-sync: shouldSync=${decision.shouldSync} (${decision.reason})")
        if (decision.shouldSync) {
            WatchConnectionService.syncNow(applicationContext)
        }
        // Always success — a skip is a normal outcome, not a failure to retry.
        return Result.success()
    }

    companion object {
        private const val TAG = "FossilQ-SyncWork"
    }
}
