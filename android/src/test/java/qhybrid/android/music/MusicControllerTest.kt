package qhybrid.android.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import qhybrid.android.music.MusicController.Decision
import qhybrid.android.music.MusicController.MusicAction

/**
 * WP12 — unit tests for the PURE music-control core: JSON → [MusicAction] parse (matching the exact
 * strings [qhybrid.protocol.FossilQAdapter.handleMusicEvent] emits) and the "control session vs.
 * launch preferred app vs. no-op" [MusicController.decide] policy. Robolectric only because
 * [MusicController.parse] uses Android's bundled `org.json`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MusicControllerTest {

    private fun music(action: String) =
        """{"type":"music","action":"$action","sequence":7,"timestamp":"2026-01-01T00:00:00Z"}"""

    // ---- parse: the watch's exact JSON action strings ------------------------

    @Test
    fun parsesEveryKnownAction() {
        assertEquals(MusicAction.TOGGLE_PLAY_PAUSE, MusicController.parse(music("TOGGLE_PLAY_PAUSE")))
        assertEquals(MusicAction.PLAY, MusicController.parse(music("PLAY")))
        assertEquals(MusicAction.PAUSE, MusicController.parse(music("PAUSE")))
        assertEquals(MusicAction.NEXT, MusicController.parse(music("NEXT")))
        assertEquals(MusicAction.PREVIOUS, MusicController.parse(music("PREVIOUS")))
        assertEquals(MusicAction.VOLUME_UP, MusicController.parse(music("VOLUME_UP")))
        assertEquals(MusicAction.VOLUME_DOWN, MusicController.parse(music("VOLUME_DOWN")))
    }

    @Test
    fun ignoresNonMusicEvents() {
        // Other adapter event types must NOT be treated as music.
        assertNull(MusicController.parse("""{"type":"battery","state":"charging","level":80}"""))
        assertNull(MusicController.parse("""{"type":"button","button":"TOP","gesture":"SINGLE_PRESS"}"""))
        assertNull(MusicController.parse("""{"type":"notification_control","action":"DISMISS"}"""))
    }

    @Test
    fun ignoresUnknownOrMissingAction() {
        // The adapter emits UNKNOWN_<n> for unmapped bytes — a graceful null no-op.
        assertNull(MusicController.parse(music("UNKNOWN_9")))
        assertNull(MusicController.parse("""{"type":"music","sequence":1}"""))
        assertNull(MusicController.parse("""{"type":"music","action":""}"""))
    }

    @Test
    fun toleratesBlankAndMalformedJson_neverThrows() {
        assertNull(MusicController.parse(null))
        assertNull(MusicController.parse(""))
        assertNull(MusicController.parse("   "))
        assertNull(MusicController.parse("{not json"))
        assertNull(MusicController.parse("not even close"))
    }

    // ---- decide: control active session vs. launch preferred app vs. no-op ---

    private val SPOTIFY = "com.spotify.music"

    @Test
    fun transportActionWithActiveSession_dispatchesDirectly() {
        for (a in listOf(MusicAction.TOGGLE_PLAY_PAUSE, MusicAction.PLAY, MusicAction.PAUSE, MusicAction.NEXT, MusicAction.PREVIOUS)) {
            assertEquals(
                Decision.Dispatch(a),
                MusicController.decide(a, hasActiveSession = true, preferredMusicApp = ""),
            )
        }
    }

    @Test
    fun transportActionWithNoSessionButPreferredApp_launchesThenDispatches() {
        assertEquals(
            Decision.LaunchThenDispatch(SPOTIFY, MusicAction.NEXT),
            MusicController.decide(MusicAction.NEXT, hasActiveSession = false, preferredMusicApp = SPOTIFY),
        )
        // The preferred-app value is trimmed.
        assertEquals(
            Decision.LaunchThenDispatch(SPOTIFY, MusicAction.TOGGLE_PLAY_PAUSE),
            MusicController.decide(MusicAction.TOGGLE_PLAY_PAUSE, hasActiveSession = false, preferredMusicApp = "  $SPOTIFY  "),
        )
    }

    @Test
    fun transportActionWithNoSessionAndNoPreferredApp_isAGracefulNoOp() {
        // MUSIC_APP_NONE is the empty string sentinel → no-op (we refuse to guess an app).
        assertEquals(
            Decision.None("no-session-no-pref"),
            MusicController.decide(MusicAction.NEXT, hasActiveSession = false, preferredMusicApp = ""),
        )
        assertEquals(
            Decision.None("no-session-no-pref"),
            MusicController.decide(MusicAction.PLAY, hasActiveSession = false, preferredMusicApp = "   "),
        )
    }

    @Test
    fun volumeAlwaysDispatchesDirectly_evenWithNoSessionOrApp() {
        // Volume goes to AudioManager.adjustStreamVolume — no session / app launch needed.
        for (v in listOf(MusicAction.VOLUME_UP, MusicAction.VOLUME_DOWN)) {
            assertEquals(Decision.Dispatch(v), MusicController.decide(v, hasActiveSession = false, preferredMusicApp = ""))
            assertEquals(Decision.Dispatch(v), MusicController.decide(v, hasActiveSession = false, preferredMusicApp = SPOTIFY))
            assertEquals(Decision.Dispatch(v), MusicController.decide(v, hasActiveSession = true, preferredMusicApp = ""))
        }
    }
}
