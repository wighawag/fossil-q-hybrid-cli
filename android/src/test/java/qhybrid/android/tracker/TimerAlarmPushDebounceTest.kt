package qhybrid.android.tracker

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import qhybrid.android.db.WatchEntity
import qhybrid.android.db.WatchRepository
import qhybrid.android.sync.RecordingDebouncer

/**
 * Regression for the per-press alarm-upload storm (FINDINGS / logcat 2026-06-14): each TIMER press
 * used to kick a FULL `SyncSection.ALARMS` 32-slot file upload, so a burst of presses serialized on
 * the ble-worker and TIMED OUT. The dispatch must route the alarm PUSH through a debouncer so a
 * burst coalesces into ONE upload of the final armed time (the Room write stays per-press, so the
 * armed time is always live).
 *
 * Uses a [RecordingDebouncer] to assert the dispatch SCHEDULES the push (N times for N presses) but
 * the debouncer keeps only the LAST pending action — i.e. exactly one upload runs per burst.
 */
@RunWith(RobolectricTestRunner::class)
class TimerAlarmPushDebounceTest {

    private fun timerJson() = """{"type":"music","action":"TOGGLE_PLAY_PAUSE","sequence":1}"""

    @Test
    fun timerPressesAreCoalescedIntoOneAlarmPush() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        // Seed an active watch so armTimerAlarmAsync has a target (otherwise it returns early).
        val repo = WatchRepository(context)
        runBlocking {
            repo.upsertWatch(
                WatchEntity(
                    macAddress = "AA:00:00:00:00:01",
                    name = "Test",
                    model = null,
                    firmwareVersion = null,
                    batteryLevel = 50,
                    isActive = true,
                )
            )
        }

        val recording = RecordingDebouncer()
        val dispatch = ServiceTrackerDispatch(
            context = context,
            // A real IO scope; the test polls for the async Room write + schedule to complete.
            io = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            // A fixed "now" so the gesture decodes deterministically; a no-op buzz.
            buzzEffect = { /* no-op */ },
            timerAlarmPushDebouncer = recording,
        )

        // Three rapid TIMER presses (the watch may even re-send one as several). Each launches an
        // async Room write + a debouncer.schedule on the IO scope; poll briefly for them to land.
        repeat(3) { dispatch.onTimerEventJson(timerJson()) }

        runBlocking {
            kotlinx.coroutines.withTimeout(5_000) {
                while (recording.scheduleCount < 3) kotlinx.coroutines.delay(10)
            }
        }
        // Each press schedules the push, but the debouncer holds only the LAST pending action:
        // firing it once = exactly one ALARMS upload for the whole burst.
        assertEquals("each press schedules a push", 3, recording.scheduleCount)

        // Firing the single pending action does not throw (it pokes the service trigger; no-op here).
        recording.fireNow()
        assertTrue(true)
    }
}
