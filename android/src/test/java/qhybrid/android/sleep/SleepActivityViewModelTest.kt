package qhybrid.android.sleep

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import qhybrid.android.db.DbTestBase

/**
 * WP16f — headless tests for the Sleep/Activity state holder. Reuses the WP4 [DbTestBase]
 * in-memory Room harness for the active-watch observation (WP16f adds NO DB — see [ActivitySource]);
 * the data source is replaced by a [FakeActivitySource]. Verifies:
 *   - the UiState reflects the active watch (and disables/empties when none),
 *   - a fake source feeds known parsed data → correct Day/Sleep summaries + timeline in the UiState,
 *   - refresh hits the fake and reports the ACTIVITY_WIRED pending flag,
 *   - refresh is a no-op without an active watch,
 *   - empty/partial-data tolerance (no crash on zero records).
 *
 * Like [qhybrid.android.calibration.CalibrationViewModelTest], the VM is given a REAL
 * [CoroutineScope] and the combined [StateFlow] is polled with a bounded [awaitState] because
 * Room's reactive Flows re-emit on Room's own executor.
 *
 * **NO test asserts any DB persistence of activity/sleep — there is none.** WP8 is an on-demand
 * parser; the fetch→parse pipeline is deferred behind the ACTIVITY_WIRED=false seam.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SleepActivityViewModelTest : DbTestBase() {

    private val vmScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())

    private class FakeActivitySource(
        initial: ActivityChartData = ActivityChartData.EMPTY,
        private val wired: Boolean = false,
    ) : ActivitySource {
        private val _data = MutableStateFlow(initial)
        override val data: Flow<ActivityChartData> = _data.asStateFlow()
        var refreshCount = 0
        fun emit(d: ActivityChartData) { _data.value = d }
        override fun refresh(): Boolean {
            refreshCount++
            return wired
        }
    }

    private fun vm(source: ActivitySource = FakeActivitySource()) =
        SleepActivityViewModel(repo, source, vmScope)

    private fun awaitState(
        flow: StateFlow<SleepActivityUiState>,
        predicate: (SleepActivityUiState) -> Boolean,
    ): SleepActivityUiState = runBlocking {
        withTimeout(5_000) { flow.first { predicate(it) } }
    }

    private fun sampleData() = ActivityChartData(
        days = listOf(
            DaySummary(date = "2024-01-01", steps = 4000, calories = 120, activeMinutes = 30, recordCount = 600),
            DaySummary(date = "2024-01-02", steps = 6000, calories = 180, activeMinutes = 50, recordCount = 600),
        ),
        sleep = listOf(
            SleepSegment(0, 0, durationMinutes = 420, restlessMinutes = 20, avgVariability = 1.5, quality = SleepQuality.GOOD),
        ),
        sleepSummary = SleepActivityAdapter.summarizeSleep(
            listOf(SleepSegment(0, 0, 420, 20, 1.5, SleepQuality.GOOD)),
        ),
    )

    // ---- active-watch observation (disables/empties when none) ----------------

    @Test
    fun reflectsActiveWatch() {
        runBlocking { watchDao.upsert(watch("AA:00:00:00:00:01", name = "One", active = true)) }
        val model = vm()
        val s = awaitState(model.uiState) { it.hasActiveWatch }
        assertEquals("AA:00:00:00:00:01", s.activeMac)
        assertTrue(s.hasActiveWatch)
        assertTrue(s.canRefresh)
        // No data fed yet → empty.
        assertTrue(s.isEmpty)
        assertEquals(10000, s.stepGoal) // WP4 default goal surfaced
    }

    @Test
    fun emptiedWhenNoActiveWatch() {
        runBlocking { watchDao.upsert(watch("AA:00:00:00:00:01", active = false)) }
        // Even if the source has data, with no active watch the screen must empty it out.
        val source = FakeActivitySource(initial = sampleData())
        val model = vm(source)
        val s = awaitState(model.uiState) { true }
        assertNull(s.activeWatch)
        assertFalse(s.hasActiveWatch)
        assertFalse(s.canRefresh)
        assertTrue(s.isEmpty) // forced empty despite the source having data
        assertTrue(s.days.isEmpty())
        assertTrue(s.sleep.isEmpty())
        assertEquals(0, s.stepGoal)
    }

    // ---- fake source feeds known data → correct summaries + timeline ----------

    @Test
    fun fakeSourceFeedsDayAndSleepSummaries() {
        runBlocking { watchDao.upsert(watch("AA:00:00:00:00:01", active = true)) }
        val source = FakeActivitySource()
        val model = vm(source)
        awaitState(model.uiState) { it.hasActiveWatch }

        source.emit(sampleData())
        val s = awaitState(model.uiState) { it.days.size == 2 }

        // Per-day timeline preserved + ordered.
        assertEquals(2, s.days.size)
        assertEquals("2024-01-01", s.days[0].date)
        assertEquals(4000, s.days[0].steps)
        assertEquals("2024-01-02", s.days[1].date)
        assertEquals(6000, s.days[1].steps)
        // Aggregate totals.
        assertEquals(10000, s.totalSteps)
        assertEquals(300, s.totalCalories)
        assertEquals(80, s.totalActiveMinutes)

        // Sleep timeline + summary.
        assertEquals(1, s.sleep.size)
        assertEquals(420, s.sleep[0].durationMinutes)
        assertEquals(400, s.sleep[0].restfulMinutes)
        assertTrue(s.sleepSummary.hasSleep)
        assertEquals(420, s.sleepSummary.totalMinutes)
        assertEquals(20, s.sleepSummary.restlessMinutes)
        // 20/420 ≈ 4.8% < 10% → good.
        assertEquals(SleepQuality.GOOD, s.sleepSummary.quality)
        assertFalse(s.isEmpty)
    }

    // ---- refresh -------------------------------------------------------------

    @Test
    fun refreshHitsTheFakeAndReportsPending() {
        runBlocking { watchDao.upsert(watch("AA:00:00:00:00:01", active = true)) }
        val source = FakeActivitySource(wired = false)
        val model = vm(source)
        awaitState(model.uiState) { it.hasActiveWatch }

        val wired = model.refresh()
        assertEquals(1, source.refreshCount)
        assertFalse(wired) // fetch→parse pipeline deferred (ACTIVITY_WIRED=false)
    }

    @Test
    fun refreshIsNoOpWithoutActiveWatch() {
        runBlocking { watchDao.upsert(watch("AA:00:00:00:00:01", active = false)) }
        val source = FakeActivitySource()
        val model = vm(source)
        awaitState(model.uiState) { !it.hasActiveWatch }
        assertFalse(model.refresh()) // gated on active watch
        assertEquals(0, source.refreshCount)
    }

    @Test
    fun refreshReportsWiredWhenSeamIsWired() {
        runBlocking { watchDao.upsert(watch("AA:00:00:00:00:01", active = true)) }
        val source = FakeActivitySource(wired = true)
        val model = vm(source)
        awaitState(model.uiState) { it.hasActiveWatch }
        assertTrue(model.refresh())
        assertEquals(1, source.refreshCount)
    }

    // ---- empty / partial tolerance -------------------------------------------

    @Test
    fun zeroRecordsDoesNotCrash() {
        runBlocking { watchDao.upsert(watch("AA:00:00:00:00:01", active = true)) }
        val source = FakeActivitySource(initial = ActivityChartData.EMPTY)
        val model = vm(source)
        val s = awaitState(model.uiState) { it.hasActiveWatch }
        assertTrue(s.isEmpty)
        assertEquals(0, s.totalSteps)
        assertEquals(0, s.totalCalories)
        assertFalse(s.sleepSummary.hasSleep)
        assertEquals(SleepQuality.NONE, s.sleepSummary.quality)
    }

    @Test
    fun partialData_daysButNoSleep() {
        runBlocking { watchDao.upsert(watch("AA:00:00:00:00:01", active = true)) }
        val source = FakeActivitySource()
        val model = vm(source)
        awaitState(model.uiState) { it.hasActiveWatch }
        // Days present, no sleep detected → still renders, summary is NONE.
        source.emit(
            ActivityChartData(
                days = listOf(DaySummary("2024-02-01", 1234, 50, 10, 100)),
                sleep = emptyList(),
                sleepSummary = SleepSummary.EMPTY,
            ),
        )
        val s = awaitState(model.uiState) { it.days.isNotEmpty() }
        assertEquals(1234, s.totalSteps)
        assertTrue(s.sleep.isEmpty())
        assertFalse(s.sleepSummary.hasSleep)
        assertFalse(s.isEmpty) // has day data
    }
}
