package qhybrid.android.tracker

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import qhybrid.android.settings.SettingsVocabulary
import qhybrid.android.tracker.EventRouter.Route

/**
 * WP-TRACKER — unit tests for the PURE onEventJson routing rule ([EventRouter]) that the service
 * mirrors: route by event TYPE first, then for the button-blind 0x05 music stream by the GLOBAL
 * multi-function role. Robolectric only because it uses Android's bundled `org.json`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EventRouterTest {

    private val MUSIC = SettingsVocabulary.MULTI_FUNCTION_ROLE_MUSIC
    private val TRACKER = SettingsVocabulary.MULTI_FUNCTION_ROLE_TRACKER

    private fun music(action: String) = """{"type":"music","action":"$action","sequence":1}"""
    private fun button(b: String) =
        """{"type":"button","button":"$b","app":"RING_PHONE","gesture":"SINGLE"}"""

    @Test
    fun musicStream_routesToMusicWhenRoleMusic() {
        assertEquals(Route.Music, EventRouter.route(music("NEXT"), MUSIC))
        assertEquals(Route.Music, EventRouter.route(music("TOGGLE_PLAY_PAUSE"), MUSIC))
    }

    @Test
    fun musicStream_routesToTrackerWhenRoleTracker() {
        assertEquals(Route.Tracker, EventRouter.route(music("NEXT"), TRACKER))
        assertEquals(Route.Tracker, EventRouter.route(music("PREVIOUS"), TRACKER))
    }

    @Test
    fun musicStream_neverRoutesToBoth() {
        // The role picks EXACTLY ONE branch for a 0x05 event.
        val asMusic = EventRouter.route(music("NEXT"), MUSIC)
        val asTracker = EventRouter.route(music("NEXT"), TRACKER)
        assertEquals(Route.Music, asMusic)
        assertEquals(Route.Tracker, asTracker)
    }

    @Test
    fun buttonStream_alwaysRoutesToPath2_regardlessOfRole() {
        // The 0x08 button-aware path is its own event type — the global role does not affect it.
        assertEquals(Route.ButtonPath2, EventRouter.route(button("TOP"), MUSIC))
        assertEquals(Route.ButtonPath2, EventRouter.route(button("BOTTOM"), TRACKER))
    }

    @Test
    fun unknownRoleDefaultsToMusic() {
        assertEquals(Route.Music, EventRouter.route(music("NEXT"), "BOGUS"))
        assertEquals(Route.Music, EventRouter.route(music("NEXT"), ""))
    }

    @Test
    fun otherEventTypesAndMalformed_ignored() {
        assertEquals(Route.Ignore, EventRouter.route("""{"type":"battery","level":50}""", TRACKER))
        assertEquals(Route.Ignore, EventRouter.route("{not json", TRACKER))
        assertEquals(Route.Ignore, EventRouter.route(null, MUSIC))
        assertEquals(Route.Ignore, EventRouter.route("   ", MUSIC))
    }

    // ---- L0: mode-aware routing (configurable rotation) ----------------------

    private val PHONE = SettingsVocabulary.MODE_MUSIC_PHONE
    private val LYRION = SettingsVocabulary.MODE_MUSIC_LYRION
    private val TRACKER_MODE = SettingsVocabulary.MODE_TRACKER
    private val TIMER_MODE = SettingsVocabulary.MODE_TIMER

    @Test
    fun routeForMode_bothMusicModesYieldMusic() {
        // Phone vs Lyrion is decided downstream; both route to Music here.
        assertEquals(Route.Music, EventRouter.routeForMode(music("NEXT"), PHONE))
        assertEquals(Route.Music, EventRouter.routeForMode(music("NEXT"), LYRION))
        assertEquals(Route.Music, EventRouter.routeForMode(music("TOGGLE_PLAY_PAUSE"), LYRION))
    }

    @Test
    fun routeForMode_trackerModeYieldsTracker() {
        assertEquals(Route.Tracker, EventRouter.routeForMode(music("NEXT"), TRACKER_MODE))
        assertEquals(Route.Tracker, EventRouter.routeForMode(music("PREVIOUS"), TRACKER_MODE))
    }

    @Test
    fun routeForMode_legacyAndUnknownFoldToMusicPhone() {
        // Legacy "MUSIC" and any unknown mode behave as a music mode (→ Route.Music).
        assertEquals(Route.Music, EventRouter.routeForMode(music("NEXT"), "MUSIC"))
        assertEquals(Route.Music, EventRouter.routeForMode(music("NEXT"), "BOGUS"))
        assertEquals(Route.Music, EventRouter.routeForMode(music("NEXT"), ""))
    }

    @Test
    fun routeForMode_timerModeYieldsTimer() {
        assertEquals(Route.Timer, EventRouter.routeForMode(music("TOGGLE_PLAY_PAUSE"), TIMER_MODE))
        assertEquals(Route.Timer, EventRouter.routeForMode(music("NEXT"), TIMER_MODE))
        assertEquals(Route.Timer, EventRouter.routeForMode(music("PREVIOUS"), TIMER_MODE))
        // The button stream is unaffected by the timer mode (its own event type).
        assertEquals(Route.ButtonPath2, EventRouter.routeForMode(button("TOP"), TIMER_MODE))
    }

    @Test
    fun routeForMode_buttonAndMalformedUnaffectedByMode() {
        assertEquals(Route.ButtonPath2, EventRouter.routeForMode(button("TOP"), PHONE))
        assertEquals(Route.ButtonPath2, EventRouter.routeForMode(button("BOTTOM"), TRACKER_MODE))
        assertEquals(Route.Ignore, EventRouter.routeForMode("{not json", LYRION))
        assertEquals(Route.Ignore, EventRouter.routeForMode(null, LYRION))
    }
}
