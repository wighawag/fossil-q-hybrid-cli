package qhybrid.android.sync

import qhybrid.android.db.ButtonMappingEntity
import qhybrid.android.db.NotificationRuleEntity
import qhybrid.android.db.WatchAlarmEntity
import qhybrid.android.db.WatchEntity

/**
 * WP14 — the immutable snapshot of one active watch's configuration that the pure
 * [SyncOrchestrator] compiles + uploads. Assembled from the WP4 Room rows + app settings by
 * [SyncDataLoader] (which does the suspending DB reads), so the orchestrator itself stays a
 * pure function of this input (no Room, no coroutines, no Android service) and is trivially
 * unit-testable.
 *
 * A null/empty section is tolerated: the orchestrator skips that upload (see [SyncOrchestrator]).
 */
data class SyncInput(
    /** The active watch row, or null if there is no active watch (→ orchestrator no-ops). */
    val watch: WatchEntity?,
    /** Standard user alarms (slots 0–15). Calendar slots 16–31 are [calendarAlarms]. */
    val alarms: List<WatchAlarmEntity> = emptyList(),
    /** Calendar-sync alarms (slots 16–31), owned by WP9/WP13 — empty until that lands. */
    val calendarAlarms: List<WatchAlarmEntity> = emptyList(),
    /** Per-app notification rules. */
    val rules: List<NotificationRuleEntity> = emptyList(),
    /** Per-button mappings. */
    val buttons: List<ButtonMappingEntity> = emptyList(),
    /** App-level live settings to apply (vibration is also persisted on [watch]). */
    val settings: SyncSettings = SyncSettings(),
) {
    val hasWatch: Boolean get() = watch != null
    val mac: String? get() = watch?.macAddress
}

/**
 * WP14 — the app-level settings the orchestrator pushes as live `ConfigurationPutRequest`
 * items (WP16g vocabulary). Vibration strength defaults to the watch row; nudge + second
 * timezone come from the WP16g app prefs ([qhybrid.android.settings.SettingsPrefs]).
 *
 * Each "apply" flag lets the caller include/exclude a given live command from a sync pass
 * (e.g. only push vibration when the user changed it). For sync-on-connect the loader includes
 * everything that has a meaningful persisted value.
 */
data class SyncSettings(
    val vibrationStrength: Int? = null,
    val nudgeEnabled: Boolean = false,
    val nudgeMinutes: Int? = null,
    val secondTimezoneOffsetMinutes: Int? = null,
    /** Window the inactivity nudge is active in (defaults to all-day 00:00–23:59). */
    val nudgeFromHour: Int = 0,
    val nudgeFromMinute: Int = 0,
    val nudgeToHour: Int = 23,
    val nudgeToMinute: Int = 59,
)
