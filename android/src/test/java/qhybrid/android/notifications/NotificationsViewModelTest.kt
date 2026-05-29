package qhybrid.android.notifications

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
import qhybrid.android.db.NotificationRuleEntity

/**
 * WP16c — headless tests for the Notifications state holder. Reuses the WP4 [DbTestBase]
 * in-memory Room harness; "Save to watch" is replaced by a [FakeNotificationSync]. Verifies:
 *   - per-app rules combine into the UiState, sorted by packageName,
 *   - add picks up defaults, and a duplicate packageName is rejected (composite PK),
 *   - vibe-pattern + hand-position updates write the right row,
 *   - delete removes the row,
 *   - save delegates to the fake and reports the WP14-pending flag.
 *
 * Like [qhybrid.android.alarms.AlarmsViewModelTest], the VM is given a REAL [CoroutineScope]
 * and the combined [StateFlow] is polled with a bounded [awaitState] because Room's reactive
 * Flows re-emit on Room's own executor (virtual-time would not observe their re-emissions).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NotificationsViewModelTest : DbTestBase() {

    private val vmScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())

    private class FakeNotificationSync(private val wired: Boolean = false) : NotificationSync {
        var saveCount = 0
        override fun saveToWatch(): Boolean { saveCount++; return wired }
    }

    private fun vm(sync: NotificationSync = FakeNotificationSync()) =
        NotificationsViewModel(repo, sync, vmScope)

    private fun awaitState(
        flow: StateFlow<NotificationsUiState>,
        predicate: (NotificationsUiState) -> Boolean,
    ): NotificationsUiState = runBlocking {
        withTimeout(5_000) { flow.first { predicate(it) } }
    }

    private fun rule(
        mac: String,
        pkg: String,
        vibe: Int = VibePatterns.DEFAULT,
        hourDeg: Int = 0,
        minDeg: Int = 0,
    ) = NotificationRuleEntity(
        watchMac = mac,
        packageName = pkg,
        vibePattern = vibe,
        hourHandDegrees = hourDeg,
        minuteHandDegrees = minDeg,
    )

    // ---- constants sanity (WP6 1:1 wire convention) --------------------------

    @Test
    fun vibeConstantsMatchWireConvention() {
        assertEquals(0, VibePatterns.AUTO)
        assertEquals(1, VibePatterns.CALL)
        assertEquals(2, VibePatterns.TEXT)
        assertEquals(3, VibePatterns.EMAIL)
        assertEquals(4, VibePatterns.DEFAULT)
        assertEquals(8, VibePatterns.ONE_LONG)
        assertEquals(9, VibePatterns.NO_VIBE)
        assertEquals(10, VibePatterns.LABELS.size)
        assertEquals(5, VibePatterns.clamp(5))
        assertEquals(9, VibePatterns.clamp(99))
        assertEquals(0, VibePatterns.clamp(-3))
        assertEquals(359, VibePatterns.clampDegrees(400))
        assertEquals(0, VibePatterns.clampDegrees(-10))
    }

    // ---- state combination ----------------------------------------------------

    @Test
    fun combinesRulesSortedByPackageIntoUiState() {
        runBlocking {
            watchDao.upsert(watch("AA:00:00:00:00:01", name = "One", active = true))
            // Inserted out of alphabetical order.
            ruleDao.upsert(rule("AA:00:00:00:00:01", "com.whatsapp"))
            ruleDao.upsert(rule("AA:00:00:00:00:01", "com.android.email"))
            ruleDao.upsert(rule("AA:00:00:00:00:01", "com.slack"))
        }
        val model = vm()
        val s = awaitState(model.uiState) { it.rules.size == 3 }
        assertEquals("AA:00:00:00:00:01", s.activeMac)
        assertTrue(s.hasActiveWatch)
        assertEquals(
            listOf("com.android.email", "com.slack", "com.whatsapp"),
            s.rules.map { it.packageName },
        )
    }

    @Test
    fun emptyWhenNoActiveWatch() {
        runBlocking { watchDao.upsert(watch("AA:00:00:00:00:01", name = "One", active = false)) }
        val model = vm()
        val s = awaitState(model.uiState) { true }
        assertNull(s.activeWatch)
        assertFalse(s.hasActiveWatch)
        assertTrue(s.rules.isEmpty())
    }

    // ---- add / duplicate rejection -------------------------------------------

    @Test
    fun addRuleInsertsWithGivenFields() {
        runBlocking { watchDao.upsert(watch("AA:00:00:00:00:01", active = true)) }
        val model = vm()
        awaitState(model.uiState) { it.hasActiveWatch }

        val ok = model.addRule("com.whatsapp", vibePattern = VibePatterns.CALL,
            hourHandDegrees = 90, minuteHandDegrees = 180)
        assertTrue(ok)

        val s = awaitState(model.uiState) { it.rules.any { r -> r.packageName == "com.whatsapp" } }
        val added = s.rules.first { it.packageName == "com.whatsapp" }
        assertEquals(VibePatterns.CALL, added.vibePattern)
        assertEquals(90, added.hourHandDegrees)
        assertEquals(180, added.minuteHandDegrees)
    }

    @Test
    fun addRejectsDuplicatePackage() {
        runBlocking {
            watchDao.upsert(watch("AA:00:00:00:00:01", active = true))
            ruleDao.upsert(rule("AA:00:00:00:00:01", "com.whatsapp", vibe = VibePatterns.CALL,
                hourDeg = 90, minDeg = 90))
        }
        val model = vm()
        awaitState(model.uiState) { it.rules.size == 1 }

        // Attempting to add the same package must be rejected and NOT overwrite the row.
        val ok = model.addRule("com.whatsapp", vibePattern = VibePatterns.NO_VIBE,
            hourHandDegrees = 0, minuteHandDegrees = 0)
        assertFalse(ok)

        runBlocking {
            val rules = repo.getRules("AA:00:00:00:00:01")
            assertEquals(1, rules.size)
            // Original values preserved (no silent REPLACE).
            assertEquals(VibePatterns.CALL, rules.first().vibePattern)
            assertEquals(90, rules.first().hourHandDegrees)
        }
    }

    @Test
    fun addNoOpWithoutActiveWatch() {
        runBlocking { watchDao.upsert(watch("AA:00:00:00:00:01", active = false)) }
        val model = vm()
        awaitState(model.uiState) { !it.hasActiveWatch }
        val ok = model.addRule("com.whatsapp")
        assertFalse(ok)
        runBlocking { assertEquals(0, repo.getRules("AA:00:00:00:00:01").size) }
    }

    @Test
    fun addRejectsBlankPackage() {
        runBlocking { watchDao.upsert(watch("AA:00:00:00:00:01", active = true)) }
        val model = vm()
        awaitState(model.uiState) { it.hasActiveWatch }
        assertFalse(model.addRule("   "))
        runBlocking { assertEquals(0, repo.getRules("AA:00:00:00:00:01").size) }
    }

    // ---- vibe + hand-position updates ----------------------------------------

    @Test
    fun setVibePatternUpdatesRow() {
        runBlocking {
            watchDao.upsert(watch("AA:00:00:00:00:01", active = true))
            ruleDao.upsert(rule("AA:00:00:00:00:01", "com.whatsapp", vibe = VibePatterns.DEFAULT))
        }
        val model = vm()
        awaitState(model.uiState) { it.rules.size == 1 }

        model.setVibePattern("com.whatsapp", VibePatterns.CALL)
        val s = awaitState(model.uiState) {
            it.rules.firstOrNull()?.vibePattern == VibePatterns.CALL
        }
        assertEquals(VibePatterns.CALL, s.rules.first().vibePattern)
        runBlocking {
            assertEquals(VibePatterns.CALL, repo.getRules("AA:00:00:00:00:01").first().vibePattern)
        }
    }

    @Test
    fun setHandPositionUpdatesRow() {
        runBlocking {
            watchDao.upsert(watch("AA:00:00:00:00:01", active = true))
            ruleDao.upsert(rule("AA:00:00:00:00:01", "com.whatsapp", hourDeg = 0, minDeg = 0))
        }
        val model = vm()
        awaitState(model.uiState) { it.rules.size == 1 }

        model.setHandPosition("com.whatsapp", hourHandDegrees = 120, minuteHandDegrees = 240)
        val s = awaitState(model.uiState) { it.rules.firstOrNull()?.hourHandDegrees == 120 }
        assertEquals(120, s.rules.first().hourHandDegrees)
        assertEquals(240, s.rules.first().minuteHandDegrees)
    }

    @Test
    fun updateRuleClampsOutOfRangeValues() {
        runBlocking {
            watchDao.upsert(watch("AA:00:00:00:00:01", active = true))
            ruleDao.upsert(rule("AA:00:00:00:00:01", "com.whatsapp"))
        }
        val model = vm()
        val s0 = awaitState(model.uiState) { it.rules.size == 1 }

        model.updateRule(s0.rules.first().copy(vibePattern = 99, hourHandDegrees = 400,
            minuteHandDegrees = -5))
        val s = awaitState(model.uiState) { it.rules.firstOrNull()?.vibePattern == 9 }
        assertEquals(9, s.rules.first().vibePattern)
        assertEquals(359, s.rules.first().hourHandDegrees)
        assertEquals(0, s.rules.first().minuteHandDegrees)
    }

    // ---- delete --------------------------------------------------------------

    @Test
    fun deleteRemovesRule() {
        runBlocking {
            watchDao.upsert(watch("AA:00:00:00:00:01", active = true))
            ruleDao.upsert(rule("AA:00:00:00:00:01", "com.whatsapp"))
            ruleDao.upsert(rule("AA:00:00:00:00:01", "com.slack"))
        }
        val model = vm()
        awaitState(model.uiState) { it.rules.size == 2 }

        model.deleteRule("com.whatsapp")
        val s = awaitState(model.uiState) { it.rules.size == 1 }
        assertEquals(listOf("com.slack"), s.rules.map { it.packageName })
        runBlocking { assertEquals(1, repo.getRules("AA:00:00:00:00:01").size) }
    }

    // ---- save ----------------------------------------------------------------

    @Test
    fun saveToWatchHitsTheFakeAndReportsPending() {
        val sync = FakeNotificationSync(wired = false)
        val model = vm(sync)
        val wired = model.saveToWatch()
        assertEquals(1, sync.saveCount)
        assertFalse(wired) // the FAKE reports false; the production flag is asserted below
    }

    @Test
    fun productionFilterUploadIsWired() {
        // WP14 sub-part 3: the real filter-byte upload pipeline (compile via WP6 → BLE write
        // through the WP3 service's SyncOrchestrator) is wired.
        assertTrue(ServiceNotificationSync.FILTER_UPLOAD_WIRED)
    }
}
