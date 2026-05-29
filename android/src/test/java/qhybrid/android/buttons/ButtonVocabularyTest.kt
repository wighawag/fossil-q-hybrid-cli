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

    // ---- ButtonModes ---------------------------------------------------------

    @Test
    fun buttonModeConstantsAndLabels() {
        assertEquals("SINGLE_ACTION", ButtonModes.SINGLE_ACTION)
        assertEquals("MUSIC_MULTIMODE", ButtonModes.MUSIC_MULTIMODE)
        assertEquals("CUSTOM_TOGGLE", ButtonModes.CUSTOM_TOGGLE)
        assertEquals(3, ButtonModes.ALL.size)
        assertEquals(ButtonModes.SINGLE_ACTION, ButtonModes.DEFAULT)
        assertTrue(ButtonModes.isKnown(ButtonModes.MUSIC_MULTIMODE))
        assertFalse(ButtonModes.isKnown("BOGUS"))
        // Unknown strings render raw (graceful fallback).
        assertEquals("BOGUS", ButtonModes.label("BOGUS"))
        // Only CUSTOM_TOGGLE implies dial-mode toggles.
        assertTrue(ButtonModes.usesDialModes(ButtonModes.CUSTOM_TOGGLE))
        assertFalse(ButtonModes.usesDialModes(ButtonModes.SINGLE_ACTION))
        assertFalse(ButtonModes.usesDialModes(ButtonModes.MUSIC_MULTIMODE))
    }

    @Test
    fun normalizeDefaultsBlankToDefault() {
        assertEquals(ButtonModes.DEFAULT, ButtonModes.normalize(null))
        assertEquals(ButtonModes.DEFAULT, ButtonModes.normalize("   "))
        assertEquals(ButtonModes.CUSTOM_TOGGLE, ButtonModes.normalize("  CUSTOM_TOGGLE "))
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
    fun actionCatalogMirrorsConfigPayload() {
        // Each id must be a real ConfigPayload enum constant (1:1 with WP7).
        for (id in ButtonActions.ALL) {
            assertEquals(id, qhybrid.protocol.buttonconfig.ConfigPayload.valueOf(id).name)
            assertTrue("missing label for $id", ButtonActions.isKnown(id))
        }
        assertEquals(11, ButtonActions.ALL.size)
        assertEquals(ButtonActions.FORWARD_TO_PHONE, ButtonActions.DEFAULT)
        assertEquals("Ring phone", ButtonActions.label(ButtonActions.RING_PHONE))
        // Unknown id renders raw.
        assertEquals("BOGUS", ButtonActions.label("BOGUS"))
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
