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
 * - `SINGLE_ACTION`   — button fires one action (incl. [ButtonActions.MUSIC_CONTROL], the
 *   "control phone media" action — the watch emits gesture events and the app drives the active
 *   media session; see WP12 [qhybrid.android.music.MusicController]).
 * - `CUSTOM_TOGGLE`   — press cycles through several physical dial modes (sub-eye positions).
 *
 * **WP-BTN:** the former `MUSIC_MULTIMODE` mode was removed — its multi-function behaviour is
 * just the single [ButtonActions.MUSIC_CONTROL] action inside `SINGLE_ACTION` (the watch emits
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
     * compiles as a single action (its single id, e.g. [ButtonActions.MUSIC_CONTROL]).
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
 * - `01 06 12 00` (the "emit gesture events to the phone" payload) → [MUSIC_CONTROL] (the concrete
 *   "control phone media" action; WP12 drives the active media session — see
 *   [qhybrid.android.music.MusicController]). It replaces the former placeholder `MULTI_FUNCTION`
 *   selectable; `MULTI_FUNCTION` / `FORWARD_TO_PHONE_MULTI` are kept ONLY as legacy aliases. Later
 *   micro-app behaviours become ADDITIONAL single-action ids — NOT a reintroduced "app decides"
 *   placeholder.
 * - `01 01 0C 00` → [RING_PHONE] (the redundant `FORWARD_TO_PHONE` is dropped from the menu).
 *
 * Most ids still map 1:1 onto a [ConfigPayload] name; [MUSIC_CONTROL] and the retained legacy
 * aliases resolve via [payloadName] so a stored id always compiles to the right golden payload
 * (no wire bytes invented — `MUSIC_CONTROL`, `MULTI_FUNCTION` and `FORWARD_TO_PHONE_MULTI` are all
 * byte-identical to [ConfigPayload.FORWARD_TO_PHONE_MULTI]). Legacy alias constants are kept (not
 * in [ALL]) so old DB rows decode; [normalize] folds them onto the selectable [MUSIC_CONTROL].
 */
object ButtonActions {
    // ---- selectable (wire-unique) actions ------------------------------------
    /** WP12 — "control phone media" action (wire `01 06 12 00`): the watch emits play/pause/next/
     *  prev/volume gestures and the app ([qhybrid.android.music.MusicController]) drives the active
     *  media session. Compiles to [ConfigPayload.FORWARD_TO_PHONE_MULTI] (byte-identical bytes). */
    const val MUSIC_CONTROL = "MUSIC_CONTROL"
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
    /** @deprecated WP12 placeholder, superseded by the concrete [MUSIC_CONTROL] (identical wire
     *  bytes). [normalize] folds it onto [MUSIC_CONTROL]; kept for legacy DB rows + the defaults
     *  seed. */
    const val MULTI_FUNCTION = "MULTI_FUNCTION"
    /** @deprecated redundant with [MUSIC_CONTROL] (identical wire bytes). Legacy rows only. */
    const val FORWARD_TO_PHONE_MULTI = "FORWARD_TO_PHONE_MULTI"

    /** All SELECTABLE action ids in display order (wire-unique only — WP-BTN / WP12). */
    val ALL = listOf(
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

    /** Default action for a brand-new SINGLE_ACTION mapping (WP12: the concrete music control). */
    const val DEFAULT = MUSIC_CONTROL

    /**
     * WP12 — tolerated legacy aliases that [normalize] folds onto the selectable [MUSIC_CONTROL]:
     * the old placeholder [MULTI_FUNCTION] and the byte-identical [FORWARD_TO_PHONE_MULTI]. Stored
     * DB rows + the defaults seed may hold any of these; they all mean "music control" now. (This
     * mirrors the [ButtonModes.LEGACY_MUSIC_MULTIMODE] → SINGLE_ACTION pattern: a label/vocabulary
     * fold with NO wire-byte change and NO DB migration.)
     */
    private val MUSIC_CONTROL_ALIASES = setOf(MULTI_FUNCTION, FORWARD_TO_PHONE_MULTI, MUSIC_CONTROL)

    /**
     * Normalize a stored action id onto the SELECTABLE vocabulary (never throws). The single source
     * of truth for legacy folding:
     *  - [MULTI_FUNCTION] / [FORWARD_TO_PHONE_MULTI] → [MUSIC_CONTROL] (WP12 rename),
     *  - [FORWARD_TO_PHONE] → [RING_PHONE] (the byte-identical dropped duplicate),
     *  - every other id is returned trimmed/unchanged.
     *
     * Decode/seed paths run ids through this so a row written before WP12 surfaces as the new
     * selectable id in the editor while compiling to byte-identical wire output.
     */
    fun normalize(actionId: String): String = when (val id = actionId.trim()) {
        MULTI_FUNCTION, FORWARD_TO_PHONE_MULTI -> MUSIC_CONTROL
        FORWARD_TO_PHONE -> RING_PHONE
        else -> id
    }

    /**
     * Resolve an app-level action id to its backing [ConfigPayload] enum NAME. [MUSIC_CONTROL] and
     * its legacy aliases collapse onto the canonical [ConfigPayload.FORWARD_TO_PHONE_MULTI] payload;
     * every other id is its own payload name. Used by the orchestrator's compile step so no wire
     * bytes are invented (the WP12 rename is label-only — these bytes are UNCHANGED).
     */
    fun payloadName(actionId: String): String = when (actionId) {
        in MUSIC_CONTROL_ALIASES -> FORWARD_TO_PHONE_MULTI
        FORWARD_TO_PHONE -> RING_PHONE
        else -> actionId
    }

    private val LABELS = mapOf(
        MUSIC_CONTROL to "Music control",
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
        MULTI_FUNCTION to "Music control",
        FORWARD_TO_PHONE_MULTI to "Music control",
    )

    fun label(action: String): String = LABELS[action] ?: action
    /** True if [action] is a SELECTABLE (shown) action. Legacy aliases return false. */
    fun isKnown(action: String): Boolean = action in ALL
}
