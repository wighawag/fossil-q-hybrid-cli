package qhybrid.android.defaults

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import qhybrid.android.buttons.ButtonActionsJson
import qhybrid.android.buttons.ButtonMappingRules
import qhybrid.android.buttons.ButtonModes
import qhybrid.android.settings.ApplyDefaultsSync
import qhybrid.android.settings.NoopApplyDefaults

/**
 * WP-DEFAULTS (sub-part 4) — the "Defaults for new watches" editor's immutable UI state. It is the
 * [DefaultsProfile] itself plus a couple of convenience accessors, so the same editor widgets used
 * by the per-watch screens can bind to it.
 */
data class DefaultsUiState(
    val profile: DefaultsProfile = DefaultsProfile.FACTORY,
) {
    val alarms get() = profile.alarms
    val rules get() = profile.rules
    val buttons get() = profile.buttons

    /** The three physical button slots (TOP/MIDDLE/BOTTOM), each with its DefaultButton or null. */
    fun buttonFor(buttonId: Int): DefaultButton? = buttons.firstOrNull { it.buttonId == buttonId }
}

/**
 * WP-DEFAULTS (sub-part 4) — the ViewModel for the app-level defaults editor. Mirrors the existing
 * `*ViewModel` style (an injectable store seam, a [StateFlow] UI state, pure-testable with a fake
 * store). Wire/IO stays OUT of the VM — edits go to the [DefaultsProfileStore]; the on-demand
 * "apply to this watch" push is delegated to the injectable [ApplyDefaultsSync] seam (fake in
 * tests), the SAME seam the Settings screen uses.
 *
 * The editor binds the SAME vocabulary/widgets as the per-watch Alarms / Notifications / Buttons
 * screens, but to the profile store instead of a watch row. Cardinality is enforced via
 * [ButtonMappingRules] so an invalid button mapping can never be persisted into the profile.
 */
open class DefaultsViewModel(
    private val store: DefaultsProfileStore,
    private val applyDefaults: ApplyDefaultsSync = NoopApplyDefaults,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DefaultsUiState(store.get()))
    val uiState: StateFlow<DefaultsUiState> = _uiState.asStateFlow()

    private fun publish(profile: DefaultsProfile) {
        store.set(profile)
        _uiState.value = DefaultsUiState(profile)
    }

    private fun current(): DefaultsProfile = _uiState.value.profile

    // ---- alarms --------------------------------------------------------------

    /** Replace the whole default-alarms list. */
    fun setAlarms(alarms: List<DefaultAlarm>) = publish(current().copy(alarms = alarms))

    /** Upsert one default alarm by slotId (replaces a same-slot row, else appends). */
    fun upsertAlarm(alarm: DefaultAlarm) {
        val rest = current().alarms.filterNot { it.slotId == alarm.slotId }
        publish(current().copy(alarms = (rest + alarm).sortedBy { it.slotId }))
    }

    /** Remove the default alarm in [slotId]. */
    fun removeAlarm(slotId: Int) =
        publish(current().copy(alarms = current().alarms.filterNot { it.slotId == slotId }))

    // ---- rules ---------------------------------------------------------------

    /** Replace the whole default-rules list. */
    fun setRules(rules: List<DefaultRule>) = publish(current().copy(rules = rules))

    /** Upsert one default rule by packageName (replaces a same-package row, else appends). */
    fun upsertRule(rule: DefaultRule) {
        val rest = current().rules.filterNot { it.packageName == rule.packageName }
        publish(current().copy(rules = rest + rule))
    }

    /** Remove the default rule for [packageName]. */
    fun removeRule(packageName: String) =
        publish(current().copy(rules = current().rules.filterNot { it.packageName == packageName }))

    // ---- buttons -------------------------------------------------------------

    /**
     * Set one physical button slot's full config (mode + action/dial-mode id list) in the profile,
     * enforcing the [ButtonMappingRules] cardinality contract BEFORE persisting (a single-action
     * mode is collapsed to a single id; only CUSTOM_TOGGLE keeps the full canonical cycle). This
     * guarantees the defaults editor can never store an invalid mapping — same rule the per-watch
     * Buttons editor applies.
     */
    fun setButtonSlot(buttonId: Int, modeType: String, ids: List<String>) {
        val mode = ButtonModes.normalize(modeType)
        // Round-trip through the JSON helper (drops blanks) then normalize cardinality.
        val normalized = ButtonMappingRules.normalizeIds(mode, ButtonActionsJson.decode(ButtonActionsJson.encode(ids)))
        val rest = current().buttons.filterNot { it.buttonId == buttonId }
        val updated = (rest + DefaultButton(buttonId, mode, normalized)).sortedBy { it.buttonId }
        publish(current().copy(buttons = updated))
    }

    /** Clear (remove) one physical button slot's default mapping. */
    fun clearButtonSlot(buttonId: Int) =
        publish(current().copy(buttons = current().buttons.filterNot { it.buttonId == buttonId }))

    /** Clear ALL default buttons (new watches then get blank buttons). */
    fun clearAllButtons() = publish(current().copy(buttons = emptyList()))

    // ---- reset + apply -------------------------------------------------------

    /** Restore the [DefaultsProfile.FACTORY] defaults (factory buttons; empty alarms/rules). */
    fun resetToFactory() {
        store.resetToFactory()
        _uiState.value = DefaultsUiState(store.get())
    }

    /**
     * WP-DEFAULTS (sub-part 3 surfaced here) — apply the CURRENT defaults profile to the active
     * watch on demand (full-overwrite of buttons + filter). Delegates to the injectable
     * [ApplyDefaultsSync] seam (which persists the re-keyed rows + triggers a targeted push, and
     * no-ops without an active watch). Returns whether it was dispatched.
     */
    fun applyToActiveWatch(): Boolean = applyDefaults.applyDefaultsToActiveWatch()

    companion object {
        /** Production factory: SharedPreferences-backed store + the service apply-defaults seam. */
        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    DefaultsViewModel(
                        store = SharedPreferencesDefaultsProfileStore(appContext),
                        applyDefaults = qhybrid.android.settings.ServiceApplyDefaults.create(appContext) { block ->
                            kotlinx.coroutines.CoroutineScope(
                                kotlinx.coroutines.Dispatchers.IO +
                                    kotlinx.coroutines.SupervisorJob()
                            ).launch { block() }
                        },
                    ) as T
            }
        }
    }
}
