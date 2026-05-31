package qhybrid.android.settings

import android.content.Context

/**
 * WP16g — the persisted, **app-level** preferences the Settings screen owns that do NOT have a WP4
 * [qhybrid.android.db.WatchEntity] field and that the WP16/WP16g breakdown does NOT say WP16g owns
 * a new Room field for. Per the task brief, when a setting is neither (a) a persisted WatchEntity
 * field nor (c) a purely-live command, it is (b) a process/app-level pref with an existing owner.
 *
 * **DATA-SOURCE DECISION (per setting):**
 *   - **Vibration strength** is (a) — it IS a [qhybrid.android.db.WatchEntity.vibrationStrength]
 *     field already, so it round-trips through [qhybrid.android.db.WatchRepository], NOT here.
 *   - **Inactivity nudge** (enabled + duration) and **second timezone offset** have NO WatchEntity
 *     field and the breakdown does not give WP16g a new Room field, so they are app-level prefs
 *     here. They are ALSO live watch commands (applied via [SettingsSync]); the value is persisted
 *     here and the apply is deferred behind `SETTINGS_WIRED=false`.
 *   - **Preferred music app** is a pure phone-side pref (ANDROID-PLAN §4.E music-control fallback);
 *     it is never sent to the watch, so it lives here only.
 *
 * Kept behind this small interface so [SettingsViewModel] is unit-testable with an in-memory fake
 * (no Android `SharedPreferences`, no `DataStore` test scaffolding). The production impl
 * ([SharedPreferencesSettingsPrefs]) is a tiny SharedPreferences blob — the same lightweight,
 * isolated approach WP3's [qhybrid.android.CompanionManager] uses for the associated MAC, so a
 * later WP can swap it for Jetpack DataStore (ANDROID-PLAN §6) without touching the ViewModel.
 *
 * These prefs are intentionally NOT per-watch-keyed: nudge/timezone/music are user-level
 * preferences (the same person, the same phone). Per-watch persisted state stays in Room.
 */
data class AppSettings(
    /** Whether the inactivity (sedentary) nudge is enabled. */
    val nudgeEnabled: Boolean = SettingsVocabulary.NUDGE_DEFAULT_ENABLED,
    /** Inactivity duration in minutes before the watch nudges (1–255). */
    val nudgeMinutes: Int = SettingsVocabulary.NUDGE_DEFAULT_MINUTES,
    /** Second-timezone offset in minutes from UTC (−720..+840). */
    val secondTimezoneOffsetMinutes: Int = SettingsVocabulary.TZ_DEFAULT_OFFSET_MINUTES,
    /** Preferred music-app package id, or [SettingsVocabulary.MUSIC_APP_NONE] if unset. */
    val preferredMusicApp: String = SettingsVocabulary.MUSIC_APP_NONE,
    /**
     * WP13 — how many minutes BEFORE a calendar event the watch alarm rings (0 = at event time).
     * Applied by [qhybrid.android.calendar.CalendarRefresher] before the pure WP9 mapper. Default 1.
     */
    val calendarAlarmOffsetMinutes: Int = SettingsVocabulary.CAL_OFFSET_DEFAULT_MINUTES,
    /**
     * WP-TRACKER — the GLOBAL multi-function role (MUSIC ⇄ TRACKER) that decides how the watch's
     * button-blind 0x05 gesture stream is interpreted. Necessarily global (the 0x05 path carries no
     * button id — see [SettingsVocabulary.MULTI_FUNCTION_ROLE_MUSIC]). Default MUSIC (WP12 behaviour).
     */
    val multiFunctionRole: String = SettingsVocabulary.MULTI_FUNCTION_ROLE_DEFAULT,
    /**
     * L0 — the configurable, ORDERED multi-function rotation the switch button iterates through
     * (see [SettingsVocabulary.normalizeRotation]). First entry = default/active. Default = phone
     * media only ([SettingsVocabulary.MULTI_FUNCTION_ROTATION_DEFAULT]).
     */
    val multiFunctionRotation: List<String> = SettingsVocabulary.MULTI_FUNCTION_ROTATION_DEFAULT,
    /** L0 — index into [multiFunctionRotation] of the currently-active mode (clamped to range). */
    val multiFunctionActiveIndex: Int = 0,
    /**
     * WP-TRACKER — how long (seconds) the loud "find my phone" ring plays before auto-stopping (a
     * pocketed phone can't ring forever). A repeated TRACKER long gesture / RING_PHONE press also
     * stops it early. Phone-side only — never sent to the watch. Default 60s (1 minute).
     */
    val ringDurationSeconds: Int = SettingsVocabulary.RING_DURATION_DEFAULT_SECONDS,
) {
    /** The currently-active multi-function mode (clamped; never throws). */
    val activeMode: String
        get() = SettingsVocabulary.activeMode(multiFunctionRotation, multiFunctionActiveIndex)
}

/**
 * WP16g — injectable seam over the app-level Settings prefs (nudge / second timezone / music app)
 * so the ViewModel is testable with a fake.
 */
interface SettingsPrefs {
    /** All app-level settings (defaults applied for any absent key). */
    fun get(): AppSettings

    /** Persist the inactivity-nudge enabled flag + duration (duration normalized 1–255). */
    fun setNudge(enabled: Boolean, minutes: Int)

    /** Persist the second-timezone offset in minutes (normalized to UTC−12..UTC+14). */
    fun setSecondTimezoneOffset(minutes: Int)

    /** Persist the preferred music-app package (blank/null clears it to NONE). */
    fun setPreferredMusicApp(pkg: String?)

    /** WP13 — persist the calendar-alarm ring offset in minutes (normalized 0..120). */
    fun setCalendarAlarmOffset(minutes: Int)

    /** WP-TRACKER — persist the legacy global multi-function role (blank/unknown → default MUSIC). */
    fun setMultiFunctionRole(role: String?)

    /**
     * L0 — persist the ordered multi-function rotation (normalized). Resets the active index to 0
     * (first entry = default) since the rotation membership/order changed.
     */
    fun setMultiFunctionRotation(modes: List<String>?)

    /** L0 — persist the active-mode index (clamped to the current rotation's range). */
    fun setMultiFunctionActiveIndex(index: Int)

    /** WP-TRACKER — persist the loud-ring auto-stop duration in seconds (normalized 5..300). */
    fun setRingDurationSeconds(seconds: Int)
}

/**
 * Production [SettingsPrefs] — a tiny SharedPreferences blob. Holds the application context so it
 * never leaks an Activity. Values are normalized on write via [SettingsVocabulary] so an
 * out-of-range value can never be persisted.
 */
class SharedPreferencesSettingsPrefs(context: Context) : SettingsPrefs {
    private val appContext = context.applicationContext

    private val prefs
        get() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun get(): AppSettings {
        val p = prefs
        return AppSettings(
            nudgeEnabled = p.getBoolean(KEY_NUDGE_ENABLED, SettingsVocabulary.NUDGE_DEFAULT_ENABLED),
            nudgeMinutes = SettingsVocabulary.normalizeNudgeMinutes(
                p.getInt(KEY_NUDGE_MINUTES, SettingsVocabulary.NUDGE_DEFAULT_MINUTES)
            ),
            secondTimezoneOffsetMinutes = SettingsVocabulary.normalizeTzOffset(
                p.getInt(KEY_TZ_OFFSET, SettingsVocabulary.TZ_DEFAULT_OFFSET_MINUTES)
            ),
            preferredMusicApp = SettingsVocabulary.normalizeMusicApp(
                p.getString(KEY_MUSIC_APP, SettingsVocabulary.MUSIC_APP_NONE)
            ),
            calendarAlarmOffsetMinutes = SettingsVocabulary.normalizeCalendarOffset(
                p.getInt(KEY_CAL_OFFSET, SettingsVocabulary.CAL_OFFSET_DEFAULT_MINUTES)
            ),
            multiFunctionRole = SettingsVocabulary.normalizeMultiFunctionRole(
                p.getString(KEY_MULTI_FUNCTION_ROLE, SettingsVocabulary.MULTI_FUNCTION_ROLE_DEFAULT)
            ),
            ringDurationSeconds = SettingsVocabulary.normalizeRingDuration(
                p.getInt(KEY_RING_DURATION, SettingsVocabulary.RING_DURATION_DEFAULT_SECONDS)
            ),
            multiFunctionRotation = readRotation(p),
            multiFunctionActiveIndex = SettingsVocabulary.clampIndex(
                readRotation(p), p.getInt(KEY_MULTI_FUNCTION_INDEX, 0)
            ),
        )
    }

    /**
     * L0 — read the rotation, migrating from the legacy [KEY_MULTI_FUNCTION_ROLE] when the new
     * [KEY_MULTI_FUNCTION_ROTATION] key is absent: `MUSIC → [MUSIC_PHONE]`, `TRACKER → [TRACKER]`.
     * Never writes (migration is read-only so rollback stays safe); the value is persisted only when
     * the user next changes the rotation via [setMultiFunctionRotation].
     */
    private fun readRotation(p: android.content.SharedPreferences): List<String> {
        val csv = p.getString(KEY_MULTI_FUNCTION_ROTATION, null)
        if (csv != null) return SettingsVocabulary.parseRotation(csv)
        // Migrate from the legacy single-role key.
        val legacy = SettingsVocabulary.normalizeMultiFunctionRole(
            p.getString(KEY_MULTI_FUNCTION_ROLE, SettingsVocabulary.MULTI_FUNCTION_ROLE_DEFAULT)
        )
        val migrated = if (legacy == SettingsVocabulary.MULTI_FUNCTION_ROLE_TRACKER)
            listOf(SettingsVocabulary.MODE_TRACKER)
        else listOf(SettingsVocabulary.MODE_MUSIC_PHONE)
        return SettingsVocabulary.normalizeRotation(migrated)
    }

    override fun setNudge(enabled: Boolean, minutes: Int) {
        prefs.edit()
            .putBoolean(KEY_NUDGE_ENABLED, enabled)
            .putInt(KEY_NUDGE_MINUTES, SettingsVocabulary.normalizeNudgeMinutes(minutes))
            .apply()
    }

    override fun setSecondTimezoneOffset(minutes: Int) {
        prefs.edit()
            .putInt(KEY_TZ_OFFSET, SettingsVocabulary.normalizeTzOffset(minutes))
            .apply()
    }

    override fun setPreferredMusicApp(pkg: String?) {
        prefs.edit()
            .putString(KEY_MUSIC_APP, SettingsVocabulary.normalizeMusicApp(pkg))
            .apply()
    }

    override fun setCalendarAlarmOffset(minutes: Int) {
        prefs.edit()
            .putInt(KEY_CAL_OFFSET, SettingsVocabulary.normalizeCalendarOffset(minutes))
            .apply()
    }

    override fun setMultiFunctionRole(role: String?) {
        prefs.edit()
            .putString(KEY_MULTI_FUNCTION_ROLE, SettingsVocabulary.normalizeMultiFunctionRole(role))
            .apply()
    }

    override fun setMultiFunctionRotation(modes: List<String>?) {
        // Changing the rotation resets the active index to 0 (first entry = default).
        prefs.edit()
            .putString(KEY_MULTI_FUNCTION_ROTATION, SettingsVocabulary.rotationToCsv(modes))
            .putInt(KEY_MULTI_FUNCTION_INDEX, 0)
            .apply()
    }

    override fun setMultiFunctionActiveIndex(index: Int) {
        val rot = readRotation(prefs)
        prefs.edit()
            .putInt(KEY_MULTI_FUNCTION_INDEX, SettingsVocabulary.clampIndex(rot, index))
            .apply()
    }

    override fun setRingDurationSeconds(seconds: Int) {
        prefs.edit()
            .putInt(KEY_RING_DURATION, SettingsVocabulary.normalizeRingDuration(seconds))
            .apply()
    }

    private companion object {
        const val PREFS = "fossilq_settings"
        const val KEY_NUDGE_ENABLED = "nudge_enabled"
        const val KEY_NUDGE_MINUTES = "nudge_minutes"
        const val KEY_TZ_OFFSET = "second_tz_offset_minutes"
        const val KEY_MUSIC_APP = "preferred_music_app"
        const val KEY_CAL_OFFSET = "calendar_alarm_offset_minutes"
        const val KEY_MULTI_FUNCTION_ROLE = "multi_function_role"
        const val KEY_MULTI_FUNCTION_ROTATION = "multi_function_rotation"
        const val KEY_MULTI_FUNCTION_INDEX = "multi_function_active_index"
        const val KEY_RING_DURATION = "ring_duration_seconds"
    }
}
