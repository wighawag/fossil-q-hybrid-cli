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
import qhybrid.android.db.WatchAlarmEntity
import qhybrid.android.db.WatchRepository
import qhybrid.android.notifications.VibePatterns
import qhybrid.android.settings.SettingsVocabulary
import qhybrid.android.settings.SharedPreferencesSettingsPrefs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import qhybrid.android.sync.CoroutineDebouncer
import qhybrid.android.sync.Debouncer
import qhybrid.android.sync.GlobalSyncStateSource
import qhybrid.android.sync.ServiceSaveToWatch
import qhybrid.android.sync.SyncState
import qhybrid.android.sync.SyncStateSource
import qhybrid.android.sync.SyncSection
import qhybrid.android.tracker.ButtonActionRouter.Path2Action
import qhybrid.android.tracker.TrackerController.WaypointKind
import qhybrid.protocol.requests.fossil.alarm.AlarmCompiler
import java.time.ZoneId

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
    // TIMER: wall clock + zone for the "ring in N min" alarm time; injectable for deterministic tests.
    private val now: () -> Long = { System.currentTimeMillis() },
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    // The buzz effect seam: production plays via the WP3 reserved-pattern path; tests record the
    // pattern(s) that actually reach the watch. Default forwards to [WatchConnectionService.buzzNow].
    private val buzzEffect: ((Int) -> Unit)? = null,
    // TIMER: debounces the alarm-file PUSH so a burst of timer presses (or the watch re-sending one
    // press) coalesces into a SINGLE ALARMS upload of the FINAL state. Each press still writes Room
    // immediately (the armed time is always live); only the BLE re-push is debounced. Without this,
    // every press kicked a full 32-slot alarm-file upload (~1.5s each) and they serialized + timed
    // out on the ble-worker. Injectable for tests; null → a fixed-window CoroutineDebouncer.
    timerAlarmPushDebouncer: Debouncer? = null,
    // TIMER two-buzz feedback: the process-wide sync state, observed to fire the SECOND (duration)
    // buzz only AFTER the alarm actually reaches the watch. Injectable for tests.
    private val syncStateSource: SyncStateSource = GlobalSyncStateSource(),
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

    // Trailing-debounce the timer alarm push (see the constructor param). ~1.2s after the LAST
    // press so the final armed time is what reaches the watch, with one upload per burst.
    private val timerAlarmPushDebouncer: Debouncer =
        timerAlarmPushDebouncer ?: CoroutineDebouncer(io, TIMER_ALARM_PUSH_DEBOUNCE_MS)

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
     * TIMER: handle one 0x05 `type:"music"` line as a TIMER gesture: arm a one-shot "ring in N min"
     * alarm on the reserved [AlarmCompiler.TIMER_SLOT]. The caller (service) MUST have already
     * decided the active mode is [SettingsVocabulary.MODE_TIMER].
     *
     * Parse + the pure time math are cheap on the caller's thread; the Room write + alarm re-push
     * are marshalled onto the IO scope. The buzz-back (1/2/3 strong pulses for 3/5/10 min) fires
     * immediately for tactile confirmation. Returns the decided action (for logging/tests) or null
     * when the line isn't a mapped gesture. Never throws on the caller's thread.
     */
    fun onTimerEventJson(json: String?): TimerController.TimerAction? {
        val gesture = TimerController.parse(json) ?: return null
        val action = TimerController.decide(gesture, now(), zone())
        Log.i(TAG, "timer gesture: %s -> ring at %02d:%02d (+%d min)"
            .format(gesture, action.hour, action.minute, action.minutesFromNow))
        // TWO-BUZZ feedback. Buzz 1 (NOW, duration-INDEPENDENT): a single short pulse = "phone got
        // your press" — immediate, because the phone may be out of range and the user wants to know
        // the press registered. Buzz 2 (later, the DURATION pattern) fires only once the alarm has
        // actually reached the watch (see [armTimerAlarmAsync]) — so the user feels WHEN the timer
        // truly starts + WHICH timer (short/double/long).
        buzz(TIMER_RECEIVED_BUZZ)
        io.launch { armTimerAlarmAsync(action) }
        return action
    }

    /**
     * Write the one-shot TIMER alarm to the reserved slot of the active watch (replacing any prior
     * pending timer), then re-push the alarm file so the watch arms it. Best-effort; never throws.
     */
    private suspend fun armTimerAlarmAsync(action: TimerController.TimerAction) {
        runCatching {
            val mac = repo.getActiveWatch()?.macAddress ?: run {
                Log.w(TAG, "armTimer: no active watch")
                return
            }
            repo.upsertAlarm(
                WatchAlarmEntity(
                    watchMac = mac,
                    slotId = AlarmCompiler.TIMER_SLOT,
                    hour = action.hour,
                    minute = action.minute,
                    isEnabled = true,
                    daysMask = 0,
                    isRepeating = false,
                    label = "Timer",
                )
            )
            // DEBOUNCE the alarm-file push: rapid presses (or a re-sent press) coalesce into ONE
            // ALARMS upload of the final armed time, instead of one full file upload per press.
            // After the push, await the ALARMS sync result and fire the DURATION buzz on success
            // (buzz 2) — so the user feels when the timer ACTUALLY started + which timer.
            timerAlarmPushDebouncer.schedule {
                io.launch { pushTimerAlarmAndConfirm(action) }
            }
            Log.i(TAG, "armed timer alarm slot ${AlarmCompiler.TIMER_SLOT} at %02d:%02d (push debounced)"
                .format(action.hour, action.minute))
        }.onFailure { Log.w(TAG, "armTimer failed", it) }
    }

    /**
     * Trigger the ALARMS push and, when it SUCCEEDS (the alarm reached the watch), play the DURATION
     * buzz ([TimerController.TimerAction.buzzPattern]) as the second feedback. On failure/timeout no
     * second buzz fires — the absence itself tells the user the timer did not arm (e.g. out of
     * range). Best-effort; never throws.
     */
    private suspend fun pushTimerAlarmAndConfirm(action: TimerController.TimerAction) {
        // Capture the current state version BEFORE triggering so a stale SUCCESS already in the
        // StateFlow (replayed to a new collector) can't satisfy the wait — we only accept a terminal
        // phase published AFTER this point (strictly newer lastUpdatedMillis).
        val since = syncStateSource.status.value.lastUpdatedMillis
        ServiceSaveToWatch.trigger(appContext, SyncSection.ALARMS_ONLY)
        val ok = withTimeoutOrNull(TIMER_CONFIRM_TIMEOUT_MS) {
            syncStateSource.status.first { st ->
                st.lastUpdatedMillis > since && when (st.phase) {
                    SyncState.SyncPhase.SUCCESS ->
                        st.lastResult?.performed?.contains(SyncSection.ALARMS) == true
                    SyncState.SyncPhase.ERROR -> true
                    else -> false
                }
            }.phase == SyncState.SyncPhase.SUCCESS
        } ?: false
        if (ok) {
            Log.i(TAG, "timer armed on watch — duration buzz ${action.buzzPattern}")
            buzz(action.buzzPattern)
        } else {
            Log.w(TAG, "timer alarm not confirmed on watch (timeout/error) — no duration buzz")
        }
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
                // Advance the rotation + buzz the resulting mode IMMEDIATELY. With the no-hand
                // reserved buzz (WP-SWITCH-BUZZ-NOHANDS) there is no ~10s hand-return lockout, so a
                // buzz lands cleanly on every press — no debounce/coalescing needed. Each press is
                // fast enough to switch again right away and feel the new mode.
                val (newIndex, newMode, switchBuzz) = advanceRotation()
                Log.i(TAG, "multi-function advanced to [$newIndex] $newMode (buzz $switchBuzz)")
                buzz(switchBuzz)
            }
        }
        return action
    }

    /** The new rotation pointer + the resolved per-mode switch buzz after a SWITCH press. */
    private data class Advanced(val index: Int, val mode: String, val switchBuzz: Int)

    /**
     * L0 — advance the GLOBAL multi-function rotation by one (wrap-around) and persist the new
     * active index. Returns the new (index, mode) + the STABLE per-mode switch buzz (override →
     * default → single) for feedback + logging. The rotation + its iteration are configured in
     * Settings; this just steps the live pointer.
     */
    private fun advanceRotation(): Advanced {
        val s = prefs.get()
        val next = SettingsVocabulary.nextIndex(s.multiFunctionRotation, s.multiFunctionActiveIndex)
        prefs.setMultiFunctionActiveIndex(next)
        val mode = SettingsVocabulary.activeMode(s.multiFunctionRotation, next)
        val switchBuzz = SettingsVocabulary.switchBuzzFor(mode, s.multiFunctionSwitchBuzz)
        return Advanced(next, mode, switchBuzz)
    }

    /** Buzz-back via the injected seam, or the existing reserved-pattern play path (no new wire bytes). */
    private fun buzz(pattern: Int) {
        val clamped = VibePatterns.clamp(pattern)
        val effect = buzzEffect
        if (effect != null) effect(clamped)
        else WatchConnectionService.buzzNow(appContext, clamped)
    }

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
        // Trailing-debounce window for the timer alarm-file push (ms).
        private const val TIMER_ALARM_PUSH_DEBOUNCE_MS = 1200L
        // Buzz 1: the immediate, duration-INDEPENDENT "press received" tick. Uses the SOFT single
        // (VibePatterns.EMAIL) so it is clearly DISTINCT from the strong 1/2/3-pulse DURATION buzzes
        // (ONE_SHORT/TWO_SHORT/THREE_SHORT) of buzz 2 — otherwise a SHORT timer's buzz 2 would feel
        // identical to buzz 1.
        private const val TIMER_RECEIVED_BUZZ = VibePatterns.EMAIL
        // How long to wait for the alarm sync to confirm before giving up on the duration buzz.
        private const val TIMER_CONFIRM_TIMEOUT_MS = 20_000L
    }
}
