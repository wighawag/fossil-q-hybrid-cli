package qhybrid.android.buttons

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
import qhybrid.android.db.ButtonMappingEntity
import qhybrid.android.db.WatchEntity
import qhybrid.android.db.WatchRepository

/**
 * WP16d — the Buttons screen's immutable UI state. A pure function of the WP4 active-watch
 * row + that watch's per-button mappings, sorted by buttonId.
 *
 * **MODEL-AGNOSTIC:** no per-model filtering — whatever [ButtonMappingEntity] rows exist for
 * the active watch are surfaced (any count, any buttonId). The "Add button mapping" flow lets
 * the user pick/enter any buttonId.
 */
data class ButtonsUiState(
    /** The WP4 active watch (the one whose mappings we edit), or null if none. */
    val activeWatch: WatchEntity? = null,
    /** Per-button mappings for the active watch, sorted by buttonId ascending. */
    val mappings: List<ButtonMappingEntity> = emptyList(),
) {
    val activeMac: String? get() = activeWatch?.macAddress
    val hasActiveWatch: Boolean get() = activeWatch != null

    /** Button ids already configured (for duplicate-rejection in the UI). */
    val buttonIds: Set<Int> get() = mappings.mapTo(HashSet()) { it.buttonId }

    /** The existing mapping for [buttonId], or null if that slot is unconfigured. */
    fun mappingFor(buttonId: Int): ButtonMappingEntity? =
        mappings.firstOrNull { it.buttonId == buttonId }

    /**
     * The fixed three-button view (TOP/MIDDLE/BOTTOM) the Buttons screen renders. Each entry is
     * the existing [ButtonMappingEntity] for that physical button, or null when unconfigured.
     */
    val slots: List<Pair<Int, ButtonMappingEntity?>>
        get() = ButtonSlots.ALL.map { id -> id to mappingFor(id) }
}

/**
 * WP16d — observes the WP4 active watch and its per-button mappings into one [ButtonsUiState],
 * and exposes the mapping intents (add/update/set-mode/set-actions/reset + save). Mirrors WP16c
 * [qhybrid.android.notifications.NotificationsViewModel] exactly (flatMapLatest over
 * observeActiveWatch; injectable [ButtonSync] seam; production [factory]).
 *
 * Add/update/reset go through [WatchRepository] (WP4). "Save to watch" delegates to the
 * injectable [ButtonSync] seam so the ViewModel is unit-testable with a fake (no service, no
 * BLE). **No new BLE/protocol behavior is added** — the real button-config upload is WP14
 * (see [ButtonSync]). The action/mode vocabulary is the model-agnostic catalog in
 * [ButtonModes] / [ButtonActions] / [ButtonDialModes]; `actionsJson` is handled by
 * [ButtonActionsJson].
 */
@Suppress("OPT_IN_USAGE") // flatMapLatest is experimental-but-stable in our coroutines version
open class ButtonsViewModel(
    private val repo: WatchRepository,
    private val sync: ButtonSync,
    // Tests inject a TestScope/real scope; production passes null → uses [viewModelScope].
    scope: CoroutineScope? = null,
) : ViewModel() {

    private val coroutineScope: CoroutineScope = scope ?: viewModelScope

    val uiState: StateFlow<ButtonsUiState> =
        repo.observeActiveWatch()
            .flatMapLatest { active -> mappingsFor(active) }
            .stateIn(coroutineScope, SharingStarted.WhileSubscribed(5_000), ButtonsUiState())

    private fun mappingsFor(active: WatchEntity?): Flow<ButtonsUiState> {
        val mac = active?.macAddress ?: return flowOf(ButtonsUiState(activeWatch = active))
        return repo.observeButtons(mac).map { rows ->
            // observeButtons already ORDER BYs buttonId, but re-sort defensively so the
            // UiState contract (sorted by buttonId) holds regardless of the DAO query.
            ButtonsUiState(activeWatch = active, mappings = rows.sortedBy { it.buttonId })
        }
    }

    // ---- intents -------------------------------------------------------------

    /**
     * Add a new per-button mapping. **Rejects a duplicate buttonId** for the active watch (the
     * composite PK is [watchMac, buttonId]; we don't want an add to silently REPLACE an
     * existing mapping). No-op (returns false) if there is no active watch or a mapping for
     * that buttonId already exists. The buttonId is NOT range-checked — any value is allowed
     * (model-agnostic; hardware validation is WP14).
     *
     * @return true if the mapping was queued for insert, false if rejected.
     */
    fun addMapping(
        buttonId: Int,
        modeType: String = ButtonModes.DEFAULT,
        actionsJson: String = ButtonActionsJson.encode(listOf(ButtonActions.DEFAULT)),
    ): Boolean {
        val state = uiState.value
        val mac = state.activeMac ?: return false
        if (buttonId in state.buttonIds) return false // duplicate-buttonId rejection
        val mode = ButtonModes.normalize(modeType)
        // Normalize the JSON through the helper so a malformed string can't be persisted.
        val json = ButtonActionsJson.encode(ButtonActionsJson.decode(actionsJson))
        coroutineScope.launch {
            // Guard against a TOCTOU race with Room's flow latency: re-check the DB.
            if (repo.getButton(mac, buttonId) != null) return@launch
            repo.upsertButton(
                ButtonMappingEntity(
                    watchMac = mac,
                    buttonId = buttonId,
                    modeType = mode,
                    actionsJson = json,
                )
            )
        }
        return true
    }

    /**
     * Upsert an edited mapping row (watchMac/buttonId is the composite PK, so it replaces the
     * matching row). The modeType is normalized and the actionsJson is round-tripped through
     * [ButtonActionsJson] so a malformed string can't be persisted.
     */
    fun updateMapping(mapping: ButtonMappingEntity) {
        coroutineScope.launch {
            repo.upsertButton(
                mapping.copy(
                    modeType = ButtonModes.normalize(mapping.modeType),
                    actionsJson = ButtonActionsJson.encode(ButtonActionsJson.decode(mapping.actionsJson)),
                )
            )
        }
    }

    /** Set the [modeType] for [buttonId]'s mapping. No-op if the mapping doesn't exist. */
    fun setMode(buttonId: Int, modeType: String) {
        val mapping = uiState.value.mappings.firstOrNull { it.buttonId == buttonId } ?: return
        updateMapping(mapping.copy(modeType = ButtonModes.normalize(modeType)))
    }

    /** Set the [actionsJson] for [buttonId]'s mapping. No-op if the mapping doesn't exist. */
    fun setActions(buttonId: Int, actionsJson: String) {
        val mapping = uiState.value.mappings.firstOrNull { it.buttonId == buttonId } ?: return
        updateMapping(mapping.copy(actionsJson = actionsJson))
    }

    /** Convenience: set the actions for [buttonId] from a typed list (encoded via the helper). */
    fun setActionList(buttonId: Int, actions: List<String>) {
        setActions(buttonId, ButtonActionsJson.encode(actions))
    }

    /**
     * WP16d (3-slot UI) — upsert one physical button slot's full config (mode + action/dial-mode
     * id list) in a single write, creating the row if the slot was previously unconfigured.
     * No-op if there is no active watch. The modeType is normalized and the id list is
     * round-tripped through [ButtonActionsJson] so a malformed value can't be persisted.
     */
    fun setSlot(buttonId: Int, modeType: String, ids: List<String>) {
        val mac = uiState.value.activeMac ?: return
        val mode = ButtonModes.normalize(modeType)
        // WP-BTN: enforce the cardinality contract BEFORE persisting — a single-action mode is
        // collapsed to a single id; only CUSTOM_TOGGLE keeps the full list. This guarantees the
        // editor can never store an invalid combination.
        val normalizedIds = ButtonMappingRules.normalizeIds(mode, ButtonActionsJson.decode(ButtonActionsJson.encode(ids)))
        coroutineScope.launch {
            repo.upsertButton(
                ButtonMappingEntity(
                    watchMac = mac,
                    buttonId = buttonId,
                    modeType = mode,
                    actionsJson = ButtonActionsJson.encode(normalizedIds),
                )
            )
        }
    }

    /**
     * Reset (remove) the mapping for [buttonId] of the active watch via the single-row
     * [WatchRepository.deleteButton]. No-op if there is no active watch.
     */
    fun resetButton(buttonId: Int) {
        val mac = uiState.value.activeMac ?: return
        coroutineScope.launch { repo.deleteButton(mac, buttonId) }
    }

    /**
     * "Save to watch" — the rows are already persisted to Room by the intents above; this
     * delegates to [ButtonSync] to poke the service. Returns whether the real button-config
     * upload is wired yet (false until WP14; the UI surfaces an "on-device-pending" note when
     * false).
     */
    fun saveToWatch(): Boolean = sync.saveToWatch()

    companion object {
        /** Production factory: real [WatchRepository] + [ServiceButtonSync]. */
        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ButtonsViewModel(
                        repo = WatchRepository(appContext),
                        sync = ServiceButtonSync(appContext),
                    ) as T
            }
        }
    }
}
