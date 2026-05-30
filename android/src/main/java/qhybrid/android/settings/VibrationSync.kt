package qhybrid.android.settings

import android.content.Context
import qhybrid.android.sync.ServiceBuzz

/**
 * WP-BUZZTEST — narrow, injectable seam for the manual "vibrate the watch now" test buttons on the
 * Settings screen (mirrors [SettingsSync] / WP16b's `AlarmSync` etc.) so [SettingsViewModel] is
 * unit-testable with a fake (no Android service, no BLE).
 *
 * The two Settings buttons are an on-device test tool: pressing one makes the watch buzz
 * immediately (connecting first if the link is down, with an honest error if unreachable). They are
 * the reusable primitive to prove the buzz path works on-device.
 *
 * **WIRED.** The buzz is driven through the WP3 [qhybrid.android.WatchConnectionService] on its
 * ble-worker via [qhybrid.android.sync.ServiceBuzz] → `buzzNow`, which calls the golden-tested
 * [qhybrid.protocol.FossilController.buzz] passthrough (NOTIFICATION_FILTER + NOTIFICATION_PLAY).
 * No wire bytes are invented. (On-device BLE vibration is verified-pending hardware; the
 * connect-then-do + SyncState reporting are unit-tested.)
 */
interface VibrationSync {
    /**
     * Make the watch vibrate NOW with the given vibration [pattern] byte. When [forceFilterPlay] is
     * true, use the self-contained two-put path (NOTIFICATION_FILTER + NOTIFICATION_PLAY) that works
     * even if the reserved buzz filter isn't on the watch (the diagnostic "put filter + send buzz");
     * otherwise a reserved pattern uses the single play-only put. Returns whether the buzz pipeline
     * is wired (`true` — see [VIBRATION_WIRED]).
     */
    fun buzz(pattern: Int, forceFilterPlay: Boolean = false): Boolean

    companion object {
        /** Strong single buzz (ONE_SHORT_VIBE). Hardware-tested pattern byte. */
        const val PATTERN_SINGLE = 5

        /** Triple buzz (CALL). Hardware-tested pattern byte. */
        const val PATTERN_TRIPLE = 1
    }
}

/**
 * Production [VibrationSync] — publishes [qhybrid.android.sync.SyncState] = SYNCING immediately
 * (so the blocking "Buzzing…" modal appears on tap) and pokes the WP3 service to buzz the watch
 * (connect-then-buzz). Holds the application context so it never leaks an Activity. Adds NO new
 * BLE/protocol behavior — reuses the golden buzz path.
 */
class ServiceVibrationSync(context: Context) : VibrationSync {
    private val appContext = context.applicationContext

    override fun buzz(pattern: Int, forceFilterPlay: Boolean): Boolean =
        ServiceBuzz.trigger(appContext, pattern, forceFilterPlay) && VIBRATION_WIRED

    companion object {
        /** WP-BUZZTEST: the manual buzz is wired (buzzNow → FossilController.buzz). */
        const val VIBRATION_WIRED = true
    }
}

/**
 * A no-op [VibrationSync] used as the [SettingsViewModel] constructor default so callers that do
 * not exercise the buzz path (and tests that don't inject a fake) never poke the service. Returns
 * `false` (nothing was triggered).
 */
object NoopVibrationSync : VibrationSync {
    override fun buzz(pattern: Int, forceFilterPlay: Boolean): Boolean = false
}
