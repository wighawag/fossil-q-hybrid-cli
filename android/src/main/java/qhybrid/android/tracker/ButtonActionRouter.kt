package qhybrid.android.tracker

import qhybrid.android.buttons.ButtonActions
import qhybrid.android.buttons.ButtonActionsJson
import qhybrid.android.db.ButtonMappingEntity
import qhybrid.android.notifications.VibePatterns

/**
 * WP-TRACKER \u2014 the **pure** per-button action router for the button-AWARE Path-2 (0x08) stream.
 *
 * The 0x08 `type:"button"` event carries the pressed button id; this router looks up THAT button's
 * configured action in the active watch's WP4 [ButtonMappingEntity] rows (decoded + normalized via
 * [ButtonActionsJson.decode]) and decides which app-side Path-2 effect to run. Because the wire
 * bytes (`01 01 0C 00`) are identical for every Path-2 action, the stored per-button action is the
 * ONLY way to tell a LOG_WAYPOINT button from a RING_PHONE / SWITCH_MULTI_FUNCTION_MODE / plain
 * "forward to phone" button \u2014 exactly the same discipline the music/tracker role uses for the 0x05
 * stream.
 *
 * SINGLE-PRESS ONLY (FINDINGS): there is no reliable double/long on this payload, so each press maps
 * to exactly one [Path2Action]. NO wire bytes / no protocol change.
 */
object ButtonActionRouter {

    /** The app-side effect a Path-2 single press should run (each carries its buzz-back vibe). */
    sealed interface Path2Action {
        val buzzPattern: Int

        /** Log a MINOR GPS waypoint, then buzz [buzzPattern]. */
        data class LogWaypoint(override val buzzPattern: Int = VibePatterns.ONE_SHORT) : Path2Action

        /** Ring/find the phone, then buzz [buzzPattern] to confirm it was heard. */
        data class RingPhone(override val buzzPattern: Int = VibePatterns.ONE_SHORT) : Path2Action

        /**
         * Toggle the GLOBAL multi-function role (MUSIC\u21c4TRACKER). The buzz-back is decided AFTER the
         * flip by the shell (now-MUSIC vs now-TRACKER) so the user feels which mode they're in; this
         * router only signals the intent. [buzzPattern] here is a placeholder (the shell overrides).
         */
        data class SwitchMultiFunctionMode(override val buzzPattern: Int = VibePatterns.ONE_SHORT) :
            Path2Action
    }

    /**
     * Resolve the [Path2Action] for a pressed [buttonId] given the active watch's [mappings], or
     * `null` when nothing should run:
     *   - no mapping row for that button, OR
     *   - the button's first decoded action is not a Path-2 action ([ButtonActions.PATH2_ACTIONS]).
     *
     * A SINGLE_ACTION button stores one action; we read the FIRST decoded id (a defensive CUSTOM_TOGGLE
     * row, or a multi-id legacy row, takes its first id). The ids are run through
     * [ButtonActionsJson.decode] which already normalizes legacy aliases, so a stored
     * `FORWARD_TO_PHONE` surfaces as `RING_PHONE`.
     */
    fun resolve(buttonId: Int, mappings: List<ButtonMappingEntity>): Path2Action? {
        val row = mappings.firstOrNull { it.buttonId == buttonId } ?: return null
        val actionId = ButtonActionsJson.decode(row.actionsJson).firstOrNull() ?: return null
        return resolveAction(actionId)
    }

    /** Map a single (already-normalized) action id to its Path-2 effect, or null if not Path-2. */
    fun resolveAction(actionId: String): Path2Action? = when (actionId) {
        ButtonActions.LOG_WAYPOINT -> Path2Action.LogWaypoint()
        ButtonActions.RING_PHONE -> Path2Action.RingPhone()
        ButtonActions.SWITCH_MULTI_FUNCTION_MODE -> Path2Action.SwitchMultiFunctionMode()
        else -> null
    }
}
