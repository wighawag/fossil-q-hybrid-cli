package qhybrid.android.buttons

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
import qhybrid.android.db.ButtonMappingEntity
import qhybrid.android.db.DbTestBase
import qhybrid.android.sync.FakeSyncStateSource
import qhybrid.android.sync.SyncProgressUi
import qhybrid.android.sync.SyncState

/**
 * WP16d — headless tests for the Buttons state holder. Reuses the WP4 [DbTestBase] in-memory
 * Room harness; "Save to watch" is replaced by a [FakeButtonSync]. Verifies:
 *   - per-button mappings combine into the UiState, sorted by buttonId (any count),
 *   - add picks up the given fields, and a duplicate buttonId is rejected (composite PK),
 *   - set-mode / set-actions write the right row,
 *   - reset removes the row,
 *   - save delegates to the fake and reports the WP14-pending flag.
 *
 * Like [qhybrid.android.notifications.NotificationsViewModelTest], the VM is given a REAL
 * [CoroutineScope] and the combined [StateFlow] is polled with a bounded [awaitState] because
 * Room's reactive Flows re-emit on Room's own executor (virtual-time would not observe them).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ButtonsViewModelTest : DbTestBase() {

    private val vmScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())

    private class FakeButtonSync(private val wired: Boolean = false) : ButtonSync {
        var saveCount = 0
        override fun saveToWatch(): Boolean { saveCount++; return wired }
    }

    private fun vm(
        sync: ButtonSync = FakeButtonSync(),
        syncSource: FakeSyncStateSource = FakeSyncStateSource(),
    ) = ButtonsViewModel(repo, sync, vmScope, syncSource)

    private fun awaitState(
        flow: StateFlow<ButtonsUiState>,
        predicate: (ButtonsUiState) -> Boolean,
    ): ButtonsUiState = runBlocking {
        withTimeout(5_000) { flow.first { predicate(it) } }
    }

    private fun mapping(
        mac: String,
        buttonId: Int,
        mode: String = ButtonModes.SINGLE_ACTION,
        actionsJson: String = ButtonActionsJson.encode(listOf(ButtonActions.FORWARD_TO_PHONE)),
    ) = ButtonMappingEntity(
        watchMac = mac,
        buttonId = buttonId,
        modeType = mode,
        actionsJson = actionsJson,
    )

    // ---- state combination (any count, sorted by buttonId) -------------------

    @Test
    fun combinesMappingsSortedByButtonIdIntoUiState() {
        runBlocking {
            watchDao.upsert(watch("AA:00:00:00:00:01", name = "One", active = true))
            // Inserted out of order + a 5-position-style extra button (0x40, 0x50).
            buttonDao.upsert(mapping("AA:00:00:00:00:01", 0x30))
            buttonDao.upsert(mapping("AA:00:00:00:00:01", 0x10))
            buttonDao.upsert(mapping("AA:00:00:00:00:01", 0x50))
            buttonDao.upsert(mapping("AA:00:00:00:00:01", 0x20))
            buttonDao.upsert(mapping("AA:00:00:00:00:01", 0x40))
        }
        val model = vm()
        val s = awaitState(model.uiState) { it.mappings.size == 5 }
        assertEquals("AA:00:00:00:00:01", s.activeMac)
        assertTrue(s.hasActiveWatch)
        assertEquals(
            listOf(0x10, 0x20, 0x30, 0x40, 0x50),
            s.mappings.map { it.buttonId },
        )
    }

    @Test
    fun emptyWhenNoActiveWatch() {
        runBlocking { watchDao.upsert(watch("AA:00:00:00:00:01", active = false)) }
        val model = vm()
        val s = awaitState(model.uiState) { true }
        assertNull(s.activeWatch)
        assertFalse(s.hasActiveWatch)
        assertTrue(s.mappings.isEmpty())
    }

    // ---- add / duplicate rejection -------------------------------------------

    @Test
    fun addMappingInsertsWithGivenFields() {
        runBlocking { watchDao.upsert(watch("AA:00:00:00:00:01", active = true)) }
        val model = vm()
        awaitState(model.uiState) { it.hasActiveWatch }

        val ok = model.addMapping(
            buttonId = 0x20,
            modeType = ButtonModes.SINGLE_ACTION,
            actionsJson = ButtonActionsJson.encode(listOf(ButtonActions.MULTI_FUNCTION)),
        )
        assertTrue(ok)

        val s = awaitState(model.uiState) { it.mappings.any { m -> m.buttonId == 0x20 } }
        val added = s.mappings.first { it.buttonId == 0x20 }
        assertEquals(ButtonModes.SINGLE_ACTION, added.modeType)
        assertEquals(listOf(ButtonActions.MULTI_FUNCTION), ButtonActionsJson.decode(added.actionsJson))
    }

    @Test
    fun addAllowsArbitraryButtonIdNoModelGating() {
        runBlocking { watchDao.upsert(watch("AA:00:00:00:00:01", active = true)) }
        val model = vm()
        awaitState(model.uiState) { it.hasActiveWatch }
        // A non-standard buttonId must NOT be rejected (model-agnostic).
        assertTrue(model.addMapping(buttonId = 0x99))
        val s = awaitState(model.uiState) { it.mappings.any { m -> m.buttonId == 0x99 } }
        assertEquals(0x99, s.mappings.first().buttonId)
    }

    @Test
    fun addRejectsDuplicateButtonId() {
        runBlocking {
            watchDao.upsert(watch("AA:00:00:00:00:01", active = true))
            buttonDao.upsert(mapping("AA:00:00:00:00:01", 0x10, mode = ButtonModes.SINGLE_ACTION))
        }
        val model = vm()
        awaitState(model.uiState) { it.mappings.size == 1 }

        // Attempting to add the same buttonId must be rejected and NOT overwrite the row.
        val ok = model.addMapping(buttonId = 0x10, modeType = ButtonModes.CUSTOM_TOGGLE)
        assertFalse(ok)

        runBlocking {
            val rows = repo.getButtons("AA:00:00:00:00:01")
            assertEquals(1, rows.size)
            assertEquals(ButtonModes.SINGLE_ACTION, rows.first().modeType) // original preserved
        }
    }

    @Test
    fun addNoOpWithoutActiveWatch() {
        runBlocking { watchDao.upsert(watch("AA:00:00:00:00:01", active = false)) }
        val model = vm()
        awaitState(model.uiState) { !it.hasActiveWatch }
        assertFalse(model.addMapping(buttonId = 0x10))
        runBlocking { assertEquals(0, repo.getButtons("AA:00:00:00:00:01").size) }
    }

    // ---- set-mode / set-actions ----------------------------------------------

    @Test
    fun setModeUpdatesRow() {
        runBlocking {
            watchDao.upsert(watch("AA:00:00:00:00:01", active = true))
            buttonDao.upsert(mapping("AA:00:00:00:00:01", 0x10, mode = ButtonModes.SINGLE_ACTION))
        }
        val model = vm()
        awaitState(model.uiState) { it.mappings.size == 1 }

        model.setMode(0x10, ButtonModes.CUSTOM_TOGGLE)
        val s = awaitState(model.uiState) {
            it.mappings.firstOrNull()?.modeType == ButtonModes.CUSTOM_TOGGLE
        }
        assertEquals(ButtonModes.CUSTOM_TOGGLE, s.mappings.first().modeType)
        runBlocking {
            assertEquals(ButtonModes.CUSTOM_TOGGLE, repo.getButtons("AA:00:00:00:00:01").first().modeType)
        }
    }

    @Test
    fun setActionsUpdatesRow() {
        runBlocking {
            watchDao.upsert(watch("AA:00:00:00:00:01", active = true))
            buttonDao.upsert(mapping("AA:00:00:00:00:01", 0x10))
        }
        val model = vm()
        awaitState(model.uiState) { it.mappings.size == 1 }

        model.setActionList(0x10, listOf(ButtonActions.DATE, ButtonActions.RING_PHONE))
        val s = awaitState(model.uiState) {
            ButtonActionsJson.decode(it.mappings.firstOrNull()?.actionsJson).size == 2
        }
        assertEquals(
            listOf(ButtonActions.DATE, ButtonActions.RING_PHONE),
            ButtonActionsJson.decode(s.mappings.first().actionsJson),
        )
    }

    @Test
    fun updateMappingNormalizesMalformedJson() {
        runBlocking {
            watchDao.upsert(watch("AA:00:00:00:00:01", active = true))
            buttonDao.upsert(mapping("AA:00:00:00:00:01", 0x10))
        }
        val model = vm()
        val s0 = awaitState(model.uiState) { it.mappings.size == 1 }

        // Feed a malformed actionsJson; the VM must persist a valid (empty-array) JSON, never crash.
        model.updateMapping(s0.mappings.first().copy(actionsJson = "{not json"))
        val s = awaitState(model.uiState) {
            ButtonActionsJson.decode(it.mappings.firstOrNull()?.actionsJson).isEmpty()
        }
        assertEquals("[]", s.mappings.first().actionsJson)
    }

    // ---- reset ----------------------------------------------------------------

    @Test
    fun resetRemovesMapping() {
        runBlocking {
            watchDao.upsert(watch("AA:00:00:00:00:01", active = true))
            buttonDao.upsert(mapping("AA:00:00:00:00:01", 0x10))
            buttonDao.upsert(mapping("AA:00:00:00:00:01", 0x20))
        }
        val model = vm()
        awaitState(model.uiState) { it.mappings.size == 2 }

        model.resetButton(0x10)
        val s = awaitState(model.uiState) { it.mappings.size == 1 }
        assertEquals(listOf(0x20), s.mappings.map { it.buttonId })
        runBlocking { assertEquals(1, repo.getButtons("AA:00:00:00:00:01").size) }
    }

    // ---- 3-slot UI surface (TOP/MIDDLE/BOTTOM) -------------------------------

    @Test
    fun slotsAlwaysExposeThreeFixedButtonsInOrder() {
        runBlocking {
            watchDao.upsert(watch("AA:00:00:00:00:01", active = true))
            // Only configure the MIDDLE button; TOP/BOTTOM must still appear (as null).
            buttonDao.upsert(mapping("AA:00:00:00:00:01", ButtonSlots.MIDDLE))
        }
        val model = vm()
        val s = awaitState(model.uiState) { it.mappingFor(ButtonSlots.MIDDLE) != null }

        assertEquals(listOf(ButtonSlots.TOP, ButtonSlots.MIDDLE, ButtonSlots.BOTTOM), s.slots.map { it.first })
        assertNull(s.slots[0].second)              // TOP unconfigured
        assertEquals(ButtonSlots.MIDDLE, s.slots[1].second?.buttonId)
        assertNull(s.slots[2].second)              // BOTTOM unconfigured
    }

    @Test
    fun setSlotCreatesThenUpdatesTheSameRow() {
        runBlocking { watchDao.upsert(watch("AA:00:00:00:00:01", active = true)) }
        val model = vm()
        awaitState(model.uiState) { it.hasActiveWatch }

        // First call creates the TOP-button row.
        model.setSlot(ButtonSlots.TOP, ButtonModes.SINGLE_ACTION, listOf(ButtonActions.DATE))
        val s1 = awaitState(model.uiState) { it.mappingFor(ButtonSlots.TOP) != null }
        assertEquals(ButtonModes.SINGLE_ACTION, s1.mappingFor(ButtonSlots.TOP)?.modeType)
        assertEquals(listOf(ButtonActions.DATE), ButtonActionsJson.decode(s1.mappingFor(ButtonSlots.TOP)?.actionsJson))

        // Second call to the same slot replaces it (still one row, no duplicate).
        model.setSlot(ButtonSlots.TOP, ButtonModes.CUSTOM_TOGGLE, listOf(ButtonDialModes.ALARM, ButtonDialModes.DATE))
        val s2 = awaitState(model.uiState) {
            it.mappingFor(ButtonSlots.TOP)?.modeType == ButtonModes.CUSTOM_TOGGLE
        }
        assertEquals(1, s2.mappings.size)
        assertEquals(
            listOf(ButtonDialModes.ALARM, ButtonDialModes.DATE),
            ButtonActionsJson.decode(s2.mappingFor(ButtonSlots.TOP)?.actionsJson),
        )
    }

    @Test
    fun setSlotCollapsesMultiIdSingleActionToOne() {
        // WP-BTN: a SINGLE_ACTION slot can never store more than one id, even if the caller
        // hands several (e.g. a legacy multi-checkbox selection).
        runBlocking { watchDao.upsert(watch("AA:00:00:00:00:01", active = true)) }
        val model = vm()
        awaitState(model.uiState) { it.hasActiveWatch }

        model.setSlot(ButtonSlots.TOP, ButtonModes.SINGLE_ACTION,
            listOf(ButtonActions.STOPWATCH, ButtonActions.DATE, ButtonActions.RING_PHONE))
        val s = awaitState(model.uiState) { it.mappingFor(ButtonSlots.TOP) != null }
        assertEquals(
            listOf(ButtonActions.STOPWATCH),
            ButtonActionsJson.decode(s.mappingFor(ButtonSlots.TOP)?.actionsJson),
        )
    }

    @Test
    fun setSlotMultiFunctionIsAPlainSingleAction() {
        // WP-BTN: MULTI_FUNCTION is just a SINGLE_ACTION id; multi-id input still collapses to one.
        runBlocking { watchDao.upsert(watch("AA:00:00:00:00:01", active = true)) }
        val model = vm()
        awaitState(model.uiState) { it.hasActiveWatch }

        model.setSlot(ButtonSlots.BOTTOM, ButtonModes.SINGLE_ACTION,
            listOf(ButtonActions.MULTI_FUNCTION, ButtonActions.STOPWATCH))
        val s = awaitState(model.uiState) { it.mappingFor(ButtonSlots.BOTTOM) != null }
        assertEquals(
            listOf(ButtonActions.MULTI_FUNCTION),
            ButtonActionsJson.decode(s.mappingFor(ButtonSlots.BOTTOM)?.actionsJson),
        )
    }

    @Test
    fun setSlotCollapsesLegacyMusicMultimodeRowToSingleAction() {
        // A legacy MUSIC_MULTIMODE modeType normalizes to SINGLE_ACTION on write.
        runBlocking { watchDao.upsert(watch("AA:00:00:00:00:01", active = true)) }
        val model = vm()
        awaitState(model.uiState) { it.hasActiveWatch }

        model.setSlot(ButtonSlots.TOP, ButtonModes.LEGACY_MUSIC_MULTIMODE,
            listOf(ButtonActions.MULTI_FUNCTION, ButtonActions.DATE))
        val s = awaitState(model.uiState) { it.mappingFor(ButtonSlots.TOP) != null }
        assertEquals(ButtonModes.SINGLE_ACTION, s.mappingFor(ButtonSlots.TOP)?.modeType)
        assertEquals(
            listOf(ButtonActions.MULTI_FUNCTION),
            ButtonActionsJson.decode(s.mappingFor(ButtonSlots.TOP)?.actionsJson),
        )
    }

    @Test
    fun setSlotCustomToggleKeepsTheWholeCycle() {
        runBlocking { watchDao.upsert(watch("AA:00:00:00:00:01", active = true)) }
        val model = vm()
        awaitState(model.uiState) { it.hasActiveWatch }

        // Tapped in a non-canonical order; stored in CANONICAL order regardless
        // (ALERT, TIMEZONE_2, ALARM, DATE, TWENTY_FOUR_HOUR).
        val tapped = listOf(ButtonDialModes.TIMEZONE_2, ButtonDialModes.DATE, ButtonDialModes.ALARM)
        model.setSlot(ButtonSlots.MIDDLE, ButtonModes.CUSTOM_TOGGLE, tapped)
        val s = awaitState(model.uiState) { it.mappingFor(ButtonSlots.MIDDLE) != null }
        assertEquals(
            listOf(ButtonDialModes.TIMEZONE_2, ButtonDialModes.ALARM, ButtonDialModes.DATE),
            ButtonActionsJson.decode(s.mappingFor(ButtonSlots.MIDDLE)?.actionsJson),
        )
    }

    @Test
    fun setSlotNoOpWithoutActiveWatch() {
        runBlocking { watchDao.upsert(watch("AA:00:00:00:00:01", active = false)) }
        val model = vm()
        awaitState(model.uiState) { !it.hasActiveWatch }
        model.setSlot(ButtonSlots.TOP, ButtonModes.SINGLE_ACTION, listOf(ButtonActions.DATE))
        runBlocking { assertEquals(0, repo.getButtons("AA:00:00:00:00:01").size) }
    }

    // ---- save -----------------------------------------------------------------

    @Test
    fun saveToWatchHitsTheFakeAndReportsPending() {
        val sync = FakeButtonSync(wired = false)
        val model = vm(sync)
        val wired = model.saveToWatch()
        assertEquals(1, sync.saveCount)
        assertFalse(wired) // the FAKE reports false; the production flag is asserted below
    }

    // ---- WP-PROGRESS: sync spinner state mapping --------------------------------

    @Test
    fun syncProgressReflectsSyncingThenSuccess() {
        val source = FakeSyncStateSource()
        val model = vm(syncSource = source)
        // Idle: no spinner.
        assertFalse(model.syncProgress.value.syncing)

        source.set(SyncState.SyncPhase.SYNCING)
        val syncing = awaitProgress(model) { it.syncing }
        assertTrue(syncing.syncing)

        source.set(
            SyncState.SyncPhase.SUCCESS,
            result = qhybrid.android.sync.SyncResult(
                "AA:00:00:00:00:01",
                listOf(qhybrid.android.sync.SyncSection.BUTTONS),
                emptyList(),
                emptyList(),
            ),
        )
        val done = awaitProgress(model) { !it.syncing && it.tone == SyncProgressUi.Tone.SUCCESS }
        assertEquals("Saved to watch.", done.note)
    }

    private fun awaitProgress(
        model: ButtonsViewModel,
        predicate: (SyncProgressUi) -> Boolean,
    ): SyncProgressUi = runBlocking { withTimeout(5_000) { model.syncProgress.first { predicate(it) } } }

    @Test
    fun productionButtonUploadIsWired() {
        // WP14 sub-part 3: the real button-config upload pipeline (compile via WP7 → BLE write
        // through the WP3 service's SyncOrchestrator) is wired.
        assertTrue(ServiceButtonSync.BUTTON_UPLOAD_WIRED)
    }

    // ---- WP-SYNCSTATUS: per-button on-watch derivation -----------------------

    private fun repoAt(clock: () -> Long) = qhybrid.android.db.WatchRepository(db, now = clock)

    @Test
    fun neverSynced_everyMappingIsPending() {
        val mac = "AA:00:00:00:00:01"
        runBlocking {
            watchDao.upsert(watch(mac, active = true))
            repoAt { 1_000L }.upsertButton(mapping(mac, 0x10))
        }
        val s = awaitState(vm().uiState) { it.mappings.size == 1 }
        assertEquals(1, s.pendingCount)
        assertFalse(s.isOnWatch(0x10))
    }

    @Test
    fun mappingEditedBeforeSync_isOnWatch() {
        val mac = "AA:00:00:00:00:01"
        runBlocking {
            watchDao.upsert(watch(mac, active = true).copy(buttonsSyncedAt = 2_000L))
            repoAt { 1_000L }.upsertButton(mapping(mac, 0x10))
        }
        val s = awaitState(vm().uiState) { it.mappings.size == 1 && it.pendingCount == 0 }
        assertTrue(s.isOnWatch(0x10))
    }

    @Test
    fun mixedMappings_pendingCountReflectsOnlyEditedSincePush() {
        val mac = "AA:00:00:00:00:01"
        runBlocking {
            watchDao.upsert(watch(mac, active = true).copy(buttonsSyncedAt = 1_500L))
            repoAt { 1_000L }.upsertButton(mapping(mac, 0x10)) // before push → on-watch
            repoAt { 2_000L }.upsertButton(mapping(mac, 0x20)) // after push  → pending
        }
        val s = awaitState(vm().uiState) { it.mappings.size == 2 }
        assertTrue(s.isOnWatch(0x10))
        assertFalse(s.isOnWatch(0x20))
        assertEquals(1, s.pendingCount)
    }
}
