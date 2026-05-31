package qhybrid.android.tracker

import org.json.JSONObject
import qhybrid.android.buttons.ButtonSlots

/**
 * WP-TRACKER — the **pure** parser for the watch's button-AWARE Path-2 (0x08) micro-app event JSON.
 *
 * **Signal path (GROUND TRUTH, measured on-device 2026-05-31; FINDINGS).** A RING_PHONE
 * (`01 01 0C 00`) button produces a MICRO_APP_EVENT (event type 0x08). For declarationId 3073
 * (RING_PHONE) [qhybrid.protocol.FossilQAdapter.handleMicroAppEvent] runs the software gesture
 * detector and emits (field names confirmed against the adapter source):
 * ```
 * {"type":"button","button":"TOP|MIDDLE|BOTTOM","app":"RING_PHONE","variant":"STANDARD",
 *  "declarationId":3073,"eventId":N,"sequence":N,"gesture":"SINGLE","timestamp":"…"}
 * ```
 * Unlike the 0x05 music stream, this event CARRIES the button id (`button`), so coexisting Path-2
 * buttons are individually distinguishable. **SINGLE-PRESS ONLY**: on this firmware every press
 * (incl. long holds) emits exactly one micro_app event with `gesture":"SINGLE"` — there is no
 * reliable firmware DOUBLE/LONG on this payload, so we treat the press as a single press and do not
 * branch on the `gesture` field beyond accepting SINGLE.
 *
 * NO wire bytes / no protocol change — the JSON contract is already emitted by the adapter.
 */
object ButtonPressParser {

    /** A parsed button press: which physical button fired (TOP/MIDDLE/BOTTOM). */
    data class Press(val buttonId: Int)

    /**
     * Parse one `onEventJson` line into a [Press], or `null` when it is not a Path-2 button press we
     * handle (wrong `type`, not the RING_PHONE app, an unknown button name, or malformed JSON).
     * **Never throws.**
     *
     * Requirements (matching the adapter's emitted JSON):
     *   - `type` == "button",
     *   - `app`  == "RING_PHONE" (the only declarationId that carries a usable button id here),
     *   - `button` maps to a known [ButtonSlots] id (TOP=0x10 / MIDDLE=0x20 / BOTTOM=0x30).
     * The `gesture` field is accepted as SINGLE (the only value this firmware emits); a non-SINGLE
     * value is tolerated but still treated as one single press.
     */
    fun parse(json: String?): Press? {
        val raw = json?.trim().orEmpty()
        if (raw.isEmpty()) return null
        return try {
            val obj = JSONObject(raw)
            if (obj.optString("type") != "button") return null
            if (obj.optString("app") != "RING_PHONE") return null
            val buttonId = buttonIdForName(obj.optString("button")) ?: return null
            Press(buttonId)
        } catch (_: Exception) {
            null
        }
    }

    /** Map the adapter's button NAME (TOP/MIDDLE/BOTTOM) to its [ButtonSlots] id, or null. */
    fun buttonIdForName(name: String?): Int? = when (name) {
        "TOP" -> ButtonSlots.TOP
        "MIDDLE" -> ButtonSlots.MIDDLE
        "BOTTOM" -> ButtonSlots.BOTTOM
        else -> null
    }
}
