package qhybrid.android.defaults

import qhybrid.android.buttons.ButtonActions
import qhybrid.android.buttons.ButtonDialModes
import qhybrid.android.buttons.ButtonModes
import qhybrid.android.buttons.ButtonSlots

/**
 * WP-DEFAULTS (sub-part 1) — the app-level **defaults profile** model.
 *
 * This is a SINGLE, app-level (not per-watch), user-editable profile applied to a watch ONLY at
 * add/provision time (and on the manual "Apply defaults to this watch" action), and ONLY for the
 * sections we CANNOT read back from the watch: **alarms** (standard slots 0–15), **notification
 * rules**, and **button mappings** (TOP / MIDDLE / BOTTOM).
 *
 * The READABLE settings (vibration / step goal / nudge / 2nd-timezone) are intentionally NOT part
 * of this profile — they are READ from the watch (WP-ONBOARD owns them). Calendar alarms (slots
 * 16–31) are NOT in the profile either (they come from WP13's calendar source).
 *
 * Each `Default*` row is the WP4 entity shape MINUS `watchMac` (the mac is supplied at apply time
 * by [DefaultsToSeed]). The model is pure (no Android, no Room) so it is trivially testable.
 */
data class DefaultsProfile(
    /** Default standard alarms (slots 0–15). Empty by default ("no surprises"). */
    val alarms: List<DefaultAlarm> = emptyList(),
    /** Default per-app notification rules. Empty by default. */
    val rules: List<DefaultRule> = emptyList(),
    /** Default per-button mappings. Non-empty by default (the factory buttons). */
    val buttons: List<DefaultButton> = emptyList(),
) {
    companion object {
        /**
         * The FACTORY defaults (user-overridable), translated to the CURRENT button vocabulary
         * (post-WP12): the stale `MUSIC_MULTIMODE` / placeholder `MULTI_FUNCTION` spec is now the
         * concrete [ButtonActions.MUSIC_CONTROL] (byte-identical wire payload; WP12 drives the
         * active phone media session via [qhybrid.android.music.MusicController]).
         *
         * - **Alarms:** empty (no standard alarms).
         * - **Notification rules:** empty.
         * - **Buttons** (non-empty):
         *   - TOP (0x10)    → SINGLE_ACTION / STOPWATCH.
         *   - MIDDLE (0x20) → CUSTOM_TOGGLE / cycle [TIMEZONE_2, ALARM, DATE] (canonical order).
         *   - BOTTOM (0x30) → SINGLE_ACTION / MUSIC_CONTROL (the music-control payload).
         */
        val FACTORY: DefaultsProfile = DefaultsProfile(
            alarms = emptyList(),
            rules = emptyList(),
            buttons = listOf(
                DefaultButton(
                    buttonId = ButtonSlots.TOP,
                    modeType = ButtonModes.SINGLE_ACTION,
                    actions = listOf(ButtonActions.STOPWATCH),
                ),
                DefaultButton(
                    buttonId = ButtonSlots.MIDDLE,
                    modeType = ButtonModes.CUSTOM_TOGGLE,
                    actions = ButtonDialModes.canonicalOrder(
                        listOf(ButtonDialModes.TIMEZONE_2, ButtonDialModes.ALARM, ButtonDialModes.DATE),
                    ),
                ),
                DefaultButton(
                    buttonId = ButtonSlots.BOTTOM,
                    modeType = ButtonModes.SINGLE_ACTION,
                    actions = listOf(ButtonActions.MUSIC_CONTROL),
                ),
            ),
        )
    }
}

/**
 * WP-DEFAULTS — a default standard alarm (WP4 [qhybrid.android.db.WatchAlarmEntity] minus
 * `watchMac`). [slotId] is 0..15 (standard user alarms); [daysMask] follows the wire `days`
 * convention (bit0=Sun..bit6=Sat).
 */
data class DefaultAlarm(
    val slotId: Int,
    val hour: Int,
    val minute: Int,
    val isEnabled: Boolean,
    val daysMask: Int,
    val isRepeating: Boolean,
    val label: String? = null,
)

/**
 * WP-DEFAULTS — a default notification rule (WP4 [qhybrid.android.db.NotificationRuleEntity] minus
 * `watchMac`).
 */
data class DefaultRule(
    val packageName: String,
    val vibePattern: Int,
    val hourHandDegrees: Int,
    val minuteHandDegrees: Int,
)

/**
 * WP-DEFAULTS — a default button mapping (WP4 [qhybrid.android.db.ButtonMappingEntity] minus
 * `watchMac`). [actions] is the ordered list of action / dial-mode ids; it is encoded to/from the
 * canonical `actionsJson` via [qhybrid.android.buttons.ButtonActionsJson] at apply time.
 */
data class DefaultButton(
    val buttonId: Int,
    val modeType: String,
    val actions: List<String>,
)
