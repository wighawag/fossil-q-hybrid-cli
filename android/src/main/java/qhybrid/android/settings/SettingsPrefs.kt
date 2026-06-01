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
     * Per-mode SWITCH-buzz overrides: the buzz the watch plays when the SWITCH button advances to a
     * mode, keyed by mode. Empty = use [SettingsVocabulary.SWITCH_BUZZ_DEFAULTS]. Resolved (override
     * → default → single) via [SettingsVocabulary.switchBuzzFor]. STABLE per mode (not by index).
     */
    val multiFunctionSwitchBuzz: Map<String, Int> = emptyMap(),
    /**
     * TRAILING debounce window (ms) for the SWITCH-mode buzz — rapid presses within this window
     * coalesce to ONE buzz for the FINAL landed mode (see [SettingsVocabulary]). Configurable so it
     * can be tuned/tested on-device. 0 = immediate (no debounce). Phone-side only.
     */
    val multiFunctionSwitchBuzzDebounceMs: Int = SettingsVocabulary.SWITCH_BUZZ_DEBOUNCE_DEFAULT_MS,
    /** L1 — Lyrion (LMS) server host/IP for the [SettingsVocabulary.MODE_MUSIC_LYRION] backend. */
    val lyrionServerHost: String = SettingsVocabulary.LYRION_HOST_NONE,
    /** L1 — Lyrion server HTTP/JSON-RPC port (default 9000). */
    val lyrionServerPort: Int = SettingsVocabulary.LYRION_PORT_DEFAULT,
    /** L1 — target Lyrion player id (MAC), or [SettingsVocabulary.LYRION_PLAYER_NONE] if unset. */
    val lyrionPlayerId: String = SettingsVocabulary.LYRION_PLAYER_NONE,
    /** L1 — cached display name of the target Lyrion player (for the Settings UI). */
    val lyrionPlayerName: String = "",
    /** L1 — empty-queue fallback for PLAY/TOGGLE (FAVORITE default | RANDOM | NONE). */
    val lyrionEmptyQueueFallback: String = SettingsVocabulary.LYRION_FALLBACK_DEFAULT,
    /** L1 — favourite id to start when fallback == FAVORITE (or NONE if unset). */
    val lyrionFavoriteId: String = SettingsVocabulary.LYRION_FAVORITE_NONE,
    /**
     * WP-TRACKER — how long (seconds) the loud "find my phone" ring plays before auto-stopping (a
     * pocketed phone can't ring forever). A repeated TRACKER long gesture / RING_PHONE press also
     * stops it early. Phone-side only — never sent to the watch. Default 60s (1 minute).
     */
    val ringDurationSeconds: Int = SettingsVocabulary.RING_DURATION_DEFAULT_SECONDS,
    /**
     * WP-NAV — GLOBAL on/off for turn-by-turn navigation cues (buzz + point both hands in the turn
     * direction, sourced from OsmAnd/OsmAnd+ via AIDL). Default OFF (opt-in; needs OsmAnd installed).
     */
    val navCueEnabled: Boolean = SettingsVocabulary.NAVCUE_DEFAULT_ENABLED,
    /** WP-NAV — "turn soon" cue distance in metres before the turn. */
    val navCueSoonMeters: Int = SettingsVocabulary.NAVCUE_SOON_METERS_DEFAULT,
    /** WP-NAV — "turn now" cue distance in metres before the turn. */
    val navCueNowMeters: Int = SettingsVocabulary.NAVCUE_NOW_METERS_DEFAULT,
    /**
     * WP-NAV — which OsmAnd AIDL backend to use (the [qhybrid.android.navcue.NavCueBackend] name:
     * AUTO / AIDL_LEGACY / AIDLAPI_V2). Default AUTO (probe both). Set on the diagnostics screen.
     */
    val navCueBackend: String = "AUTO",
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

    /**
     * Persist the SWITCH buzz for one [mode] (normalized onto the 4 valid patterns). Merges into the
     * existing override map (other modes untouched). Setting a mode's default is fine — it's stored
     * as an explicit override but resolves to the same value.
     */
    fun setMultiFunctionSwitchBuzz(mode: String, pattern: Int)

    /** Persist the SWITCH-buzz trailing debounce window in ms (normalized 0..5000). */
    fun setMultiFunctionSwitchBuzzDebounceMs(ms: Int)

    /** L1 — persist the Lyrion server host (blank/null → NONE) + port (clamped/default). */
    fun setLyrionServer(host: String?, port: Int)

    /** L1 — persist the target Lyrion player id (blank/null → NONE) + cached display name. */
    fun setLyrionPlayer(id: String?, name: String?)

    /** L1 — persist the empty-queue fallback (blank/unknown → default FAVORITE). */
    fun setLyrionEmptyQueueFallback(fallback: String?)

    /** L1 — persist the favourite id used by the FAVORITE fallback (blank/null → NONE). */
    fun setLyrionFavoriteId(id: String?)

    /** WP-TRACKER — persist the loud-ring auto-stop duration in seconds (normalized 5..300). */
    fun setRingDurationSeconds(seconds: Int)

    /** WP-NAV — persist the navigation-cues enabled flag + the soon/now trigger distances. */
    fun setNavCue(enabled: Boolean, soonMeters: Int, nowMeters: Int)

    /** WP-NAV — persist the OsmAnd AIDL backend choice (a NavCueBackend enum name). */
    fun setNavCueBackend(backend: String)
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
            multiFunctionSwitchBuzz = SettingsVocabulary.parseSwitchBuzzMap(
                p.getString(KEY_MULTI_FUNCTION_SWITCH_BUZZ, null)
            ),
            multiFunctionSwitchBuzzDebounceMs = SettingsVocabulary.normalizeSwitchBuzzDebounceMs(
                p.getInt(KEY_MULTI_FUNCTION_SWITCH_BUZZ_DEBOUNCE, SettingsVocabulary.SWITCH_BUZZ_DEBOUNCE_DEFAULT_MS)
            ),
            lyrionServerHost = SettingsVocabulary.normalizeLyrionHost(
                p.getString(KEY_LYRION_HOST, SettingsVocabulary.LYRION_HOST_NONE)
            ),
            lyrionServerPort = SettingsVocabulary.normalizeLyrionPort(
                p.getInt(KEY_LYRION_PORT, SettingsVocabulary.LYRION_PORT_DEFAULT)
            ),
            lyrionPlayerId = SettingsVocabulary.normalizeLyrionPlayerId(
                p.getString(KEY_LYRION_PLAYER_ID, SettingsVocabulary.LYRION_PLAYER_NONE)
            ),
            lyrionPlayerName = p.getString(KEY_LYRION_PLAYER_NAME, "").orEmpty(),
            lyrionEmptyQueueFallback = SettingsVocabulary.normalizeLyrionFallback(
                p.getString(KEY_LYRION_FALLBACK, SettingsVocabulary.LYRION_FALLBACK_DEFAULT)
            ),
            lyrionFavoriteId = SettingsVocabulary.normalizeLyrionFavoriteId(
                p.getString(KEY_LYRION_FAVORITE_ID, SettingsVocabulary.LYRION_FAVORITE_NONE)
            ),
            navCueEnabled = p.getBoolean(KEY_NAVCUE_ENABLED, SettingsVocabulary.NAVCUE_DEFAULT_ENABLED),
            navCueSoonMeters = SettingsVocabulary.normalizeNavCueSoonMeters(
                p.getInt(KEY_NAVCUE_SOON_M, SettingsVocabulary.NAVCUE_SOON_METERS_DEFAULT)
            ),
            navCueNowMeters = SettingsVocabulary.normalizeNavCueNowMeters(
                p.getInt(KEY_NAVCUE_NOW_M, SettingsVocabulary.NAVCUE_NOW_METERS_DEFAULT)
            ),
            navCueBackend = p.getString(KEY_NAVCUE_BACKEND, "AUTO") ?: "AUTO",
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

    override fun setMultiFunctionSwitchBuzz(mode: String, pattern: Int) {
        val current = SettingsVocabulary.parseSwitchBuzzMap(
            prefs.getString(KEY_MULTI_FUNCTION_SWITCH_BUZZ, null)
        ).toMutableMap()
        current[SettingsVocabulary.normalizeMode(mode)] = SettingsVocabulary.normalizeSwitchBuzz(pattern)
        prefs.edit()
            .putString(KEY_MULTI_FUNCTION_SWITCH_BUZZ, SettingsVocabulary.switchBuzzMapToCsv(current))
            .apply()
    }

    override fun setMultiFunctionSwitchBuzzDebounceMs(ms: Int) {
        prefs.edit()
            .putInt(
                KEY_MULTI_FUNCTION_SWITCH_BUZZ_DEBOUNCE,
                SettingsVocabulary.normalizeSwitchBuzzDebounceMs(ms),
            )
            .apply()
    }

    override fun setLyrionServer(host: String?, port: Int) {
        prefs.edit()
            .putString(KEY_LYRION_HOST, SettingsVocabulary.normalizeLyrionHost(host))
            .putInt(KEY_LYRION_PORT, SettingsVocabulary.normalizeLyrionPort(port))
            .apply()
    }

    override fun setLyrionPlayer(id: String?, name: String?) {
        prefs.edit()
            .putString(KEY_LYRION_PLAYER_ID, SettingsVocabulary.normalizeLyrionPlayerId(id))
            .putString(KEY_LYRION_PLAYER_NAME, name?.trim().orEmpty())
            .apply()
    }

    override fun setLyrionEmptyQueueFallback(fallback: String?) {
        prefs.edit()
            .putString(KEY_LYRION_FALLBACK, SettingsVocabulary.normalizeLyrionFallback(fallback))
            .apply()
    }

    override fun setLyrionFavoriteId(id: String?) {
        prefs.edit()
            .putString(KEY_LYRION_FAVORITE_ID, SettingsVocabulary.normalizeLyrionFavoriteId(id))
            .apply()
    }

    override fun setRingDurationSeconds(seconds: Int) {
        prefs.edit()
            .putInt(KEY_RING_DURATION, SettingsVocabulary.normalizeRingDuration(seconds))
            .apply()
    }

    override fun setNavCue(enabled: Boolean, soonMeters: Int, nowMeters: Int) {
        prefs.edit()
            .putBoolean(KEY_NAVCUE_ENABLED, enabled)
            .putInt(KEY_NAVCUE_SOON_M, SettingsVocabulary.normalizeNavCueSoonMeters(soonMeters))
            .putInt(KEY_NAVCUE_NOW_M, SettingsVocabulary.normalizeNavCueNowMeters(nowMeters))
            .apply()
    }

    override fun setNavCueBackend(backend: String) {
        prefs.edit().putString(KEY_NAVCUE_BACKEND, backend).apply()
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
        const val KEY_MULTI_FUNCTION_SWITCH_BUZZ = "multi_function_switch_buzz"
        const val KEY_MULTI_FUNCTION_SWITCH_BUZZ_DEBOUNCE = "multi_function_switch_buzz_debounce_ms"
        const val KEY_LYRION_HOST = "lyrion_server_host"
        const val KEY_LYRION_PORT = "lyrion_server_port"
        const val KEY_LYRION_PLAYER_ID = "lyrion_player_id"
        const val KEY_LYRION_PLAYER_NAME = "lyrion_player_name"
        const val KEY_LYRION_FALLBACK = "lyrion_empty_queue_fallback"
        const val KEY_LYRION_FAVORITE_ID = "lyrion_favorite_id"
        const val KEY_RING_DURATION = "ring_duration_seconds"
        const val KEY_NAVCUE_ENABLED = "navcue_enabled"
        const val KEY_NAVCUE_SOON_M = "navcue_soon_meters"
        const val KEY_NAVCUE_NOW_M = "navcue_now_meters"
        const val KEY_NAVCUE_BACKEND = "navcue_backend"
    }
}
