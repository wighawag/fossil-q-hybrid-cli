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
        // 1:1 with ButtonCompiler.DialMode {ALERT, TIMEZONE_2, ALARM, DATE, TWENTY_FOUR_HOUR}.
        assertEquals(
            listOf("ALERT", "TIMEZONE_2", "ALARM", "DATE", "TWENTY_FOUR_HOUR"),
            ButtonDialModes.ALL,
        )
        assertEquals("2nd timezone", ButtonDialModes.label(ButtonDialModes.TIMEZONE_2))
        assertEquals("24-hour", ButtonDialModes.label(ButtonDialModes.TWENTY_FOUR_HOUR))
        assertTrue(ButtonDialModes.isKnown(ButtonDialModes.ALARM))
    }

    // ---- ButtonActions -------------------------------------------------------

    @Test
    fun actionCatalogIsDedupedToWireUniquePayloads() {
        // WP-BTN: 9 SELECTABLE actions, each resolving (via payloadName) to a real ConfigPayload.
        for (id in ButtonActions.ALL) {
            assertEquals(
                ButtonActions.payloadName(id),
                qhybrid.protocol.buttonconfig.ConfigPayload.valueOf(ButtonActions.payloadName(id)).name,
            )
            assertTrue("missing label for $id", ButtonActions.isKnown(id))
        }
        assertEquals(9, ButtonActions.ALL.size)
        assertEquals(ButtonActions.MULTI_FUNCTION, ButtonActions.DEFAULT)
        // The redundant duplicates are NOT offered.
        assertFalse(ButtonActions.MUSIC_CONTROL in ButtonActions.ALL)
        assertFalse(ButtonActions.FORWARD_TO_PHONE_MULTI in ButtonActions.ALL)
        assertFalse(ButtonActions.FORWARD_TO_PHONE in ButtonActions.ALL)
        // isKnown reflects the SELECTABLE set (legacy aliases are not "known").
        assertFalse(ButtonActions.isKnown(ButtonActions.MUSIC_CONTROL))
        assertEquals("Ring phone", ButtonActions.label(ButtonActions.RING_PHONE))
        assertEquals("Multi-function (app decides)", ButtonActions.label(ButtonActions.MULTI_FUNCTION))
        // Unknown id renders raw.
        assertEquals("BOGUS", ButtonActions.label("BOGUS"))
    }

    @Test
    fun payloadNameCollapsesRedundantAliasesAndIsIdentityOtherwise() {
        // The two duplicate pairs collapse onto a single canonical wire payload.
        assertEquals("FORWARD_TO_PHONE_MULTI", ButtonActions.payloadName(ButtonActions.MULTI_FUNCTION))
        assertEquals("FORWARD_TO_PHONE_MULTI", ButtonActions.payloadName(ButtonActions.MUSIC_CONTROL))
        assertEquals("FORWARD_TO_PHONE_MULTI", ButtonActions.payloadName(ButtonActions.FORWARD_TO_PHONE_MULTI))
        assertEquals("RING_PHONE", ButtonActions.payloadName(ButtonActions.FORWARD_TO_PHONE))
        // Distinct actions are their own payloads (identity).
        assertEquals("VOLUME_UP", ButtonActions.payloadName(ButtonActions.VOLUME_UP))
        assertEquals("VOLUME_DOWN", ButtonActions.payloadName(ButtonActions.VOLUME_DOWN))
        assertEquals("STOPWATCH", ButtonActions.payloadName(ButtonActions.STOPWATCH))
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
