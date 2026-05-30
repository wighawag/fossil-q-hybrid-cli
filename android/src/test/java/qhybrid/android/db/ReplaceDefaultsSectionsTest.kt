package qhybrid.android.db

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import qhybrid.android.buttons.ButtonActionsJson
import qhybrid.android.buttons.ButtonModes
import qhybrid.android.buttons.ButtonSlots
import qhybrid.android.defaults.DefaultsProfile
import qhybrid.android.defaults.DefaultsToSeed

/**
 * WP-DEFAULTS (sub-part 2) — proves the seeded child rows from [DefaultsToSeed] persist (re-keyed
 * to the mac) via [WatchRepository.replaceDefaultsSections], a FULL-REPLACE of the unreadable
 * sections (the same contract provisioning + apply-defaults use).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ReplaceDefaultsSectionsTest : DbTestBase() {

    private val mac = "AA:BB:CC:DD:EE:FF"

    @Test
    fun factorySeed_persistsThreeButtons_reKeyedToMac() = runTest {
        watchDao.upsert(watch(mac))
        val seed = DefaultsToSeed.seed(DefaultsProfile.FACTORY, mac)

        repo.replaceDefaultsSections(mac, seed.alarms, seed.rules, seed.buttons)

        val buttons = buttonDao.getForWatch(mac)
        assertEquals(3, buttons.size)
        buttons.forEach { assertEquals(mac, it.watchMac) }
        val byId = buttons.associateBy { it.buttonId }
        assertEquals(ButtonModes.SINGLE_ACTION, byId[ButtonSlots.TOP]!!.modeType)
        assertEquals(
            listOf("STOPWATCH"),
            ButtonActionsJson.decode(byId[ButtonSlots.TOP]!!.actionsJson),
        )
        // Factory alarms + rules empty.
        assertTrue(alarmDao.getForWatch(mac).isEmpty())
        assertTrue(ruleDao.getForWatch(mac).isEmpty())
    }

    @Test
    fun fullReplace_overwritesExistingSections_notMerge() = runTest {
        watchDao.upsert(watch(mac))
        // Pre-existing per-watch rows the user set.
        buttonDao.upsert(button(mac, ButtonSlots.TOP).copy(actionsJson = """[{"action":"DATE"}]"""))
        ruleDao.upsert(rule(mac, "com.old.app"))
        alarmDao.upsert(alarm(mac, 5))

        val seed = DefaultsToSeed.seed(DefaultsProfile.FACTORY, mac)
        repo.replaceDefaultsSections(mac, seed.alarms, seed.rules, seed.buttons)

        // Buttons fully replaced (the old TOP=DATE is gone; the 3 factory buttons are there).
        val buttons = buttonDao.getForWatch(mac)
        assertEquals(3, buttons.size)
        assertEquals(
            listOf("STOPWATCH"),
            ButtonActionsJson.decode(buttons.first { it.buttonId == ButtonSlots.TOP }.actionsJson),
        )
        // Rules + alarms fully replaced with the (empty) factory sections.
        assertTrue(ruleDao.getForWatch(mac).isEmpty())
        assertTrue(alarmDao.getForWatch(mac).isEmpty())
    }

    @Test
    fun replaceAlarmsFalse_leavesAlarmsUntouched() = runTest {
        watchDao.upsert(watch(mac))
        alarmDao.upsert(alarm(mac, 3))

        val seed = DefaultsToSeed.seed(DefaultsProfile.FACTORY, mac)
        // The apply-defaults action (sub-part 3) overwrites buttons + rules but can leave alarms.
        repo.replaceDefaultsSections(mac, seed.alarms, seed.rules, seed.buttons, replaceAlarms = false)

        assertEquals(1, alarmDao.getForWatch(mac).size) // alarm slot 3 preserved
        assertEquals(3, buttonDao.getForWatch(mac).size) // buttons still replaced
    }

    @Test
    fun lowerCaseMac_normalizedToRowKey() = runTest {
        watchDao.upsert(watch(mac.uppercase()))
        val seed = DefaultsToSeed.seed(DefaultsProfile.FACTORY, mac.lowercase())
        repo.replaceDefaultsSections(mac.lowercase(), seed.alarms, seed.rules, seed.buttons)

        assertEquals(3, buttonDao.getForWatch(mac.uppercase()).size)
    }
}
