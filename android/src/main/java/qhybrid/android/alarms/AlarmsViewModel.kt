package qhybrid.android.alarms

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import qhybrid.android.db.WatchAlarmEntity
import qhybrid.android.db.WatchEntity
import qhybrid.android.db.WatchRepository
import qhybrid.android.sync.CoroutineDebouncer
import qhybrid.android.sync.Debouncer
import qhybrid.android.sync.GlobalSyncStateSource
import qhybrid.android.sync.SectionSyncStatus
import qhybrid.android.sync.SyncProgressUi
import qhybrid.android.sync.SyncStateSource

/**
 * WP16b — the Alarms screen's immutable UI state. A pure function of the WP4 active-watch
 * row + that watch's alarm rows, filtered/sorted to the **standard user slots 0–15 ONLY**
 * (slots 16–31 are calendar-auto, owned by WP9/WP13 — out of scope here).
 */
data class AlarmsUiState(
    /** The WP4 active watch (the one whose alarms we edit), or null if none. */
    val activeWatch: WatchEntity? = null,
    /** Slot-0–15 alarms for the active watch, sorted by slotId ascending. */
    val alarms: List<WatchAlarmEntity> = emptyList(),
) {
    val activeMac: String? get() = activeWatch?.macAddress
    val hasActiveWatch: Boolean get() = activeWatch != null

    /** True once all 16 user slots are taken (UI disables "Add"). */
    val isFull: Boolean get() = alarms.size >= USER_SLOT_COUNT

    /** Lowest free slot in 0..15, or null if full. */
    val nextFreeSlot: Int?
        get() {
            val used = alarms.mapTo(HashSet()) { it.slotId }
            return (USER_SLOT_MIN..USER_SLOT_MAX).firstOrNull { it !in used }
        }

    // ---- WP-SYNCSTATUS: "is this alarm on the watch?" (pure derivation) ------------

    /** When the watch's alarms section was last (re-)pushed. 0 = never synced. */
    val alarmsSyncedAt: Long get() = activeWatch?.alarmsSyncedAt ?: 0

    /** True iff [slotId]'s alarm has been pushed to the watch since its last edit. */
    fun isOnWatch(slotId: Int): Boolean {
        val alarm = alarms.firstOrNull { it.slotId == slotId } ?: return false
        return SectionSyncStatus.isOnWatch(alarm.updatedAt, alarmsSyncedAt)
    }

    /** How many alarms are NOT yet on the watch (edited since the last push, or never pushed). */
    val pendingCount: Int get() = SectionSyncStatus.pendingCount(alarms.map { it.updatedAt }, alarmsSyncedAt)

    companion object {
        const val USER_SLOT_MIN = 0
        const val USER_SLOT_MAX = 15
        const val USER_SLOT_COUNT = USER_SLOT_MAX - USER_SLOT_MIN + 1 // 16
    }
}

/**
 * WP16b — observes the WP4 active watch and its slot-0–15 alarms into one [AlarmsUiState],
 * and exposes the alarm intents (add/update/delete/toggle/set-days/shortcuts + save).
 *
 * Add/update/delete go through [WatchRepository] (WP4). "Save to watch" delegates to the
 * injectable [AlarmSync] seam so the ViewModel is unit-testable with a fake (no service, no
 * BLE). **No new BLE/protocol behavior is added** — the real alarm-byte upload is WP14
 * (see [AlarmSync]); daysMask is the WP5 wire convention 1:1 (see [AlarmDays]).
 */
@Suppress("OPT_IN_USAGE") // flatMapLatest is experimental-but-stable in our coroutines version
open class AlarmsViewModel(
    private val repo: WatchRepository,
    private val sync: AlarmSync,
    // Tests inject a TestScope/real scope; production passes null → uses [viewModelScope].
    scope: CoroutineScope? = null,
    // WP-PROGRESS (sub-part 3): the process-wide sync signal the Save button observes.
    syncSource: SyncStateSource = GlobalSyncStateSource(),
    // WP-SYNCSTATUS (Step 4): debounces the auto-save so a burst of edits coalesces into ONE
    // ALARMS_ONLY push (and ONE blocking "Saving…" modal), not one per keystroke. Injectable so the
    // auto-save is unit-testable without real time. Defaulted lazily once the scope is resolved.
    debouncer: Debouncer? = null,
) : ViewModel() {

    private val coroutineScope: CoroutineScope = scope ?: viewModelScope

    // WP-SYNCSTATUS (Step 4): ~750 ms coalesce window on the resolved scope unless a test injects one.
    private val autoSaveDebouncer: Debouncer = debouncer ?: CoroutineDebouncer(coroutineScope)

    val uiState: StateFlow<AlarmsUiState> =
        repo.observeActiveWatch()
            .flatMapLatest { active -> alarmsFor(active) }
            .stateIn(coroutineScope, SharingStarted.WhileSubscribed(5_000), AlarmsUiState())

    /**
     * WP-PROGRESS (sub-part 3) — the Save button's progress state, mapped purely from the
     * process-wide [qhybrid.android.sync.SyncState] via [SyncProgressUi] (spinner + disable while
     * SYNCING; transient success/error note). Visual rendering is on-device-pending.
     */
    val syncProgress: StateFlow<SyncProgressUi> =
        syncSource.status
            .map { SyncProgressUi.from(it) }
            .stateIn(coroutineScope, SharingStarted.WhileSubscribed(5_000), SyncProgressUi.IDLE)

    private fun alarmsFor(active: WatchEntity?): Flow<AlarmsUiState> {
        val mac = active?.macAddress ?: return flowOf(AlarmsUiState(activeWatch = active))
        return repo.observeAlarms(mac).map { rows ->
            val userAlarms = rows
                .filter { it.slotId in AlarmsUiState.USER_SLOT_MIN..AlarmsUiState.USER_SLOT_MAX }
                .sortedBy { it.slotId }
            AlarmsUiState(activeWatch = active, alarms = userAlarms)
        }
    }

    // ---- intents -------------------------------------------------------------

    /**
     * Add a new alarm into the lowest free user slot (0..15). No-op if there is no active
     * watch or all 16 slots are taken (the 16-slot user cap). Defaults to a sensible
     * enabled, repeating, weekday alarm; callers may override.
     */
    fun addAlarm(
        hour: Int = 7,
        minute: Int = 0,
        daysMask: Int = AlarmDays.WEEKDAY,
        isRepeating: Boolean = true,
        isEnabled: Boolean = true,
        label: String? = null,
    ) {
        val state = uiState.value
        val mac = state.activeMac ?: return
        val slot = state.nextFreeSlot ?: return // cap at 16
        coroutineScope.launch {
            repo.upsertAlarm(
                WatchAlarmEntity(
                    watchMac = mac,
                    slotId = slot,
                    hour = hour,
                    minute = minute,
                    isEnabled = isEnabled,
                    daysMask = daysMask and AlarmDays.EVERYDAY,
                    isRepeating = isRepeating,
                    label = label,
                )
            )
        }
        scheduleAutoSave()
    }

    /** Upsert an edited alarm row (slotId/watchMac is the composite PK, so it replaces). */
    fun updateAlarm(alarm: WatchAlarmEntity) {
        coroutineScope.launch {
            repo.upsertAlarm(alarm.copy(daysMask = alarm.daysMask and AlarmDays.EVERYDAY))
        }
        scheduleAutoSave()
    }

    /** Delete the alarm in [slotId] of the active watch. No-op if no active watch. */
    fun deleteAlarm(slotId: Int) {
        val mac = uiState.value.activeMac ?: return
        coroutineScope.launch { repo.deleteAlarmSlot(mac, slotId) }
        scheduleAutoSave()
    }

    /**
     * WP-SYNCSTATUS (Step 4) — after an alarm add/edit/delete (the row is already written to Room),
     * schedule a DEBOUNCED [SyncSection.ALARMS_ONLY] push (the whole 32-slot file re-push, exactly
     * what the manual save did). A burst of edits coalesces into ONE push via [autoSaveDebouncer],
     * so BLE isn't spammed and the blocking "Saving to watch…" modal appears once per coalesced
     * save (the seam publishes SYNCING when it actually fires, not per keystroke). The synced-marker
     * then flips each row to ✓ shortly after.
     */
    private fun scheduleAutoSave() {
        autoSaveDebouncer.schedule { sync.saveToWatch() }
    }

    /** Flip the enabled flag of the alarm in [slotId]. */
    fun toggleEnabled(slotId: Int) {
        val alarm = uiState.value.alarms.firstOrNull { it.slotId == slotId } ?: return
        updateAlarm(alarm.copy(isEnabled = !alarm.isEnabled))
    }

    /** Replace the whole days mask of [slotId] (wire convention 1:1, see [AlarmDays]). */
    fun setDays(slotId: Int, daysMask: Int) {
        val alarm = uiState.value.alarms.firstOrNull { it.slotId == slotId } ?: return
        updateAlarm(alarm.copy(daysMask = daysMask and AlarmDays.EVERYDAY))
    }

    /** Toggle a single day bit of [slotId]. */
    fun toggleDay(slotId: Int, dayBit: Int) {
        val alarm = uiState.value.alarms.firstOrNull { it.slotId == slotId } ?: return
        updateAlarm(alarm.copy(daysMask = AlarmDays.toggle(alarm.daysMask, dayBit)))
    }

    /** Shortcut: Mon–Fri (0x3E) + repeating. */
    fun setWeekdays(slotId: Int) = applyShortcut(slotId, AlarmDays.WEEKDAY)

    /** Shortcut: Sat+Sun (0x41) + repeating. */
    fun setWeekend(slotId: Int) = applyShortcut(slotId, AlarmDays.WEEKEND)

    /** Shortcut: every day (0x7F) + repeating. */
    fun setEveryday(slotId: Int) = applyShortcut(slotId, AlarmDays.EVERYDAY)

    private fun applyShortcut(slotId: Int, mask: Int) {
        val alarm = uiState.value.alarms.firstOrNull { it.slotId == slotId } ?: return
        updateAlarm(alarm.copy(daysMask = mask, isRepeating = true))
    }

    /**
     * "Save to watch" — the rows are already persisted to Room by the intents above; this
     * delegates to [AlarmSync] to poke the service. Returns whether the real byte upload is
     * wired yet (false until WP14; UI surfaces an "on-device-pending" note when false).
     */
    fun saveToWatch(): Boolean = sync.saveToWatch()

    companion object {
        /** Production factory: real [WatchRepository] + [ServiceAlarmSync]. */
        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    AlarmsViewModel(
                        repo = WatchRepository(appContext),
                        sync = ServiceAlarmSync(appContext),
                    ) as T
            }
        }
    }
}
