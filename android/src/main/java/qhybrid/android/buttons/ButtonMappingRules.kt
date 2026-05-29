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
 * - [ButtonModes.SINGLE_ACTION]   → **exactly one** [ButtonActions] / [ConfigPayload] id.
 * - [ButtonModes.MUSIC_MULTIMODE] → **exactly one** id; the multi-function is *inside* the
 *   payload (e.g. [ButtonActions.MUSIC_CONTROL] / [ButtonActions.FORWARD_TO_PHONE_MULTI]); only
 *   the music-capable actions are offered.
 * - [ButtonModes.CUSTOM_TOGGLE]   → **one-or-more** dial-mode ids — the genuine "cycle through
 *   several dial modes in turn" (see [ButtonDialModes] / `ButtonCompiler.compileMultiEntry`).
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
     * The music-capable actions a [ButtonModes.MUSIC_MULTIMODE] button may carry. These are the
     * multi-function music payloads (the "multi" is *inside* the payload). Mirrors the
     * [ButtonActions] ids 1:1; a non-music action picked for a music button is dropped by
     * [normalizeIds].
     */
    val MUSIC_ACTIONS: List<String> = listOf(
        ButtonActions.MUSIC_CONTROL,
        ButtonActions.FORWARD_TO_PHONE_MULTI,
        ButtonActions.VOLUME_UP,
        ButtonActions.VOLUME_DOWN,
    )

    /** Whether [actionId] is a music-capable action (offer-set for [ButtonModes.MUSIC_MULTIMODE]). */
    fun isMusicAction(actionId: String): Boolean = actionId in MUSIC_ACTIONS

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
     * - [ButtonModes.CUSTOM_TOGGLE] → keep the list as-is (order preserved; it is the cycle order),
     *   blank entries dropped.
     * - [ButtonModes.MUSIC_MULTIMODE] → keep at most one id, **and only a music-capable one**
     *   (the first music action in the list; if none, the list is emptied).
     * - any other (single-action) mode → keep at most the **first** non-blank id.
     *
     * This is the collapse rule both the ViewModel (before persisting) and the orchestrator
     * (defensive, for legacy multi-id DB rows) apply, so an invalid combination can never be
     * stored or compiled.
     */
    fun normalizeIds(modeType: String, ids: List<String>): List<String> {
        val clean = ids.map { it.trim() }.filter { it.isNotEmpty() }
        val mode = ButtonModes.normalize(modeType)
        return when {
            allowsMultiple(mode) -> clean
            mode == ButtonModes.MUSIC_MULTIMODE -> clean.firstOrNull { isMusicAction(it) }?.let { listOf(it) } ?: emptyList()
            else -> clean.take(1)
        }
    }
}
