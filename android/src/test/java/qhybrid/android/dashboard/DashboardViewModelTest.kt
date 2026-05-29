package qhybrid.android.dashboard

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
import qhybrid.android.WatchState
import qhybrid.android.db.DbTestBase
import qhybrid.android.sleep.ActivityChartData
import qhybrid.android.sleep.ActivityState
import qhybrid.android.sleep.DaySummary

/**
 * WP16a — headless tests for the Dashboard state holder. Reuses the WP4 [DbTestBase]
 * in-memory Room harness; the live link is driven via an injected [MutableStateFlow] of
 * [WatchState.WatchStatus] (so we don't mutate the process-wide [WatchState] object), and
 * the service is replaced by a [FakeWatchActions]. Verifies:
 *   - link + active-watch row combine into the expected [DashboardUiState],
 *   - switching the active watch updates the state,
 *   - intents call the right fake service / repo methods.
 *
 * The VM is given a REAL [CoroutineScope] (Room's reactive Flows run on Room's own
 * executor, so virtual-time would not observe their re-emissions); we collect the
 * combined [StateFlow] with a real, bounded [awaitState] poll instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DashboardViewModelTest : DbTestBase() {

    private val vmScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())

    private class FakeWatchActions : WatchActions {
        val connectCalls = mutableListOf<String?>()
        var disconnectCount = 0
        var syncCount = 0
        var findWatchCount = 0
        override fun connect(mac: String?) { connectCalls.add(mac) }
        override fun disconnect() { disconnectCount++ }
        override fun sync() { syncCount++ }
        override fun findWatch() { findWatchCount++ }
    }

    private fun status(
        link: WatchState.LinkState = WatchState.LinkState.IDLE,
        mac: String? = null,
        battery: Int? = null,
        firmware: String? = null,
        model: String? = null,
        mtu: Int = 0,
        message: String? = null,
    ) = WatchState.WatchStatus(link, mac, battery, firmware, model, mtu, message)

    private fun vm(
        statusFlow: MutableStateFlow<WatchState.WatchStatus>,
        actions: WatchActions = FakeWatchActions(),
        activityFlow: MutableStateFlow<ActivityState.ActivityStatus> =
            MutableStateFlow(ActivityState.ActivityStatus()),
    ) = DashboardViewModel(repo, actions, statusFlow, activityFlow, vmScope)

    private fun activityStatus(steps: Int, updated: Long = 1L) = ActivityState.ActivityStatus(
        data = ActivityChartData(
            days = listOf(DaySummary("2024-01-01", steps = steps, calories = 0, activeMinutes = 0, recordCount = 1)),
        ),
        lastUpdatedMillis = updated,
        hasFetched = true,
    )

    /** Poll the combined StateFlow until [predicate] holds (or fail after a real timeout). */
    private fun awaitState(
        flow: StateFlow<DashboardUiState>,
        predicate: (DashboardUiState) -> Boolean,
    ): DashboardUiState = runBlocking {
        withTimeout(5_000) { flow.first { predicate(it) } }
    }

    @Test
    fun combinesLiveLinkAndActiveWatchIntoUiState() {
        runBlocking {
            watchDao.upsert(watch("AA:00:00:00:00:01", name = "One"))
            watchDao.upsert(watch("BB:00:00:00:00:02", name = "Two", active = true))
        }
        val flow = MutableStateFlow(
            status(
                link = WatchState.LinkState.INITIALIZED,
                mac = "BB:00:00:00:00:02",
                battery = 88, firmware = "FW1", model = "HW.0.0", mtu = 185,
                message = "Connected",
            )
        )
        val model = vm(flow)

        val s = awaitState(model.uiState) {
            it.link == WatchState.LinkState.INITIALIZED && it.watches.size == 2
        }
        assertTrue(s.isConnected)
        assertFalse(s.isBusy)
        assertEquals(88, s.batteryPercent)
        assertEquals("FW1", s.firmware)
        assertEquals("HW.0.0", s.model)
        assertEquals(185, s.mtu)
        assertEquals("Connected", s.statusMessage)
        assertEquals("BB:00:00:00:00:02", s.activeWatch?.macAddress)
        assertEquals("BB:00:00:00:00:02", s.selectedMac)
        assertEquals(10000, s.stepGoal)
        // WP-ACTIVITY: steps are null until the first activity fetch publishes (none here).
        assertNull(s.steps)
    }

    @Test
    fun liveStepsComeFromActivityState() {
        runBlocking { watchDao.upsert(watch("AA:00:00:00:00:01", name = "One", active = true)) }
        val flow = MutableStateFlow(status(link = WatchState.LinkState.INITIALIZED, mac = "AA:00:00:00:00:01"))
        val activity = MutableStateFlow(ActivityState.ActivityStatus())
        val model = vm(flow, activityFlow = activity)

        // No fetch yet → steps null (placeholder).
        val pending = awaitState(model.uiState) { it.activeWatch != null }
        assertNull(pending.steps)

        // Publish a parsed activity total → the Dashboard steps go live.
        activity.value = activityStatus(steps = 7421, updated = 999L)
        val s = awaitState(model.uiState) { it.steps != null }
        assertEquals(7421, s.steps)
        assertEquals(999L, s.activityUpdatedMillis)
    }

    @Test
    fun liveStepsAreNullWithoutActiveWatch() {
        // Steps only make sense for the active watch; with none, stay null even if a fetch happened.
        val flow = MutableStateFlow(status(link = WatchState.LinkState.INITIALIZED))
        val activity = MutableStateFlow(activityStatus(steps = 5000))
        val model = vm(flow, activityFlow = activity)
        val s = awaitState(model.uiState) { it.watches.isEmpty() && it.link == WatchState.LinkState.INITIALIZED }
        assertNull(s.activeWatch)
        assertNull(s.steps)
    }

    @Test
    fun batteryFallsBackToActiveWatchWhenLinkHasNone() {
        runBlocking { watchDao.upsert(watch("CC:00:00:00:00:03", active = true)) }
        val flow = MutableStateFlow(status(link = WatchState.LinkState.DISCONNECTED))
        val model = vm(flow)

        val s = awaitState(model.uiState) { it.activeWatch != null }
        assertEquals(22, s.batteryPercent) // fixture batteryLevel
        assertEquals("HW0.0.2.9r.v3", s.firmware) // fixture firmware fallback
    }

    @Test
    fun switchingActiveWatchUpdatesState() {
        runBlocking {
            watchDao.upsert(watch("AA:00:00:00:00:01", name = "One", active = true))
            watchDao.upsert(watch("BB:00:00:00:00:02", name = "Two"))
        }
        val flow = MutableStateFlow(status(link = WatchState.LinkState.DISCONNECTED))
        val model = vm(flow)

        awaitState(model.uiState) { it.selectedMac == "AA:00:00:00:00:01" }

        model.setActiveWatch("BB:00:00:00:00:02")

        val s = awaitState(model.uiState) { it.selectedMac == "BB:00:00:00:00:02" }
        assertEquals("BB:00:00:00:00:02", s.selectedMac)
        // The intent went through WatchRepository (WP4): the DB reflects the switch.
        runBlocking { assertEquals("BB:00:00:00:00:02", repo.getActiveWatch()?.macAddress) }
    }

    @Test
    fun connectIntentUsesActiveWatchMacWhenNotSpecified() {
        runBlocking { watchDao.upsert(watch("DD:00:00:00:00:04", active = true)) }
        val actions = FakeWatchActions()
        val flow = MutableStateFlow(status())
        val model = vm(flow, actions)
        // Wait until the active watch is reflected in the combined state.
        awaitState(model.uiState) { it.selectedMac == "DD:00:00:00:00:04" }

        model.connect()

        assertEquals(listOf("DD:00:00:00:00:04"), actions.connectCalls)
    }

    @Test
    fun connectIntentHonoursExplicitMac() {
        val actions = FakeWatchActions()
        val flow = MutableStateFlow(status())
        val model = vm(flow, actions)

        model.connect("EE:00:00:00:00:05")

        assertEquals(listOf("EE:00:00:00:00:05"), actions.connectCalls)
    }

    @Test
    fun disconnectSyncFindIntentsHitTheFake() {
        val actions = FakeWatchActions()
        val flow = MutableStateFlow(status())
        val model = vm(flow, actions)

        model.disconnect()
        model.sync()
        model.findWatch()

        assertEquals(1, actions.disconnectCount)
        assertEquals(1, actions.syncCount)
        assertEquals(1, actions.findWatchCount)
    }

    @Test
    fun busyStateReflectsConnectingAndAuth() {
        val flow = MutableStateFlow(status(link = WatchState.LinkState.CONNECTING))
        val model = vm(flow)

        assertTrue(awaitState(model.uiState) { it.link == WatchState.LinkState.CONNECTING }.isBusy)

        flow.value = status(link = WatchState.LinkState.AUTH_REQUIRED)
        assertTrue(awaitState(model.uiState) { it.link == WatchState.LinkState.AUTH_REQUIRED }.isBusy)

        flow.value = status(link = WatchState.LinkState.INITIALIZED)
        val s = awaitState(model.uiState) { it.link == WatchState.LinkState.INITIALIZED }
        assertFalse(s.isBusy)
        assertTrue(s.isConnected)
    }
}
