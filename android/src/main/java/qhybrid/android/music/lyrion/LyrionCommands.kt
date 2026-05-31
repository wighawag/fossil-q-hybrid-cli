package qhybrid.android.music.lyrion

import org.json.JSONArray
import org.json.JSONObject
import qhybrid.android.music.MusicController.MusicAction
import qhybrid.android.settings.SettingsVocabulary

/**
 * L2 \u2014 the **pure**, unit-testable core of the Lyrion (LMS) music backend: build JSON-RPC request
 * bodies, map a watch [MusicAction] to LMS command params, build the discovery/status queries, and
 * parse the responses. **No I/O** \u2014 the actual HTTP POST lives behind the [LyrionClient] seam, and
 * the routing/dispatch decision lives in the dispatcher (L4). Mirrors the seam discipline of the
 * WP12 [qhybrid.android.music.MusicController] pure layer.
 *
 * **Transport.** LMS exposes one command vocabulary; we use **JSON-RPC over HTTP** (`POST
 * /jsonrpc.js` on the web port, default 9000):
 * `{"id":1,"method":"slim.request","params":["<playerid>",[<cmd>,<arg>,...]]}`. The server echoes
 * the request and returns the data under `result`. A global (no-player) command uses player id `0`.
 *
 * **No watch wire bytes.** This is phone-side interpretation only; the watch's
 * `{"type":"music",...}` event contract is reused unchanged (the LOCAL and LYRION backends share it).
 */
object LyrionCommands {

    /** Player id used for server-global commands/queries (e.g. `players`, `version`). */
    const val GLOBAL_PLAYER = "0"

    /**
     * Build a JSON-RPC `slim.request` body for [playerId] with positional command [params]
     * (e.g. `["play"]`, `["mixer","volume","+5"]`). Percent-escaping is NOT needed for JSON-RPC.
     */
    fun request(playerId: String, params: List<String>): String {
        val arr = JSONArray()
        for (p in params) arr.put(p)
        val all = JSONArray()
        all.put(playerId.ifEmpty { GLOBAL_PLAYER })
        all.put(arr)
        return JSONObject()
            .put("id", 1)
            .put("method", "slim.request")
            .put("params", all)
            .toString()
    }

    /**
     * Map a watch [MusicAction] to the LMS command params (the transport-control table). Volume
     * uses a signed relative step (default [SettingsVocabulary.LYRION_VOLUME_STEP]). PLAY/TOGGLE are
     * intentionally NOT here \u2014 they go through [resolvePlay] (empty-queue fallback) in the dispatcher.
     */
    fun forAction(action: MusicAction, volumeStep: Int = SettingsVocabulary.LYRION_VOLUME_STEP): List<String> =
        when (action) {
            MusicAction.PLAY -> listOf("play")
            MusicAction.PAUSE -> listOf("pause", "1")
            MusicAction.TOGGLE_PLAY_PAUSE -> listOf("pause") // no arg = toggle
            MusicAction.NEXT -> listOf("playlist", "index", "+1")
            MusicAction.PREVIOUS -> listOf("playlist", "index", "-1")
            MusicAction.VOLUME_UP -> listOf("mixer", "volume", "+$volumeStep")
            MusicAction.VOLUME_DOWN -> listOf("mixer", "volume", "-$volumeStep")
        }

    /** `power 1` \u2014 wake a sleeping player so a subsequent play actually starts. */
    fun powerOn(): List<String> = listOf("power", "1")

    /** Query all players known to the server (id, name, model, connected, power, isplaying). */
    fun playersQuery(): List<String> = listOf("players", "0", "999")

    /** Query a player's status; the default `tags` keep it cheap (we mainly want playlist_tracks). */
    fun statusQuery(): List<String> = listOf("status", "-", "1", "tags:")

    /** Query the server's favourites (audio items: id + name). */
    fun favoritesQuery(): List<String> = listOf("favorites", "items", "0", "999")

    /**
     * Resolve the params for a PLAY/TOGGLE when the queue may be empty. With a non-empty queue we
     * just `play` (resume). With an EMPTY queue we apply the configured empty-queue fallback so a
     * gesture can *start* music from nothing:
     *   - FAVORITE \u2192 play the configured favourite; if none set, degrade to RANDOM,
     *   - RANDOM   \u2192 `randomplay tracks`,
     *   - NONE     \u2192 passive `play` (no-op on an empty queue).
     */
    fun resolvePlay(queueEmpty: Boolean, fallback: String, favoriteId: String): List<String> {
        if (!queueEmpty) return listOf("play")
        return when (SettingsVocabulary.normalizeLyrionFallback(fallback)) {
            SettingsVocabulary.LYRION_FALLBACK_NONE -> listOf("play")
            SettingsVocabulary.LYRION_FALLBACK_RANDOM -> listOf("randomplay", "tracks")
            else -> { // FAVORITE
                val fav = SettingsVocabulary.normalizeLyrionFavoriteId(favoriteId)
                if (fav.isNotEmpty()) listOf("favorites", "playlist", "play", "item_id:$fav")
                else listOf("randomplay", "tracks") // graceful degrade when no favourite configured
            }
        }
    }

    /** A discovered Lyrion player. */
    data class LyrionPlayer(
        val id: String,
        val name: String,
        val model: String = "",
        val connected: Boolean = true,
        val powered: Boolean = true,
    )

    /** A Lyrion server favourite (audio item). */
    data class LyrionFavorite(val id: String, val name: String)

    /**
     * Parse the `players` response into a list (id + name required; rest best-effort). Tolerant of
     * the JSON-RPC envelope (`{"result":{...}}`) or a bare result object. Never throws \u2192 empty list.
     */
    fun parsePlayers(responseJson: String?): List<LyrionPlayer> {
        val result = resultObject(responseJson) ?: return emptyList()
        val loop = result.optJSONArray("players_loop") ?: return emptyList()
        val out = ArrayList<LyrionPlayer>(loop.length())
        for (i in 0 until loop.length()) {
            val o = loop.optJSONObject(i) ?: continue
            val id = o.optString("playerid").trim()
            if (id.isEmpty()) continue
            out.add(
                LyrionPlayer(
                    id = id,
                    name = o.optString("name").ifEmpty { id },
                    model = o.optString("model"),
                    connected = o.optInt("connected", 1) != 0,
                    powered = o.optInt("power", 1) != 0,
                )
            )
        }
        return out
    }

    /**
     * Parse the `favorites items` response into a list. LMS returns items under `loop_loop`; we keep
     * only entries that have an id (audio items have `isaudio:1`, but folders are still browsable so
     * we keep all id-bearing entries and let the UI filter). Never throws \u2192 empty list.
     */
    fun parseFavorites(responseJson: String?): List<LyrionFavorite> {
        val result = resultObject(responseJson) ?: return emptyList()
        val loop = result.optJSONArray("loop_loop") ?: return emptyList()
        val out = ArrayList<LyrionFavorite>(loop.length())
        for (i in 0 until loop.length()) {
            val o = loop.optJSONObject(i) ?: continue
            val id = o.optString("id").trim()
            if (id.isEmpty()) continue
            out.add(LyrionFavorite(id = id, name = o.optString("name").ifEmpty { id }))
        }
        return out
    }

    /**
     * Whether the player's current queue is empty, from a `status` response (`playlist_tracks`).
     * A missing/zero count = empty. Never throws \u2192 treated as empty (so a fresh/unknown player still
     * triggers the start-music fallback rather than a silent no-op).
     */
    fun parseQueueEmpty(statusJson: String?): Boolean {
        val result = resultObject(statusJson) ?: return true
        return result.optInt("playlist_tracks", 0) <= 0
    }

    /** Unwrap the JSON-RPC `result` object (or treat a bare object as the result). Null on garbage. */
    private fun resultObject(json: String?): JSONObject? {
        val raw = json?.trim().orEmpty()
        if (raw.isEmpty()) return null
        return runCatching {
            val obj = JSONObject(raw)
            obj.optJSONObject("result") ?: obj
        }.getOrNull()
    }
}
