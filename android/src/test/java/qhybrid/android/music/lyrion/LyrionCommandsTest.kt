package qhybrid.android.music.lyrion

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import qhybrid.android.music.MusicController.MusicAction
import qhybrid.android.settings.SettingsVocabulary

/**
 * L2 \u2014 unit tests for the PURE Lyrion command/parse layer ([LyrionCommands]). Robolectric only
 * because it uses Android's bundled `org.json`. No network, no Android media stack.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LyrionCommandsTest {

    // ---- request body --------------------------------------------------------

    @Test
    fun requestBuildsJsonRpcEnvelope() {
        val body = LyrionCommands.request("00:04:20:ab:cd:ef", listOf("mixer", "volume", "+5"))
        val o = JSONObject(body)
        assertEquals("slim.request", o.getString("method"))
        val params = o.getJSONArray("params")
        assertEquals("00:04:20:ab:cd:ef", params.getString(0))
        val cmd = params.getJSONArray(1)
        assertEquals("mixer", cmd.getString(0))
        assertEquals("volume", cmd.getString(1))
        assertEquals("+5", cmd.getString(2))
    }

    @Test
    fun requestBlankPlayerBecomesGlobalZero() {
        val body = LyrionCommands.request("", listOf("players", "0", "999"))
        val params = JSONObject(body).getJSONArray("params")
        assertEquals(LyrionCommands.GLOBAL_PLAYER, params.getString(0))
    }

    // ---- action mapping ------------------------------------------------------

    @Test
    fun forActionMapsTransportControls() {
        assertEquals(listOf("play"), LyrionCommands.forAction(MusicAction.PLAY))
        assertEquals(listOf("pause", "1"), LyrionCommands.forAction(MusicAction.PAUSE))
        assertEquals(listOf("pause"), LyrionCommands.forAction(MusicAction.TOGGLE_PLAY_PAUSE))
        assertEquals(listOf("playlist", "index", "+1"), LyrionCommands.forAction(MusicAction.NEXT))
        assertEquals(listOf("playlist", "index", "-1"), LyrionCommands.forAction(MusicAction.PREVIOUS))
    }

    @Test
    fun forActionVolumeUsesSignedStep() {
        assertEquals(
            listOf("mixer", "volume", "+${SettingsVocabulary.LYRION_VOLUME_STEP}"),
            LyrionCommands.forAction(MusicAction.VOLUME_UP),
        )
        assertEquals(
            listOf("mixer", "volume", "-${SettingsVocabulary.LYRION_VOLUME_STEP}"),
            LyrionCommands.forAction(MusicAction.VOLUME_DOWN),
        )
        // Custom step honoured.
        assertEquals(listOf("mixer", "volume", "+10"), LyrionCommands.forAction(MusicAction.VOLUME_UP, 10))
    }

    @Test
    fun powerOnAndQueries() {
        assertEquals(listOf("power", "1"), LyrionCommands.powerOn())
        assertEquals(listOf("players", "0", "999"), LyrionCommands.playersQuery())
        assertEquals(listOf("favorites", "items", "0", "999"), LyrionCommands.favoritesQuery())
        assertEquals(listOf("status", "-", "1", "tags:"), LyrionCommands.statusQuery())
    }

    // ---- empty-queue fallback ------------------------------------------------

    @Test
    fun resolvePlay_nonEmptyQueueJustPlays() {
        assertEquals(
            listOf("play"),
            LyrionCommands.resolvePlay(queueEmpty = false, fallback = "RANDOM", favoriteId = ""),
        )
    }

    @Test
    fun resolvePlay_emptyQueueFavorite() {
        assertEquals(
            listOf("favorites", "playlist", "play", "item_id:fav42"),
            LyrionCommands.resolvePlay(queueEmpty = true, fallback = "FAVORITE", favoriteId = "fav42"),
        )
    }

    @Test
    fun resolvePlay_emptyQueueFavoriteDegradesToRandomWhenUnset() {
        assertEquals(
            listOf("randomplay", "tracks"),
            LyrionCommands.resolvePlay(queueEmpty = true, fallback = "FAVORITE", favoriteId = ""),
        )
    }

    @Test
    fun resolvePlay_emptyQueueRandomAndNone() {
        assertEquals(
            listOf("randomplay", "tracks"),
            LyrionCommands.resolvePlay(queueEmpty = true, fallback = "RANDOM", favoriteId = "fav42"),
        )
        assertEquals(
            listOf("play"),
            LyrionCommands.resolvePlay(queueEmpty = true, fallback = "NONE", favoriteId = "fav42"),
        )
        // Unknown fallback folds to FAVORITE; with no favourite it degrades to RANDOM.
        assertEquals(
            listOf("randomplay", "tracks"),
            LyrionCommands.resolvePlay(queueEmpty = true, fallback = "BOGUS", favoriteId = ""),
        )
    }

    // ---- parsing -------------------------------------------------------------

    @Test
    fun parsePlayersFromEnvelope() {
        val json = """
            {"id":1,"result":{"count":2,"players_loop":[
              {"playerid":"00:04:20:aa:bb:cc","name":"Kitchen","model":"baby","connected":1,"power":1,"isplaying":1},
              {"playerid":"00:04:20:dd:ee:ff","name":"Office","model":"squeezelite","connected":0,"power":0}
            ]}}
        """.trimIndent()
        val players = LyrionCommands.parsePlayers(json)
        assertEquals(2, players.size)
        assertEquals("00:04:20:aa:bb:cc", players[0].id)
        assertEquals("Kitchen", players[0].name)
        assertEquals("baby", players[0].model)
        assertTrue(players[0].connected)
        assertTrue(players[0].powered)
        assertEquals("Office", players[1].name)
        assertFalse(players[1].connected)
        assertFalse(players[1].powered)
    }

    @Test
    fun parsePlayersTolerantOfGarbageAndEmpty() {
        assertTrue(LyrionCommands.parsePlayers(null).isEmpty())
        assertTrue(LyrionCommands.parsePlayers("not json").isEmpty())
        assertTrue(LyrionCommands.parsePlayers("""{"result":{}}""").isEmpty())
        // Entry with no playerid is skipped; name defaults to the id when absent.
        val json = """{"result":{"players_loop":[{"name":"NoId"},{"playerid":"x"}]}}"""
        val players = LyrionCommands.parsePlayers(json)
        assertEquals(1, players.size)
        assertEquals("x", players[0].id)
        assertEquals("x", players[0].name)
    }

    @Test
    fun parseFavorites() {
        val json = """
            {"result":{"count":2,"loop_loop":[
              {"id":"fav.1","name":"Morning Mix","isaudio":1},
              {"id":"fav.2","name":"Jazz"}
            ]}}
        """.trimIndent()
        val favs = LyrionCommands.parseFavorites(json)
        assertEquals(2, favs.size)
        assertEquals("fav.1", favs[0].id)
        assertEquals("Morning Mix", favs[0].name)
        assertEquals("Jazz", favs[1].name)
        // Tolerant.
        assertTrue(LyrionCommands.parseFavorites(null).isEmpty())
        assertTrue(LyrionCommands.parseFavorites("""{"result":{}}""").isEmpty())
    }

    @Test
    fun parseQueueEmpty() {
        assertFalse(LyrionCommands.parseQueueEmpty("""{"result":{"playlist_tracks":3}}"""))
        assertTrue(LyrionCommands.parseQueueEmpty("""{"result":{"playlist_tracks":0}}"""))
        // Missing count or garbage = treated as empty (so the start-music fallback fires).
        assertTrue(LyrionCommands.parseQueueEmpty("""{"result":{}}"""))
        assertTrue(LyrionCommands.parseQueueEmpty(null))
        assertTrue(LyrionCommands.parseQueueEmpty("not json"))
    }
}
