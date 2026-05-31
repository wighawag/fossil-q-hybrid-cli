package qhybrid.android.music

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import qhybrid.android.notifications.FossilNotificationListenerService
import qhybrid.android.settings.SharedPreferencesSettingsPrefs

/**
 * WP12 — the production Android shell that turns the watch's music gestures into media-stack
 * actions. It builds a [MusicDispatcher] over the live Android stack:
 *   - `hasActiveSession` — does [MediaSessionManager.getActiveSessions] return any session?
 *   - `preferredMusicApp` — the existing [SharedPreferencesSettingsPrefs] pref (WP16g), used as the
 *     launch-fallback target when nothing is currently playing,
 *   - a [SystemMusicSessionDispatcher] that issues [MediaController.TransportControls] calls (play/
 *     pause/next/prev) or [AudioManager.adjustStreamVolume] (volume), and launches the preferred app
 *     when there is no active session.
 *
 * **Threading.** The watch's `onEventJson` callback arrives on the ble-gatt HandlerThread (see
 * [qhybrid.android.WatchConnectionService]). Media calls want a main/looper thread, so
 * [onEventJson] marshals the whole decide+dispatch onto the main thread via a main-looper [Handler].
 * The pure decision ([MusicController]) is cheap; only the actual media-stack touch needs the main
 * thread.
 *
 * **Access reuse.** [MediaSessionManager.getActiveSessions] requires the caller to be an enabled
 * [android.service.notification.NotificationListenerService] — which the app ALREADY has for WP11
 * notifications ([FossilNotificationListenerService]). We pass that component as the required
 * notification-listener token; no NEW permission is needed (the user already granted Notification
 * Access). If access is not yet granted, `getActiveSessions` throws [SecurityException], which we
 * swallow → treated as "no active session" (graceful: launch the preferred app or no-op).
 *
 * **On-device-pending.** The real [SystemMusicSessionDispatcher] needs a live media stack + granted
 * notification access, so it is verified on-device; all the routing/decision logic is unit-tested
 * off-device via [MusicController] / [MusicDispatcher] with fakes. Adds NO new wire bytes.
 */
class ServiceMusicDispatch(context: Context) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private val mediaSessionManager: MediaSessionManager? =
        appContext.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager

    private val audioManager: AudioManager? =
        appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    /** The WP11 notification-listener component — the token getActiveSessions requires. */
    private val listenerComponent =
        ComponentName(appContext, FossilNotificationListenerService::class.java)

    private val dispatcher = MusicDispatcher(
        hasActiveSession = { activeSessions().isNotEmpty() },
        preferredMusicApp = {
            SharedPreferencesSettingsPrefs(appContext).get().preferredMusicApp
        },
        dispatcher = SystemMusicSessionDispatcher(),
    )

    /**
     * Feed one watch `onEventJson` line into the music pipeline. Marshals the decide+dispatch onto
     * the main looper (media calls want the main thread; the callback arrives on the ble-gatt
     * thread). A non-music / unhandled line is a cheap no-op. Never throws on the caller's thread.
     */
    fun onEventJson(json: String?) {
        // Parse cheaply on the calling thread; only post if it IS a music event we handle.
        if (MusicController.parse(json) == null) return
        mainHandler.post {
            runCatching { dispatcher.onEventJson(json) }
                .onFailure { Log.w(TAG, "music dispatch failed", it) }
        }
    }

    /** The currently-active media sessions, or empty if none / access not granted. */
    private fun activeSessions(): List<MediaController> =
        runCatching { mediaSessionManager?.getActiveSessions(listenerComponent) ?: emptyList() }
            .getOrElse { e ->
                // SecurityException = notification access not granted yet (treated as no session).
                Log.d(TAG, "getActiveSessions unavailable: ${e.message}")
                emptyList()
            }

    /**
     * Production [MusicSessionDispatcher] over the live media stack. Transport controls go to the
     * top active session's [MediaController.TransportControls]; volume goes to the music stream via
     * [AudioManager]; the launch-fallback opens the preferred app's launch intent (which grabs audio
     * focus + publishes a session) — the actual control after launch is best-effort on the next
     * gesture, since a freshly-launched app may not have a session in the same instant.
     */
    private inner class SystemMusicSessionDispatcher : MusicSessionDispatcher {
        override fun dispatch(action: MusicController.MusicAction) {
            when (action) {
                MusicController.MusicAction.VOLUME_UP ->
                    audioManager?.adjustStreamVolume(
                        AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                MusicController.MusicAction.VOLUME_DOWN ->
                    audioManager?.adjustStreamVolume(
                        AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                else -> controlTopSession(action)
            }
        }

        override fun launchThenDispatch(packageName: String, action: MusicController.MusicAction) {
            // Launch the preferred app so it grabs audio focus / publishes a session.
            runCatching {
                appContext.packageManager.getLaunchIntentForPackage(packageName)?.let { intent ->
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    appContext.startActivity(intent)
                }
            }.onFailure { Log.w(TAG, "launch of $packageName failed", it) }
            // Best-effort immediate control (the just-launched app may not have a session yet; the
            // user's next gesture then finds an active session and dispatches directly).
            controlTopSession(action)
        }

        /** Apply a transport [action] to the top active session, if any. */
        private fun controlTopSession(action: MusicController.MusicAction) {
            val controls = activeSessions().firstOrNull()?.transportControls ?: return
            when (action) {
                MusicController.MusicAction.TOGGLE_PLAY_PAUSE -> {
                    // Toggle off the current playback state of the top session.
                    val playing = activeSessions().firstOrNull()?.playbackState?.state ==
                        android.media.session.PlaybackState.STATE_PLAYING
                    if (playing) controls.pause() else controls.play()
                }
                MusicController.MusicAction.PLAY -> controls.play()
                MusicController.MusicAction.PAUSE -> controls.pause()
                MusicController.MusicAction.NEXT -> controls.skipToNext()
                MusicController.MusicAction.PREVIOUS -> controls.skipToPrevious()
                // Volume is handled by the AudioManager branch above, never here.
                MusicController.MusicAction.VOLUME_UP,
                MusicController.MusicAction.VOLUME_DOWN -> { /* unreachable */ }
            }
        }
    }

    private companion object {
        private const val TAG = "FossilQ-Music"

        /** WP12: the music dispatch is wired (onEventJson → MediaController/AudioManager). */
        const val MUSIC_DISPATCH_WIRED = MusicSessionDispatcher.MUSIC_DISPATCH_WIRED
    }
}
