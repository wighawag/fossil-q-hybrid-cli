package qhybrid.android.settings

/**
 * WP16g — centralized, **model-agnostic** settings vocabulary: the value ranges, step sizes,
 * defaults, normalization, and display formatters for the Settings screen. Shared by the
 * ViewModel, the tests, and the Compose UI (same discipline as WP16b
 * [qhybrid.android.alarms.AlarmDays], WP16c [qhybrid.android.notifications.VibePatterns], WP16d
 * [qhybrid.android.buttons.ButtonModes], WP16e [qhybrid.android.calibration.CalibrationHands], and
 * WP16f [qhybrid.android.sleep.SleepQuality]) so the controls and the persisted/live values can
 * never drift apart.
 *
 * **Design decision (WP16g): MODEL-AGNOSTIC.** Different watches support different subsets of these
 * settings; we deliberately do NOT hard-code per-model capability tables. Every value here is a
 * plain, normalized range. A watch that physically lacks a feature simply ignores the live command
 * on-device (hardware behaviour on-device-pending / WP14); persisted prefs are always saved
 * app-side regardless.
 *
 * Ranges mirror the CLI / protocol vocabulary 1:1 (see `ANDROID-PLAN.md` §4.G and the protocol
 * `ConfigurationPutRequest.VibrationStrengthConfigItem` / `InactivityWarningItem` /
 * `TimezoneOffsetConfigItem`):
 *   - vibration strength: a 0–100 percentage (the protocol stores it as a single byte),
 *   - inactivity nudge: an on/off toggle + an inactivity duration in minutes (1–255),
 *   - second timezone: an offset in minutes from UTC (−720..+840, i.e. UTC−12 .. UTC+14).
 */
object SettingsVocabulary {

    // ---- vibration strength (persisted: WatchEntity.vibrationStrength) --------

    /** Minimum vibration strength (off / weakest). */
    const val VIBE_MIN = 0

    /** Maximum vibration strength (strongest). */
    const val VIBE_MAX = 100

    /** Default vibration strength — mirrors [qhybrid.android.db.WatchEntity.vibrationStrength]. */
    const val VIBE_DEFAULT = 50

    /** Normalize an arbitrary vibration-strength value onto [VIBE_MIN]..[VIBE_MAX] (clamps). */
    fun normalizeVibration(value: Int): Int = value.coerceIn(VIBE_MIN, VIBE_MAX)

    // ---- inactivity nudge (app pref; live command deferred → WP14) -----------

    /** Minimum inactivity duration in minutes (protocol stores 1 byte; 1–255). */
    const val NUDGE_MIN_MINUTES = 1

    /** Maximum inactivity duration in minutes (1 byte). */
    const val NUDGE_MAX_MINUTES = 255

    /** Default inactivity duration in minutes (a common 30-minute sedentary reminder). */
    const val NUDGE_DEFAULT_MINUTES = 30

    /** Step size for the nudge-duration stepper (one quarter-hour). */
    const val NUDGE_STEP_MINUTES = 15

    /** Default nudge enabled state (off by default — opt-in feature). */
    const val NUDGE_DEFAULT_ENABLED = false

    /** Normalize an arbitrary nudge duration onto [NUDGE_MIN_MINUTES]..[NUDGE_MAX_MINUTES]. */
    fun normalizeNudgeMinutes(value: Int): Int =
        value.coerceIn(NUDGE_MIN_MINUTES, NUDGE_MAX_MINUTES)

    // ---- second timezone offset (app pref; live command deferred → WP14) -----

    /** Minimum second-timezone offset in minutes (UTC−12:00). */
    const val TZ_MIN_OFFSET_MINUTES = -12 * 60

    /** Maximum second-timezone offset in minutes (UTC+14:00). */
    const val TZ_MAX_OFFSET_MINUTES = 14 * 60

    /** Default second-timezone offset (UTC, i.e. 0 minutes). */
    const val TZ_DEFAULT_OFFSET_MINUTES = 0

    /**
     * The discrete second-timezone offsets the dropdown offers, in minutes from UTC. Covers the
     * real-world range UTC−12 .. UTC+14 at 30-minute granularity (so half-hour and 45-minute zones
     * like India +330 and Nepal +345 round-trip cleanly via [normalizeTzOffset]).
     */
    val TZ_OFFSET_OPTIONS_MINUTES: List<Int> = buildList {
        var m = TZ_MIN_OFFSET_MINUTES
        while (m <= TZ_MAX_OFFSET_MINUTES) {
            add(m)
            m += 30
        }
    }

    /** Clamp an arbitrary offset onto the valid UTC−12..UTC+14 range. */
    fun normalizeTzOffset(value: Int): Int =
        value.coerceIn(TZ_MIN_OFFSET_MINUTES, TZ_MAX_OFFSET_MINUTES)

    /** Format a minute offset as `UTC+05:30` / `UTC-08:00` / `UTC`. */
    fun tzOffsetLabel(offsetMinutes: Int): String {
        val o = normalizeTzOffset(offsetMinutes)
        if (o == 0) return "UTC"
        val sign = if (o < 0) "-" else "+"
        val abs = kotlin.math.abs(o)
        val h = abs / 60
        val m = abs % 60
        return "UTC%s%02d:%02d".format(sign, h, m)
    }

    // ---- calendar alarm ring offset (WP13; app pref, applied in CalendarRefresher) ----

    /**
     * How many minutes BEFORE a calendar event the watch alarm should ring. 0 = ring exactly at the
     * event start. The alarm wall-clock time is computed as `eventStart - offset` before the pure
     * WP9 mapper runs, so all windowing/dedup/weekday logic stays in terms of the ring time.
     */
    const val CAL_OFFSET_MIN_MINUTES = 0

    /** Maximum lead time (2 hours) the offset stepper allows. */
    const val CAL_OFFSET_MAX_MINUTES = 120

    /** Default lead time: ring 1 minute before the event. */
    const val CAL_OFFSET_DEFAULT_MINUTES = 1

    /** Step size for the calendar-offset stepper. */
    const val CAL_OFFSET_STEP_MINUTES = 1

    /** Clamp an arbitrary calendar offset onto [CAL_OFFSET_MIN_MINUTES]..[CAL_OFFSET_MAX_MINUTES]. */
    fun normalizeCalendarOffset(value: Int): Int =
        value.coerceIn(CAL_OFFSET_MIN_MINUTES, CAL_OFFSET_MAX_MINUTES)

    /** Format a calendar lead time: `At event time` / `1 min before` / `15 min before`. */
    fun calendarOffsetLabel(offsetMinutes: Int): String {
        val o = normalizeCalendarOffset(offsetMinutes)
        return if (o == 0) "At event time" else "$o min before"
    }

    // ---- WP-TRACKER: "find my phone" loud-ring duration (app pref; phone-side only) ----------

    /** Minimum ring duration the stepper allows (5 seconds). */
    const val RING_DURATION_MIN_SECONDS = 5

    /** Maximum ring duration the stepper allows (5 minutes). */
    const val RING_DURATION_MAX_SECONDS = 300

    /** Default ring duration: 1 minute (60 seconds). */
    const val RING_DURATION_DEFAULT_SECONDS = 60

    /** Step size for the ring-duration stepper (5 seconds). */
    const val RING_DURATION_STEP_SECONDS = 5

    /** Clamp an arbitrary ring duration onto [RING_DURATION_MIN_SECONDS]..[RING_DURATION_MAX_SECONDS]. */
    fun normalizeRingDuration(seconds: Int): Int =
        seconds.coerceIn(RING_DURATION_MIN_SECONDS, RING_DURATION_MAX_SECONDS)

    /** Format a ring duration: `30 sec` / `1 min` / `1 min 30 sec` / `2 min`. */
    fun ringDurationLabel(seconds: Int): String {
        val s = normalizeRingDuration(seconds)
        val mins = s / 60
        val rem = s % 60
        return when {
            mins == 0 -> "$rem sec"
            rem == 0 -> "$mins min"
            else -> "$mins min $rem sec"
        }
    }

    // ---- preferred music app (app pref; pure app-side, no live watch command) -

    /**
     * Sentinel for "no preferred music app chosen" — the music-control fallback then dispatches
     * a generic media key instead of launching a specific package (ANDROID-PLAN §4.E). Stored as
     * an empty/absent pref value.
     */
    const val MUSIC_APP_NONE = ""

    /** Normalize a stored package id: blank → [MUSIC_APP_NONE]. Never throws. */
    fun normalizeMusicApp(pkg: String?): String =
        pkg?.trim()?.takeIf { it.isNotEmpty() } ?: MUSIC_APP_NONE

    // ---- multi-function role (WP-TRACKER; app pref, GLOBAL, pure app-side) ----

    /**
     * WP-TRACKER — how the watch's button-blind multi-function gesture stream (the 0x05
     * `type:"music"` events emitted by a MUSIC_CONTROL / `01 06 12 00` button) is interpreted.
     *
     * **This is necessarily a single GLOBAL app setting, NOT per-button.** Hardware fact
     * (FINDINGS, measured 2026-05-31): the 0x05 MUSIC_EVENT path carries NO button id — two
     * such buttons are indistinguishable on the wire (raw frames `01 05 41 02` short /
     * `01 05 42 03` double / `01 05 43 04` long carry only the firmware-classified gesture).
     * So the role that decides "is a gesture a media command or a GPS-tracker action?" cannot be
     * attached to a specific button — it must be one global toggle.
     */
    const val MULTI_FUNCTION_ROLE_MUSIC = "MUSIC"
    const val MULTI_FUNCTION_ROLE_TRACKER = "TRACKER"

    /** Default multi-function role — preserves the WP12 music-control behaviour out of the box. */
    const val MULTI_FUNCTION_ROLE_DEFAULT = MULTI_FUNCTION_ROLE_MUSIC

    /** All selectable multi-function roles in display order. */
    val MULTI_FUNCTION_ROLES = listOf(MULTI_FUNCTION_ROLE_MUSIC, MULTI_FUNCTION_ROLE_TRACKER)

    /**
     * Normalize a stored multi-function role onto the known set (never throws): blank/unknown →
     * [MULTI_FUNCTION_ROLE_DEFAULT]; case-insensitive + trimmed so a legacy/lower-case value still
     * resolves.
     */
    fun normalizeMultiFunctionRole(role: String?): String =
        when (role?.trim()?.uppercase()) {
            MULTI_FUNCTION_ROLE_TRACKER -> MULTI_FUNCTION_ROLE_TRACKER
            MULTI_FUNCTION_ROLE_MUSIC -> MULTI_FUNCTION_ROLE_MUSIC
            else -> MULTI_FUNCTION_ROLE_DEFAULT
        }

    /** Human label for a multi-function role; falls back gracefully. */
    fun multiFunctionRoleLabel(role: String): String = when (normalizeMultiFunctionRole(role)) {
        MULTI_FUNCTION_ROLE_TRACKER -> "GPS waypoint tracker"
        else -> "Music control"
    }

    // ---- multi-function ROTATION (L0; configurable, ordered, GLOBAL) ----------

    /**
     * L0 — the configurable multi-function ROTATION generalises the legacy 2-way
     * [MULTI_FUNCTION_ROLE_MUSIC] ⇄ [MULTI_FUNCTION_ROLE_TRACKER] flip into a user-defined ORDERED
     * list of MODES that the `SWITCH_MULTI_FUNCTION_MODE` button iterates through (wrap-around).
     *
     * **Modes** are a superset of the legacy roles — they split "music" by backend so Lyrion is a
     * first-class, watch-switchable mode (not a hidden Settings-only backend):
     *   - [MODE_MUSIC_PHONE]  — media control on the phone (the legacy MUSIC behaviour),
     *   - [MODE_MUSIC_LYRION] — media control on the configured Lyrion (LMS) player,
     *   - [MODE_TRACKER]      — GPS waypoint / ring-phone gestures (the legacy TRACKER behaviour).
     *
     * **Still necessarily GLOBAL** for the same hardware reason as the legacy role: the 0x05 stream
     * carries no button id, so the active meaning must be one global value. The rotation is the list
     * of *candidate* meanings; [multiFunctionActiveIndex] picks the live one; the button advances it.
     *
     * **First entry = default/active** when the rotation is (re)configured (the active index resets
     * to 0). Legacy [MULTI_FUNCTION_ROLE_MUSIC] maps to [MODE_MUSIC_PHONE] for back-compat.
     */
    const val MODE_MUSIC_PHONE = "MUSIC_PHONE"
    const val MODE_MUSIC_LYRION = "MUSIC_LYRION"
    const val MODE_TRACKER = "TRACKER"

    /** All selectable multi-function modes in canonical display order. */
    val MULTI_FUNCTION_MODES = listOf(MODE_MUSIC_PHONE, MODE_MUSIC_LYRION, MODE_TRACKER)

    /** Default rotation out of the box — phone media only (preserves WP12 default behaviour). */
    val MULTI_FUNCTION_ROTATION_DEFAULT = listOf(MODE_MUSIC_PHONE)

    /** Delimiter used to persist the ordered rotation as a single SharedPreferences string. */
    const val ROTATION_DELIM = ","

    /**
     * Normalize a stored/raw mode onto the known set (never throws). Blank/unknown → [MODE_MUSIC_PHONE];
     * the legacy role value `MUSIC` also maps to [MODE_MUSIC_PHONE]. Case-insensitive + trimmed.
     */
    fun normalizeMode(mode: String?): String = when (mode?.trim()?.uppercase()) {
        MODE_MUSIC_LYRION -> MODE_MUSIC_LYRION
        MODE_TRACKER -> MODE_TRACKER
        MODE_MUSIC_PHONE -> MODE_MUSIC_PHONE
        MULTI_FUNCTION_ROLE_MUSIC -> MODE_MUSIC_PHONE // legacy "MUSIC" → phone backend
        else -> MODE_MUSIC_PHONE
    }

    /**
     * Normalize an ordered list of modes (never throws): map each onto the known set, drop
     * duplicates while preserving first-seen order, and fall back to [MULTI_FUNCTION_ROTATION_DEFAULT]
     * when the result is empty. Note: because unknown/blank entries fold to [MODE_MUSIC_PHONE], a
     * list of only-unknown entries collapses to `[MODE_MUSIC_PHONE]` (a safe default), never empty.
     */
    fun normalizeRotation(modes: List<String>?): List<String> {
        if (modes.isNullOrEmpty()) return MULTI_FUNCTION_ROTATION_DEFAULT
        val out = LinkedHashSet<String>()
        for (m in modes) out.add(normalizeMode(m))
        return if (out.isEmpty()) MULTI_FUNCTION_ROTATION_DEFAULT else out.toList()
    }

    /** Parse a persisted CSV rotation string into a normalized ordered mode list. Never throws. */
    fun parseRotation(csv: String?): List<String> =
        normalizeRotation(csv?.split(ROTATION_DELIM)?.map { it.trim() }?.filter { it.isNotEmpty() })

    /** Serialize a rotation to the persisted CSV string (normalized first). */
    fun rotationToCsv(modes: List<String>?): String =
        normalizeRotation(modes).joinToString(ROTATION_DELIM)

    /** Clamp an active index into a (normalized) rotation's range; empty-safe (returns 0). */
    fun clampIndex(rotation: List<String>, index: Int): Int {
        val rot = normalizeRotation(rotation)
        return index.coerceIn(0, (rot.size - 1).coerceAtLeast(0))
    }

    /** The active mode for a rotation + index (clamped). Never throws. */
    fun activeMode(rotation: List<String>, index: Int): String {
        val rot = normalizeRotation(rotation)
        return rot[clampIndex(rot, index)]
    }

    /** The next index when the switch button advances (wrap-around). Empty-safe. */
    fun nextIndex(rotation: List<String>, index: Int): Int {
        val rot = normalizeRotation(rotation)
        if (rot.size <= 1) return 0
        return (clampIndex(rot, index) + 1) % rot.size
    }

    /** Human label for a mode; falls back gracefully. */
    fun modeLabel(mode: String): String = when (normalizeMode(mode)) {
        MODE_MUSIC_LYRION -> "Music (Lyrion player)"
        MODE_TRACKER -> "GPS waypoint tracker"
        else -> "Music (phone)"
    }

    // ---- L1: Lyrion (LMS) music control (app prefs; pure app-side) -----------

    /**
     * L1 — connection + target config for the [MODE_MUSIC_LYRION] backend (control a Lyrion / LMS
     * player over the network). All app-level prefs (never sent to the watch). The transport is
     * JSON-RPC over HTTP on the LMS web port (default [LYRION_PORT_DEFAULT] = 9000).
     */
    const val LYRION_HOST_NONE = ""
    const val LYRION_PORT_DEFAULT = 9000
    const val LYRION_PORT_MIN = 1
    const val LYRION_PORT_MAX = 65535
    /** Sentinel for "no player chosen" — the dispatcher no-ops until a player is selected. */
    const val LYRION_PLAYER_NONE = ""
    /** Sentinel for "no favourite chosen". */
    const val LYRION_FAVORITE_NONE = ""
    /** Volume step (percent) applied per VOLUME_UP / VOLUME_DOWN gesture. */
    const val LYRION_VOLUME_STEP = 5

    /** Trim a host string; blank → [LYRION_HOST_NONE]. Never throws. */
    fun normalizeLyrionHost(host: String?): String =
        host?.trim()?.takeIf { it.isNotEmpty() } ?: LYRION_HOST_NONE

    /** Clamp a port onto [LYRION_PORT_MIN]..[LYRION_PORT_MAX]; out-of-range/0 → default. */
    fun normalizeLyrionPort(port: Int): Int =
        if (port < LYRION_PORT_MIN || port > LYRION_PORT_MAX) LYRION_PORT_DEFAULT
        else port

    /** Trim a player id (MAC); blank → [LYRION_PLAYER_NONE]. Never throws. */
    fun normalizeLyrionPlayerId(id: String?): String =
        id?.trim()?.takeIf { it.isNotEmpty() } ?: LYRION_PLAYER_NONE

    /** Trim a favourite id; blank → [LYRION_FAVORITE_NONE]. Never throws. */
    fun normalizeLyrionFavoriteId(id: String?): String =
        id?.trim()?.takeIf { it.isNotEmpty() } ?: LYRION_FAVORITE_NONE

    /**
     * L1 — what to start when a PLAY/TOGGLE gesture targets a Lyrion player whose queue is EMPTY
     * (plain `play` would do nothing). See the plan §5.3.
     *   - [LYRION_FALLBACK_FAVORITE] (default) — play the configured favourite; if none set, the
     *     dispatcher degrades to RANDOM.
     *   - [LYRION_FALLBACK_RANDOM] — `randomplay tracks`.
     *   - [LYRION_FALLBACK_NONE] — passive `play` (no-op on an empty queue).
     */
    const val LYRION_FALLBACK_FAVORITE = "FAVORITE"
    const val LYRION_FALLBACK_RANDOM = "RANDOM"
    const val LYRION_FALLBACK_NONE = "NONE"

    const val LYRION_FALLBACK_DEFAULT = LYRION_FALLBACK_FAVORITE

    /** All selectable empty-queue fallbacks in display order. */
    val LYRION_FALLBACKS = listOf(LYRION_FALLBACK_FAVORITE, LYRION_FALLBACK_RANDOM, LYRION_FALLBACK_NONE)

    /** Normalize a fallback onto the known set (never throws): blank/unknown → default FAVORITE. */
    fun normalizeLyrionFallback(fallback: String?): String = when (fallback?.trim()?.uppercase()) {
        LYRION_FALLBACK_RANDOM -> LYRION_FALLBACK_RANDOM
        LYRION_FALLBACK_NONE -> LYRION_FALLBACK_NONE
        LYRION_FALLBACK_FAVORITE -> LYRION_FALLBACK_FAVORITE
        else -> LYRION_FALLBACK_DEFAULT
    }

    /** Human label for an empty-queue fallback; falls back gracefully. */
    fun lyrionFallbackLabel(fallback: String): String = when (normalizeLyrionFallback(fallback)) {
        LYRION_FALLBACK_RANDOM -> "Random tracks"
        LYRION_FALLBACK_NONE -> "Do nothing"
        else -> "Favourite"
    }
}
