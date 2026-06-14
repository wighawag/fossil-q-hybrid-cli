package qhybrid.android.settings.backup

import org.json.JSONArray
import org.json.JSONObject
import qhybrid.android.buttons.ButtonActionsJson
import qhybrid.android.db.ButtonMappingEntity
import qhybrid.android.db.NotificationRuleEntity
import qhybrid.android.db.WatchAlarmEntity
import qhybrid.android.db.WatchEntity
import qhybrid.android.settings.AppSettings
import java.nio.charset.StandardCharsets

/**
 * BACKUP/RESTORE — pure, Android-free (de)serialization for the two backup payloads, so the user
 * can export everything to a JSON file (surviving an app uninstall / storage wipe) and import it
 * back later. Mirrors [qhybrid.android.defaults.DefaultsProfileJson]'s tolerant, identity
 * round-trip design and uses Android's bundled `org.json` (no new deps).
 *
 *  - [AppSettingsBackup] — the app-wide [AppSettings] (NOT tied to any watch).
 *  - [WatchConfigBackup] — ONE watch's full config: the [WatchEntity] row + its alarms, notification
 *    rules, and button mappings. Import is NOT gated by MAC address: the restored rows are re-keyed
 *    onto whatever watch the caller targets ([WatchConfig.macAddress] is informational only).
 *
 * **Tolerant on decode:** blank / malformed / foreign JSON returns null (the caller then leaves the
 * current state untouched and surfaces an error), and individual malformed rows are skipped rather
 * than throwing. Encoding round-trips with decoding (encode -> decode is identity for valid input).
 */

// ============================================================ app-wide settings

/** A typed snapshot of the app-wide settings for export/import (just wraps [AppSettings]). */
object AppSettingsBackup {

    const val EXPORT_FILENAME = "fossilq-app-settings.json"
    const val MIME_TYPE = "application/json"

    private const val KIND = "kind"
    private const val KIND_VALUE = "fossilq-app-settings"
    private const val VERSION = "version"
    private const val CURRENT_VERSION = 1

    fun toBytes(settings: AppSettings): ByteArray =
        encode(settings).toByteArray(StandardCharsets.UTF_8)

    /** Decode bytes back into [AppSettings]; null when the blob is missing/foreign/garbage. */
    fun fromBytes(bytes: ByteArray?): AppSettings? {
        if (bytes == null) return null
        return decode(String(bytes, StandardCharsets.UTF_8))
    }

    fun encode(s: AppSettings): String {
        val root = JSONObject()
            .put(KIND, KIND_VALUE)
            .put(VERSION, CURRENT_VERSION)
            .put("nudgeEnabled", s.nudgeEnabled)
            .put("nudgeMinutes", s.nudgeMinutes)
            .put("secondTimezoneOffsetMinutes", s.secondTimezoneOffsetMinutes)
            .put("preferredMusicApp", s.preferredMusicApp)
            .put("calendarAlarmOffsetMinutes", s.calendarAlarmOffsetMinutes)
            .put("multiFunctionRole", s.multiFunctionRole)
            .put("multiFunctionRotation", JSONArray(s.multiFunctionRotation))
            .put("multiFunctionActiveIndex", s.multiFunctionActiveIndex)
            .put("multiFunctionSwitchBuzz", JSONObject(s.multiFunctionSwitchBuzz.mapValues { it.value }))
            .put("reservedBuzzMoveHands", s.reservedBuzzMoveHands)
            .put("lyrionServerHost", s.lyrionServerHost)
            .put("lyrionServerPort", s.lyrionServerPort)
            .put("lyrionPlayerId", s.lyrionPlayerId)
            .put("lyrionPlayerName", s.lyrionPlayerName)
            .put("lyrionEmptyQueueFallback", s.lyrionEmptyQueueFallback)
            .put("lyrionFavoriteId", s.lyrionFavoriteId)
            .put("ringDurationSeconds", s.ringDurationSeconds)
            .put("navCueEnabled", s.navCueEnabled)
            .put("navCueSoonMeters", s.navCueSoonMeters)
            .put("navCueNowMeters", s.navCueNowMeters)
            .put("navCueBackend", s.navCueBackend)
        return root.toString()
    }

    /** Tolerant decode: blank/garbage/foreign -> null; absent fields fall back to the defaults. */
    fun decode(json: String?): AppSettings? {
        val raw = json?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val root = try {
            JSONObject(raw)
        } catch (_: Exception) {
            return null
        }
        // Require our marker so a foreign JSON file can't masquerade as app settings.
        if (root.optString(KIND) != KIND_VALUE) return null

        val d = AppSettings() // defaults for any absent field
        val rotation = decodeStringList(root.optJSONArray("multiFunctionRotation"), d.multiFunctionRotation)
        val switchBuzz = decodeIntMap(root.optJSONObject("multiFunctionSwitchBuzz"), d.multiFunctionSwitchBuzz)
        return AppSettings(
            nudgeEnabled = root.optBoolean("nudgeEnabled", d.nudgeEnabled),
            nudgeMinutes = root.optInt("nudgeMinutes", d.nudgeMinutes),
            secondTimezoneOffsetMinutes = root.optInt("secondTimezoneOffsetMinutes", d.secondTimezoneOffsetMinutes),
            preferredMusicApp = root.optString("preferredMusicApp", d.preferredMusicApp),
            calendarAlarmOffsetMinutes = root.optInt("calendarAlarmOffsetMinutes", d.calendarAlarmOffsetMinutes),
            multiFunctionRole = root.optString("multiFunctionRole", d.multiFunctionRole),
            multiFunctionRotation = rotation,
            multiFunctionActiveIndex = root.optInt("multiFunctionActiveIndex", d.multiFunctionActiveIndex),
            multiFunctionSwitchBuzz = switchBuzz,
            reservedBuzzMoveHands = root.optBoolean("reservedBuzzMoveHands", d.reservedBuzzMoveHands),
            lyrionServerHost = root.optString("lyrionServerHost", d.lyrionServerHost),
            lyrionServerPort = root.optInt("lyrionServerPort", d.lyrionServerPort),
            lyrionPlayerId = root.optString("lyrionPlayerId", d.lyrionPlayerId),
            lyrionPlayerName = root.optString("lyrionPlayerName", d.lyrionPlayerName),
            lyrionEmptyQueueFallback = root.optString("lyrionEmptyQueueFallback", d.lyrionEmptyQueueFallback),
            lyrionFavoriteId = root.optString("lyrionFavoriteId", d.lyrionFavoriteId),
            ringDurationSeconds = root.optInt("ringDurationSeconds", d.ringDurationSeconds),
            navCueEnabled = root.optBoolean("navCueEnabled", d.navCueEnabled),
            navCueSoonMeters = root.optInt("navCueSoonMeters", d.navCueSoonMeters),
            navCueNowMeters = root.optInt("navCueNowMeters", d.navCueNowMeters),
            navCueBackend = root.optString("navCueBackend", d.navCueBackend),
        )
    }

    private fun decodeStringList(arr: JSONArray?, fallback: List<String>): List<String> {
        if (arr == null) return fallback
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val s = arr.optString(i, "").trim()
            if (s.isNotEmpty()) out.add(s)
        }
        return if (out.isEmpty()) fallback else out
    }

    private fun decodeIntMap(obj: JSONObject?, fallback: Map<String, Int>): Map<String, Int> {
        if (obj == null) return fallback
        val out = LinkedHashMap<String, Int>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            out[k] = obj.optInt(k)
        }
        return out
    }
}

// ============================================================ per-watch config

/**
 * A watch's full exportable config: identity/state fields ([WatchEntity]) plus its child rows.
 * [macAddress] is informational on import (the rows are re-keyed onto the TARGET watch's MAC, so a
 * backup can be restored onto ANY watch — never gated by the original MAC).
 */
data class WatchConfig(
    val macAddress: String,
    val name: String,
    val model: String?,
    val firmwareVersion: String?,
    val stepGoal: Int,
    val vibrationStrength: Int,
    val alarms: List<WatchAlarmEntity>,
    val rules: List<NotificationRuleEntity>,
    val buttons: List<ButtonMappingEntity>,
)

object WatchConfigBackup {

    const val EXPORT_FILENAME = "fossilq-watch-config.json"
    const val MIME_TYPE = "application/json"

    private const val KIND = "kind"
    private const val KIND_VALUE = "fossilq-watch-config"
    private const val VERSION = "version"
    private const val CURRENT_VERSION = 1

    fun toBytes(config: WatchConfig): ByteArray =
        encode(config).toByteArray(StandardCharsets.UTF_8)

    /** Decode bytes back into a [WatchConfig]; null when the blob is missing/foreign/garbage. */
    fun fromBytes(bytes: ByteArray?): WatchConfig? {
        if (bytes == null) return null
        return decode(String(bytes, StandardCharsets.UTF_8))
    }

    fun encode(c: WatchConfig): String {
        val root = JSONObject()
            .put(KIND, KIND_VALUE)
            .put(VERSION, CURRENT_VERSION)
            .put("macAddress", c.macAddress)
            .put("name", c.name)
            .put("stepGoal", c.stepGoal)
            .put("vibrationStrength", c.vibrationStrength)
        if (c.model != null) root.put("model", c.model)
        if (c.firmwareVersion != null) root.put("firmwareVersion", c.firmwareVersion)

        val alarms = JSONArray()
        for (a in c.alarms) {
            val o = JSONObject()
                .put("slotId", a.slotId)
                .put("hour", a.hour)
                .put("minute", a.minute)
                .put("isEnabled", a.isEnabled)
                .put("daysMask", a.daysMask)
                .put("isRepeating", a.isRepeating)
            if (a.label != null) o.put("label", a.label)
            alarms.put(o)
        }
        root.put("alarms", alarms)

        val rules = JSONArray()
        for (r in c.rules) {
            rules.put(
                JSONObject()
                    .put("packageName", r.packageName)
                    .put("vibePattern", r.vibePattern)
                    .put("hourHandDegrees", r.hourHandDegrees)
                    .put("minuteHandDegrees", r.minuteHandDegrees)
                    .put("isEnabled", r.isEnabled),
            )
        }
        root.put("rules", rules)

        val buttons = JSONArray()
        for (b in c.buttons) {
            buttons.put(
                JSONObject()
                    .put("buttonId", b.buttonId)
                    .put("modeType", b.modeType)
                    .put("actionsJson", b.actionsJson),
            )
        }
        root.put("buttons", buttons)

        return root.toString()
    }

    /** Tolerant decode: blank/garbage/foreign -> null; malformed child rows are skipped. */
    fun decode(json: String?): WatchConfig? {
        val raw = json?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val root = try {
            JSONObject(raw)
        } catch (_: Exception) {
            return null
        }
        if (root.optString(KIND) != KIND_VALUE) return null

        val mac = root.optString("macAddress", "")
        return WatchConfig(
            macAddress = mac,
            name = root.optString("name", mac),
            model = if (root.has("model") && !root.isNull("model")) root.optString("model") else null,
            firmwareVersion = if (root.has("firmwareVersion") && !root.isNull("firmwareVersion"))
                root.optString("firmwareVersion") else null,
            stepGoal = root.optInt("stepGoal", 10000),
            vibrationStrength = root.optInt("vibrationStrength", 50),
            alarms = decodeAlarms(root.optJSONArray("alarms"), mac),
            rules = decodeRules(root.optJSONArray("rules"), mac),
            buttons = decodeButtons(root.optJSONArray("buttons"), mac),
        )
    }

    private fun decodeAlarms(arr: JSONArray?, mac: String): List<WatchAlarmEntity> {
        if (arr == null) return emptyList()
        val out = ArrayList<WatchAlarmEntity>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            runCatching {
                WatchAlarmEntity(
                    watchMac = mac,
                    slotId = o.optInt("slotId", 0),
                    hour = o.optInt("hour", 0),
                    minute = o.optInt("minute", 0),
                    isEnabled = o.optBoolean("isEnabled", false),
                    daysMask = o.optInt("daysMask", 0),
                    isRepeating = o.optBoolean("isRepeating", false),
                    label = if (o.has("label") && !o.isNull("label")) o.optString("label") else null,
                )
            }.getOrNull()?.let { out.add(it) }
        }
        return out
    }

    private fun decodeRules(arr: JSONArray?, mac: String): List<NotificationRuleEntity> {
        if (arr == null) return emptyList()
        val out = ArrayList<NotificationRuleEntity>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val pkg = o.optString("packageName", "").trim()
            if (pkg.isEmpty()) continue
            runCatching {
                NotificationRuleEntity(
                    watchMac = mac,
                    packageName = pkg,
                    vibePattern = o.optInt("vibePattern", 0),
                    hourHandDegrees = o.optInt("hourHandDegrees", 0),
                    minuteHandDegrees = o.optInt("minuteHandDegrees", 0),
                    isEnabled = o.optBoolean("isEnabled", true),
                )
            }.getOrNull()?.let { out.add(it) }
        }
        return out
    }

    private fun decodeButtons(arr: JSONArray?, mac: String): List<ButtonMappingEntity> {
        if (arr == null) return emptyList()
        val out = ArrayList<ButtonMappingEntity>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            runCatching {
                val id = o.optInt("buttonId", -1)
                if (id < 0) return@runCatching null
                val mode = o.optString("modeType", "").trim()
                if (mode.isEmpty()) return@runCatching null
                // Keep actionsJson as the canonical array string; normalize via the shared codec.
                val actions = ButtonActionsJson.encode(ButtonActionsJson.decode(o.optString("actionsJson", "")))
                ButtonMappingEntity(watchMac = mac, buttonId = id, modeType = mode, actionsJson = actions)
            }.getOrNull()?.let { out.add(it) }
        }
        return out
    }
}
