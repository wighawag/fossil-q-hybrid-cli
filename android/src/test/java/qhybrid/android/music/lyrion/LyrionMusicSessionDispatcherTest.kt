package qhybrid.android.music.lyrion

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import qhybrid.android.music.MusicController.MusicAction

/**
 * L4 \u2014 unit tests for [LyrionMusicSessionDispatcher] over a fake [LyrionClient]. Verifies the
 * command SEQUENCE per action (incl. power-on + status + empty-queue fallback for PLAY/TOGGLE) and
 * the configured/unconfigured no-op behaviour. Robolectric only for `org.json`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LyrionMusicSessionDispatcherTest {

    /** Records the command params of each POST; returns a programmable status for `status` queries. */
    private class FakeClient(private val playlistTracks: Int) : LyrionClient {
        val sent = mutableListOf<List<String>>()
        override fun post(host: String, port: Int, body: String): String? {
            val cmd = JSONObject(body).getJSONArray("params").getJSONArray(1)
            val params = (0 until cmd.length()).map { cmd.getString(it) }
            sent.add(params)
            return if (params.firstOrNull() == "status")
                """{"result":{"playlist_tracks":$playlistTracks}}"""
            else """{"result":{}}"""
        }
    }

    private fun cfg(
        host: String = "192.168.1.10",
        port: Int = 9000,
        player: String = "00:04:20:aa:bb:cc",
        fallback: String = "FAVORITE",
        favorite: String = "fav42",
    ) = LyrionMusicSessionDispatcher.Config(host, port, player, fallback, favorite)

    @Test
    fun next_sendsSinglePlaylistIndexCommand() {
        val fake = FakeClient(playlistTracks = 3)
        val d = LyrionMusicSessionDispatcher(fake) { cfg() }
        d.dispatch(MusicAction.NEXT)
        assertEquals(listOf(listOf("playlist", "index", "+1")), fake.sent)
    }

    @Test
    fun volume_sendsSignedMixerCommand() {
        val fake = FakeClient(playlistTracks = 3)
        val d = LyrionMusicSessionDispatcher(fake) { cfg() }
        d.dispatch(MusicAction.VOLUME_UP)
        d.dispatch(MusicAction.VOLUME_DOWN)
        assertEquals("mixer", fake.sent[0][0])
        assertTrue(fake.sent[0][2].startsWith("+"))
        assertTrue(fake.sent[1][2].startsWith("-"))
    }

    @Test
    fun play_populatedQueue_powersOnThenStatusThenPlay() {
        val fake = FakeClient(playlistTracks = 5)
        val d = LyrionMusicSessionDispatcher(fake) { cfg() }
        d.dispatch(MusicAction.PLAY)
        assertEquals(
            listOf(
                listOf("power", "1"),
                listOf("status", "-", "1", "tags:"),
                listOf("play"),
            ),
            fake.sent,
        )
    }

    @Test
    fun play_emptyQueue_startsConfiguredFavourite() {
        val fake = FakeClient(playlistTracks = 0)
        val d = LyrionMusicSessionDispatcher(fake) { cfg(fallback = "FAVORITE", favorite = "fav42") }
        d.dispatch(MusicAction.PLAY)
        assertEquals(listOf("power", "1"), fake.sent[0])
        assertEquals(listOf("status", "-", "1", "tags:"), fake.sent[1])
        assertEquals(listOf("favorites", "playlist", "play", "item_id:fav42"), fake.sent[2])
    }

    @Test
    fun play_emptyQueue_randomFallback() {
        val fake = FakeClient(playlistTracks = 0)
        val d = LyrionMusicSessionDispatcher(fake) { cfg(fallback = "RANDOM") }
        d.dispatch(MusicAction.PLAY)
        assertEquals(listOf("randomplay", "tracks"), fake.sent.last())
    }

    @Test
    fun toggle_populatedQueue_sendsNoArgPauseToggle() {
        val fake = FakeClient(playlistTracks = 2)
        val d = LyrionMusicSessionDispatcher(fake) { cfg() }
        d.dispatch(MusicAction.TOGGLE_PLAY_PAUSE)
        // power, status, then a bare "pause" (toggle).
        assertEquals(listOf("pause"), fake.sent.last())
    }

    @Test
    fun toggle_emptyQueue_startsFallback() {
        val fake = FakeClient(playlistTracks = 0)
        val d = LyrionMusicSessionDispatcher(fake) { cfg(fallback = "RANDOM") }
        d.dispatch(MusicAction.TOGGLE_PLAY_PAUSE)
        assertEquals(listOf("randomplay", "tracks"), fake.sent.last())
    }

    @Test
    fun unconfigured_isNoOp() {
        val noHost = FakeClient(playlistTracks = 3)
        LyrionMusicSessionDispatcher(noHost) { cfg(host = "") }.dispatch(MusicAction.NEXT)
        assertTrue(noHost.sent.isEmpty())

        val noPlayer = FakeClient(playlistTracks = 3)
        LyrionMusicSessionDispatcher(noPlayer) { cfg(player = "") }.dispatch(MusicAction.PLAY)
        assertTrue(noPlayer.sent.isEmpty())
    }

    @Test
    fun launchThenDispatch_behavesLikeDispatch() {
        val fake = FakeClient(playlistTracks = 3)
        val d = LyrionMusicSessionDispatcher(fake) { cfg() }
        d.launchThenDispatch("ignored.pkg", MusicAction.NEXT)
        assertEquals(listOf(listOf("playlist", "index", "+1")), fake.sent)
    }
}
