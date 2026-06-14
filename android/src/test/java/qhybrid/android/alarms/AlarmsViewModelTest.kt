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
import qhybrid.android.sync.FakeSyncStateSource
import qhybrid.android.sync.SyncProgressUi
import qhybrid.android.sync.SyncState

/**
 * WP16b — headless tests for the Alarms state holder. Reuses the WP4 [DbTestBase] in-memory
 * Room harness; "Save to watch" is replaced by a [FakeAlarmSync]. Verifies:
 *   - only user slots 0–14 combine into the UiState, sorted by slotId (15 = timer, 16+ = calendar),
 *   - add picks the lowest free slot and enforces the 15-slot user cap,
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

    private fun vm(
        sync: AlarmSync = FakeAlarmSync(),
        syncSource: FakeSyncStateSource = FakeSyncStateSource(),
        // Existing tests pass null → the real CoroutineDebouncer (delay never fires under Unconfined
        // without virtual time, so those tests are unaffected by the Step-4 auto-save). Step-4 tests
        // inject an ImmediateDebouncer / RecordingDebouncer to drive the auto-save deterministically.
        debouncer: qhybrid.android.sync.Debouncer? = null,
    ) = AlarmsViewModel(repo, sync, vmScope, syncSource, debouncer)

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
    fun combinesOnlyUserSlots0to14SortedIntoUiState() {
        runBlocking {
            watchDao.upsert(watch("AA:00:00:00:00:01", name = "One", active = true))
            // Out of order + the TIMER slot (15) and a calendar slot (16) which must be filtered out.
            alarmDao.upsert(userAlarm("AA:00:00:00:00:01", slot = 5))
            alarmDao.upsert(userAlarm("AA:00:00:00:00:01", slot = 0))
            alarmDao.upsert(userAlarm("AA:00:00:00:00:01", slot = 14))
            alarmDao.upsert(userAlarm("AA:00:00:00:00:01", slot = 15)) // TIMER — excluded
            alarmDao.upsert(userAlarm("AA:00:00:00:00:01", slot = 16)) // calendar — excluded
        }
        val model = vm()
        val s = awaitState(model.uiState) { it.alarms.size == 3 }
        assertEquals("AA:00:00:00:00:01", s.activeMac)
        assertEquals(listOf(0, 5, 14), s.alarms.map { it.slotId })
        // The TIMER slot (15) and calendar slot (16) are NOT in the editable user list.
        assertTrue(s.alarms.none { it.slotId == 15 })
        assertTrue(s.alarms.none { it.slotId == 16 })
        assertTrue(s.hasActiveWatch)
        assertFalse(s.isFull)
        assertEquals(1, s.nextFreeSlot) // lowest free after {0,5,14}
    }

    @Test
    fun calendarSlots16to31_surfacedReadOnly_separateFromUserAlarms() {
        runBlocking {
            watchDao.upsert(watch("AA:00:00:00:00:02", name = "Two", active = true))
            alarmDao.upsert(userAlarm("AA:00:00:00:00:02", slot = 0)) // user
            alarmDao.upsert(userAlarm("AA:00:00:00:00:02", slot = 17)) // calendar
            alarmDao.upsert(userAlarm("AA:00:00:00:00:02", slot = 16)) // calendar (out of order)
        }
        val model = vm()
        val s = awaitState(model.uiState) { it.calendarAlarms.size == 2 }
        // User list = only 0; calendar list = 16,17 sorted; the two never overlap.
        assertEquals(listOf(0), s.alarms.map { it.slotId })
        assertEquals(listOf(16, 17), s.calendarAlarms.map { it.slotId })
    }

    @Test
    fun timerSlot15_surfacedReadOnly_separateFromUserAndCalendar() {
        runBlocking {
            watchDao.upsert(watch("AA:00:00:00:00:03", name = "Three", active = true))
            alarmDao.upsert(userAlarm("AA:00:00:00:00:03", slot = 0)) // user
            alarmDao.upsert(userAlarm("AA:00:00:00:00:03", slot = 15, hour = 20, minute = 17)) // TIMER
            alarmDao.upsert(userAlarm("AA:00:00:00:00:03", slot = 16)) // calendar
        }
        val model = vm()
        val s = awaitState(model.uiState) { it.timerAlarm != null }
        // The timer (slot 15) is surfaced on its own, NOT in the user or calendar lists.
        assertEquals(listOf(0), s.alarms.map { it.slotId })
        assertEquals(listOf(16), s.calendarAlarms.map { it.slotId })
        assertEquals(15, s.timerAlarm?.slotId)
        assertEquals(20, s.timerAlarm?.hour)
        assertEquals(17, s.timerAlarm?.minute)
    }

    @Test
    fun disabledTimerSlot_isNotSurfaced() {
        runBlocking {
            watchDao.upsert(watch("AA:00:00:00:00:03", name = "Three", active = true))
            alarmDao.upsert(userAlarm("AA:00:00:00:00:03", slot = 15, enabled = false)) // disabled timer
        }
        val model = vm()
        val s = awaitState(model.uiState) { it.hasActiveWatch }
        assertNull(s.timerAlarm)
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
    fun addEnforces15SlotUserCap() {
        runBlocking {
            watchDao.upsert(watch("AA:00:00:00:00:01", active = true))
            // 15 user slots (0..14); slot 15 is the reserved TIMER slot, not a user slot.
            for (slot in 0..14) alarmDao.upsert(userAlarm("AA:00:00:00:00:01", slot = slot))
        }
        val model = vm()
        val full = awaitState(model.uiState) { it.alarms.size == 15 }
        assertTrue(full.isFull)
        assertNull(full.nextFreeSlot)

        model.addAlarm() // must be a no-op at the cap

        // Give Room a beat; the count must remain 15.
        runBlocking { assertEquals(15, repo.getAlarms("AA:00:00:00:00:01").size) }
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
    fun syncProgressReflectsSyncingThenError() {
        // WP-PROGRESS: the Save button's progress flow maps the process-wide sync signal.
        val source = FakeSyncStateSource()
        val model = vm(syncSource = source)
        assertFalse(model.syncProgress.value.syncing)

        source.set(SyncState.SyncPhase.SYNCING)
        val syncing = runBlocking {
            withTimeout(5_000) { model.syncProgress.first { it.syncing } }
        }
        assertTrue(syncing.syncing)

        source.set(SyncState.SyncPhase.ERROR, errorMessage = "link lost")
        val err = runBlocking {
            withTimeout(5_000) { model.syncProgress.first { it.tone == SyncProgressUi.Tone.ERROR } }
        }
        assertFalse(err.syncing)
        assertTrue(err.note!!.contains("link lost"))
    }

    @Test
    fun productionAlarmUploadIsWired() {
        // WP14 sub-part 3: the real alarm-byte upload pipeline (compile via WP5 → BLE write
        // through the WP3 service's SyncOrchestrator) is wired.
        assertTrue(ServiceAlarmSync.ALARM_UPLOAD_WIRED)
    }

    // ---- WP-SYNCSTATUS: per-alarm on-watch derivation ------------------------

    private fun repoAt(clock: () -> Long) = qhybrid.android.db.WatchRepository(db, now = clock)

    @Test
    fun neverSynced_everyAlarmIsPending() {
        val mac = "AA:00:00:00:00:01"
        runBlocking {
            watchDao.upsert(watch(mac, active = true))
            repoAt { 1_000L }.upsertAlarm(userAlarm(mac, slot = 0))
        }
        val s = awaitState(vm().uiState) { it.alarms.size == 1 }
        assertEquals(1, s.pendingCount)
        assertFalse(s.isOnWatch(0))
    }

    @Test
    fun alarmEditedBeforeSync_isOnWatch() {
        val mac = "AA:00:00:00:00:01"
        runBlocking {
            watchDao.upsert(watch(mac, active = true).copy(alarmsSyncedAt = 2_000L))
            repoAt { 1_000L }.upsertAlarm(userAlarm(mac, slot = 0))
        }
        val s = awaitState(vm().uiState) { it.alarms.size == 1 && it.pendingCount == 0 }
        assertTrue(s.isOnWatch(0))
    }

    @Test
    fun mixedAlarms_pendingCountReflectsOnlyEditedSincePush() {
        val mac = "AA:00:00:00:00:01"
        runBlocking {
            watchDao.upsert(watch(mac, active = true).copy(alarmsSyncedAt = 1_500L))
            repoAt { 1_000L }.upsertAlarm(userAlarm(mac, slot = 0)) // before push → on-watch
            repoAt { 2_000L }.upsertAlarm(userAlarm(mac, slot = 1)) // after push  → pending
        }
        val s = awaitState(vm().uiState) { it.alarms.size == 2 }
        assertTrue(s.isOnWatch(0))
        assertFalse(s.isOnWatch(1))
        assertEquals(1, s.pendingCount)
    }

    // ---- WP-SYNCSTATUS (Step 4): alarm edits auto-save (debounced) ------------

    @Test
    fun addAlarmAutoSaves() {
        runBlocking { watchDao.upsert(watch("AA:00:00:00:00:01", active = true)) }
        val sync = FakeAlarmSync(wired = true)
        val model = vm(sync = sync, debouncer = qhybrid.android.sync.ImmediateDebouncer())
        awaitState(model.uiState) { it.hasActiveWatch }

        model.addAlarm(hour = 7, minute = 0)
        // The immediate debouncer fires the auto-save synchronously → the ALARMS_ONLY push is hit.
        assertEquals(1, sync.saveCount)
    }

    @Test
    fun updateAndDeleteAlsoAutoSave() {
        runBlocking {
            watchDao.upsert(watch("AA:00:00:00:00:01", active = true))
            alarmDao.upsert(userAlarm("AA:00:00:00:00:01", slot = 0))
        }
        val sync = FakeAlarmSync(wired = true)
        val model = vm(sync = sync, debouncer = qhybrid.android.sync.ImmediateDebouncer())
        val s = awaitState(model.uiState) { it.alarms.size == 1 }

        model.updateAlarm(s.alarms.first().copy(hour = 9))
        model.deleteAlarm(0)
        // Each edit schedules (immediately fires) an auto-save.
        assertEquals(2, sync.saveCount)
    }

    @Test
    fun burstOfEditsCoalescesIntoOneSave() {
        runBlocking { watchDao.upsert(watch("AA:00:00:00:00:01", active = true)) }
        val sync = FakeAlarmSync(wired = true)
        val rec = qhybrid.android.sync.RecordingDebouncer()
        val model = vm(sync = sync, debouncer = rec)
        awaitState(model.uiState) { it.hasActiveWatch }

        // A burst: three rapid edits. The recording debouncer keeps only the LAST scheduled action
        // (coalescing), and nothing has fired yet.
        model.addAlarm(hour = 7)
        model.addAlarm(hour = 8)
        model.addAlarm(hour = 9)
        assertEquals(0, sync.saveCount) // not fired during the burst

        // The window elapses once after the burst settles → exactly ONE push.
        rec.fireNow()
        assertEquals(1, sync.saveCount)
    }
}
