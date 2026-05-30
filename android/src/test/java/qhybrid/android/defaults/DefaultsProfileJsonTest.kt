package qhybrid.android.defaults

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import qhybrid.android.buttons.ButtonActions
import qhybrid.android.buttons.ButtonDialModes
import qhybrid.android.buttons.ButtonModes
import qhybrid.android.buttons.ButtonSlots

/**
 * WP-DEFAULTS (sub-part 1) — the profile codec + factory defaults. Robolectric because the codec
 * uses Android's bundled `org.json` (same as `ButtonActionsJson`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DefaultsProfileJsonTest {

    // ---- factory defaults -----------------------------------------------------

    @Test
    fun factoryButtons_matchTheSpec() {
        val b = DefaultsProfile.FACTORY.buttons.associateBy { it.buttonId }

        val top = b[ButtonSlots.TOP]!!
        assertEquals(ButtonModes.SINGLE_ACTION, top.modeType)
        assertEquals(listOf(ButtonActions.STOPWATCH), top.actions)

        val mid = b[ButtonSlots.MIDDLE]!!
        assertEquals(ButtonModes.CUSTOM_TOGGLE, mid.modeType)
        // Canonical order: TIMEZONE_2, ALARM, DATE.
        assertEquals(
            listOf(ButtonDialModes.TIMEZONE_2, ButtonDialModes.ALARM, ButtonDialModes.DATE),
            mid.actions,
        )

        val bot = b[ButtonSlots.BOTTOM]!!
        assertEquals(ButtonModes.SINGLE_ACTION, bot.modeType)
        assertEquals(listOf(ButtonActions.MULTI_FUNCTION), bot.actions)
    }

    @Test
    fun factoryAlarmsAndRules_areEmpty() {
        assertTrue(DefaultsProfile.FACTORY.alarms.isEmpty())
        assertTrue(DefaultsProfile.FACTORY.rules.isEmpty())
    }

    // ---- round-trip identity --------------------------------------------------

    @Test
    fun encodeDecode_isIdentity_forFactory() {
        val encoded = DefaultsProfileJson.encode(DefaultsProfile.FACTORY)
        val decoded = DefaultsProfileJson.decode(encoded)
        assertEquals(DefaultsProfile.FACTORY, decoded)
    }

    @Test
    fun encodeDecode_isIdentity_forRichProfile() {
        val profile = DefaultsProfile(
            alarms = listOf(
                DefaultAlarm(0, 7, 30, isEnabled = true, daysMask = 0x3E, isRepeating = true, label = "Wake"),
                DefaultAlarm(3, 22, 0, isEnabled = false, daysMask = 0x00, isRepeating = false, label = null),
            ),
            rules = listOf(
                DefaultRule("com.whatsapp", vibePattern = 2, hourHandDegrees = 90, minuteHandDegrees = 180),
            ),
            buttons = listOf(
                DefaultButton(ButtonSlots.TOP, ButtonModes.SINGLE_ACTION, listOf(ButtonActions.DATE)),
                DefaultButton(
                    ButtonSlots.MIDDLE, ButtonModes.CUSTOM_TOGGLE,
                    listOf(ButtonDialModes.ALERT, ButtonDialModes.DATE),
                ),
            ),
        )
        val decoded = DefaultsProfileJson.decode(DefaultsProfileJson.encode(profile))
        assertEquals(profile, decoded)
    }

    @Test
    fun emptySections_roundTrip() {
        val profile = DefaultsProfile(alarms = emptyList(), rules = emptyList(), buttons = emptyList())
        val decoded = DefaultsProfileJson.decode(DefaultsProfileJson.encode(profile))
        assertEquals(profile, decoded)
        // The user CLEARED buttons — an explicit empty array must NOT spring back to factory.
        assertTrue(decoded.buttons.isEmpty())
    }

    // ---- tolerance ------------------------------------------------------------

    @Test
    fun blankInput_decodesToFactory() {
        assertEquals(DefaultsProfile.FACTORY, DefaultsProfileJson.decode(null))
        assertEquals(DefaultsProfile.FACTORY, DefaultsProfileJson.decode(""))
        assertEquals(DefaultsProfile.FACTORY, DefaultsProfileJson.decode("   "))
    }

    @Test
    fun malformedJson_decodesToFactory() {
        assertEquals(DefaultsProfile.FACTORY, DefaultsProfileJson.decode("{not json"))
        assertEquals(DefaultsProfile.FACTORY, DefaultsProfileJson.decode("[1,2,3]"))
    }

    @Test
    fun foreignJson_decodesToFactory() {
        // A valid JSON object with none of our keys is foreign → factory.
        assertEquals(DefaultsProfile.FACTORY, DefaultsProfileJson.decode("""{"foo":"bar"}"""))
    }

    @Test
    fun partialBlob_keepsPresentSections_factoryButtonsWhenAbsent() {
        // Only alarms present: alarms decoded, rules empty, buttons fall back to FACTORY.
        val json = """{"alarms":[{"slotId":1,"hour":6,"minute":15,"isEnabled":true,"daysMask":62,"isRepeating":true}]}"""
        val decoded = DefaultsProfileJson.decode(json)
        assertEquals(1, decoded.alarms.size)
        assertEquals(6, decoded.alarms[0].hour)
        assertTrue(decoded.rules.isEmpty())
        assertEquals(DefaultsProfile.FACTORY.buttons, decoded.buttons)
    }

    @Test
    fun malformedRows_areSkipped_notThrown() {
        // A buttons array with one garbage entry + one good entry: garbage dropped, good kept.
        val json = """{"buttons":[{"nope":true},{"buttonId":16,"modeType":"SINGLE_ACTION","actionsJson":"[{\"action\":\"STOPWATCH\"}]"}]}"""
        val decoded = DefaultsProfileJson.decode(json)
        assertEquals(1, decoded.buttons.size)
        assertEquals(ButtonSlots.TOP, decoded.buttons[0].buttonId)
        assertEquals(listOf(ButtonActions.STOPWATCH), decoded.buttons[0].actions)
    }
}
