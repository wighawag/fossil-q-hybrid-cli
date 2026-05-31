package qhybrid.android.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import qhybrid.android.notifications.VibePatterns
import qhybrid.android.tracker.TrackerController.TrackerAction
import qhybrid.android.tracker.TrackerController.TrackerGesture
import qhybrid.android.tracker.TrackerController.WaypointKind

/**
 * WP-TRACKER — unit tests for the PURE tracker core on the 0x05 multi-function stream: JSON →
 * [TrackerGesture] parse (reusing the EXACT music-action strings the adapter emits) + the
 * gesture → [TrackerAction] decision with its buzz-back pattern. Robolectric only because
 * [TrackerController.parse] uses Android's bundled `org.json`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TrackerControllerTest {

    private fun music(action: String) =
        """{"type":"music","action":"$action","sequence":3,"timestamp":"2026-01-01T00:00:00Z"}"""

    // ---- parse: the firmware-classified gestures -----------------------------

    @Test
    fun parsesTheThreeMappedGestures() {
        assertEquals(TrackerGesture.SHORT, TrackerController.parse(music("TOGGLE_PLAY_PAUSE")))
        assertEquals(TrackerGesture.DOUBLE, TrackerController.parse(music("NEXT")))
        assertEquals(TrackerGesture.LONG, TrackerController.parse(music("PREVIOUS")))
    }

    @Test
    fun ignoresNonMappedMusicActions() {
        // volume + play/pause are not tracker gestures (graceful no-op).
        assertNull(TrackerController.parse(music("VOLUME_UP")))
        assertNull(TrackerController.parse(music("VOLUME_DOWN")))
        assertNull(TrackerController.parse(music("PLAY")))
        assertNull(TrackerController.parse(music("PAUSE")))
        assertNull(TrackerController.parse(music("UNKNOWN_9")))
    }

    @Test
    fun ignoresNonMusicEvents() {
        assertNull(TrackerController.parse("""{"type":"battery","state":"charging","level":80}"""))
        assertNull(TrackerController.parse("""{"type":"button","button":"TOP","app":"RING_PHONE","gesture":"SINGLE"}"""))
    }

    @Test
    fun toleratesBlankAndMalformedJson_neverThrows() {
        assertNull(TrackerController.parse(null))
        assertNull(TrackerController.parse(""))
        assertNull(TrackerController.parse("   "))
        assertNull(TrackerController.parse("{not json"))
        assertNull(TrackerController.parse("""{"type":"music","sequence":1}"""))
    }

    // ---- decide: action + buzz-back per gesture ------------------------------

    @Test
    fun shortLogsMinorWithOneShortBuzz() {
        val a = TrackerController.decide(TrackerGesture.SHORT)
        assertEquals(TrackerAction.Log(WaypointKind.MINOR, VibePatterns.ONE_SHORT), a)
        assertEquals(5, a.buzzPattern)
    }

    @Test
    fun doubleLogsMajorWithTwoShortBuzz() {
        val a = TrackerController.decide(TrackerGesture.DOUBLE)
        assertEquals(TrackerAction.Log(WaypointKind.MAJOR, VibePatterns.TWO_SHORT), a)
        assertEquals(6, a.buzzPattern)
    }

    @Test
    fun longRingsPhoneWithOneLongBuzz() {
        val a = TrackerController.decide(TrackerGesture.LONG)
        assertEquals(TrackerAction.RingPhone(VibePatterns.ONE_LONG), a)
        assertEquals(8, a.buzzPattern)
    }

    @Test
    fun gestureForActionMatchesParse() {
        assertEquals(TrackerGesture.SHORT, TrackerController.gestureForAction("TOGGLE_PLAY_PAUSE"))
        assertEquals(TrackerGesture.DOUBLE, TrackerController.gestureForAction("NEXT"))
        assertEquals(TrackerGesture.LONG, TrackerController.gestureForAction("PREVIOUS"))
        assertNull(TrackerController.gestureForAction("VOLUME_UP"))
        assertNull(TrackerController.gestureForAction(null))
    }
}
