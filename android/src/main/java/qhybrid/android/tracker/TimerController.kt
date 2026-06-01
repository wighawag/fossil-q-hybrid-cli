package qhybrid.android.tracker

import org.json.JSONObject
import qhybrid.android.notifications.VibePatterns
import qhybrid.android.settings.SettingsVocabulary
import java.time.Instant
import java.time.ZoneId

/**
 * TIMER \u2014 the **pure**, unit-testable core of the multi-function TIMER mode on the watch's
 * button-blind multi-function (0x05) gesture stream. Mirrors WP12's
 * [qhybrid.android.music.MusicController] and WP-TRACKER's [TrackerController]: parse the watch's
 * `type:"music"` event JSON into a [TimerGesture], then [decide] which [TimerAction] (carrying the
 * absolute ring time + a buzz-back pattern) to run. No Android, no BLE, no Room \u2014 those live behind
 * seams in [ServiceTrackerDispatch].
 *
 * **Gestures (SAME firmware classification as the tracker/music modes; FINDINGS):**
 *   - short press  \u2192 `TOGGLE_PLAY_PAUSE` \u2192 ring in [SettingsVocabulary.TIMER_SHORT_MINUTES] (3),
 *   - double press \u2192 `NEXT`              \u2192 ring in [SettingsVocabulary.TIMER_DOUBLE_MINUTES] (5),
 *   - long press   \u2192 `PREVIOUS`          \u2192 ring in [SettingsVocabulary.TIMER_LONG_MINUTES] (10).
 *
 * **Rounding:** the ring time is `now + N minutes`, rounded to the NEAREST minute (so the alarm can
 * fire up to ~30 s EARLIER than the nominal N \u2014 e.g. a +3 min press at 10:00:40 rings at 10:04,
 * i.e. ~3m20 later). The watch alarm table only stores HH:MM, so sub-minute precision is impossible.
 *
 * **No wire bytes / no protocol change.** The JSON contract is already emitted by the adapter; this
 * is connection- & app-side re-interpretation only. The resulting one-shot alarm is written to the
 * reserved [qhybrid.protocol.requests.fossil.alarm.AlarmCompiler.TIMER_SLOT] and pushed by the
 * existing alarm-sync pipeline.
 */
object TimerController {

    /** The three gestures the firmware distinguishes on the 0x05 stream, re-labelled for TIMER. */
    enum class TimerGesture { SHORT, DOUBLE, LONG }

    /**
     * Parse one `onEventJson` line into a [TimerGesture], or `null` when it is not a mapped
     * music-stream gesture (wrong `type`, a non-transport action like volume, or malformed JSON).
     * **Never throws.** Reuses the EXACT action strings the adapter emits (same as
     * [TrackerController.parse] / [qhybrid.android.music.MusicController.parse]).
     */
    fun parse(json: String?): TimerGesture? {
        val raw = json?.trim().orEmpty()
        if (raw.isEmpty()) return null
        return try {
            val obj = JSONObject(raw)
            if (obj.optString("type") != "music") return null
            gestureForAction(obj.optString("action"))
        } catch (_: Exception) {
            null
        }
    }

    /** Map a music action string to the timer gesture, or null for non-mapped actions. */
    fun gestureForAction(action: String?): TimerGesture? = when (action) {
        "TOGGLE_PLAY_PAUSE" -> TimerGesture.SHORT
        "NEXT" -> TimerGesture.DOUBLE
        "PREVIOUS" -> TimerGesture.LONG
        else -> null
    }

    /** The minutes-from-now a gesture arms. */
    fun minutesFor(gesture: TimerGesture): Int = when (gesture) {
        TimerGesture.SHORT -> SettingsVocabulary.TIMER_SHORT_MINUTES
        TimerGesture.DOUBLE -> SettingsVocabulary.TIMER_DOUBLE_MINUTES
        TimerGesture.LONG -> SettingsVocabulary.TIMER_LONG_MINUTES
    }

    /** The buzz-back vibe a gesture confirms with (1/2/3 strong pulses for short/double/long). */
    fun buzzFor(gesture: TimerGesture): Int = when (gesture) {
        TimerGesture.SHORT -> VibePatterns.ONE_SHORT
        TimerGesture.DOUBLE -> VibePatterns.TWO_SHORT
        TimerGesture.LONG -> VibePatterns.THREE_SHORT
    }

    /**
     * Decide the [TimerAction] for a [gesture] given the current wall-clock [nowEpochMillis] and the
     * local [zone]. The ring time is `now + minutesFor(gesture)`, rounded to the NEAREST minute, and
     * decomposed into local [TimerAction.hour]/[TimerAction.minute]. Never throws.
     */
    fun decide(gesture: TimerGesture, nowEpochMillis: Long, zone: ZoneId): TimerAction {
        val minutes = minutesFor(gesture)
        val ring = roundToNearestMinute(nowEpochMillis + minutes * 60_000L)
        val local = Instant.ofEpochMilli(ring).atZone(zone)
        return TimerAction(
            hour = local.hour,
            minute = local.minute,
            minutesFromNow = minutes,
            buzzPattern = buzzFor(gesture),
        )
    }

    /** Round an epoch-millis instant to the nearest whole minute (ties round up). */
    fun roundToNearestMinute(epochMillis: Long): Long {
        val minuteMs = 60_000L
        return ((epochMillis + minuteMs / 2) / minuteMs) * minuteMs
    }

    /**
     * The outcome of [decide]; consumed by the Android shell ([ServiceTrackerDispatch]) which writes
     * a one-shot alarm at [hour]:[minute] to the reserved TIMER slot then re-pushes the alarm file.
     */
    data class TimerAction(
        val hour: Int,
        val minute: Int,
        /** The nominal minutes-from-now this timer was armed for (for logging/UI). */
        val minutesFromNow: Int,
        /** The buzz-back vibe (0..9) the watch plays to confirm the timer was set. */
        val buzzPattern: Int,
    )
}
