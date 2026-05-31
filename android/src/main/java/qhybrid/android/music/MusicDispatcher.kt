package qhybrid.android.music

/**
 * WP12 — the testable glue between the watch's `onEventJson` stream and Android's media stack: it
 * parses a JSON line via the pure [MusicController.parse], reads "is a session active?" + the user's
 * preferred-music-app pref through injected snapshots, runs the pure [MusicController.decide], and
 * forwards the outcome to the injected [MusicSessionDispatcher] seam. Mirrors WP11's
 * [qhybrid.android.notifications.NotificationDispatcher] (pure decide + seam) so the whole path is
 * unit-testable with fakes — no Android media stack, no BLE, no Service.
 *
 * Android-free: [hasActiveSession] + [preferredMusicApp] are injected snapshots (the production
 * shell reads them from `MediaSessionManager` + `SettingsPrefs`), and the dispatch is an injected
 * [MusicSessionDispatcher] (production = [ServiceMusicDispatch.SystemMusicSessionDispatcher],
 * on-device-pending). Adds NO new wire bytes — the JSON contract is already emitted by the adapter.
 */
class MusicDispatcher(
    /** Snapshot: whether Android currently has an active media session we can control. */
    private val hasActiveSession: () -> Boolean,
    /** Snapshot: the preferred music-app package (MUSIC_APP_NONE / blank = unset). */
    private val preferredMusicApp: () -> String,
    /** Media-stack seam — production controls MediaController/AudioManager; tests inject a fake. */
    private val dispatcher: MusicSessionDispatcher,
) {

    /**
     * Handle one `onEventJson` line. Returns the [MusicController.Decision] (for logging/tests), or
     * `null` when the line was not a music event we handle (a graceful no-op). Never throws.
     */
    fun onEventJson(json: String?): MusicController.Decision? {
        val action = MusicController.parse(json) ?: return null
        val decision = MusicController.decide(action, hasActiveSession(), preferredMusicApp())
        when (decision) {
            is MusicController.Decision.Dispatch -> dispatcher.dispatch(decision.action)
            is MusicController.Decision.LaunchThenDispatch ->
                dispatcher.launchThenDispatch(decision.packageName, decision.action)
            is MusicController.Decision.None -> { /* graceful no-op */ }
        }
        return decision
    }
}

/**
 * WP12 — narrow, injectable seam for the actual media-stack dispatch (mirrors WP11's
 * [qhybrid.android.notifications.NotificationPlay]). The production impl
 * ([ServiceMusicDispatch.SystemMusicSessionDispatcher]) talks to
 * [android.media.session.MediaController.TransportControls] + [android.media.AudioManager]; tests
 * inject a fake so the decision path is verified without a live Android media stack.
 *
 * The real impl is **on-device-pending** (it needs a live `MediaSessionManager` + the notification-
 * listener component), but it is cleanly seam-injected so all the routing logic above is unit-tested.
 */
interface MusicSessionDispatcher {
    /** Apply [action] to the currently-active media session (or the music volume stream). */
    fun dispatch(action: MusicController.MusicAction)

    /** Launch [packageName] (so it publishes a session), then apply [action] to it (best-effort). */
    fun launchThenDispatch(packageName: String, action: MusicController.MusicAction)

    companion object {
        /** WP12: whether the on-device media dispatch is wired (true for the production impl). */
        const val MUSIC_DISPATCH_WIRED = true
    }
}

/**
 * A no-op [MusicSessionDispatcher] used as a safe default so callers / tests that don't exercise the
 * media stack never touch it.
 */
object NoopMusicSessionDispatcher : MusicSessionDispatcher {
    override fun dispatch(action: MusicController.MusicAction) {}
    override fun launchThenDispatch(packageName: String, action: MusicController.MusicAction) {}
}
