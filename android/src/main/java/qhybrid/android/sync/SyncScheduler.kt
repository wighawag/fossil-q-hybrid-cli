package qhybrid.android.sync

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.Constraints
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * WP14 — schedules the periodic [SyncSafetyWorker] via WorkManager.
 *
 * Enqueued as a UNIQUE periodic work (KEEP policy) so re-arming (boot / app start) never stacks
 * duplicate jobs. The interval is clamped to WorkManager's 15-minute floor via
 * [SyncScheduleDecider.normalizePeriodMinutes].
 *
 * **Constraints (respect Doze / battery):** the safety job does NOT require network (the watch
 * link is BLE, not IP) and is BATTERY-NOT-LOW — it is a low-priority reconciler, so the system is
 * free to defer it during Doze. No continuous scanning is involved; the worker only pokes
 * `syncNow` when the link is already up (see [SyncScheduleDecider]).
 */
object SyncScheduler {

    const val WORK_NAME = "fossilq-periodic-safety-sync"

    /** Schedule (or keep) the periodic safety sync. Idempotent. */
    fun schedule(
        context: Context,
        periodMinutes: Long = SyncScheduleDecider.DEFAULT_PERIOD_MINUTES,
    ) {
        val period = SyncScheduleDecider.normalizePeriodMinutes(periodMinutes)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
            .setRequiresBatteryNotLow(true)
            .build()
        val request = PeriodicWorkRequestBuilder<SyncSafetyWorker>(period, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /** Cancel the periodic safety sync (e.g. on full stop / disassociation). */
    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }
}
