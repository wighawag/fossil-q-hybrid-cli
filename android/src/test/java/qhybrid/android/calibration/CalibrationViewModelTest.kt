package qhybrid.android.calibration

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
import qhybrid.android.db.DbTestBase

/**
 * WP16e — headless tests for the Calibration state holder. Reuses the WP4 [DbTestBase] in-memory
 * Room harness PURELY for the active-watch observation (calibration adds NO DB); "Apply" is
 * replaced by a [FakeCalibrationSync]. Verifies:
 *   - the UiState reflects the active watch (and disables when none),
 *   - enterCalibration starts a NEUTRAL session (inProgress=true, offsets zeroed),
 *   - exitCalibration clears the session, and re-entering starts neutral again (NO reloaded
 *     offset — calibration is ephemeral),
 *   - nudge applies + normalizes incl. wrap past 0 and past 359,
 *   - setHand / selectHand update the in-memory session,
 *   - apply hits the fake and reports the WP14 / WP-F-pending flag,
 *   - nudge/setHand/apply are no-ops when not in a session.
 *
 * Like [qhybrid.android.buttons.ButtonsViewModelTest], the VM is given a REAL [CoroutineScope]
 * and the combined [StateFlow] is polled with a bounded [awaitState] because Room's reactive
 * Flows re-emit on Room's own executor (virtual-time would not observe them).
 *
 * **NO test asserts any DB persistence — there is none.** Calibration is ephemeral by design.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CalibrationViewModelTest : DbTestBase() {

    private val vmScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())

    private class FakeCalibrationSync(private val wired: Boolean = false) : CalibrationSync {
        var applyCount = 0
        var lastHour: Int? = null
        var lastMinute: Int? = null
        var lastSub: Int? = null
        override fun apply(hourDegrees: Int?, minuteDegrees: Int?, subDegrees: Int?): Boolean {
            applyCount++
            lastHour = hourDegrees
            lastMinute = minuteDegrees
            lastSub = subDegrees
            return wired
        }
    }

    private fun vm(sync: CalibrationSync = FakeCalibrationSync()) =
        CalibrationViewModel(repo, sync, vmScope)

    private fun awaitState(
        flow: StateFlow<CalibrationUiState>,
        predicate: (CalibrationUiState) -> Boolean,
    ): CalibrationUiState = runBlocking {
        withTimeout(5_000) { flow.first { predicate(it) } }
    }

    // ---- active-watch observation (disables when none) ------------------------

    @Test
    fun reflectsActiveWatch() {
        runBlocking { watchDao.upsert(watch("AA:00:00:00:00:01", name = "One", active = true)) }
        val model = vm()
        val s = awaitState(model.uiState) { it.hasActiveWatch }
        assertEquals("AA:00:00:00:00:01", s.activeMac)
        assertTrue(s.hasActiveWatch)
        // No session started yet → cannot calibrate.
        assertFalse(s.inProgress)
        assertFalse(s.canCalibrate)
    }

    @Test
    fun disabledWhenNoActiveWatch() {
        runBlocking { watchDao.upsert(watch("AA:00:00:00:00:01", active = false)) }
        val model = vm()
        val s = awaitState(model.uiState) { true }
        assertNull(s.activeWatch)
        assertFalse(s.hasActiveWatch)
        // Even after entering a session, with no active watch the controls stay disabled.
        model.enterCalibration()
        val s2 = awaitState(model.uiState) { it.inProgress }
        assertTrue(s2.inProgress)
        assertFalse(s2.canCalibrate) // gated on hasActiveWatch
    }

    // ---- enter starts a neutral session --------------------------------------

    @Test
    fun enterStartsNeutralSession() {
        runBlocking { watchDao.upsert(watch("AA:00:00:00:00:01", active = true)) }
        val model = vm()
        awaitState(model.uiState) { it.hasActiveWatch }

        model.enterCalibration()
        val s = awaitState(model.uiState) { it.inProgress }
        assertTrue(s.inProgress)
        assertTrue(s.canCalibrate)
        assertEquals(CalibrationHands.DEFAULT, s.selectedHand)
        // All hands neutral (0°).
        for (hand in CalibrationHands.ALL) {
            assertEquals(0, s.offsetOf(hand))
        }
    }

    // ---- exit clears + re-enter is neutral (NO reloaded offset) ---------------

    @Test
    fun exitClearsAndReEnterIsNeutral() {
        runBlocking { watchDao.upsert(watch("AA:00:00:00:00:01", active = true)) }
        val model = vm()
        awaitState(model.uiState) { it.hasActiveWatch }

        model.enterCalibration()
        awaitState(model.uiState) { it.inProgress }
        // Nudge the hour hand away from neutral.
        model.nudge(CalibrationHands.HOUR, 6 * 5) // +30°
        val nudged = awaitState(model.uiState) { it.offsetOf(CalibrationHands.HOUR) == 30 }
        assertEquals(30, nudged.offsetOf(CalibrationHands.HOUR))

        // Exit clears the session.
        model.exitCalibration()
        val cleared = awaitState(model.uiState) { !it.inProgress }
        assertFalse(cleared.inProgress)

        // Re-entering must start NEUTRAL — the previous 30° must NOT be reloaded.
        model.enterCalibration()
        val reentered = awaitState(model.uiState) { it.inProgress }
        assertEquals(0, reentered.offsetOf(CalibrationHands.HOUR))
        assertEquals(CalibrationHands.DEFAULT, reentered.selectedHand)
    }

    // ---- nudge applies + normalizes (wrap past 0 and 359) ---------------------

    @Test
    fun nudgeAppliesAndWrapsPast359() {
        runBlocking { watchDao.upsert(watch("AA:00:00:00:00:01", active = true)) }
        val model = vm()
        awaitState(model.uiState) { it.hasActiveWatch }
        model.enterCalibration()
        awaitState(model.uiState) { it.inProgress }

        model.setHand(CalibrationHands.MINUTE, 354)
        awaitState(model.uiState) { it.offsetOf(CalibrationHands.MINUTE) == 354 }
        // +6° from 354 wraps to 0.
        model.nudge(CalibrationHands.MINUTE, 6)
        val s = awaitState(model.uiState) { it.offsetOf(CalibrationHands.MINUTE) == 0 }
        assertEquals(0, s.offsetOf(CalibrationHands.MINUTE))
    }

    @Test
    fun nudgeAppliesAndWrapsPast0() {
        runBlocking { watchDao.upsert(watch("AA:00:00:00:00:01", active = true)) }
        val model = vm()
        awaitState(model.uiState) { it.hasActiveWatch }
        model.enterCalibration()
        awaitState(model.uiState) { it.inProgress }

        // From neutral 0°, a -1° fine nudge wraps to 359.
        model.nudge(CalibrationHands.SUB, -1)
        val s = awaitState(model.uiState) { it.offsetOf(CalibrationHands.SUB) == 359 }
        assertEquals(359, s.offsetOf(CalibrationHands.SUB))
    }

    @Test
    fun nudgeIsNoOpWhenNotInSession() {
        runBlocking { watchDao.upsert(watch("AA:00:00:00:00:01", active = true)) }
        val model = vm()
        awaitState(model.uiState) { it.hasActiveWatch }
        // Not in a session → nudge does nothing.
        model.nudge(CalibrationHands.HOUR, 6)
        val s = awaitState(model.uiState) { true }
        assertEquals(0, s.offsetOf(CalibrationHands.HOUR))
        assertFalse(s.inProgress)
    }

    // ---- setHand / selectHand ------------------------------------------------

    @Test
    fun setHandSetsAbsoluteNormalizedValue() {
        runBlocking { watchDao.upsert(watch("AA:00:00:00:00:01", active = true)) }
        val model = vm()
        awaitState(model.uiState) { it.hasActiveWatch }
        model.enterCalibration()
        awaitState(model.uiState) { it.inProgress }

        // Out-of-range / negative input is normalized.
        model.setHand(CalibrationHands.HOUR, 365)
        val s1 = awaitState(model.uiState) { it.offsetOf(CalibrationHands.HOUR) == 5 }
        assertEquals(5, s1.offsetOf(CalibrationHands.HOUR))

        model.setHand(CalibrationHands.HOUR, -10)
        val s2 = awaitState(model.uiState) { it.offsetOf(CalibrationHands.HOUR) == 350 }
        assertEquals(350, s2.offsetOf(CalibrationHands.HOUR))
    }

    @Test
    fun selectHandUpdatesSelection() {
        runBlocking { watchDao.upsert(watch("AA:00:00:00:00:01", active = true)) }
        val model = vm()
        awaitState(model.uiState) { it.hasActiveWatch }
        model.enterCalibration()
        awaitState(model.uiState) { it.inProgress }

        model.selectHand(CalibrationHands.SUB)
        val s = awaitState(model.uiState) { it.selectedHand == CalibrationHands.SUB }
        assertEquals(CalibrationHands.SUB, s.selectedHand)

        // An unknown id falls back to the default (never poisons selection).
        model.selectHand("BOGUS")
        val s2 = awaitState(model.uiState) { it.selectedHand == CalibrationHands.DEFAULT }
        assertEquals(CalibrationHands.DEFAULT, s2.selectedHand)
    }

    // ---- apply ---------------------------------------------------------------

    @Test
    fun applyHitsTheFakeAndReportsPending() {
        runBlocking { watchDao.upsert(watch("AA:00:00:00:00:01", active = true)) }
        val sync = FakeCalibrationSync(wired = false)
        val model = vm(sync)
        awaitState(model.uiState) { it.hasActiveWatch }
        model.enterCalibration()
        awaitState(model.uiState) { it.inProgress }

        model.setHand(CalibrationHands.HOUR, 12)
        model.setHand(CalibrationHands.MINUTE, 24)
        awaitState(model.uiState) {
            it.offsetOf(CalibrationHands.HOUR) == 12 && it.offsetOf(CalibrationHands.MINUTE) == 24
        }

        val wired = model.apply()
        assertEquals(1, sync.applyCount)
        assertFalse(wired) // move-hands/save-calibration deferred to WP14 / WP F
        assertEquals(12, sync.lastHour)
        assertEquals(24, sync.lastMinute)
        assertNull(sync.lastSub) // sub never set → null (left untouched)
    }

    @Test
    fun applyIsNoOpWithoutSession() {
        runBlocking { watchDao.upsert(watch("AA:00:00:00:00:01", active = true)) }
        val sync = FakeCalibrationSync()
        val model = vm(sync)
        awaitState(model.uiState) { it.hasActiveWatch }
        // No session → apply does nothing and reports not-wired.
        assertFalse(model.apply())
        assertEquals(0, sync.applyCount)
    }

    @Test
    fun applyIsNoOpWithoutActiveWatch() {
        runBlocking { watchDao.upsert(watch("AA:00:00:00:00:01", active = false)) }
        val sync = FakeCalibrationSync()
        val model = vm(sync)
        awaitState(model.uiState) { !it.hasActiveWatch }
        model.enterCalibration()
        awaitState(model.uiState) { it.inProgress }
        assertFalse(model.apply()) // gated on active watch
        assertEquals(0, sync.applyCount)
    }
}
