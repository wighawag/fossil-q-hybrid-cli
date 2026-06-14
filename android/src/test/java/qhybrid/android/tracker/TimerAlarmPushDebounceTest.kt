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
import qhybrid.android.notifications.VibePatterns
import qhybrid.android.sync.FakeSyncStateSource
import qhybrid.android.sync.RecordingDebouncer
import qhybrid.android.sync.SyncResult
import qhybrid.android.sync.SyncSection
import qhybrid.android.sync.SyncState

/**
 * TIMER feedback + sync behaviour:
 *  - the alarm PUSH is debounced (a burst of presses → ONE upload; FINDINGS 2026-06-14), and
 *  - TWO-BUZZ feedback: buzz 1 (immediate, duration-INDEPENDENT "press received") fires on every
 *    press; buzz 2 (the DURATION pattern) fires ONLY after the alarm sync actually SUCCEEDS.
 */
@RunWith(RobolectricTestRunner::class)
class TimerAlarmPushDebounceTest {

    private fun shortTimerJson() = """{"type":"music","action":"TOGGLE_PLAY_PAUSE","sequence":1}"""

    private fun seedActiveWatch() {
        val repo = WatchRepository(ApplicationProvider.getApplicationContext())
        runBlocking {
            repo.upsertWatch(
                WatchEntity(
                    macAddress = "AA:00:00:00:00:01", name = "Test", model = null,
                    firmwareVersion = null, batteryLevel = 50, isActive = true,
                )
            )
        }
    }

    private fun dispatch(
        debouncer: RecordingDebouncer,
        sync: FakeSyncStateSource,
        buzzes: MutableList<Int>,
    ) = ServiceTrackerDispatch(
        context = ApplicationProvider.getApplicationContext(),
        io = CoroutineScope(Dispatchers.IO + SupervisorJob()),
        buzzEffect = { pattern -> synchronized(buzzes) { buzzes.add(pattern) } },
        timerAlarmPushDebouncer = debouncer,
        syncStateSource = sync,
    )

    @Test
    fun timerPressesAreCoalescedIntoOneAlarmPush() {
        seedActiveWatch()
        val recording = RecordingDebouncer()
        val buzzes = mutableListOf<Int>()
        val d = dispatch(recording, FakeSyncStateSource(), buzzes)

        repeat(3) { d.onTimerEventJson(shortTimerJson()) }

        runBlocking {
            kotlinx.coroutines.withTimeout(5_000) {
                while (recording.scheduleCount < 3) kotlinx.coroutines.delay(10)
            }
        }
        // Three presses → three schedules, but the debouncer holds only the last (one upload/burst).
        assertEquals(3, recording.scheduleCount)
    }

    @Test
    fun buzz1IsImmediateReceivedPulse_buzz2IsDurationAfterSyncSuccess() {
        seedActiveWatch()
        val recording = RecordingDebouncer()
        val sync = FakeSyncStateSource()
        val buzzes = mutableListOf<Int>()
        val d = dispatch(recording, sync, buzzes)

        // One SHORT-timer press.
        d.onTimerEventJson(shortTimerJson())

        // Buzz 1 fires immediately: the duration-independent "received" pulse (ONE_SHORT).
        runBlocking {
            kotlinx.coroutines.withTimeout(5_000) {
                while (buzzes.isEmpty()) kotlinx.coroutines.delay(10)
            }
        }
        assertEquals(VibePatterns.ONE_SHORT, buzzes.first())

        // Let the debounced push schedule, then fire it (runs pushTimerAlarmAndConfirm on the IO scope).
        runBlocking {
            kotlinx.coroutines.withTimeout(5_000) {
                while (recording.scheduleCount < 1) kotlinx.coroutines.delay(10)
            }
        }
        recording.fireNow()

        // No second buzz yet — the alarm hasn't been confirmed on the watch.
        runBlocking { kotlinx.coroutines.delay(100) }
        assertEquals("only the received buzz so far", 1, buzzes.size)

        // Drive a SUCCESS that PERFORMED the ALARMS section → buzz 2 = the SHORT duration pattern.
        sync.set(
            SyncState.SyncPhase.SUCCESS,
            result = SyncResult("AA:00:00:00:00:01", listOf(SyncSection.ALARMS), emptyList(), emptyList()),
            nowMillis = 1L,
        )
        runBlocking {
            kotlinx.coroutines.withTimeout(5_000) {
                while (buzzes.size < 2) kotlinx.coroutines.delay(10)
            }
        }
        // SHORT timer → TimerController.buzzFor(SHORT) == ONE_SHORT (the duration pattern).
        assertEquals(TimerController.buzzFor(TimerController.TimerGesture.SHORT), buzzes[1])
        assertEquals(2, buzzes.size)
    }

    @Test
    fun noDurationBuzz_whenSyncErrors() {
        seedActiveWatch()
        val recording = RecordingDebouncer()
        val sync = FakeSyncStateSource()
        val buzzes = mutableListOf<Int>()
        val d = dispatch(recording, sync, buzzes)

        d.onTimerEventJson(shortTimerJson())
        runBlocking {
            kotlinx.coroutines.withTimeout(5_000) {
                while (recording.scheduleCount < 1) kotlinx.coroutines.delay(10)
            }
        }
        recording.fireNow()

        // Sync ERRORs (e.g. out of range) → NO second buzz; the absence tells the user it didn't arm.
        sync.set(SyncState.SyncPhase.ERROR, errorMessage = "Watch not reachable", nowMillis = 1L)
        runBlocking { kotlinx.coroutines.delay(200) }
        assertEquals("only the received buzz; no duration buzz on error", 1, buzzes.size)
        assertTrue(buzzes.first() == VibePatterns.ONE_SHORT)
    }
}
