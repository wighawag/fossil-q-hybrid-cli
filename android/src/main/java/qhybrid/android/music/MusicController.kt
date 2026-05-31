package qhybrid.android.music

import org.json.JSONObject

/**
 * WP12 — the **pure**, unit-testable core of the music-control feature: parse the watch's music
 * event JSON into a [MusicAction], and decide HOW to dispatch it (control an already-active media
 * session, or first launch the preferred music app then control it). No Android media stack, no
 * BLE, no Service — exactly the seam discipline of WP11's [qhybrid.android.notifications]
 * [NotificationDecider] / [NotificationDispatcher].
 *
 * **Signal path it sits in (WP12).** The watch firmware detects a music gesture on a MUSIC_CONTROL
 * (`01 06 12 00`) button and emits a proprietary MUSIC_EVENT (event type 0x05 on `3dda0006`). The
 * platform-neutral [qhybrid.protocol.FossilQAdapter.handleMusicEvent] already decodes that into the
 * JSON contract `{"type":"music","action":"NEXT","sequence":N,"timestamp":"…"}` and surfaces it via
 * [qhybrid.protocol.FossilController.onEventJson]. The Android shell ([ServiceMusicDispatch] wired in
 * [qhybrid.android.WatchConnectionService.wireConnectionCallbacks]) feeds each JSON line here.
 *
 * **No HID.** Per FINDINGS the Fossil Q does NOT send HID media keys — only these proprietary Fossil
 * events. So dispatch goes through Android's [android.media.session.MediaController]
 * (`MediaSessionManager.getActiveSessions`) + [android.media.AudioManager] for volume, NOT HID. The
 * actual media-stack calls live behind the injected [MusicSessionDispatcher] seam so the pure
 * mapping + the fallback decision are testable without a live Android media stack (on-device-pending
 * only for the real dispatch).
 *
 * **No wire bytes.** This is connection- & app-side interpretation only; nothing here changes the
 * protocol (the JSON contract is already emitted by the adapter).
 */
object MusicController {

    /** The music actions the watch can emit (mirrors [qhybrid.protocol.FossilQAdapter] music JSON). */
    enum class MusicAction {
        TOGGLE_PLAY_PAUSE,
        PLAY,
        PAUSE,
        NEXT,
        PREVIOUS,
        VOLUME_UP,
        VOLUME_DOWN,
    }

    /**
     * Parse one `onEventJson` line into a [MusicAction], or `null` when it is not a music event we
     * handle (wrong `type`, missing/unknown `action`, or malformed JSON). **Never throws.**
     *
     * The action strings match [qhybrid.protocol.FossilQAdapter.handleMusicEvent] exactly:
     * `PLAY`, `PAUSE`, `TOGGLE_PLAY_PAUSE`, `NEXT`, `PREVIOUS`, `VOLUME_UP`, `VOLUME_DOWN`
     * (any `UNKNOWN_*` / unrecognised action → `null`, a graceful no-op).
     */
    fun parse(json: String?): MusicAction? {
        val raw = json?.trim().orEmpty()
        if (raw.isEmpty()) return null
        return try {
            val obj = JSONObject(raw)
            if (obj.optString("type") != "music") return null
            when (obj.optString("action")) {
                "TOGGLE_PLAY_PAUSE" -> MusicAction.TOGGLE_PLAY_PAUSE
                "PLAY" -> MusicAction.PLAY
                "PAUSE" -> MusicAction.PAUSE
                "NEXT" -> MusicAction.NEXT
                "PREVIOUS" -> MusicAction.PREVIOUS
                "VOLUME_UP" -> MusicAction.VOLUME_UP
                "VOLUME_DOWN" -> MusicAction.VOLUME_DOWN
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * The dispatch decision for a parsed [MusicAction] given whether a media session is currently
     * active and the user's preferred-music-app package (the existing
     * [qhybrid.android.settings.SettingsVocabulary.MUSIC_APP_NONE] sentinel = unset).
     *
     * - [Dispatch] — a session IS active: control it directly.
     * - [LaunchThenDispatch] — no active session BUT a preferred app is configured: launch
     *   [packageName] first (so it grabs audio focus / publishes a session), then control it.
     * - [None] — no active session and no preferred app (pref is NONE/blank): a graceful no-op (we
     *   refuse to guess which app to open).
     *
     * Volume actions are the exception: [android.media.AudioManager.adjustStreamVolume] does NOT need
     * a media session, so a VOLUME_UP/DOWN with no active session still dispatches directly rather
     * than launching an app (changing the stream volume is always safe + expected). This keeps the
     * "no active session" launch-fallback for transport controls (play/pause/next/prev) only.
     */
    fun decide(
        action: MusicAction,
        hasActiveSession: Boolean,
        preferredMusicApp: String,
    ): Decision {
        val pref = preferredMusicApp.trim()
        // Volume always adjusts the music stream directly — no session / app launch needed.
        if (action == MusicAction.VOLUME_UP || action == MusicAction.VOLUME_DOWN) {
            return Decision.Dispatch(action)
        }
        if (hasActiveSession) return Decision.Dispatch(action)
        // No active session: fall back to launching the preferred app (if one is configured).
        return if (pref.isEmpty()) Decision.None("no-session-no-pref")
        else Decision.LaunchThenDispatch(pref, action)
    }

    /** The outcome of [decide]; consumed by the Android shell ([ServiceMusicDispatch]). */
    sealed interface Decision {
        /** Control the active session now (or adjust the volume stream). */
        data class Dispatch(val action: MusicAction) : Decision

        /** Launch [packageName] first (it will publish a session), then control [action]. */
        data class LaunchThenDispatch(val packageName: String, val action: MusicAction) : Decision

        /** Nothing to do; [reason] explains why (logging/tests). */
        data class None(val reason: String) : Decision
    }
}
