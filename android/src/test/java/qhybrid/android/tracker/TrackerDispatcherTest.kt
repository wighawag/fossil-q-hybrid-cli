package qhybrid.android.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import qhybrid.android.notifications.VibePatterns
import qhybrid.android.tracker.TrackerController.TrackerAction
import qhybrid.android.tracker.TrackerController.WaypointKind

/**
 * WP-TRACKER — unit tests for the dispatch glue ([TrackerDispatcher]): it parses the watch's
 * `onEventJson`, runs the pure decider, and routes to the injected [TrackerEffects] seam (record /
 * ring / buzz). Verified with a fake seam (no GPS, no Room, no BLE). Robolectric only because
 * parsing uses Android's bundled `org.json`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TrackerDispatcherTest {

    private class FakeEffects : TrackerEffects {
        val recorded = mutableListOf<WaypointKind>()
        val buzzed = mutableListOf<Int>()
        var rings = 0
        override fun recordWaypoint(kind: WaypointKind) { recorded += kind }
        override fun ringPhone() { rings++ }
        override fun buzzBack(pattern: Int) { buzzed += pattern }
    }

    private fun music(action: String) =
        """{"type":"music","action":"$action","sequence":1,"timestamp":"t"}"""

    @Test
    fun short_logsMinorThenBuzzesFive() {
        val fx = FakeEffects()
        val d = TrackerDispatcher(fx)
        val a = d.onEventJson(music("TOGGLE_PLAY_PAUSE"))
        assertEquals(TrackerAction.Log(WaypointKind.MINOR, VibePatterns.ONE_SHORT), a)
        assertEquals(listOf(WaypointKind.MINOR), fx.recorded)
        assertEquals(listOf(VibePatterns.ONE_SHORT), fx.buzzed)
        assertEquals(0, fx.rings)
    }

    @Test
    fun double_logsMajorThenBuzzesSix() {
        val fx = FakeEffects()
        val d = TrackerDispatcher(fx)
        val a = d.onEventJson(music("NEXT"))
        assertEquals(TrackerAction.Log(WaypointKind.MAJOR, VibePatterns.TWO_SHORT), a)
        assertEquals(listOf(WaypointKind.MAJOR), fx.recorded)
        assertEquals(listOf(VibePatterns.TWO_SHORT), fx.buzzed)
        assertEquals(0, fx.rings)
    }

    @Test
    fun long_ringsPhoneThenBuzzesEight() {
        val fx = FakeEffects()
        val d = TrackerDispatcher(fx)
        val a = d.onEventJson(music("PREVIOUS"))
        assertEquals(TrackerAction.RingPhone(VibePatterns.ONE_LONG), a)
        assertEquals(1, fx.rings)
        assertEquals(listOf(VibePatterns.ONE_LONG), fx.buzzed)
        assertTrue(fx.recorded.isEmpty())
    }

    @Test
    fun nonMappedOrMalformed_isIgnored_neverTouchesSeam() {
        val fx = FakeEffects()
        val d = TrackerDispatcher(fx)
        assertNull(d.onEventJson(music("VOLUME_UP")))
        assertNull(d.onEventJson("""{"type":"battery","level":50}"""))
        assertNull(d.onEventJson("{not json"))
        assertNull(d.onEventJson(null))
        assertTrue(fx.recorded.isEmpty())
        assertTrue(fx.buzzed.isEmpty())
        assertEquals(0, fx.rings)
    }
}
