package qhybrid.android.onboard

import qhybrid.protocol.FossilQAdapter

/**
 * WP-ONBOARD — pure mapper from the watch's live config (read back via
 * [qhybrid.protocol.FossilController.readConfig]) into the **readable** settings used to seed a
 * brand-new watch's [qhybrid.android.db.WatchEntity] row (+ app prefs).
 *
 * **The invariant (ANDROID-FOLLOWUPS-PLAN → WP-ONBOARD): readable settings are READ from the watch,
 * never a user default.** When a value is absent on the watch (or its bytes are malformed), we fall
 * back to the hardcoded constant (vibration [DEFAULT_VIBRATION_STRENGTH], step goal
 * [DEFAULT_STEP_GOAL]) or to blank/off (nudge off, second-timezone unset). There is NO user
 * "default profile" for readable settings here (that's WP-DEFAULTS, which covers only the UNREADABLE
 * sections and is out of scope).
 *
 * Parsing is done from [FossilQAdapter.ConfigEntry.rawData] (robust to formatting changes), NOT from
 * `formattedValue`. The wire decode mirrors [FossilQAdapter]'s `formatConfigValue` layout:
 *   - `0x0A` VIBE_STRENGTH — 1 byte = percent (0–100), clamped.
 *   - `0x03` DAILY_STEP_GOAL — 4-byte little-endian int.
 *   - `0x09` INACTIVE_NUDGE — 6 bytes: fromH, fromM, toH, toM, minutes, enabled(0/1).
 *   - `0x11` SECOND_TIMEZONE_OFFSET — 2-byte little-endian short (1024 == DISABLED/unset).
 *
 * This object is pure (no Android types) and never throws on short/garbage rawData — it falls back.
 */
object ConfigToSeed {

    // Config ids (mirrors FossilQAdapter.CONFIG_NAMES; kept local so this stays dependency-light).
    private const val ID_DAILY_STEP_GOAL = 0x03
    private const val ID_INACTIVE_NUDGE = 0x09
    private const val ID_VIBE_STRENGTH = 0x0A
    private const val ID_SECOND_TIMEZONE_OFFSET = 0x11

    /** Hardcoded fallbacks — match the [qhybrid.android.db.WatchEntity] constant defaults. */
    const val DEFAULT_VIBRATION_STRENGTH = 50
    const val DEFAULT_STEP_GOAL = 10000

    /** SECOND_TIMEZONE_OFFSET sentinel value the watch reports when the 2nd timezone is unset. */
    private const val SECOND_TZ_DISABLED = 1024

    /**
     * Live device-info read off the BLE link at provision time (NOT from the config TLV) — passed
     * through onto the seeded entity. All nullable: absent → leave the entity's neutral default.
     */
    data class DeviceInfo(
        val model: String? = null,
        val firmwareVersion: String? = null,
        val batteryLevel: Int? = null,
    )

    /** The readable settings extracted from the watch (the seed for a new watch's row + prefs). */
    data class SeededSettings(
        /** Vibration strength percent (0–100). Absent on the watch → [DEFAULT_VIBRATION_STRENGTH]. */
        val vibrationStrength: Int = DEFAULT_VIBRATION_STRENGTH,
        /** Daily step goal. Absent on the watch → [DEFAULT_STEP_GOAL]. */
        val stepGoal: Int = DEFAULT_STEP_GOAL,
        /** Whether the inactivity nudge is enabled. Absent → off. */
        val nudgeEnabled: Boolean = false,
        /** Inactivity nudge interval in minutes, or null when absent/disabled. */
        val nudgeMinutes: Int? = null,
        /** Second-timezone offset in minutes, or null when unset (1024 sentinel or absent). */
        val secondTimezoneOffsetMinutes: Int? = null,
        /** Live device info from the link (model/firmware/battery), passed through. */
        val deviceInfo: DeviceInfo = DeviceInfo(),
    )

    /**
     * Map the watch's [entries] (+ optional live [deviceInfo]) into the readable seed. Tolerant:
     * a null/empty list yields all-fallbacks; malformed/short rawData for any id falls back per
     * field without throwing.
     */
    fun seed(
        entries: List<FossilQAdapter.ConfigEntry>?,
        deviceInfo: DeviceInfo? = null,
    ): SeededSettings {
        val byId: Map<Int, FossilQAdapter.ConfigEntry> =
            entries.orEmpty().associateBy { it.id }

        return SeededSettings(
            vibrationStrength = parseVibration(byId[ID_VIBE_STRENGTH]),
            stepGoal = parseStepGoal(byId[ID_DAILY_STEP_GOAL]),
            nudgeEnabled = parseNudgeEnabled(byId[ID_INACTIVE_NUDGE]),
            nudgeMinutes = parseNudgeMinutes(byId[ID_INACTIVE_NUDGE]),
            secondTimezoneOffsetMinutes = parseSecondTimezone(byId[ID_SECOND_TIMEZONE_OFFSET]),
            deviceInfo = deviceInfo ?: DeviceInfo(),
        )
    }

    // ---- per-field decoders (never throw; fall back on short/garbage rawData) ----------------

    private fun parseVibration(entry: FossilQAdapter.ConfigEntry?): Int {
        val raw = entry?.rawData ?: return DEFAULT_VIBRATION_STRENGTH
        if (raw.isEmpty()) return DEFAULT_VIBRATION_STRENGTH
        val pct = raw[0].toInt() and 0xFF
        return pct.coerceIn(0, 100)
    }

    private fun parseStepGoal(entry: FossilQAdapter.ConfigEntry?): Int {
        val raw = entry?.rawData ?: return DEFAULT_STEP_GOAL
        if (raw.size < 4) return DEFAULT_STEP_GOAL
        return (raw[0].toInt() and 0xFF) or
            ((raw[1].toInt() and 0xFF) shl 8) or
            ((raw[2].toInt() and 0xFF) shl 16) or
            ((raw[3].toInt() and 0xFF) shl 24)
    }

    private fun parseNudgeEnabled(entry: FossilQAdapter.ConfigEntry?): Boolean {
        val raw = entry?.rawData ?: return false
        if (raw.size < 6) return false
        return raw[5].toInt() == 0x01
    }

    private fun parseNudgeMinutes(entry: FossilQAdapter.ConfigEntry?): Int? {
        val raw = entry?.rawData ?: return null
        if (raw.size < 6) return null
        // Only meaningful when enabled; report the minutes byte when the entry is enabled.
        if (raw[5].toInt() != 0x01) return null
        return raw[4].toInt() and 0xFF
    }

    private fun parseSecondTimezone(entry: FossilQAdapter.ConfigEntry?): Int? {
        val raw = entry?.rawData ?: return null
        if (raw.size < 2) return null
        val offset = (((raw[1].toInt() and 0xFF) shl 8) or (raw[0].toInt() and 0xFF)).toShort().toInt()
        if (offset == SECOND_TZ_DISABLED) return null
        return offset
    }
}
