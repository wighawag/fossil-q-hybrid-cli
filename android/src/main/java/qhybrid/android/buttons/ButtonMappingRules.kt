package qhybrid.android.buttons

/**
 * WP-BTN — the pure, model-agnostic **cardinality contract** for a button mapping's id list.
 *
 * The bug this fixes: the editor used to let a `SINGLE_ACTION` / `MUSIC_MULTIMODE` button hold
 * *several* ids (multi-select checkboxes), and the orchestrator emitted one wire entry per id —
 * silently compiling a "single action" button into a multi-entry cycle the user never intended.
 * The mode and the action cardinality disagreed.
 *
 * The contract (verified in ANDROID-FOLLOWUPS-PLAN.md WP-BTN):
 * - [ButtonModes.SINGLE_ACTION] → **exactly one** [ButtonActions] id (incl.
 *   [ButtonActions.MUSIC_CONTROL], the "control phone media" action).
 * - [ButtonModes.CUSTOM_TOGGLE] → **one-or-more** dial-mode ids — the genuine "cycle through
 *   several dial modes in turn" (see [ButtonDialModes] / `ButtonCompiler.compileMultiEntry`).
 *
 * **WP-BTN / WP12:** the former `MUSIC_MULTIMODE` mode was removed — music control is now just the
 * single [ButtonActions.MUSIC_CONTROL] action inside `SINGLE_ACTION`.
 *
 * This is the single source of truth shared by [ButtonsViewModel.setSlot] (normalize before
 * persisting) and `SyncOrchestrator.entriesFor` (defensively collapse a legacy multi-id row),
 * so the two cannot drift apart. It is **pure** (no Android deps) and invents **no wire bytes** —
 * it only ever *drops* ids that violate the chosen mode's cardinality.
 *
 * [qhybrid.protocol.buttonconfig.ConfigPayload]
 */
object ButtonMappingRules {

    /**
     * True if [modeType] allows more than one id (the cycle). Only [ButtonModes.CUSTOM_TOGGLE]
     * is multi-valued; every other mode is single-action. Mirrors [ButtonModes.usesDialModes]
     * but expressed in cardinality terms so callers read intent clearly.
     */
    fun allowsMultiple(modeType: String): Boolean =
        ButtonModes.usesDialModes(ButtonModes.normalize(modeType))

    /**
     * The maximum number of ids a [modeType] mapping may store: `Int.MAX_VALUE` for
     * [ButtonModes.CUSTOM_TOGGLE] (one-or-more), `1` for every single-action mode.
     */
    fun maxIds(modeType: String): Int = if (allowsMultiple(modeType)) Int.MAX_VALUE else 1

    /**
     * Normalize an id list to honour [modeType]'s cardinality. **Pure; never throws.**
     *
     * - [ButtonModes.CUSTOM_TOGGLE] → keep the selected dial modes in the USER'S CHOSEN ORDER
     *   (drop unknown/blank ids + de-duplicate via [ButtonDialModes.dedup]). The cycle order is
     *   preserved end-to-end so it can match the watch's dial layout (which differs per Fossil
     *   model); the editor and the on-watch cycle agree because the compiler emits one entry per
     *   id in this same order.
     * - any other (single-action) mode → keep at most the **first** non-blank id.
     *
     * This is the collapse rule both the ViewModel (before persisting) and the orchestrator
     * (defensive, for legacy multi-id DB rows) apply, so an invalid combination can never be
     * stored or compiled.
     */
    fun normalizeIds(modeType: String, ids: List<String>): List<String> {
        val clean = ids.map { it.trim() }.filter { it.isNotEmpty() }
        return if (allowsMultiple(modeType)) ButtonDialModes.dedup(clean) else clean.take(1)
    }
}
