package qhybrid.android.music.lyrion

import android.util.Log
import qhybrid.android.music.MusicController.MusicAction
import qhybrid.android.music.MusicSessionDispatcher

/**
 * L4 \u2014 the Lyrion (LMS) implementation of the WP12 [MusicSessionDispatcher] seam: turn a watch
 * [MusicAction] into LMS JSON-RPC commands sent to the configured player over [LyrionClient]. Sibling
 * of the local [qhybrid.android.music.ServiceMusicDispatch.SystemMusicSessionDispatcher]; which one
 * runs is chosen by the active multi-function mode (L5).
 *
 * **Config is read fresh per dispatch** via the injected [config] snapshot (production reads it from
 * `SharedPreferencesSettingsPrefs`), so changing the server/player/fallback in Settings takes effect
 * immediately. The actual HTTP is the injected [client] seam (production [HttpLyrionClient]); tests
 * inject a fake client + a fixed config so command sequencing is verified without a live server.
 *
 * **Start-music semantics.** For PLAY / TOGGLE_PLAY_PAUSE we:
 *   1. send `power 1` (wake a sleeping player),
 *   2. read the player `status` to learn whether the queue is empty,
 *   3. send the [LyrionCommands.resolvePlay] result \u2014 resume if there's a queue, else start the
 *      configured empty-queue fallback (favourite / random / none).
 * NEXT / PREVIOUS / VOLUME map straight through [LyrionCommands.forAction].
 *
 * **Never throws** on the caller's thread; a missing host/player is a quiet no-op (logged at debug).
 * Adds NO watch wire bytes.
 */
class LyrionMusicSessionDispatcher(
    private val client: LyrionClient,
    private val config: () -> Config,
) : MusicSessionDispatcher {

    /** Snapshot of the Lyrion connection + target config for one dispatch. */
    data class Config(
        val host: String,
        val port: Int,
        val playerId: String,
        val emptyQueueFallback: String,
        val favoriteId: String,
    )

    override fun dispatch(action: MusicAction) {
        val c = config()
        if (c.host.isBlank() || c.playerId.isBlank()) {
            Log.d(TAG, "Lyrion not configured (host/player) — ignoring $action")
            return
        }
        when (action) {
            MusicAction.PLAY, MusicAction.TOGGLE_PLAY_PAUSE -> startOrToggle(c, action)
            else -> send(c, LyrionCommands.forAction(action))
        }
    }

    /**
     * For LMS there is no \"app to launch\" — treat the launch-fallback the same as a direct dispatch
     * (the local-backend launch concept doesn't apply; the player is already a network endpoint).
     */
    override fun launchThenDispatch(packageName: String, action: MusicAction) = dispatch(action)

    /**
     * PLAY / TOGGLE: power on, inspect the queue, then resume or start the fallback. TOGGLE only
     * starts/resumes here when the queue would otherwise be empty or stopped; for a populated queue
     * we honour the toggle semantics by sending `pause` (no-arg toggle) instead of forcing play, so a
     * playing track still pauses. PLAY always plays/starts.
     */
    private fun startOrToggle(c: Config, action: MusicAction) {
        send(c, LyrionCommands.powerOn())
        val status = send(c, LyrionCommands.statusQuery())
        val queueEmpty = LyrionCommands.parseQueueEmpty(status)
        if (action == MusicAction.TOGGLE_PLAY_PAUSE && !queueEmpty) {
            // Populated queue: a real toggle (pause if playing, resume if paused).
            send(c, LyrionCommands.forAction(MusicAction.TOGGLE_PLAY_PAUSE))
            return
        }
        // PLAY, or TOGGLE on an empty queue: start music (resume / favourite / random).
        send(c, LyrionCommands.resolvePlay(queueEmpty, c.emptyQueueFallback, c.favoriteId))
    }

    /** Build + POST a command for the configured player; returns the raw response (or null). */
    private fun send(c: Config, params: List<String>): String? {
        val body = LyrionCommands.request(c.playerId, params)
        val resp = client.post(c.host, c.port, body)
        Log.d(TAG, "LMS $params -> ${if (resp != null) "ok" else "no-response"}")
        return resp
    }

    private companion object {
        const val TAG = "FossilQ-Lyrion"
    }
}
