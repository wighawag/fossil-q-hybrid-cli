package qhybrid.android.buttons

/**
 * WP16d — centralized, **model-agnostic** button vocabulary: the `modeType` strings, the
 * physical dial-mode toggles, and the selectable action catalog. Shared by the ViewModel,
 * the tests, and the Compose UI (same discipline as WP16b [qhybrid.android.alarms.AlarmDays]
 * and WP16c [qhybrid.android.notifications.VibePatterns]) so the dropdowns and the stored
 * [qhybrid.android.db.ButtonMappingEntity] values cannot drift apart.
 *
 * **Design decision (WP16d): MODEL-AGNOSTIC.** We deliberately do NOT hard-code per-model
 * button counts/layouts or gate any mode by watch model. The protocol *does* have a model
 * concept ([qhybrid.protocol.requests.fossil.button.ButtonCompiler.DialModel] +
 * `availableModes`) but WP16d treats this as a flat catalog and lets ALL buttons/modes/actions
 * be possible; per-model hardware validation is out of scope (WP14 / on-device-pending).
 *
 * `modeType` strings match the WP4 [qhybrid.android.db.ButtonMappingEntity] doc + DAO tests:
 * - `SINGLE_ACTION`   — button fires one action.
 * - `MUSIC_MULTIMODE` — multi-function music control (one press cycles music functions).
 * - `CUSTOM_TOGGLE`   — press cycles through several physical dial modes (sub-eye positions).
 */
/**
 * WP16d — the three physical buttons every Fossil Q Hybrid watch has, in TOP/MIDDLE/BOTTOM
 * order. The ids mirror the protocol exactly: 0x10=TOP, 0x20=MIDDLE, 0x30=BOTTOM (see
 * [qhybrid.protocol.requests.fossil.button.ButtonCompiler] `BUTTON_INDICES`). The Buttons
 * screen renders one fixed slot per entry — no free-form buttonId entry, no add/remove flow.
 */
object ButtonSlots {
    const val TOP = 0x10
    const val MIDDLE = 0x20
    const val BOTTOM = 0x30

    /** The three buttons in physical (top-to-bottom) order. */
    val ALL = listOf(TOP, MIDDLE, BOTTOM)

    private val LABELS = mapOf(
        TOP to "Top button",
        MIDDLE to "Middle button",
        BOTTOM to "Bottom button",
    )

    /** Human label for a slot id; falls back to a hex label for unknown ids. */
    fun label(buttonId: Int): String = LABELS[buttonId] ?: "Button 0x%02X".format(buttonId)

    /** True if [buttonId] is one of the three known physical buttons. */
    fun isKnown(buttonId: Int): Boolean = buttonId in LABELS
}

object ButtonModes {
    const val SINGLE_ACTION = "SINGLE_ACTION"
    const val MUSIC_MULTIMODE = "MUSIC_MULTIMODE"
    const val CUSTOM_TOGGLE = "CUSTOM_TOGGLE"

    /** All known modeType ids in display order. */
    val ALL = listOf(SINGLE_ACTION, MUSIC_MULTIMODE, CUSTOM_TOGGLE)

    /** Default for a brand-new mapping. */
    const val DEFAULT = SINGLE_ACTION

    private val LABELS = mapOf(
        SINGLE_ACTION to "Single action",
        MUSIC_MULTIMODE to "Music (multi-function)",
        CUSTOM_TOGGLE to "Dial-mode toggle",
    )

    /** Human label for a modeType; falls back gracefully for unknown/legacy strings. */
    fun label(modeType: String): String = LABELS[modeType] ?: modeType

    /** True if [modeType] is one we know; unknown strings are still tolerated (rendered raw). */
    fun isKnown(modeType: String): Boolean = modeType in LABELS

    /** Normalize a modeType, defaulting blank/null to [DEFAULT] (never throws). */
    fun normalize(modeType: String?): String =
        modeType?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT

    /**
     * Whether the chosen mode implies the dial-mode toggle UI (sub-eye positions). True only
     * for [CUSTOM_TOGGLE]; [SINGLE_ACTION]/[MUSIC_MULTIMODE] use the action picker instead.
     */
    fun usesDialModes(modeType: String): Boolean = modeType == CUSTOM_TOGGLE
}

/**
 * WP16d — the physical dial modes a [ButtonModes.CUSTOM_TOGGLE] cycles (watch-face sub-eye
 * positions). Ids mirror [qhybrid.protocol.requests.fossil.button.ButtonCompiler.DialMode]
 * 1:1 (music is NOT a dial mode — it's a phone-side action). Offered for ALL watches
 * regardless of model (model-agnostic; WP14 may validate per-hardware later).
 */
object ButtonDialModes {
    const val ALERT = "ALERT"
    const val TIMEZONE_2 = "TIMEZONE_2"
    const val ALARM = "ALARM"
    const val DATE = "DATE"
    const val TWENTY_FOUR_HOUR = "TWENTY_FOUR_HOUR"

    /** All dial modes in display order. */
    val ALL = listOf(ALERT, TIMEZONE_2, ALARM, DATE, TWENTY_FOUR_HOUR)

    private val LABELS = mapOf(
        ALERT to "Alert",
        TIMEZONE_2 to "2nd timezone",
        ALARM to "Alarm",
        DATE to "Date",
        TWENTY_FOUR_HOUR to "24-hour",
    )

    fun label(mode: String): String = LABELS[mode] ?: mode
    fun isKnown(mode: String): Boolean = mode in LABELS
}

/**
 * WP16d — the selectable button-action catalog. Ids mirror the WP7
 * [qhybrid.protocol.buttonconfig.ConfigPayload] enum names 1:1 so a stored action id maps
 * straight onto the protocol payload at compile time (WP14). Labels lifted from each
 * payload's `getDescription()`. Centralized so the action picker and the stored
 * `actionsJson` cannot drift; treated as a flat catalog (no per-model gating).
 */
object ButtonActions {
    const val FORWARD_TO_PHONE = "FORWARD_TO_PHONE"
    const val FORWARD_TO_PHONE_MULTI = "FORWARD_TO_PHONE_MULTI"
    const val MUSIC_CONTROL = "MUSIC_CONTROL"
    const val STOPWATCH = "STOPWATCH"
    const val DATE = "DATE"
    const val LAST_NOTIFICATION = "LAST_NOTIFICATION"
    const val SECOND_TIMEZONE = "SECOND_TIMEZONE"
    const val VOLUME_UP = "VOLUME_UP"
    const val VOLUME_DOWN = "VOLUME_DOWN"
    const val STEP_GOAL_COMPLETION = "STEP_GOAL_COMPLETION"
    const val RING_PHONE = "RING_PHONE"

    /** All action ids in display order (mirrors [ConfigPayload] declaration order). */
    val ALL = listOf(
        FORWARD_TO_PHONE,
        FORWARD_TO_PHONE_MULTI,
        MUSIC_CONTROL,
        STOPWATCH,
        DATE,
        LAST_NOTIFICATION,
        SECOND_TIMEZONE,
        VOLUME_UP,
        VOLUME_DOWN,
        STEP_GOAL_COMPLETION,
        RING_PHONE,
    )

    /** Default action for a brand-new SINGLE_ACTION mapping. */
    const val DEFAULT = FORWARD_TO_PHONE

    private val LABELS = mapOf(
        FORWARD_TO_PHONE to "Forward to phone",
        FORWARD_TO_PHONE_MULTI to "Forward to phone (multi-function)",
        MUSIC_CONTROL to "Control music (play/pause/prev/next)",
        STOPWATCH to "Stopwatch",
        DATE to "Show date",
        LAST_NOTIFICATION to "Show last notification",
        SECOND_TIMEZONE to "Show second timezone",
        VOLUME_UP to "Music volume up",
        VOLUME_DOWN to "Music volume down",
        STEP_GOAL_COMPLETION to "Show step goal completion",
        RING_PHONE to "Ring phone",
    )

    fun label(action: String): String = LABELS[action] ?: action
    fun isKnown(action: String): Boolean = action in LABELS
}
