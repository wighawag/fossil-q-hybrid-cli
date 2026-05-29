package qhybrid.android.buttons

import org.json.JSONArray
import org.json.JSONObject

/**
 * WP16d — small, robust encode/decode helper for [qhybrid.android.db.ButtonMappingEntity.actionsJson].
 *
 * `actionsJson` is a free-form JSON **array** string (the WP4 DAO test uses
 * `[{"action":"MUSIC_PLAY"}]`). This helper keeps the representation simple — a list of action
 * id strings — and is **deliberately tolerant**: empty, blank, or malformed JSON decodes to an
 * empty list rather than throwing, so a corrupt row can never crash the UI. Encoding always
 * produces the canonical `[{"action":"…"}, …]` array shape so it round-trips with WP14's
 * compile step (which will map each id onto a [qhybrid.protocol.buttonconfig.ConfigPayload]).
 *
 * Uses Android's bundled `org.json` (available on the JVM under Robolectric), no extra deps.
 */
object ButtonActionsJson {

    private const val KEY = "action"

    /** Encode an ordered list of action ids into the canonical `[{"action":"…"}]` JSON array. */
    fun encode(actions: List<String>): String {
        val arr = JSONArray()
        for (a in actions) {
            val id = a.trim()
            if (id.isEmpty()) continue
            arr.put(JSONObject().put(KEY, id))
        }
        return arr.toString()
    }

    /**
     * Decode an `actionsJson` string into an ordered list of action ids. Tolerates:
     * - empty/blank input → empty list,
     * - malformed JSON → empty list (never throws),
     * - array of objects `[{"action":"X"}]` (canonical),
     * - array of bare strings `["X","Y"]` (lenient fallback).
     * Blank/whitespace-only entries are dropped.
     */
    fun decode(actionsJson: String?): List<String> {
        val raw = actionsJson?.trim().orEmpty()
        if (raw.isEmpty()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                val id = when (val item = arr.opt(i)) {
                    is JSONObject -> item.optString(KEY, "")
                    is String -> item
                    else -> item?.toString().orEmpty()
                }.trim()
                if (id.isNotEmpty()) out.add(id)
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** A short human summary of the encoded actions for a mapping row, e.g. "Show date, Ring phone". */
    fun summary(actionsJson: String?): String {
        val ids = decode(actionsJson)
        if (ids.isEmpty()) return "No actions"
        return ids.joinToString(", ") { ButtonActions.label(it) }
    }
}
