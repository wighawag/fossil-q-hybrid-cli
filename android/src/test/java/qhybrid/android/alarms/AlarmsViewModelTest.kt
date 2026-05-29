package qhybrid.android.alarms

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
import qhybrid.android.db.WatchAlarmEntity

/**
 * WP16b — headless tests for the Alarms state holder. Reuses the WP4 [DbTestBase] in-memory
 * Room harness; "Save to watch" is replaced by a [FakeAlarmSync]. Verifies:
 *   - only slots 0–15 combine into the UiState, sorted by slotId,
 *   - add picks the lowest free slot and enforces the 16-slot user cap,
 *   - weekday/weekend/everyday shortcuts produce the correct (WP5 1:1) daysMask,
 *   - toggle/delete/update/setDays call the right repo methods,
 *   - save delegates to the fake.
 *
 * Like [DashboardViewModelTest], the VM is given a REAL [CoroutineScope] and the combined
 * [StateFlow] is polled with a bounded [awaitState] because Room's reactive Flows re-emit
 * on Room's own executor (virtual-time would not observe their re-emissions).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AlarmsViewModelTest : DbTestBase() {

    private val vmScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())

    private class FakeAlarmSync(private val wired: Boolean = false) : AlarmSync {
        var saveCount = 0
        override fun saveToWatch(): Boolean { saveCount++; return wired }
    }

    private fun vm(sync: AlarmSync = FakeAlarmSync()) =
        AlarmsViewModel(repo, sync, vmScope)

    private fun awaitState(
        flow: StateFlow<AlarmsUiState>,
        predicate: (AlarmsUiState) -> Boolean,
    ): AlarmsUiState = runBlocking {
        withTimeout(5_000) { flow.first { predicate(it) } }
    }

    private fun userAlarm(
        mac: String,
        slot: Int,
        hour: Int = 7,
        minute: Int = 0,
        enabled: Boolean = true,
        days: Int = AlarmDays.WEEKDAY,
        repeating: Boolean = true,
    ) = WatchAlarmEntity(
        watchMac = mac,
        slotId = slot,
        hour = hour,
        minute = minute,
        isEnabled = enabled,
        daysMask = days,
        isRepeating = repeating,
        label = "Alarm $slot",
    )

    // ---- constants sanity (WP5 1:1 wire convention) --------------------------

    @Test
    fun dayConstantsMatchWireConvention() {
        assertEquals(0x3E, AlarmDays.WEEKDAY) // Mon–Fri
        assertEquals(0x41, AlarmDays.WEEKEND) // Sat + Sun
        assertEquals(0x7F, AlarmDays.EVERYDAY)
        assertEquals(0x01, AlarmDays.SUN)
        assertEquals(0x08, AlarmDays.WED) // bit3 = Wed
        assertEquals(0x10, AlarmDays.THU) // bit4 = Thu
        assertEquals(0x40, AlarmDays.SAT)
    }

    // ---- state combination ----------------------------------------------------

    @Test
    fun combinesOnlySlots0to15SortedIntoUiState() {
        runBlocking {
            watchDao.upsert(watch("AA:00:00:00:00:01", name = "One", active = true))
            // Out of order + a calendar slot (16) which must be filtered out.
            alarmDao.upsert(userAlarm("AA:00:00:00:00:01", slot = 5))
            alarmDao.upsert(userAlarm("AA:00:00:00:00:01", slot = 0))
            alarmDao.upsert(userAlarm("AA:00:00:00:00:01", slot = 15))
            alarmDao.upsert(userAlarm("AA:00:00:00:00:01", slot = 16)) // calendar — excluded
        }
        val model = vm()
        val s = awaitState(model.uiState) { it.alarms.size == 3 }
        assertEquals("AA:00:00:00:00:01", s.activeMac)
        assertEquals(listOf(0, 5, 15), s.alarms.map { it.slotId })
        assertTrue(s.hasActiveWatch)
        assertFalse(s.isFull)
        assertEquals(1, s.nextFreeSlot) // lowest free after {0,5,15}
    }

    @Test
    fun emptyWhenNoActiveWatch() {
        runBlocking { watchDao.upsert(watch("AA:00:00:00:00:01", name = "One", active = false)) }
        val model = vm()
        val s = awaitState(model.uiState) { true }
        assertNull(s.activeWatch)
        assertFalse(s.hasActiveWatch)
        assertTrue(s.alarms.isEmpty())
        assertEquals(0, s.nextFreeSlot) // all free
    }

    // ---- add / lowest-free-slot / cap ----------------------------------------

    @Test
    fun addPicksLowestFreeSlot() {
        runBlocking {
            watchDao.upsert(watch("AA:00:00:00:00:01", active = true))
            alarmDao.upsert(userAlarm("AA:00:00:00:00:01", slot = 0))
            alarmDao.upsert(userAlarm("AA:00:00:00:00:01", slot = 1))
            alarmDao.upsert(userAlarm("AA:00:00:00:00:01", slot = 3))
        }
        val model = vm()
        awaitState(model.uiState) { it.alarms.size == 3 }

        model.addAlarm(hour = 9, minute = 15)

        // Slot 2 is the lowest free.
        val s = awaitState(model.uiState) { it.alarms.any { a -> a.slotId == 2 } }
        val added = s.alarms.first { it.slotId == 2 }
        assertEquals(9, added.hour)
        assertEquals(15, added.minute)
        assertEquals(AlarmDays.WEEKDAY, added.daysMask)
        assertTrue(added.isRepeating)
        assertTrue(added.isEnabled)
    }

    @Test
    fun addEnforces16SlotCap() {
        runBlocking {
            watchDao.upsert(watch("AA:00:00:00:00:01", active = true))
            for (slot in 0..15) alarmDao.upsert(userAlarm("AA:00:00:00:00:01", slot = slot))
        }
        val model = vm()
        val full = awaitState(model.uiState) { it.alarms.size == 16 }
        assertTrue(full.isFull)
        assertNull(full.nextFreeSlot)

        model.addAlarm() // must be a no-op at the cap

        // Give Room a beat; the count must remain 16.
        runBlocking { assertEquals(16, repo.getAlarms("AA:00:00:00:00:01").size) }
    }

    @Test
    fun addNoOpWithoutActiveWatch() {
        runBlocking { watchDao.upsert(watch("AA:00:00:00:00:01", active = false)) }
        val model = vm()
        awaitState(model.uiState) { !it.hasActiveWatch }
        model.addAlarm()
        runBlocking { assertEquals(0, repo.getAlarms("AA:00:00:00:00:01").size) }
    }

    // ---- shortcuts (WP5 1:1 daysMask) ----------------------------------------

    @Test
    fun weekdayWeekendEverydayShortcutsProduceCorrectMask() {
        runBlocking {
            watchDao.upsert(watch("AA:00:00:00:00:01", active = true))
            alarmDao.upsert(userAlarm("AA:00:00:00:00:01", slot = 0, days = 0, repeating = false))
        }
        val model = vm()
        awaitState(model.uiState) { it.alarms.size == 1 }

        model.setWeekdays(0)
        var s = awaitState(model.uiState) { it.alarms.first().daysMask == AlarmDays.WEEKDAY }
        assertEquals(0x3E, s.alarms.first().daysMask)
        assertTrue(s.alarms.first().isRepeating) // shortcut forces repeating

        model.setWeekend(0)
        s = awaitState(model.uiState) { it.alarms.first().daysMask == AlarmDays.WEEKEND }
        assertEquals(0x41, s.alarms.first().daysMask)

        model.setEveryday(0)
        s = awaitState(model.uiState) { it.alarms.first().daysMask == AlarmDays.EVERYDAY }
        assertEquals(0x7F, s.alarms.first().daysMask)
    }

    @Test
    fun toggleDayFlipsSingleBit() {
        runBlocking {
            watchDao.upsert(watch("AA:00:00:00:00:01", active = true))
            alarmDao.upsert(userAlarm("AA:00:00:00:00:01", slot = 0, days = AlarmDays.WEEKDAY))
        }
        val model = vm()
        awaitState(model.uiState) { it.alarms.size == 1 }

        // Add Saturday (bit6).
        model.toggleDay(0, AlarmDays.SAT)
        var s = awaitState(model.uiState) { (it.alarms.first().daysMask and AlarmDays.SAT) != 0 }
        assertEquals(AlarmDays.WEEKDAY or AlarmDays.SAT, s.alarms.first().daysMask)

        // Remove Wednesday (bit3).
        model.toggleDay(0, AlarmDays.WED)
        s = awaitState(model.uiState) { (it.alarms.first().daysMask and AlarmDays.WED) == 0 }
        assertEquals((AlarmDays.WEEKDAY or AlarmDays.SAT) and AlarmDays.WED.inv() and AlarmDays.EVERYDAY,
            s.alarms.first().daysMask)
    }

    // ---- toggle / delete / update / setDays ----------------------------------

    @Test
    fun toggleEnabledFlipsFlag() {
        runBlocking {
            watchDao.upsert(watch("AA:00:00:00:00:01", active = true))
            alarmDao.upsert(userAlarm("AA:00:00:00:00:01", slot = 0, enabled = true))
        }
        val model = vm()
        awaitState(model.uiState) { it.alarms.firstOrNull()?.isEnabled == true }

        model.toggleEnabled(0)
        val s = awaitState(model.uiState) { it.alarms.firstOrNull()?.isEnabled == false }
        assertFalse(s.alarms.first().isEnabled)
        runBlocking { assertFalse(repo.getAlarms("AA:00:00:00:00:01").first().isEnabled) }
    }

    @Test
    fun deleteRemovesSlot() {
        runBlocking {
            watchDao.upsert(watch("AA:00:00:00:00:01", active = true))
            alarmDao.upsert(userAlarm("AA:00:00:00:00:01", slot = 0))
            alarmDao.upsert(userAlarm("AA:00:00:00:00:01", slot = 1))
        }
        val model = vm()
        awaitState(model.uiState) { it.alarms.size == 2 }

        model.deleteAlarm(0)
        val s = awaitState(model.uiState) { it.alarms.size == 1 }
        assertEquals(listOf(1), s.alarms.map { it.slotId })
        runBlocking { assertEquals(1, repo.getAlarms("AA:00:00:00:00:01").size) }
    }

    @Test
    fun updateAlarmReplacesRow() {
        runBlocking {
            watchDao.upsert(watch("AA:00:00:00:00:01", active = true))
            alarmDao.upsert(userAlarm("AA:00:00:00:00:01", slot = 0, hour = 7, minute = 0))
        }
        val model = vm()
        val s0 = awaitState(model.uiState) { it.alarms.size == 1 }

        model.updateAlarm(s0.alarms.first().copy(hour = 22, minute = 45, daysMask = AlarmDays.EVERYDAY))
        val s = awaitState(model.uiState) { it.alarms.firstOrNull()?.hour == 22 }
        assertEquals(22, s.alarms.first().hour)
        assertEquals(45, s.alarms.first().minute)
        assertEquals(AlarmDays.EVERYDAY, s.alarms.first().daysMask)
    }

    @Test
    fun setDaysReplacesMask() {
        runBlocking {
            watchDao.upsert(watch("AA:00:00:00:00:01", active = true))
            alarmDao.upsert(userAlarm("AA:00:00:00:00:01", slot = 0, days = AlarmDays.WEEKDAY))
        }
        val model = vm()
        awaitState(model.uiState) { it.alarms.size == 1 }

        model.setDays(0, AlarmDays.SUN or AlarmDays.SAT)
        val s = awaitState(model.uiState) { it.alarms.first().daysMask == (AlarmDays.SUN or AlarmDays.SAT) }
        assertEquals(0x41, s.alarms.first().daysMask)
    }

    // ---- save ----------------------------------------------------------------

    @Test
    fun saveToWatchHitsTheFakeAndReportsPending() {
        val sync = FakeAlarmSync(wired = false)
        val model = vm(sync)
        val wired = model.saveToWatch()
        assertEquals(1, sync.saveCount)
        assertFalse(wired) // the FAKE reports false; the production flag is asserted below
    }

    @Test
    fun productionAlarmUploadIsWired() {
        // WP14 sub-part 3: the real alarm-byte upload pipeline (compile via WP5 → BLE write
        // through the WP3 service's SyncOrchestrator) is wired.
        assertTrue(ServiceAlarmSync.ALARM_UPLOAD_WIRED)
    }
}
