package qhybrid.android.tracker

import org.json.JSONObject
import qhybrid.android.settings.SettingsVocabulary

/**
 * WP-TRACKER — the **pure**, unit-testable routing rule for the watch's `onEventJson` stream. The
 * on-device shell ([qhybrid.android.WatchConnectionService.routeEventJson]) mirrors this decision
 * exactly; keeping it here lets the rule be tested without a live service.
 *
 * The two button event paths are DIFFERENT event types, so we route by `type` FIRST, then for the
 * button-blind 0x05 music stream by the GLOBAL multi-function role (a single pref read per event):
 *   - `type:"music"` (0x05) + role MUSIC   → [Route.Music]   (WP12 media control, unchanged),
 *   - `type:"music"` (0x05) + role TRACKER → [Route.Tracker] (Part A GPS-tracker gestures),
 *   - `type:"button"` (0x08)               → [Route.ButtonPath2] (Part B button-aware single press),
 *   - anything else                        → [Route.Ignore].
 *
 * A `type:"music"` line is NEVER routed to both music and tracker — the global role picks exactly
 * one. NO wire bytes / no protocol change.
 */
object EventRouter {

    enum class Route { Music, Tracker, ButtonPath2, Ignore }

    /** Route one event line given the current GLOBAL [multiFunctionRole]. Never throws. */
    fun route(json: String?, multiFunctionRole: String): Route {
        val type = eventType(json) ?: return Route.Ignore
        return when (type) {
            "music" ->
                if (SettingsVocabulary.normalizeMultiFunctionRole(multiFunctionRole) ==
                    SettingsVocabulary.MULTI_FUNCTION_ROLE_TRACKER
                ) Route.Tracker else Route.Music
            "button" -> Route.ButtonPath2
            else -> Route.Ignore
        }
    }

    /** Read just the `type` field of an event line (null on blank/malformed). Never throws. */
    fun eventType(json: String?): String? {
        val raw = json?.trim().orEmpty()
        if (raw.isEmpty()) return null
        return runCatching { JSONObject(raw).optString("type").ifEmpty { null } }.getOrNull()
    }
}
