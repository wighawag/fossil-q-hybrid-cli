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
 *   - SINGLE_ACTION / MUSIC_MULTIMODE → at most one id,
 *   - MUSIC_MULTIMODE only keeps a music-capable id,
 *   - CUSTOM_TOGGLE keeps the whole (cycle) list, order preserved.
 */
class ButtonMappingRulesTest {

    // ---- allowsMultiple / maxIds ---------------------------------------------

    @Test
    fun onlyCustomToggleAllowsMultiple() {
        assertTrue(ButtonMappingRules.allowsMultiple(ButtonModes.CUSTOM_TOGGLE))
        assertFalse(ButtonMappingRules.allowsMultiple(ButtonModes.SINGLE_ACTION))
        assertFalse(ButtonMappingRules.allowsMultiple(ButtonModes.MUSIC_MULTIMODE))
        // Blank/unknown normalizes to SINGLE_ACTION → single-valued.
        assertFalse(ButtonMappingRules.allowsMultiple("   "))
        assertFalse(ButtonMappingRules.allowsMultiple("BOGUS"))
    }

    @Test
    fun maxIdsReflectsCardinality() {
        assertEquals(1, ButtonMappingRules.maxIds(ButtonModes.SINGLE_ACTION))
        assertEquals(1, ButtonMappingRules.maxIds(ButtonModes.MUSIC_MULTIMODE))
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

    // ---- music-multimode: one music-capable id -------------------------------

    @Test
    fun musicMultimodeKeepsFirstMusicActionOnly() {
        assertEquals(
            listOf(ButtonActions.MUSIC_CONTROL),
            ButtonMappingRules.normalizeIds(
                ButtonModes.MUSIC_MULTIMODE,
                listOf(ButtonActions.MUSIC_CONTROL, ButtonActions.FORWARD_TO_PHONE_MULTI),
            ),
        )
    }

    @Test
    fun musicMultimodeSkipsNonMusicActionsToFindAMusicOne() {
        assertEquals(
            listOf(ButtonActions.FORWARD_TO_PHONE_MULTI),
            ButtonMappingRules.normalizeIds(
                ButtonModes.MUSIC_MULTIMODE,
                // A non-music action first; the first MUSIC-capable id wins.
                listOf(ButtonActions.STOPWATCH, ButtonActions.FORWARD_TO_PHONE_MULTI, ButtonActions.MUSIC_CONTROL),
            ),
        )
    }

    @Test
    fun musicMultimodeWithNoMusicActionYieldsEmpty() {
        assertTrue(
            ButtonMappingRules.normalizeIds(
                ButtonModes.MUSIC_MULTIMODE,
                listOf(ButtonActions.STOPWATCH, ButtonActions.DATE),
            ).isEmpty(),
        )
    }

    @Test
    fun musicActionSetIsTheMultiFunctionPayloads() {
        assertTrue(ButtonMappingRules.isMusicAction(ButtonActions.MUSIC_CONTROL))
        assertTrue(ButtonMappingRules.isMusicAction(ButtonActions.FORWARD_TO_PHONE_MULTI))
        assertTrue(ButtonMappingRules.isMusicAction(ButtonActions.VOLUME_UP))
        assertTrue(ButtonMappingRules.isMusicAction(ButtonActions.VOLUME_DOWN))
        assertFalse(ButtonMappingRules.isMusicAction(ButtonActions.STOPWATCH))
        assertFalse(ButtonMappingRules.isMusicAction(ButtonActions.FORWARD_TO_PHONE))
    }

    // ---- custom-toggle: keep the whole cycle, order preserved ----------------

    @Test
    fun customToggleKeepsWholeListInOrder() {
        val cycle = listOf(ButtonDialModes.TIMEZONE_2, ButtonDialModes.DATE, ButtonDialModes.ALARM)
        assertEquals(cycle, ButtonMappingRules.normalizeIds(ButtonModes.CUSTOM_TOGGLE, cycle))
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
