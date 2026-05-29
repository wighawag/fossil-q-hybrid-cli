package qhybrid.android.sleep

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * WP-ACTIVITY (sub-part 2) — headless tests for the process-wide in-memory holder [ActivityState]
 * (the cache decision: in-memory, mirroring WP3 `WatchState`; NO Room schema). Verifies the
 * publish/observe contract the service writes and the Sleep screen + Dashboard read.
 */
class ActivityStateTest {

    @Before fun resetBefore() = ActivityState.reset()
    @After fun resetAfter() = ActivityState.reset()

    @Test
    fun pristineState_isEmptyAndNeverFetched() {
        val s = ActivityState.status.value
        assertSame(ActivityChartData.EMPTY, s.data)
        assertFalse(s.hasFetched)
        assertEquals(0L, s.lastUpdatedMillis)
        // steps is "unknown" (null) until a fetch happens, so the Dashboard shows a placeholder.
        assertNull(s.steps)
        assertSame(ActivityChartData.EMPTY, ActivityState.data.value)
    }

    @Test
    fun publish_updatesStatusAndDataFlow() {
        val chart = ActivityChartData(
            days = listOf(DaySummary("2024-01-01", steps = 4200, calories = 100, activeMinutes = 30, recordCount = 100)),
        )
        ActivityState.publish(chart, nowMillis = 123_456L)

        val s = ActivityState.status.value
        assertEquals(chart, s.data)
        assertTrue(s.hasFetched)
        assertEquals(123_456L, s.lastUpdatedMillis)
        assertEquals(4200, s.steps) // Dashboard step total
        assertEquals(chart, ActivityState.data.value)
    }

    @Test
    fun publishEmpty_marksFetchedAndStepsZero() {
        // A confirmed-empty fetch (watch had no data) must resolve steps to 0, not "unknown".
        ActivityState.publish(ActivityChartData.EMPTY, nowMillis = 1L)
        val s = ActivityState.status.value
        assertTrue(s.hasFetched)
        assertEquals(0, s.steps)
        assertFalse(s.data.hasData)
    }

    @Test
    fun publish_overwritesPrevious() {
        ActivityState.publish(
            ActivityChartData(days = listOf(DaySummary("2024-01-01", 100, 0, 0, 1))), 1L,
        )
        val second = ActivityChartData(days = listOf(DaySummary("2024-01-02", 999, 0, 0, 1)))
        ActivityState.publish(second, 2L)
        assertEquals(second, ActivityState.status.value.data)
        assertEquals(999, ActivityState.status.value.steps)
        assertEquals(2L, ActivityState.status.value.lastUpdatedMillis)
    }

    @Test
    fun reset_returnsToPristine() {
        ActivityState.publish(ActivityChartData(days = listOf(DaySummary("d", 1, 0, 0, 1))), 5L)
        ActivityState.reset()
        assertFalse(ActivityState.status.value.hasFetched)
        assertNull(ActivityState.status.value.steps)
    }
}
