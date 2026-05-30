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
 * - `SINGLE_ACTION`   — button fires one action (incl. [ButtonActions.MULTI_FUNCTION], the
 *   open-ended "emit gesture events to the phone" action whose meaning the app decides later).
 * - `CUSTOM_TOGGLE`   — press cycles through several physical dial modes (sub-eye positions).
 *
 * **WP-BTN:** the former `MUSIC_MULTIMODE` mode was removed — its multi-function behaviour is
 * just the single [ButtonActions.MULTI_FUNCTION] action inside `SINGLE_ACTION` (the watch emits
 * gesture events; the app interprets them). The constant is retained ONLY as a tolerated legacy
 * value ([LEGACY_MUSIC_MULTIMODE]) so old DB rows normalize to `SINGLE_ACTION` without crashing.
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
    const val CUSTOM_TOGGLE = "CUSTOM_TOGGLE"

    /**
     * WP-BTN — legacy modeType (removed from the selectable set). Old DB rows may still hold this
     * string; [normalize] maps it to [SINGLE_ACTION] so nothing crashes and a former music button
     * compiles as a single action (its single id, e.g. [ButtonActions.MULTI_FUNCTION]).
     */
    const val LEGACY_MUSIC_MULTIMODE = "MUSIC_MULTIMODE"

    /** All SELECTABLE modeType ids in display order (two only — WP-BTN). */
    val ALL = listOf(SINGLE_ACTION, CUSTOM_TOGGLE)

    /** Default for a brand-new mapping. */
    const val DEFAULT = SINGLE_ACTION

    private val LABELS = mapOf(
        SINGLE_ACTION to "Single action",
        CUSTOM_TOGGLE to "Dial-mode toggle",
    )

    /** Human label for a modeType; falls back gracefully for unknown/legacy strings. */
    fun label(modeType: String): String = LABELS[modeType] ?: modeType

    /** True if [modeType] is one we know; unknown strings are still tolerated (rendered raw). */
    fun isKnown(modeType: String): Boolean = modeType in LABELS

    /**
     * Normalize a modeType, defaulting blank/null to [DEFAULT] (never throws). The legacy
     * [LEGACY_MUSIC_MULTIMODE] string collapses to [SINGLE_ACTION] (WP-BTN mode removal).
     */
    fun normalize(modeType: String?): String {
        val m = modeType?.trim()?.takeIf { it.isNotEmpty() } ?: return DEFAULT
        return if (m == LEGACY_MUSIC_MULTIMODE) SINGLE_ACTION else m
    }

    /**
     * Whether the chosen mode implies the dial-mode toggle UI (sub-eye positions). True only
     * for [CUSTOM_TOGGLE]; [SINGLE_ACTION] uses the single-select action picker instead.
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

    /**
     * All dial modes in the CANONICAL display + cycle order, 1:1 with the protocol
     * [qhybrid.protocol.requests.fossil.button.ButtonCompiler.DialMode]
     * {ALERT, TIMEZONE_2, ALARM, DATE, TWENTY_FOUR_HOUR}. A [ButtonModes.CUSTOM_TOGGLE] mapping
     * always cycles its selected modes in THIS order (unselected ones are simply skipped) — the
     * editor and the on-watch cycle agree because [ButtonDialModes.canonicalOrder] sorts the stored
     * ids by this list before persisting/compiling.
     */
    val ALL = listOf(ALERT, TIMEZONE_2, ALARM, DATE, TWENTY_FOUR_HOUR)

    /**
     * Re-order an arbitrary set of dial-mode [ids] into the canonical [ALL] order, dropping unknown
     * ids and de-duplicating. This is the single source of truth for the cycle order so the editor
     * (chip selection) and the compiler (wire entries) can never disagree, and the order does NOT
     * depend on the order the user tapped the chips.
     */
    fun canonicalOrder(ids: List<String>): List<String> {
        val set = ids.toHashSet()
        return ALL.filter { it in set }
    }

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
 * WP-BTN — the selectable button-action catalog, **deduped to one entry per UNIQUE wire payload**.
 *
 * Several [qhybrid.protocol.buttonconfig.ConfigPayload] constants are byte-for-byte identical, so
 * offering both would let the user pick two menu items that produce the SAME watch bytes. We expose
 * only the wire-unique set:
 * - `01 06 12 00` (the open-ended "emit gesture events to the phone" payload) → [MULTI_FUNCTION]
 *   (replaces the redundant `MUSIC_CONTROL` / `FORWARD_TO_PHONE_MULTI`; the app decides per-gesture
 *   meaning in a LATER config).
 * - `01 01 0C 00` → [RING_PHONE] (the redundant `FORWARD_TO_PHONE` is dropped from the menu).
 *
 * Most ids still map 1:1 onto a [ConfigPayload] name; [MULTI_FUNCTION] and the retained legacy
 * aliases resolve via [payloadName] so a stored id always compiles to the right golden payload
 * (no wire bytes invented). Legacy alias constants are kept (not in [ALL]) so old DB rows decode.
 */
object ButtonActions {
    // ---- selectable (wire-unique) actions ------------------------------------
    /** Open-ended "emit gesture events to the phone" action (wire `01 06 12 00`); meaning decided
     *  app-side later. Compiles to [ConfigPayload.FORWARD_TO_PHONE_MULTI] (== `MUSIC_CONTROL`). */
    const val MULTI_FUNCTION = "MULTI_FUNCTION"
    const val STOPWATCH = "STOPWATCH"
    const val DATE = "DATE"
    const val LAST_NOTIFICATION = "LAST_NOTIFICATION"
    const val SECOND_TIMEZONE = "SECOND_TIMEZONE"
    const val VOLUME_UP = "VOLUME_UP"
    const val VOLUME_DOWN = "VOLUME_DOWN"
    const val STEP_GOAL_COMPLETION = "STEP_GOAL_COMPLETION"
    const val RING_PHONE = "RING_PHONE"

    // ---- retained legacy aliases (NOT shown; kept so old DB rows still decode/compile) --------
    /** @deprecated redundant with [RING_PHONE] (identical wire bytes). Legacy rows only. */
    const val FORWARD_TO_PHONE = "FORWARD_TO_PHONE"
    /** @deprecated redundant with [MULTI_FUNCTION] (identical wire bytes). Legacy rows only. */
    const val FORWARD_TO_PHONE_MULTI = "FORWARD_TO_PHONE_MULTI"
    /** @deprecated redundant with [MULTI_FUNCTION] (identical wire bytes). Legacy rows only. */
    const val MUSIC_CONTROL = "MUSIC_CONTROL"

    /** All SELECTABLE action ids in display order (wire-unique only — WP-BTN). */
    val ALL = listOf(
        MULTI_FUNCTION,
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
    const val DEFAULT = MULTI_FUNCTION

    /**
     * Resolve an app-level action id to its backing [ConfigPayload] enum NAME. [MULTI_FUNCTION]
     * and the legacy aliases collapse onto their canonical payload; every other id is its own
     * payload name. Used by the orchestrator's compile step so no wire bytes are invented.
     */
    fun payloadName(actionId: String): String = when (actionId) {
        MULTI_FUNCTION, FORWARD_TO_PHONE_MULTI, MUSIC_CONTROL -> FORWARD_TO_PHONE_MULTI
        FORWARD_TO_PHONE -> RING_PHONE
        else -> actionId
    }

    private val LABELS = mapOf(
        MULTI_FUNCTION to "Multi-function (app decides)",
        STOPWATCH to "Stopwatch",
        DATE to "Show date",
        LAST_NOTIFICATION to "Show last notification",
        SECOND_TIMEZONE to "Show second timezone",
        VOLUME_UP to "Music volume up",
        VOLUME_DOWN to "Music volume down",
        STEP_GOAL_COMPLETION to "Show step goal completion",
        RING_PHONE to "Ring phone",
        // Legacy aliases keep a readable label if an old row surfaces in a summary.
        FORWARD_TO_PHONE to "Ring phone",
        FORWARD_TO_PHONE_MULTI to "Multi-function (app decides)",
        MUSIC_CONTROL to "Multi-function (app decides)",
    )

    fun label(action: String): String = LABELS[action] ?: action
    /** True if [action] is a SELECTABLE (shown) action. Legacy aliases return false. */
    fun isKnown(action: String): Boolean = action in ALL
}
