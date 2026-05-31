package qhybrid.android.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import qhybrid.android.music.MusicController.Decision
import qhybrid.android.music.MusicController.MusicAction

/**
 * WP12 — unit tests for the dispatch glue ([MusicDispatcher]): it parses the watch's `onEventJson`,
 * reads the injected "active session?" + preferred-app snapshots, runs the pure decider, and routes
 * to the injected media-stack seam. Verified with a fake seam (no Android media stack, no BLE).
 * Robolectric only because parsing uses Android's bundled `org.json`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MusicDispatcherTest {

    private val SPOTIFY = "com.spotify.music"

    private class FakeSeam : MusicSessionDispatcher {
        val dispatched = mutableListOf<MusicAction>()
        val launched = mutableListOf<Pair<String, MusicAction>>()
        override fun dispatch(action: MusicAction) { dispatched += action }
        override fun launchThenDispatch(packageName: String, action: MusicAction) {
            launched += packageName to action
        }
    }

    private fun music(action: String) =
        """{"type":"music","action":"$action","sequence":1,"timestamp":"t"}"""

    @Test
    fun activeSession_transportActionDispatchesToSeam() {
        val seam = FakeSeam()
        val d = MusicDispatcher(hasActiveSession = { true }, preferredMusicApp = { "" }, dispatcher = seam)
        val decision = d.onEventJson(music("NEXT"))
        assertEquals(Decision.Dispatch(MusicAction.NEXT), decision)
        assertEquals(listOf(MusicAction.NEXT), seam.dispatched)
        assertTrue(seam.launched.isEmpty())
    }

    @Test
    fun noSessionWithPreferredApp_launchesThenDispatches() {
        val seam = FakeSeam()
        val d = MusicDispatcher(hasActiveSession = { false }, preferredMusicApp = { SPOTIFY }, dispatcher = seam)
        val decision = d.onEventJson(music("TOGGLE_PLAY_PAUSE"))
        assertEquals(Decision.LaunchThenDispatch(SPOTIFY, MusicAction.TOGGLE_PLAY_PAUSE), decision)
        assertEquals(listOf(SPOTIFY to MusicAction.TOGGLE_PLAY_PAUSE), seam.launched)
        assertTrue(seam.dispatched.isEmpty())
    }

    @Test
    fun noSessionNoPreferredApp_isAGracefulNoOp() {
        val seam = FakeSeam()
        val d = MusicDispatcher(hasActiveSession = { false }, preferredMusicApp = { "" }, dispatcher = seam)
        val decision = d.onEventJson(music("PLAY"))
        assertEquals(Decision.None("no-session-no-pref"), decision)
        assertTrue(seam.dispatched.isEmpty())
        assertTrue(seam.launched.isEmpty())
    }

    @Test
    fun volume_dispatchesDirectlyEvenWithNoSession() {
        val seam = FakeSeam()
        val d = MusicDispatcher(hasActiveSession = { false }, preferredMusicApp = { "" }, dispatcher = seam)
        d.onEventJson(music("VOLUME_UP"))
        d.onEventJson(music("VOLUME_DOWN"))
        assertEquals(listOf(MusicAction.VOLUME_UP, MusicAction.VOLUME_DOWN), seam.dispatched)
        assertTrue(seam.launched.isEmpty())
    }

    @Test
    fun nonMusicOrMalformedJson_isIgnored_neverTouchesSeam() {
        val seam = FakeSeam()
        val d = MusicDispatcher(hasActiveSession = { true }, preferredMusicApp = { SPOTIFY }, dispatcher = seam)
        assertNull(d.onEventJson("""{"type":"battery","level":50}"""))
        assertNull(d.onEventJson(music("UNKNOWN_9")))
        assertNull(d.onEventJson("{not json"))
        assertNull(d.onEventJson(null))
        assertTrue(seam.dispatched.isEmpty())
        assertTrue(seam.launched.isEmpty())
    }

    @Test
    fun liveSnapshotsAreReadEachEvent() {
        // The lambdas are read per-event: a session that becomes active mid-stream changes routing.
        val seam = FakeSeam()
        var active = false
        val d = MusicDispatcher(hasActiveSession = { active }, preferredMusicApp = { SPOTIFY }, dispatcher = seam)
        d.onEventJson(music("NEXT"))            // no session yet → launch fallback
        active = true
        d.onEventJson(music("NEXT"))            // now active → direct dispatch
        assertEquals(listOf(SPOTIFY to MusicAction.NEXT), seam.launched)
        assertEquals(listOf(MusicAction.NEXT), seam.dispatched)
    }
}
