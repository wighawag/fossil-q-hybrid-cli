package qhybrid.android.buttons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WP-BTN sub-part 1 — pure cardinality-contract tests for [ButtonMappingRules].
 *
 * No Android deps needed (the rules are plain Kotlin); asserts the collapse semantics that both
 * [ButtonsViewModel.setSlot] and `SyncOrchestrator.entriesFor` rely on:
 *   - SINGLE_ACTION → at most one id,
 *   - CUSTOM_TOGGLE keeps the whole (cycle) list, order preserved.
 */
class ButtonMappingRulesTest {

    // ---- allowsMultiple / maxIds ---------------------------------------------

    @Test
    fun onlyCustomToggleAllowsMultiple() {
        assertTrue(ButtonMappingRules.allowsMultiple(ButtonModes.CUSTOM_TOGGLE))
        assertFalse(ButtonMappingRules.allowsMultiple(ButtonModes.SINGLE_ACTION))
        // Legacy MUSIC_MULTIMODE normalizes to SINGLE_ACTION → single-valued.
        assertFalse(ButtonMappingRules.allowsMultiple(ButtonModes.LEGACY_MUSIC_MULTIMODE))
        // Blank/unknown normalizes to SINGLE_ACTION → single-valued.
        assertFalse(ButtonMappingRules.allowsMultiple("   "))
        assertFalse(ButtonMappingRules.allowsMultiple("BOGUS"))
    }

    @Test
    fun maxIdsReflectsCardinality() {
        assertEquals(1, ButtonMappingRules.maxIds(ButtonModes.SINGLE_ACTION))
        assertEquals(1, ButtonMappingRules.maxIds(ButtonModes.LEGACY_MUSIC_MULTIMODE))
        assertEquals(Int.MAX_VALUE, ButtonMappingRules.maxIds(ButtonModes.CUSTOM_TOGGLE))
    }

    // ---- single-action collapse ----------------------------------------------

    @Test
    fun singleActionCollapsesToFirstId() {
        assertEquals(
            listOf(ButtonActions.STOPWATCH),
            ButtonMappingRules.normalizeIds(
                ButtonModes.SINGLE_ACTION,
                listOf(ButtonActions.STOPWATCH, ButtonActions.DATE, ButtonActions.RING_PHONE),
            ),
        )
    }

    @Test
    fun singleActionEmptyStaysEmpty() {
        assertTrue(ButtonMappingRules.normalizeIds(ButtonModes.SINGLE_ACTION, emptyList()).isEmpty())
        // Blank-only entries are dropped before the take(1).
        assertTrue(ButtonMappingRules.normalizeIds(ButtonModes.SINGLE_ACTION, listOf("  ", "")).isEmpty())
    }

    @Test
    fun singleActionKeepsFirstNonBlankSkippingBlanks() {
        assertEquals(
            listOf(ButtonActions.DATE),
            ButtonMappingRules.normalizeIds(ButtonModes.SINGLE_ACTION, listOf("  ", ButtonActions.DATE, ButtonActions.RING_PHONE)),
        )
    }

    // ---- legacy MUSIC_MULTIMODE collapses to single-action --------------------

    @Test
    fun legacyMusicMultimodeCollapsesToFirstIdLikeSingleAction() {
        assertEquals(
            listOf(ButtonActions.MULTI_FUNCTION),
            ButtonMappingRules.normalizeIds(
                ButtonModes.LEGACY_MUSIC_MULTIMODE,
                listOf(ButtonActions.MULTI_FUNCTION, ButtonActions.STOPWATCH),
            ),
        )
    }

    // ---- custom-toggle: keep the selected modes in the USER'S ORDER -----------

    @Test
    fun customTogglePreservesUserOrder() {
        // The cycle order is the USER'S chosen order (so it can match the watch's dial layout),
        // NOT a canonical re-sort. Unknown/blank ids are dropped; the rest stay as given.
        val userOrder = listOf(ButtonDialModes.TIMEZONE_2, ButtonDialModes.DATE, ButtonDialModes.ALARM)
        assertEquals(
            userOrder,
            ButtonMappingRules.normalizeIds(ButtonModes.CUSTOM_TOGGLE, userOrder),
        )
    }

    @Test
    fun customToggleDedupesKeepingFirstOccurrenceOrder() {
        // A duplicate is dropped (first occurrence wins); the user's order is otherwise preserved.
        assertEquals(
            listOf(ButtonDialModes.DATE, ButtonDialModes.ALARM, ButtonDialModes.ALERT),
            ButtonMappingRules.normalizeIds(
                ButtonModes.CUSTOM_TOGGLE,
                listOf(ButtonDialModes.DATE, ButtonDialModes.ALARM, ButtonDialModes.ALERT, ButtonDialModes.DATE),
            ),
        )
    }

    @Test
    fun customToggleDropsBlankEntriesButKeepsTheRest() {
        assertEquals(
            listOf(ButtonDialModes.ALARM, ButtonDialModes.DATE),
            ButtonMappingRules.normalizeIds(ButtonModes.CUSTOM_TOGGLE, listOf("  ", ButtonDialModes.ALARM, "", ButtonDialModes.DATE)),
        )
    }

    @Test
    fun customToggleSingleIdAllowed() {
        assertEquals(
            listOf(ButtonDialModes.ALERT),
            ButtonMappingRules.normalizeIds(ButtonModes.CUSTOM_TOGGLE, listOf(ButtonDialModes.ALERT)),
        )
    }
}
