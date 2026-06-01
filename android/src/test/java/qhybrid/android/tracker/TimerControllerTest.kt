package qhybrid.android.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import qhybrid.android.notifications.VibePatterns
import qhybrid.android.settings.SettingsVocabulary
import qhybrid.android.tracker.TimerController.TimerGesture
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * TIMER — unit tests for the PURE timer core on the 0x05 multi-function stream: JSON →
 * [TimerGesture] parse (reusing the EXACT music-action strings the adapter emits), the
 * gesture → minutes/buzz mapping, the nearest-minute rounding, and the absolute ring-time
 * decomposition in [TimerController.decide]. Robolectric only because [TimerController.parse]
 * uses Android's bundled `org.json`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TimerControllerTest {

    private val UTC: ZoneId = ZoneOffset.UTC

    private fun music(action: String) =
        """{"type":"music","action":"$action","sequence":3,"timestamp":"2026-01-01T00:00:00Z"}"""

    /** Epoch millis for a UTC wall-clock HH:MM:SS on 2026-01-01. */
    private fun at(h: Int, m: Int, s: Int = 0): Long =
        Instant.parse("2026-01-01T%02d:%02d:%02dZ".format(h, m, s)).toEpochMilli()

    // ---- parse: the firmware-classified gestures -----------------------------

    @Test
    fun parsesTheThreeMappedGestures() {
        assertEquals(TimerGesture.SHORT, TimerController.parse(music("TOGGLE_PLAY_PAUSE")))
        assertEquals(TimerGesture.DOUBLE, TimerController.parse(music("NEXT")))
        assertEquals(TimerGesture.LONG, TimerController.parse(music("PREVIOUS")))
    }

    @Test
    fun ignoresNonMappedActionsAndOtherTypes() {
        assertNull(TimerController.parse(music("PLAY")))
        assertNull(TimerController.parse(music("VOLUME_UP")))
        assertNull(TimerController.parse("""{"type":"button","button":"TOP"}"""))
        assertNull(TimerController.parse(null))
        assertNull(TimerController.parse("   "))
        assertNull(TimerController.parse("{not json"))
    }

    // ---- gesture → minutes / buzz --------------------------------------------

    @Test
    fun minutesPerGesture() {
        assertEquals(SettingsVocabulary.TIMER_SHORT_MINUTES, TimerController.minutesFor(TimerGesture.SHORT))
        assertEquals(SettingsVocabulary.TIMER_DOUBLE_MINUTES, TimerController.minutesFor(TimerGesture.DOUBLE))
        assertEquals(SettingsVocabulary.TIMER_LONG_MINUTES, TimerController.minutesFor(TimerGesture.LONG))
        assertEquals(3, TimerController.minutesFor(TimerGesture.SHORT))
        assertEquals(5, TimerController.minutesFor(TimerGesture.DOUBLE))
        assertEquals(10, TimerController.minutesFor(TimerGesture.LONG))
    }

    @Test
    fun buzzPerGesture() {
        assertEquals(VibePatterns.ONE_SHORT, TimerController.buzzFor(TimerGesture.SHORT))
        assertEquals(VibePatterns.TWO_SHORT, TimerController.buzzFor(TimerGesture.DOUBLE))
        assertEquals(VibePatterns.THREE_SHORT, TimerController.buzzFor(TimerGesture.LONG))
    }

    // ---- rounding to the nearest minute --------------------------------------

    @Test
    fun roundsToNearestMinute_downWhenUnder30s() {
        // 10:03:20 → 10:03 (20s rounds down).
        assertEquals(at(10, 3), TimerController.roundToNearestMinute(at(10, 3, 20)))
    }

    @Test
    fun roundsToNearestMinute_upWhenOver30s() {
        // 10:03:40 → 10:04 (40s rounds up).
        assertEquals(at(10, 4), TimerController.roundToNearestMinute(at(10, 3, 40)))
    }

    @Test
    fun roundsToNearestMinute_tiesRoundUp() {
        // Exactly 30s rounds up.
        assertEquals(at(10, 4), TimerController.roundToNearestMinute(at(10, 3, 30)))
    }

    // ---- decide: absolute HH:MM ring time ------------------------------------

    @Test
    fun decideShort_at1000_40s_ringsAt1004() {
        // 10:00:40 + 3 min = 10:03:40 → rounds UP to 10:04 (so it rings ~3m20 later).
        val a = TimerController.decide(TimerGesture.SHORT, at(10, 0, 40), UTC)
        assertEquals(10, a.hour)
        assertEquals(4, a.minute)
        assertEquals(3, a.minutesFromNow)
        assertEquals(VibePatterns.ONE_SHORT, a.buzzPattern)
    }

    @Test
    fun decideDouble_at1000_00s_ringsAt1005() {
        val a = TimerController.decide(TimerGesture.DOUBLE, at(10, 0, 0), UTC)
        assertEquals(10, a.hour)
        assertEquals(5, a.minute)
        assertEquals(5, a.minutesFromNow)
    }

    @Test
    fun decideLong_at1000_10s_ringsAt1010() {
        // 10:00:10 + 10 min = 10:10:10 → rounds DOWN to 10:10 (rings ~9m50 later, worst-case early).
        val a = TimerController.decide(TimerGesture.LONG, at(10, 0, 10), UTC)
        assertEquals(10, a.hour)
        assertEquals(10, a.minute)
        assertEquals(10, a.minutesFromNow)
        assertEquals(VibePatterns.THREE_SHORT, a.buzzPattern)
    }

    @Test
    fun decideRollsOverPastMidnight() {
        // 23:58:00 + 5 min = 00:03 the next day (one-shot fires at the next HH:MM occurrence).
        val a = TimerController.decide(TimerGesture.DOUBLE, at(23, 58, 0), UTC)
        assertEquals(0, a.hour)
        assertEquals(3, a.minute)
    }

    @Test
    fun decideRollsOverTheHour() {
        // 10:58:00 + 3 min = 11:01.
        val a = TimerController.decide(TimerGesture.SHORT, at(10, 58, 0), UTC)
        assertEquals(11, a.hour)
        assertEquals(1, a.minute)
    }
}
