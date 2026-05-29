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
)

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
        )
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

    private companion object {
        const val PREFS = "fossilq_settings"
        const val KEY_NUDGE_ENABLED = "nudge_enabled"
        const val KEY_NUDGE_MINUTES = "nudge_minutes"
        const val KEY_TZ_OFFSET = "second_tz_offset_minutes"
        const val KEY_MUSIC_APP = "preferred_music_app"
    }
}
