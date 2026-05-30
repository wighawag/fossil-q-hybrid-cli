package qhybrid.android.defaults

import org.json.JSONArray
import org.json.JSONObject
import qhybrid.android.buttons.ButtonActionsJson

/**
 * WP-DEFAULTS (sub-part 1) — the **single source of truth** (de)serialization for a
 * [DefaultsProfile], using Android's bundled `org.json` (same as
 * [qhybrid.android.buttons.ButtonActionsJson]; no new deps).
 *
 * The shape is the same as a per-watch export MINUS `watchMac` (WP-MULTIWATCH was skipped, so this
 * is self-contained — sub-part 5's export/import reuses this codec). It is **deliberately
 * tolerant**: empty / blank / malformed JSON decodes to the **factory** profile (so a corrupt blob
 * never wipes the user to a useless empty state), and individual malformed rows are skipped rather
 * than throwing. Encoding round-trips with decoding (encode→decode is identity).
 *
 * Button `actions` are stored via [ButtonActionsJson.encode] / `.decode` so the button ids share
 * the canonical `[{"action":"…"}]` representation with the rest of the app.
 */
object DefaultsProfileJson {

    private const val KEY_ALARMS = "alarms"
    private const val KEY_RULES = "rules"
    private const val KEY_BUTTONS = "buttons"

    // alarm keys
    private const val A_SLOT = "slotId"
    private const val A_HOUR = "hour"
    private const val A_MINUTE = "minute"
    private const val A_ENABLED = "isEnabled"
    private const val A_DAYS = "daysMask"
    private const val A_REPEATING = "isRepeating"
    private const val A_LABEL = "label"

    // rule keys
    private const val R_PKG = "packageName"
    private const val R_VIBE = "vibePattern"
    private const val R_HOUR_DEG = "hourHandDegrees"
    private const val R_MIN_DEG = "minuteHandDegrees"

    // button keys
    private const val B_ID = "buttonId"
    private const val B_MODE = "modeType"
    private const val B_ACTIONS = "actionsJson"

    /** Encode a [DefaultsProfile] into the canonical JSON string. */
    fun encode(profile: DefaultsProfile): String {
        val root = JSONObject()

        val alarms = JSONArray()
        for (a in profile.alarms) {
            val o = JSONObject()
                .put(A_SLOT, a.slotId)
                .put(A_HOUR, a.hour)
                .put(A_MINUTE, a.minute)
                .put(A_ENABLED, a.isEnabled)
                .put(A_DAYS, a.daysMask)
                .put(A_REPEATING, a.isRepeating)
            if (a.label != null) o.put(A_LABEL, a.label)
            alarms.put(o)
        }
        root.put(KEY_ALARMS, alarms)

        val rules = JSONArray()
        for (r in profile.rules) {
            rules.put(
                JSONObject()
                    .put(R_PKG, r.packageName)
                    .put(R_VIBE, r.vibePattern)
                    .put(R_HOUR_DEG, r.hourHandDegrees)
                    .put(R_MIN_DEG, r.minuteHandDegrees),
            )
        }
        root.put(KEY_RULES, rules)

        val buttons = JSONArray()
        for (b in profile.buttons) {
            buttons.put(
                JSONObject()
                    .put(B_ID, b.buttonId)
                    .put(B_MODE, b.modeType)
                    // Reuse the canonical actionsJson representation for the ids.
                    .put(B_ACTIONS, ButtonActionsJson.encode(b.actions)),
            )
        }
        root.put(KEY_BUTTONS, buttons)

        return root.toString()
    }

    /**
     * Decode a JSON string into a [DefaultsProfile]. **Tolerant:** empty / blank / malformed input
     * decodes to [DefaultsProfile.FACTORY] (never throws); individual malformed rows are skipped.
     *
     * A valid-but-section-absent object keeps that section empty for alarms/rules (those are empty
     * by default anyway), and falls back to the FACTORY buttons only when the WHOLE blob is
     * unparseable. A valid object that EXPLICITLY contains an empty buttons array is honoured as
     * "no buttons" (the user cleared the button defaults) — only a missing/garbage blob restores
     * the factory buttons.
     */
    fun decode(json: String?): DefaultsProfile {
        val raw = json?.trim().orEmpty()
        if (raw.isEmpty()) return DefaultsProfile.FACTORY
        val root = try {
            JSONObject(raw)
        } catch (_: Exception) {
            return DefaultsProfile.FACTORY
        }

        // A blob that has none of our keys is foreign/garbage → factory.
        if (!root.has(KEY_ALARMS) && !root.has(KEY_RULES) && !root.has(KEY_BUTTONS)) {
            return DefaultsProfile.FACTORY
        }

        val alarms = decodeAlarms(root.optJSONArray(KEY_ALARMS))
        val rules = decodeRules(root.optJSONArray(KEY_RULES))
        // Buttons present (even empty) → honour it; absent → factory buttons.
        val buttons = if (root.has(KEY_BUTTONS)) {
            decodeButtons(root.optJSONArray(KEY_BUTTONS))
        } else {
            DefaultsProfile.FACTORY.buttons
        }

        return DefaultsProfile(alarms = alarms, rules = rules, buttons = buttons)
    }

    private fun decodeAlarms(arr: JSONArray?): List<DefaultAlarm> {
        if (arr == null) return emptyList()
        val out = ArrayList<DefaultAlarm>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            runCatching {
                DefaultAlarm(
                    slotId = o.optInt(A_SLOT, 0),
                    hour = o.optInt(A_HOUR, 0),
                    minute = o.optInt(A_MINUTE, 0),
                    isEnabled = o.optBoolean(A_ENABLED, false),
                    daysMask = o.optInt(A_DAYS, 0),
                    isRepeating = o.optBoolean(A_REPEATING, false),
                    label = if (o.has(A_LABEL) && !o.isNull(A_LABEL)) o.optString(A_LABEL) else null,
                )
            }.getOrNull()?.let { out.add(it) }
        }
        return out
    }

    private fun decodeRules(arr: JSONArray?): List<DefaultRule> {
        if (arr == null) return emptyList()
        val out = ArrayList<DefaultRule>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val pkg = o.optString(R_PKG, "").trim()
            if (pkg.isEmpty()) continue
            runCatching {
                DefaultRule(
                    packageName = pkg,
                    vibePattern = o.optInt(R_VIBE, 0),
                    hourHandDegrees = o.optInt(R_HOUR_DEG, 0),
                    minuteHandDegrees = o.optInt(R_MIN_DEG, 0),
                )
            }.getOrNull()?.let { out.add(it) }
        }
        return out
    }

    private fun decodeButtons(arr: JSONArray?): List<DefaultButton> {
        if (arr == null) return emptyList()
        val out = ArrayList<DefaultButton>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            runCatching {
                val id = o.optInt(B_ID, -1)
                if (id < 0) return@runCatching null
                val mode = o.optString(B_MODE, "").trim()
                if (mode.isEmpty()) return@runCatching null
                // actionsJson is itself a canonical actions array string.
                val actions = ButtonActionsJson.decode(o.optString(B_ACTIONS, ""))
                DefaultButton(buttonId = id, modeType = mode, actions = actions)
            }.getOrNull()?.let { out.add(it) }
        }
        return out
    }
}
