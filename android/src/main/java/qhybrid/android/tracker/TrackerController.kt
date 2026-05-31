package qhybrid.android.tracker

import org.json.JSONObject
import qhybrid.android.notifications.VibePatterns

/**
 * WP-TRACKER \u2014 the **pure**, unit-testable core of the GPS-waypoint TRACKER role on the watch's
 * button-blind multi-function (0x05) gesture stream. Mirrors WP12's
 * [qhybrid.android.music.MusicController]: parse the watch's `type:"music"` event JSON into a
 * [TrackerGesture], then [decide] which [TrackerAction] (each carrying its own buzz-back pattern)
 * to run. No Android, no GPS, no BLE \u2014 those live behind seams in [TrackerDispatcher] /
 * [ServiceTrackerDispatch].
 *
 * **Signal path (GROUND TRUTH, measured on-device 2026-05-31; FINDINGS).** A MUSIC_CONTROL
 * (`01 06 12 00`) button makes the firmware classify the gesture ITSELF and emit a MUSIC_EVENT
 * (event type 0x05). [qhybrid.protocol.FossilQAdapter.handleMusicEvent] decodes the gesture byte
 * into `{"type":"music","action":"TOGGLE_PLAY_PAUSE|NEXT|PREVIOUS|\u2026","sequence":N,"timestamp":\u2026}`:
 *   - short press \u2192 `TOGGLE_PLAY_PAUSE` (byte 02),
 *   - double press \u2192 `NEXT` (byte 03),
 *   - long press \u2192 `PREVIOUS` (byte 04).
 * That stream carries NO button id, so the TRACKER role is necessarily a single GLOBAL setting (see
 * [qhybrid.android.settings.SettingsVocabulary.MULTI_FUNCTION_ROLE_MUSIC]). When the global role is
 * TRACKER, the SAME three gestures map to GPS-tracker actions instead of media commands.
 *
 * **No wire bytes / no protocol change.** The JSON contract is already emitted by the adapter; this
 * is connection- & app-side re-interpretation only.
 */
object TrackerController {

    /**
     * The three gestures the firmware distinguishes on the 0x05 stream, re-labelled for the tracker
     * role (volume up/down 05/06 come from OTHER payloads and are ignored here).
     */
    enum class TrackerGesture { SHORT, DOUBLE, LONG }

    /** The kind of GPS waypoint a [TrackerAction.Log] records. */
    enum class WaypointKind { MINOR, MAJOR }

    /**
     * Parse one `onEventJson` line into a [TrackerGesture], or `null` when it is not a music-stream
     * gesture we map (wrong `type`, a non-transport action like volume, or malformed JSON). **Never
     * throws.** Reuses the EXACT action strings [qhybrid.protocol.FossilQAdapter.handleMusicEvent]
     * emits (the same ones [qhybrid.android.music.MusicController.parse] consumes):
     *   - `TOGGLE_PLAY_PAUSE` \u2192 [TrackerGesture.SHORT],
     *   - `NEXT`              \u2192 [TrackerGesture.DOUBLE],
     *   - `PREVIOUS`          \u2192 [TrackerGesture.LONG],
     *   - anything else (PLAY/PAUSE/VOLUME_UP/VOLUME_DOWN/UNKNOWN) \u2192 `null` (graceful no-op).
     */
    fun parse(json: String?): TrackerGesture? {
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

    /** Map a music action string to the tracker gesture, or null for non-mapped actions. */
    fun gestureForAction(action: String?): TrackerGesture? = when (action) {
        "TOGGLE_PLAY_PAUSE" -> TrackerGesture.SHORT
        "NEXT" -> TrackerGesture.DOUBLE
        "PREVIOUS" -> TrackerGesture.LONG
        else -> null
    }

    /**
     * Decide the [TrackerAction] for a gesture in the TRACKER role. Each action carries its own
     * distinct buzz-back vibe so the user (watch pocketed) FEELS which action ran:
     *   - SHORT  \u2192 [TrackerAction.Log] MINOR waypoint, buzz [VibePatterns.ONE_SHORT] (5),
     *   - DOUBLE \u2192 [TrackerAction.Log] MAJOR POI,       buzz [VibePatterns.TWO_SHORT] (6),
     *   - LONG   \u2192 [TrackerAction.RingPhone] (find the phone),  buzz [VibePatterns.ONE_LONG] (8).
     */
    fun decide(gesture: TrackerGesture): TrackerAction = when (gesture) {
        TrackerGesture.SHORT -> TrackerAction.Log(WaypointKind.MINOR, VibePatterns.ONE_SHORT)
        TrackerGesture.DOUBLE -> TrackerAction.Log(WaypointKind.MAJOR, VibePatterns.TWO_SHORT)
        TrackerGesture.LONG -> TrackerAction.RingPhone(VibePatterns.ONE_LONG)
    }

    /** The outcome of [decide]; consumed by the Android shell ([ServiceTrackerDispatch]). */
    sealed interface TrackerAction {
        /** The buzz-back vibe pattern (0..9) the watch should play to confirm the action. */
        val buzzPattern: Int

        /** Log a GPS fix as a [kind] waypoint, then buzz [buzzPattern]. */
        data class Log(val kind: WaypointKind, override val buzzPattern: Int) : TrackerAction

        /** Ring the phone (find-phone), then buzz [buzzPattern] to confirm it was heard. */
        data class RingPhone(override val buzzPattern: Int) : TrackerAction
    }
}
