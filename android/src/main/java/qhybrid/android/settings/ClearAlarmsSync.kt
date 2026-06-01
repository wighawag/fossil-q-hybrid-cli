package qhybrid.android.settings

import android.content.Context
import android.util.Log
import qhybrid.android.db.WatchRepository
import qhybrid.android.sync.ServiceSaveToWatch
import qhybrid.android.sync.SyncSection

/**
 * WP-CLEARALARMS — narrow, injectable seam for the Settings "Clear all alarms" action (mirrors
 * [ApplyDefaultsSync] / [FullSync] / [VibrationSync]) so [SettingsViewModel] stays unit-testable
 * with a fake (no Android service, no Room, no BLE).
 *
 * **Why this isn't just "Apply defaults".** Apply-defaults deliberately leaves the per-watch alarms
 * untouched (`replaceAlarms = false`) and the factory alarms are empty anyway, so it never clears
 * alarms. This action specifically DELETEs the active watch's standard alarms (slots 0..15; the
 * calendar slots 16..31 are left alone) and pushes the EMPTY alarm section to the watch.
 *
 * **Why PROVISION mode.** An ordinary targeted save runs in RECONCILE mode, which **skip-empties**
 * the alarm section — so pushing an empty set would NOT clear the watch. This action triggers the
 * push with `forceProvision = true` so the orchestrator force-writes the empty 32-slot alarm file,
 * actively blanking the watch's alarms. No new wire bytes — it reuses the existing
 * [ServiceSaveToWatch] path + the PROVISION force-write the orchestrator already does.
 */
interface ClearAlarmsSync {
    /**
     * Clear the active watch's standard alarms (DB delete) + push the blanked alarm file to the
     * watch. No-op (returns `false`) without an active watch. Returns whether the pipeline is wired.
     */
    fun clearAlarmsOnActiveWatch(): Boolean

    companion object {
        /** WP-CLEARALARMS: the clear pipeline is wired (DB delete + force-write empty alarms). */
        const val CLEAR_ALARMS_WIRED = true
    }
}

/**
 * Production [ClearAlarmsSync] — resolves the active watch, deletes its standard alarms, then pokes
 * [ServiceSaveToWatch] for a targeted [SyncSection.ALARMS_ONLY] push in PROVISION mode (force-write
 * the empty file to blank the watch). The DB write is dispatched off the caller's thread via
 * [launchPersist]. Holds the application context so it never leaks an Activity.
 */
class ServiceClearAlarms(
    context: Context,
    /** Resolves the active watch's mac (or null). Production = WP4 repo. Injected for tests. */
    private val activeMac: suspend () -> String?,
    /** Deletes [mac]'s standard user alarms (slots 0..14; timer slot 15 preserved). Production = WP4 repo. */
    private val clearAlarms: suspend (mac: String) -> Unit,
    /** Launches the suspend delete off the caller's thread; production = an IO coroutine. */
    private val launchPersist: (suspend () -> Unit) -> Unit,
) : ClearAlarmsSync {
    private val appContext = context.applicationContext

    override fun clearAlarmsOnActiveWatch(): Boolean {
        // Publish SYNCING SYNCHRONOUSLY so the blocking modal appears the instant the user confirms.
        qhybrid.android.sync.SyncState.publish(
            qhybrid.android.sync.SyncState.SyncPhase.SYNCING,
            nowMillis = System.currentTimeMillis(),
        )
        launchPersist {
            val mac = activeMac()
            if (mac == null) {
                Log.w(TAG, "clearAlarms: no active watch — nothing to clear")
                return@launchPersist
            }
            runCatching { clearAlarms(mac) }
                .onFailure { Log.w(TAG, "clearAlarms($mac): DB delete failed", it) }
            Log.i(TAG, "clearAlarms($mac): deleted standard alarms; pushing blank alarm file")
            // Force-write the EMPTY alarm section so the watch's 32 slots are actively blanked.
            ServiceSaveToWatch.trigger(appContext, SyncSection.ALARMS_ONLY, forceProvision = true)
        }
        return ClearAlarmsSync.CLEAR_ALARMS_WIRED
    }

    companion object {
        private const val TAG = "FossilQ-ClearAlarms"

        /** Production factory: WP4 repo active-mac lookup + standard-alarm delete + IO launcher. */
        fun create(context: Context, launch: (suspend () -> Unit) -> Unit): ServiceClearAlarms {
            val appContext = context.applicationContext
            return ServiceClearAlarms(
                context = appContext,
                activeMac = { WatchRepository(appContext).getActiveWatch()?.macAddress },
                clearAlarms = { mac -> WatchRepository(appContext).clearStandardAlarms(mac) },
                launchPersist = launch,
            )
        }
    }
}

/**
 * A no-op [ClearAlarmsSync] used as the [SettingsViewModel] constructor default so callers / tests
 * that don't exercise the clear path never touch the service or Room. Returns `false`.
 */
object NoopClearAlarms : ClearAlarmsSync {
    override fun clearAlarmsOnActiveWatch(): Boolean = false
}
