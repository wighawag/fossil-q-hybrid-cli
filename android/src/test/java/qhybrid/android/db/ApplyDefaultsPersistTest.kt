package qhybrid.android.db

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import qhybrid.android.buttons.ButtonActionsJson
import qhybrid.android.buttons.ButtonSlots
import qhybrid.android.defaults.DefaultsProfile
import qhybrid.android.defaults.DefaultsToSeed

/**
 * WP-DEFAULTS (sub-part 3) — the DB-persist path the "Apply defaults to this watch" action uses:
 * the re-keyed defaults rows REPLACE the active watch's buttons + rules (full overwrite, NOT a
 * merge), while the per-watch alarms are left untouched (`replaceAlarms = false`). This mirrors
 * exactly what [qhybrid.android.settings.ServiceApplyDefaults]'s persist hook does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ApplyDefaultsPersistTest : DbTestBase() {

    private val mac = "AA:00:00:00:00:01"

    @Test
    fun applyDefaults_replacesButtonsAndRules_preservesAlarms() = runTest {
        watchDao.upsert(watch(mac, active = true))
        // The user's per-watch setup: custom buttons, a rule, and an alarm.
        buttonDao.upsert(button(mac, ButtonSlots.TOP).copy(actionsJson = """[{"action":"DATE"}]"""))
        buttonDao.upsert(button(mac, ButtonSlots.BOTTOM).copy(actionsJson = """[{"action":"RING_PHONE"}]"""))
        ruleDao.upsert(rule(mac, "com.user.app"))
        alarmDao.upsert(alarm(mac, 4))

        // Apply the factory defaults profile, re-keyed to the active mac (alarms left alone).
        val seed = DefaultsToSeed.seed(DefaultsProfile.FACTORY, mac)
        repo.replaceDefaultsSections(mac, seed.alarms, seed.rules, seed.buttons, replaceAlarms = false)

        // Buttons fully replaced with the 3 factory buttons (old TOP=DATE / BOTTOM=RING gone).
        val buttons = buttonDao.getForWatch(mac).associateBy { it.buttonId }
        assertEquals(3, buttons.size)
        assertEquals(listOf("STOPWATCH"), ButtonActionsJson.decode(buttons[ButtonSlots.TOP]!!.actionsJson))
        // WP12: the factory BOTTOM button seeds + decodes the concrete MUSIC_CONTROL action.
        assertEquals(listOf("MUSIC_CONTROL"), ButtonActionsJson.decode(buttons[ButtonSlots.BOTTOM]!!.actionsJson))
        // Rules fully replaced (factory rules empty → the user's rule is gone).
        assertTrue(ruleDao.getForWatch(mac).isEmpty())
        // Alarms preserved (apply does NOT blank the user's per-watch alarms).
        assertEquals(1, alarmDao.getForWatch(mac).size)
    }
}
