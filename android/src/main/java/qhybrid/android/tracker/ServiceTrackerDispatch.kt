package qhybrid.android.tracker

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import qhybrid.android.WatchConnectionService
import qhybrid.android.db.WaypointEntity
import qhybrid.android.db.WatchRepository
import qhybrid.android.notifications.VibePatterns
import qhybrid.android.settings.SettingsVocabulary
import qhybrid.android.settings.SharedPreferencesSettingsPrefs
import qhybrid.android.tracker.ButtonActionRouter.Path2Action
import qhybrid.android.tracker.TrackerController.WaypointKind

/**
 * WP-TRACKER \u2014 the production Android shell that turns the watch's gestures/presses into GPS-tracker
 * effects. It owns BOTH event paths (the service feeds every `onEventJson` line here, AFTER the
 * music-vs-tracker role split for 0x05):
 *
 *   - **Part A \u2014 0x05 multi-function (TRACKER role).** [onMusicEventJson] runs the pure
 *     [TrackerDispatcher] over a [SystemTrackerEffects] seam: short \u2192 log MINOR + buzz 5, double \u2192
 *     log MAJOR + buzz 6, long \u2192 ring phone + buzz 8. The service only calls this when the GLOBAL
 *     [SettingsVocabulary.MULTI_FUNCTION_ROLE_TRACKER] role is active (else the WP12 music dispatch
 *     runs) \u2014 the 0x05 stream is button-blind so the role MUST be global.
 *
 *   - **Part B \u2014 0x08 button-aware single-press.** [onButtonEventJson] parses the RING_PHONE 0x08
 *     `type:"button"` event ([ButtonPressParser]), looks up the pressed button's stored action in
 *     the active watch's WP4 mappings ([ButtonActionRouter]), and runs: LOG_WAYPOINT \u2192 log MINOR +
 *     buzz; RING_PHONE \u2192 ring + buzz; SWITCH_MULTI_FUNCTION_MODE \u2192 flip the global role pref + buzz
 *     the RESULTING-mode pattern (now-MUSIC=5 / now-TRACKER=6) so the user feels which mode they're
 *     in. Single-press only (FINDINGS).
 *
 * **Threading.** `onEventJson` arrives on the ble-gatt thread. The pure parse is cheap and done on
 * the caller's thread; the GPS fix + Room write are marshalled onto an IO scope (the fix may block);
 * buzz-back reuses [WatchConnectionService.buzzNow] (which marshals onto its own ble-worker). Never
 * throws on the caller's thread.
 *
 * **GPS + loud ring wired (zero Google Play Services).** The live GPS fix ([SystemLocationSource],
 * platform LocationManager) and the loud phone ring ([SystemPhoneRinger], alarm-stream ringtone +
 * vibrate) are both wired. The Room write + buzz-back + ALL routing are unit-tested off-device via
 * the pure [TrackerController] / [TrackerDispatcher] / [ButtonPressParser] / [ButtonActionRouter]
 * with fakes, and the ring path via a fake [PhoneRinger]. Adds NO new wire bytes.
 */
class ServiceTrackerDispatch(
    context: Context,
    location: LocationSource? = null,
    ringer: PhoneRinger? = null,
    // The IO scope used for the GPS fix + Room write; injectable for tests.
    private val io: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
) {
    private val appContext = context.applicationContext
    // Default to the live platform-LocationManager source (zero Google Play Services); tests inject
    // a fake [LocationSource]. NoopLocationSource is no longer the default now that GPS is wired.
    private val location: LocationSource = location ?: SystemLocationSource(appContext)
    // Default to the live loud-ring effect (alarm-stream ringtone + vibrate, zero GMS); tests inject
    // a fake [PhoneRinger]. NoopPhoneRinger is no longer the default now that the ring is wired.
    private val ringer: PhoneRinger = ringer ?: SystemPhoneRinger(appContext)
    private val repo = WatchRepository(appContext)
    private val prefs = SharedPreferencesSettingsPrefs(appContext)

    private val effects = SystemTrackerEffects()
    private val dispatcher = TrackerDispatcher(effects)

    /** True iff the GLOBAL multi-function role is currently TRACKER (read fresh per call). */
    fun isTrackerRole(): Boolean =
        prefs.get().activeMode == SettingsVocabulary.MODE_TRACKER

    /**
     * Part A \u2014 handle one 0x05 `type:"music"` line as a TRACKER gesture. Cheap parse on the caller's
     * thread; the effects (GPS + Room + buzz) run via the dispatcher. The caller (service) MUST have
     * already decided the role is TRACKER. Returns the action taken (for logging/tests) or null.
     */
    fun onMusicEventJson(json: String?): TrackerController.TrackerAction? {
        val action = runCatching { dispatcher.onEventJson(json) }
            .onFailure { Log.w(TAG, "tracker music dispatch failed", it) }
            .getOrNull()
        if (action != null) Log.i(TAG, "tracker gesture decision: $action")
        return action
    }

    /**
     * Part B \u2014 handle one 0x08 `type:"button"` RING_PHONE line as a button-aware single press.
     * Parses + routes against the active watch's stored button mappings; runs the resolved Path-2
     * action. Returns the action taken (for logging/tests) or null when nothing matched.
     */
    fun onButtonEventJson(json: String?): Path2Action? {
        val press = ButtonPressParser.parse(json) ?: return null
        Log.i(TAG, "Path-2 button press: 0x%02X".format(press.buttonId))
        // The per-button mapping lookup needs the active watch's rows (a suspend DB read). Do it +
        // run the effect on the IO scope; resolve the action eagerly for the return value/log.
        val action = runCatching {
            val mac = runBlocking { repo.getActiveWatch()?.macAddress }
            val mappings = if (mac != null) runBlocking { repo.getButtons(mac) } else emptyList()
            ButtonActionRouter.resolve(press.buttonId, mappings)
        }.onFailure { Log.w(TAG, "Path-2 router failed", it) }.getOrNull() ?: return null

        Log.i(TAG, "Path-2 action: $action")
        when (action) {
            is Path2Action.LogWaypoint -> {
                io.launch { recordWaypointAsync(WaypointKind.MINOR) }
                buzz(action.buzzPattern)
            }
            is Path2Action.RingPhone -> {
                ringPhone()
                buzz(action.buzzPattern)
            }
            is Path2Action.SwitchMultiFunctionMode -> {
                val (newIndex, newMode) = advanceRotation()
                // Buzz a count that signals WHICH of the N rotation entries the user landed on:
                // (index+1) short pulses, clamped to the reserved-pattern range, so even >2-mode
                // rotations give distinct tactile feedback (1 pulse = first entry, 2 = second, …).
                buzz(VibePatterns.clamp(VibePatterns.ONE_SHORT + newIndex))
                Log.i(TAG, "multi-function advanced to [$newIndex] $newMode")
            }
        }
        return action
    }

    /**
     * L0 — advance the GLOBAL multi-function rotation by one (wrap-around) and persist the new
     * active index. Returns the new (index, mode) for buzz feedback + logging. The rotation + its
     * iteration are configured in Settings; this just steps the live pointer.
     */
    private fun advanceRotation(): Pair<Int, String> {
        val s = prefs.get()
        val next = SettingsVocabulary.nextIndex(s.multiFunctionRotation, s.multiFunctionActiveIndex)
        prefs.setMultiFunctionActiveIndex(next)
        val mode = SettingsVocabulary.activeMode(s.multiFunctionRotation, next)
        return next to mode
    }

    /** Buzz-back via the existing reserved-pattern play path (no new wire bytes). */
    private fun buzz(pattern: Int) =
        WatchConnectionService.buzzNow(appContext, VibePatterns.clamp(pattern))

    /**
     * Ring/find the phone: a loud, looping ringtone on the alarm stream at max volume + a waveform
     * vibration (modelled on Gadgetbridge `FindPhoneActivity`), via the injected [PhoneRinger] seam.
     *
     * **Toggle:** the SAME trigger (a TRACKER-role LONG gesture / a RING_PHONE button) both rings
     * and silences — if it's already ringing, a second press STOPS it; otherwise it starts. (It
     * still auto-stops after [RingPolicy.AUTO_STOP_MS] so a pocketed phone can't ring forever.)
     * Never throws on the caller's thread.
     */
    private fun ringPhone() {
        runCatching {
            val started = ringer.toggle()
            Log.i(TAG, if (started) "ringPhone(): started" else "ringPhone(): stopped (toggle)")
        }.onFailure { Log.w(TAG, "ringPhone() failed", it) }
    }

    /** Capture a GPS fix + persist it as a [kind] waypoint (best-effort; never throws). */
    private suspend fun recordWaypointAsync(kind: WaypointKind) {
        runCatching {
            val fix = location.currentFix()
            if (fix == null) {
                Log.w(TAG, "recordWaypoint($kind): no GPS fix (no permission / no fix / timeout)")
                return
            }
            val mac = repo.getActiveWatch()?.macAddress
            repo.insertWaypoint(
                WaypointEntity(
                    watchMac = mac,
                    kind = kind.name,
                    lat = fix.lat,
                    lon = fix.lon,
                    accuracyM = fix.accuracyM,
                    capturedAt = fix.timestamp,
                )
            )
            Log.i(TAG, "logged $kind waypoint at ${fix.lat},${fix.lon}")
        }.onFailure { Log.w(TAG, "recordWaypoint failed", it) }
    }

    /** Production [TrackerEffects] over the live device: GPS+Room write, ring, and buzz-back. */
    private inner class SystemTrackerEffects : TrackerEffects {
        override fun recordWaypoint(kind: WaypointKind) {
            io.launch { recordWaypointAsync(kind) }
        }

        override fun ringPhone() = this@ServiceTrackerDispatch.ringPhone()

        override fun buzzBack(pattern: Int) = this@ServiceTrackerDispatch.buzz(pattern)
    }

    private companion object {
        private const val TAG = "FossilQ-Tracker"
    }
}
