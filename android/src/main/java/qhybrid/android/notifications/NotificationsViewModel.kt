package qhybrid.android.notifications

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
import qhybrid.android.db.NotificationRuleEntity
import qhybrid.android.db.WatchEntity
import qhybrid.android.db.WatchRepository
import qhybrid.android.sync.GlobalSyncStateSource
import qhybrid.android.sync.SectionSyncStatus
import qhybrid.android.sync.SyncProgressUi
import qhybrid.android.sync.SyncStateSource

/**
 * WP16c — the Notifications screen's immutable UI state. A pure function of the WP4
 * active-watch row + that watch's per-app notification rules, sorted by packageName.
 */
data class NotificationsUiState(
    /** The WP4 active watch (the one whose rules we edit), or null if none. */
    val activeWatch: WatchEntity? = null,
    /** Per-app rules for the active watch, sorted by packageName ascending. */
    val rules: List<NotificationRuleEntity> = emptyList(),
) {
    val activeMac: String? get() = activeWatch?.macAddress
    val hasActiveWatch: Boolean get() = activeWatch != null

    /** Package names already configured (for duplicate-rejection in the UI). */
    val packageNames: Set<String> get() = rules.mapTo(HashSet()) { it.packageName }

    // ---- WP-SYNCSTATUS: "is this rule on the watch?" (pure derivation) -------------

    /** When the watch's notification-filter section was last (re-)pushed. 0 = never synced. */
    val filterSyncedAt: Long get() = activeWatch?.notificationFilterSyncedAt ?: 0

    /** True iff [packageName]'s rule has been pushed to the watch since its last edit. */
    fun isOnWatch(packageName: String): Boolean {
        val rule = rules.firstOrNull { it.packageName == packageName } ?: return false
        return SectionSyncStatus.isOnWatch(rule.updatedAt, filterSyncedAt)
    }

    /** How many rules are NOT yet on the watch (edited since the last push, or never pushed). */
    val pendingCount: Int get() = SectionSyncStatus.pendingCount(rules.map { it.updatedAt }, filterSyncedAt)
}

/**
 * WP16c — observes the WP4 active watch and its per-app notification rules into one
 * [NotificationsUiState], and exposes the rule intents (add/update/delete + per-field
 * setters + save).
 *
 * Add/update/delete go through [WatchRepository] (WP4). "Save to watch" delegates to the
 * injectable [NotificationSync] seam so the ViewModel is unit-testable with a fake (no
 * service, no BLE). **No new BLE/protocol behavior is added** — the real filter-byte upload
 * is WP14 (see [NotificationSync]); vibePattern is the WP6 wire convention 1:1 (see
 * [VibePatterns]).
 */
@Suppress("OPT_IN_USAGE") // flatMapLatest is experimental-but-stable in our coroutines version
open class NotificationsViewModel(
    private val repo: WatchRepository,
    private val sync: NotificationSync,
    // Tests inject a TestScope/real scope; production passes null → uses [viewModelScope].
    scope: CoroutineScope? = null,
    // WP-PROGRESS (sub-part 3): the process-wide sync signal the Save button observes.
    syncSource: SyncStateSource = GlobalSyncStateSource(),
    // WP11: the per-app "Play" test button's seam (mirrors the buzz seam). Default Noop so tests
    // that don't exercise it never poke the service; production injects [ServiceNotificationPlay].
    private val play: NotificationPlay = NoopNotificationPlay,
) : ViewModel() {

    private val coroutineScope: CoroutineScope = scope ?: viewModelScope

    val uiState: StateFlow<NotificationsUiState> =
        repo.observeActiveWatch()
            .flatMapLatest { active -> rulesFor(active) }
            .stateIn(coroutineScope, SharingStarted.WhileSubscribed(5_000), NotificationsUiState())

    /**
     * WP-PROGRESS (sub-part 3) — the Save button's progress state, mapped purely from the
     * process-wide [qhybrid.android.sync.SyncState] via [SyncProgressUi] (spinner + disable while
     * SYNCING; transient success/error note). Visual rendering is on-device-pending.
     */
    val syncProgress: StateFlow<SyncProgressUi> =
        syncSource.status
            .map { SyncProgressUi.from(it) }
            .stateIn(coroutineScope, SharingStarted.WhileSubscribed(5_000), SyncProgressUi.IDLE)

    private fun rulesFor(active: WatchEntity?): Flow<NotificationsUiState> {
        val mac = active?.macAddress ?: return flowOf(NotificationsUiState(activeWatch = active))
        return repo.observeRules(mac).map { rows ->
            // observeRules already ORDER BYs packageName, but re-sort defensively so the
            // UiState contract (sorted by packageName) holds regardless of the DAO query.
            NotificationsUiState(activeWatch = active, rules = rows.sortedBy { it.packageName })
        }
    }

    // ---- intents -------------------------------------------------------------

    /**
     * Add a new per-app rule. **Rejects a duplicate packageName** for the active watch
     * (the composite PK is [watchMac, packageName]; we don't want an add to silently
     * REPLACE an existing rule). No-op (returns false) if there is no active watch, the
     * package is blank, or a rule for that package already exists.
     *
     * @return true if the rule was queued for insert, false if rejected.
     */
    fun addRule(
        packageName: String,
        vibePattern: Int = VibePatterns.DEFAULT,
        hourHandDegrees: Int = 0,
        minuteHandDegrees: Int = 0,
    ): Boolean {
        val state = uiState.value
        val mac = state.activeMac ?: return false
        val pkg = packageName.trim()
        if (pkg.isEmpty()) return false
        if (pkg in state.packageNames) return false // duplicate-package rejection
        coroutineScope.launch {
            // Guard against a TOCTOU race with Room's flow latency: re-check the DB.
            if (repo.getRules(mac).any { it.packageName == pkg }) return@launch
            repo.upsertRule(
                NotificationRuleEntity(
                    watchMac = mac,
                    packageName = pkg,
                    vibePattern = VibePatterns.clamp(vibePattern),
                    hourHandDegrees = VibePatterns.clampDegrees(hourHandDegrees),
                    minuteHandDegrees = VibePatterns.clampDegrees(minuteHandDegrees),
                )
            )
        }
        return true
    }

    /**
     * Upsert an edited rule row (watchMac/packageName is the composite PK, so it replaces
     * the matching row). Values are clamped to their valid ranges.
     */
    fun updateRule(rule: NotificationRuleEntity) {
        coroutineScope.launch {
            repo.upsertRule(
                rule.copy(
                    vibePattern = VibePatterns.clamp(rule.vibePattern),
                    hourHandDegrees = VibePatterns.clampDegrees(rule.hourHandDegrees),
                    minuteHandDegrees = VibePatterns.clampDegrees(rule.minuteHandDegrees),
                )
            )
        }
    }

    /** Delete the rule for [packageName] of the active watch. No-op if no active watch. */
    fun deleteRule(packageName: String) {
        val mac = uiState.value.activeMac ?: return
        coroutineScope.launch { repo.deleteRule(mac, packageName) }
    }

    /**
     * WP11 — **test** the on-watch play for [packageName]'s rule: poke the watch to play that
     * package's notification (buzz + move hands per the rule that's already on the watch in its
     * NOTIFICATION_FILTER). A play-only-by-package put — invents NO new wire bytes (reuses the WP11
     * [NotificationPlay] seam). No-op (returns false) without an active watch or without a saved
     * rule for [packageName] (the watch needs the filter entry for the buzz to apply the configured
     * vibe + hands). Returns whether the play was triggered.
     */
    fun playRule(packageName: String): Boolean {
        if (!uiState.value.hasActiveWatch) return false
        if (uiState.value.rules.none { it.packageName == packageName }) return false
        return play.play(packageName)
    }

    /**
     * Flip the enabled flag of [packageName]'s rule. A disabled rule is kept (still listed/editable)
     * but excluded from the uploaded notification filter, so the watch stops buzzing for that app
     * without losing the rule's vibe/hands config (mirrors the alarm enable/disable switch).
     */
    fun toggleEnabled(packageName: String) {
        val rule = uiState.value.rules.firstOrNull { it.packageName == packageName } ?: return
        updateRule(rule.copy(isEnabled = !rule.isEnabled))
    }

    /** Set the vibe pattern (0–9, WP6 1:1) for [packageName]'s rule. */
    fun setVibePattern(packageName: String, vibePattern: Int) {
        val rule = uiState.value.rules.firstOrNull { it.packageName == packageName } ?: return
        updateRule(rule.copy(vibePattern = VibePatterns.clamp(vibePattern)))
    }

    /** Set the hour + minute hand positions (0–359 degrees) for [packageName]'s rule. */
    fun setHandPosition(packageName: String, hourHandDegrees: Int, minuteHandDegrees: Int) {
        val rule = uiState.value.rules.firstOrNull { it.packageName == packageName } ?: return
        updateRule(
            rule.copy(
                hourHandDegrees = VibePatterns.clampDegrees(hourHandDegrees),
                minuteHandDegrees = VibePatterns.clampDegrees(minuteHandDegrees),
            )
        )
    }

    /**
     * "Save to watch" — the rows are already persisted to Room by the intents above; this
     * delegates to [NotificationSync] to poke the service. Returns whether the real
     * filter-byte upload is wired yet (false until WP14; the UI surfaces an
     * "on-device-pending" note when false).
     */
    fun saveToWatch(): Boolean = sync.saveToWatch()

    companion object {
        /** Production factory: real [WatchRepository] + [ServiceNotificationSync]. */
        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    NotificationsViewModel(
                        repo = WatchRepository(appContext),
                        sync = ServiceNotificationSync(appContext),
                        play = ServiceNotificationPlay(appContext),
                    ) as T
            }
        }
    }
}
