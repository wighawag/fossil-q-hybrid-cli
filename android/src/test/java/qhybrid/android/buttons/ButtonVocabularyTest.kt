package qhybrid.android.buttons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * WP16d — constants sanity + actionsJson encode/decode round-trip & malformed tolerance.
 * Robolectric is used only because [ButtonActionsJson] relies on Android's bundled `org.json`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ButtonVocabularyTest {

    // ---- ButtonSlots (fixed 3-button layout) ---------------------------------

    @Test
    fun buttonSlotsMirrorProtocolIndices() {
        // 0x10=TOP, 0x20=MIDDLE, 0x30=BOTTOM (ButtonCompiler.BUTTON_INDICES).
        assertEquals(0x10, ButtonSlots.TOP)
        assertEquals(0x20, ButtonSlots.MIDDLE)
        assertEquals(0x30, ButtonSlots.BOTTOM)
        assertEquals(listOf(0x10, 0x20, 0x30), ButtonSlots.ALL)
        assertEquals("Top button", ButtonSlots.label(ButtonSlots.TOP))
        assertEquals("Middle button", ButtonSlots.label(ButtonSlots.MIDDLE))
        assertEquals("Bottom button", ButtonSlots.label(ButtonSlots.BOTTOM))
        assertTrue(ButtonSlots.isKnown(ButtonSlots.BOTTOM))
        assertFalse(ButtonSlots.isKnown(0x99))
        // Unknown ids fall back to a hex label.
        assertEquals("Button 0x99", ButtonSlots.label(0x99))
    }

    // ---- ButtonModes ---------------------------------------------------------

    @Test
    fun buttonModeConstantsAndLabels() {
        // WP-BTN: two selectable modes only (MUSIC_MULTIMODE removed).
        assertEquals("SINGLE_ACTION", ButtonModes.SINGLE_ACTION)
        assertEquals("CUSTOM_TOGGLE", ButtonModes.CUSTOM_TOGGLE)
        assertEquals(listOf(ButtonModes.SINGLE_ACTION, ButtonModes.CUSTOM_TOGGLE), ButtonModes.ALL)
        assertEquals(2, ButtonModes.ALL.size)
        assertEquals(ButtonModes.SINGLE_ACTION, ButtonModes.DEFAULT)
        assertTrue(ButtonModes.isKnown(ButtonModes.SINGLE_ACTION))
        assertTrue(ButtonModes.isKnown(ButtonModes.CUSTOM_TOGGLE))
        // The removed legacy mode is no longer a known/selectable mode.
        assertFalse(ButtonModes.isKnown(ButtonModes.LEGACY_MUSIC_MULTIMODE))
        assertFalse(ButtonModes.isKnown("BOGUS"))
        // Unknown strings render raw (graceful fallback).
        assertEquals("BOGUS", ButtonModes.label("BOGUS"))
        // Only CUSTOM_TOGGLE implies dial-mode toggles.
        assertTrue(ButtonModes.usesDialModes(ButtonModes.CUSTOM_TOGGLE))
        assertFalse(ButtonModes.usesDialModes(ButtonModes.SINGLE_ACTION))
    }

    @Test
    fun normalizeDefaultsBlankToDefaultAndCollapsesLegacyMusicMode() {
        assertEquals(ButtonModes.DEFAULT, ButtonModes.normalize(null))
        assertEquals(ButtonModes.DEFAULT, ButtonModes.normalize("   "))
        assertEquals(ButtonModes.CUSTOM_TOGGLE, ButtonModes.normalize("  CUSTOM_TOGGLE "))
        // WP-BTN: a legacy MUSIC_MULTIMODE row normalizes to SINGLE_ACTION (no crash, no new mode).
        assertEquals(ButtonModes.SINGLE_ACTION, ButtonModes.normalize(ButtonModes.LEGACY_MUSIC_MULTIMODE))
        assertEquals(ButtonModes.SINGLE_ACTION, ButtonModes.normalize("  MUSIC_MULTIMODE "))
    }

    // ---- ButtonDialModes -----------------------------------------------------

    @Test
    fun dialModeConstantsMirrorProtocol() {
        // 1:1 with ButtonCompiler.DialMode {ALERT, TIMEZONE_2, ALARM, DATE, TWENTY_FOUR_HOUR} —
        // this is the catalog/display order (NOT the cycle order, which is user-chosen).
        assertEquals(
            listOf("ALERT", "TIMEZONE_2", "ALARM", "DATE", "TWENTY_FOUR_HOUR"),
            ButtonDialModes.ALL,
        )
        assertEquals("2nd timezone", ButtonDialModes.label(ButtonDialModes.TIMEZONE_2))
        assertEquals("24-hour", ButtonDialModes.label(ButtonDialModes.TWENTY_FOUR_HOUR))
        assertTrue(ButtonDialModes.isKnown(ButtonDialModes.ALARM))
    }

    @Test
    fun dedupPreservesUserOrderAndDropsUnknownBlankDuplicates() {
        // The cycle order is the user's order; dedup keeps first occurrence + drops unknown/blank.
        assertEquals(
            listOf(ButtonDialModes.DATE, ButtonDialModes.ALARM, ButtonDialModes.TIMEZONE_2),
            ButtonDialModes.dedup(
                listOf(
                    ButtonDialModes.DATE, ButtonDialModes.ALARM, ButtonDialModes.TIMEZONE_2,
                    ButtonDialModes.DATE, "", "  ", "BOGUS",
                ),
            ),
        )
    }

    // ---- ButtonActions -------------------------------------------------------

    @Test
    fun actionCatalogIsDedupedToWireUniquePayloads() {
        // WP-BTN/WP12: 9 wire-unique actions; WP-TRACKER adds 2 button-aware Path-2 actions that
        // share the RING_PHONE payload (distinguished by the 0x08 event's button id, not the wire).
        // Every selectable id resolves (via payloadName) to a real ConfigPayload.
        for (id in ButtonActions.ALL) {
            assertEquals(
                ButtonActions.payloadName(id),
                qhybrid.protocol.buttonconfig.ConfigPayload.valueOf(ButtonActions.payloadName(id)).name,
            )
            assertTrue("missing label for $id", ButtonActions.isKnown(id))
        }
        assertEquals(11, ButtonActions.ALL.size)
        // WP-TRACKER: the two new button-aware single-press actions are selectable + labelled.
        assertTrue(ButtonActions.LOG_WAYPOINT in ButtonActions.ALL)
        assertTrue(ButtonActions.SWITCH_MULTI_FUNCTION_MODE in ButtonActions.ALL)
        assertEquals("Log GPS waypoint", ButtonActions.label(ButtonActions.LOG_WAYPOINT))
        assertEquals("Switch multi-function mode", ButtonActions.label(ButtonActions.SWITCH_MULTI_FUNCTION_MODE))
        // WP12: the concrete MUSIC_CONTROL is now the selectable + the default.
        assertEquals(ButtonActions.MUSIC_CONTROL, ButtonActions.DEFAULT)
        assertTrue(ButtonActions.MUSIC_CONTROL in ButtonActions.ALL)
        assertTrue(ButtonActions.isKnown(ButtonActions.MUSIC_CONTROL))
        // The redundant duplicates / former placeholder are NOT offered.
        assertFalse(ButtonActions.MULTI_FUNCTION in ButtonActions.ALL)
        assertFalse(ButtonActions.FORWARD_TO_PHONE_MULTI in ButtonActions.ALL)
        assertFalse(ButtonActions.FORWARD_TO_PHONE in ButtonActions.ALL)
        // isKnown reflects the SELECTABLE set (legacy aliases are not "known").
        assertFalse(ButtonActions.isKnown(ButtonActions.MULTI_FUNCTION))
        assertFalse(ButtonActions.isKnown(ButtonActions.FORWARD_TO_PHONE_MULTI))
        assertEquals("Ring phone", ButtonActions.label(ButtonActions.RING_PHONE))
        assertEquals("Music control", ButtonActions.label(ButtonActions.MUSIC_CONTROL))
        // The legacy placeholder keeps a readable (folded) label if an old row surfaces.
        assertEquals("Music control", ButtonActions.label(ButtonActions.MULTI_FUNCTION))
        // Unknown id renders raw.
        assertEquals("BOGUS", ButtonActions.label("BOGUS"))
    }

    @Test
    fun payloadNameCollapsesRedundantAliasesAndIsIdentityOtherwise() {
        // WP12: MUSIC_CONTROL + its legacy aliases collapse onto the SAME canonical wire payload
        // (byte-identical to the pre-rename MULTI_FUNCTION output).
        assertEquals("FORWARD_TO_PHONE_MULTI", ButtonActions.payloadName(ButtonActions.MUSIC_CONTROL))
        assertEquals("FORWARD_TO_PHONE_MULTI", ButtonActions.payloadName(ButtonActions.MULTI_FUNCTION))
        assertEquals("FORWARD_TO_PHONE_MULTI", ButtonActions.payloadName(ButtonActions.FORWARD_TO_PHONE_MULTI))
        assertEquals("RING_PHONE", ButtonActions.payloadName(ButtonActions.FORWARD_TO_PHONE))
        // Distinct actions are their own payloads (identity).
        assertEquals("VOLUME_UP", ButtonActions.payloadName(ButtonActions.VOLUME_UP))
        assertEquals("VOLUME_DOWN", ButtonActions.payloadName(ButtonActions.VOLUME_DOWN))
        assertEquals("STOPWATCH", ButtonActions.payloadName(ButtonActions.STOPWATCH))
        // WP-TRACKER: the new button-aware Path-2 actions compile byte-identically to RING_PHONE
        // (`01 01 0C 00`) — same payload, distinguished only by the per-button stored action.
        assertEquals("RING_PHONE", ButtonActions.payloadName(ButtonActions.RING_PHONE))
        assertEquals("RING_PHONE", ButtonActions.payloadName(ButtonActions.LOG_WAYPOINT))
        assertEquals("RING_PHONE", ButtonActions.payloadName(ButtonActions.SWITCH_MULTI_FUNCTION_MODE))
    }

    @Test
    fun normalizeFoldsLegacyAliasesOntoSelectableVocabulary() {
        // WP12: the rename is a label/vocabulary fold — a stored placeholder MULTI_FUNCTION (or the
        // byte-identical FORWARD_TO_PHONE_MULTI) surfaces as the new selectable MUSIC_CONTROL.
        assertEquals(ButtonActions.MUSIC_CONTROL, ButtonActions.normalize(ButtonActions.MULTI_FUNCTION))
        assertEquals(ButtonActions.MUSIC_CONTROL, ButtonActions.normalize(ButtonActions.FORWARD_TO_PHONE_MULTI))
        assertEquals(ButtonActions.MUSIC_CONTROL, ButtonActions.normalize("  MULTI_FUNCTION "))
        // MUSIC_CONTROL is already canonical; the dropped RING duplicate folds too.
        assertEquals(ButtonActions.MUSIC_CONTROL, ButtonActions.normalize(ButtonActions.MUSIC_CONTROL))
        assertEquals(ButtonActions.RING_PHONE, ButtonActions.normalize(ButtonActions.FORWARD_TO_PHONE))
        // Distinct ids pass through (trimmed); unknown ids are left for the caller.
        assertEquals(ButtonActions.STOPWATCH, ButtonActions.normalize("  STOPWATCH "))
        assertEquals("BOGUS", ButtonActions.normalize("BOGUS"))
    }

    // ---- ButtonActionsJson round-trip + tolerance ----------------------------

    @Test
    fun encodeDecodeRoundTrip() {
        val actions = listOf(ButtonActions.DATE, ButtonActions.RING_PHONE, ButtonActions.MUSIC_CONTROL)
        val json = ButtonActionsJson.encode(actions)
        assertEquals(actions, ButtonActionsJson.decode(json))
        // Canonical object shape.
        assertTrue(json.contains("\"action\""))
    }

    @Test
    fun decodeToleratesEmptyAndMalformed() {
        assertTrue(ButtonActionsJson.decode(null).isEmpty())
        assertTrue(ButtonActionsJson.decode("").isEmpty())
        assertTrue(ButtonActionsJson.decode("   ").isEmpty())
        assertTrue(ButtonActionsJson.decode("{not json").isEmpty())
        assertTrue(ButtonActionsJson.decode("not even close").isEmpty())
        // Encoding the empty list yields a valid empty JSON array.
        assertEquals("[]", ButtonActionsJson.encode(emptyList()))
    }

    @Test
    fun decodeAcceptsBareStringArrayFallback() {
        // Lenient: an array of bare strings is also understood.
        assertEquals(
            listOf("DATE", "RING_PHONE"),
            ButtonActionsJson.decode("""["DATE","RING_PHONE"]"""),
        )
    }

    @Test
    fun decodeMatchesDaoTestShape() {
        // The WP4 DAO fixture uses [{"action":"MUSIC_PLAY"}] — decode it without throwing.
        assertEquals(listOf("MUSIC_PLAY"), ButtonActionsJson.decode("""[{"action":"MUSIC_PLAY"}]"""))
    }

    @Test
    fun encodeDropsBlankEntries() {
        assertEquals(
            listOf(ButtonActions.DATE),
            ButtonActionsJson.decode(ButtonActionsJson.encode(listOf("  ", ButtonActions.DATE, ""))),
        )
    }

    @Test
    fun summaryIsHumanReadable() {
        assertEquals("No actions", ButtonActionsJson.summary("[]"))
        assertEquals("No actions", ButtonActionsJson.summary(null))
        assertEquals(
            "Show date, Ring phone",
            ButtonActionsJson.summary(ButtonActionsJson.encode(listOf(ButtonActions.DATE, ButtonActions.RING_PHONE))),
        )
    }
}
