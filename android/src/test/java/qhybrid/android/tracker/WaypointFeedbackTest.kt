package qhybrid.android.tracker

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import qhybrid.android.db.WatchEntity
import qhybrid.android.db.WatchRepository
import qhybrid.android.notifications.VibePatterns

/**
 * THREE-BUZZ waypoint feedback (a LOG_WAYPOINT button / a TRACKER SHORT|DOUBLE gesture):
 *   - RECEIVED (immediate): soft single (EMAIL) = "phone got your press",
 *   - SUCCESS  (GPS fix resolved + row saved): long (ONE_LONG),
 *   - FAILURE  (no fix / no permission): soft double (TEXT).
 * Mirrors the timer's received+confirm, plus a distinct failure so a field user knows whether the
 * waypoint actually recorded.
 */
@RunWith(RobolectricTestRunner::class)
class WaypointFeedbackTest {

    private class FakeLocation(private val fix: LocationSource.Fix?) : LocationSource {
        override fun currentFix(): LocationSource.Fix? = fix
    }

    private fun logWaypointButtonJson() =
        // The 0x08 RING_PHONE button event shape FossilQAdapter emits; routed to a LOG_WAYPOINT
        // mapping below. Button MIDDLE.
        """{"type":"button","button":"MIDDLE","app":"RING_PHONE","variant":"STANDARD",""" +
            """"declarationId":3073,"eventId":32,"sequence":1,"gesture":"SINGLE"}"""

    private fun seedActiveWatchWithLogWaypointMiddleButton() {
        val repo = WatchRepository(ApplicationProvider.getApplicationContext())
        runBlocking {
            repo.upsertWatch(
                WatchEntity(
                    macAddress = "AA:00:00:00:00:01", name = "Test", model = null,
                    firmwareVersion = null, batteryLevel = 50, isActive = true,
                )
            )
            // Map MIDDLE (0x20) -> LOG_WAYPOINT so onButtonEventJson resolves a waypoint action.
            repo.upsertButton(
                qhybrid.android.db.ButtonMappingEntity(
                    watchMac = "AA:00:00:00:00:01",
                    buttonId = qhybrid.android.buttons.ButtonSlots.MIDDLE,
                    modeType = "SINGLE_ACTION",
                    actionsJson = """[{"action":"LOG_WAYPOINT"}]""",
                )
            )
        }
    }

    private fun dispatch(fix: LocationSource.Fix?, buzzes: MutableList<Int>) =
        ServiceTrackerDispatch(
            context = ApplicationProvider.getApplicationContext(),
            location = FakeLocation(fix),
            io = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            buzzEffect = { p -> synchronized(buzzes) { buzzes.add(p) } },
        )

    private fun awaitBuzzes(buzzes: MutableList<Int>, n: Int) = runBlocking {
        kotlinx.coroutines.withTimeout(5_000) {
            while (synchronized(buzzes) { buzzes.size } < n) kotlinx.coroutines.delay(10)
        }
    }

    @Test
    fun gpsFix_received_thenSuccessLongBuzz() {
        seedActiveWatchWithLogWaypointMiddleButton()
        val buzzes = mutableListOf<Int>()
        val d = dispatch(LocationSource.Fix(48.85, 2.29, 5f, 1L), buzzes)

        d.onButtonEventJson(logWaypointButtonJson())

        awaitBuzzes(buzzes, 2)
        // 1: received soft single (EMAIL). 2: success long (ONE_LONG).
        assertEquals(listOf(VibePatterns.EMAIL, VibePatterns.ONE_LONG), buzzes)
    }

    @Test
    fun noGpsFix_received_thenFailureDoubleBuzz() {
        seedActiveWatchWithLogWaypointMiddleButton()
        val buzzes = mutableListOf<Int>()
        val d = dispatch(null, buzzes) // no fix → failure

        d.onButtonEventJson(logWaypointButtonJson())

        awaitBuzzes(buzzes, 2)
        // 1: received soft single (EMAIL). 2: failure soft double (TEXT).
        assertEquals(listOf(VibePatterns.EMAIL, VibePatterns.TEXT), buzzes)
    }
}
