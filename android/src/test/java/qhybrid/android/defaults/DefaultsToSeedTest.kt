package qhybrid.android.defaults

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import qhybrid.android.buttons.ButtonActions
import qhybrid.android.buttons.ButtonActionsJson
import qhybrid.android.buttons.ButtonDialModes
import qhybrid.android.buttons.ButtonModes
import qhybrid.android.buttons.ButtonSlots

/**
 * WP-DEFAULTS (sub-part 2) — the pure [DefaultsToSeed] mapper. Robolectric only because the button
 * actionsJson encode uses Android's `org.json`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DefaultsToSeedTest {

    private val mac = "AA:BB:CC:DD:EE:FF"

    @Test
    fun emptySections_yieldEmptySeedRows() {
        val seed = DefaultsToSeed.seed(
            DefaultsProfile(alarms = emptyList(), rules = emptyList(), buttons = emptyList()),
            mac,
        )
        assertTrue(seed.alarms.isEmpty())
        assertTrue(seed.rules.isEmpty())
        assertTrue(seed.buttons.isEmpty())
    }

    @Test
    fun nAlarms_materializeExactlyNRows_reKeyedToMac() {
        val profile = DefaultsProfile(
            alarms = listOf(
                DefaultAlarm(0, 7, 30, true, 0x3E, true, "Wake"),
                DefaultAlarm(1, 8, 0, false, 0x00, false, null),
                DefaultAlarm(2, 22, 15, true, 0x7F, true, "Sleep"),
            ),
        )
        val seed = DefaultsToSeed.seed(profile, mac)
        assertEquals(3, seed.alarms.size)
        seed.alarms.forEach { assertEquals(mac.uppercase(), it.watchMac) }
        assertEquals(0, seed.alarms[0].slotId)
        assertEquals(7, seed.alarms[0].hour)
        assertEquals("Wake", seed.alarms[0].label)
        assertEquals(false, seed.alarms[1].isEnabled)
    }

    @Test
    fun factoryButtons_materializeCorrectly() {
        val seed = DefaultsToSeed.seed(DefaultsProfile.FACTORY, mac)
        val byId = seed.buttons.associateBy { it.buttonId }
        assertEquals(3, seed.buttons.size)
        seed.buttons.forEach { assertEquals(mac.uppercase(), it.watchMac) }

        val top = byId[ButtonSlots.TOP]!!
        assertEquals(ButtonModes.SINGLE_ACTION, top.modeType)
        assertEquals(listOf(ButtonActions.STOPWATCH), ButtonActionsJson.decode(top.actionsJson))

        val mid = byId[ButtonSlots.MIDDLE]!!
        assertEquals(ButtonModes.CUSTOM_TOGGLE, mid.modeType)
        // MIDDLE keeps the canonical dial cycle: TIMEZONE_2, ALARM, DATE.
        assertEquals(
            listOf(ButtonDialModes.TIMEZONE_2, ButtonDialModes.ALARM, ButtonDialModes.DATE),
            ButtonActionsJson.decode(mid.actionsJson),
        )

        val bot = byId[ButtonSlots.BOTTOM]!!
        assertEquals(ButtonModes.SINGLE_ACTION, bot.modeType)
        assertEquals(listOf(ButtonActions.MULTI_FUNCTION), ButtonActionsJson.decode(bot.actionsJson))
    }

    @Test
    fun rules_reKeyedToMac() {
        val profile = DefaultsProfile(
            rules = listOf(DefaultRule("com.whatsapp", 2, 90, 180)),
        )
        val seed = DefaultsToSeed.seed(profile, mac)
        assertEquals(1, seed.rules.size)
        assertEquals(mac.uppercase(), seed.rules[0].watchMac)
        assertEquals("com.whatsapp", seed.rules[0].packageName)
        assertEquals(2, seed.rules[0].vibePattern)
    }

    @Test
    fun lowerCaseMac_isNormalizedToUpper() {
        val seed = DefaultsToSeed.seed(DefaultsProfile.FACTORY, mac.lowercase())
        seed.buttons.forEach { assertEquals(mac.uppercase(), it.watchMac) }
    }
}
