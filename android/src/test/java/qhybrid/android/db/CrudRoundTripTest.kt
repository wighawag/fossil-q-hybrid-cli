package qhybrid.android.db

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** WP4 — CRUD round-trips for each of the 4 entities. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CrudRoundTripTest : DbTestBase() {

    private val mac = "D9:20:71:11:74:2A"

    @Test
    fun watch_crud() = runTest {
        watchDao.upsert(watch(mac, name = "Daily"))
        assertEquals("Daily", watchDao.getByMac(mac)?.name)

        watchDao.upsert(watch(mac, name = "Daily").copy(batteryLevel = 80))
        assertEquals(80, watchDao.getByMac(mac)?.batteryLevel)

        watchDao.deleteByMac(mac)
        assertNull(watchDao.getByMac(mac))
    }

    @Test
    fun alarm_crud() = runTest {
        watchDao.upsert(watch(mac))
        alarmDao.upsert(alarm(mac, 3))
        assertEquals(1, alarmDao.getForWatch(mac).size)
        assertEquals(33, alarmDao.getForWatch(mac).first().minute)

        // REPLACE on same PK [watchMac, slotId] updates rather than duplicating.
        alarmDao.upsert(alarm(mac, 3).copy(hour = 9))
        assertEquals(1, alarmDao.getForWatch(mac).size)
        assertEquals(9, alarmDao.getForWatch(mac).first().hour)

        alarmDao.deleteSlot(mac, 3)
        assertTrue(alarmDao.getForWatch(mac).isEmpty())
    }

    @Test
    fun rule_crud() = runTest {
        watchDao.upsert(watch(mac))
        ruleDao.upsert(rule(mac, "com.whatsapp"))
        assertNotNull(ruleDao.getRule(mac, "com.whatsapp"))

        ruleDao.upsert(rule(mac, "com.whatsapp").copy(vibePattern = 5))
        assertEquals(5, ruleDao.getRule(mac, "com.whatsapp")?.vibePattern)
        assertEquals(1, ruleDao.getForWatch(mac).size)

        ruleDao.delete(rule(mac, "com.whatsapp"))
        assertNull(ruleDao.getRule(mac, "com.whatsapp"))
    }

    /** WP16c — single-row [NotificationRuleDao.deleteRule] removes only the matching package. */
    @Test
    fun rule_deleteSingleRow() = runTest {
        watchDao.upsert(watch(mac))
        ruleDao.upsert(rule(mac, "com.whatsapp"))
        ruleDao.upsert(rule(mac, "com.slack"))
        assertEquals(2, ruleDao.getForWatch(mac).size)

        ruleDao.deleteRule(mac, "com.whatsapp")
        val remaining = ruleDao.getForWatch(mac)
        assertEquals(1, remaining.size)
        assertEquals("com.slack", remaining.first().packageName)

        // Deleting a non-existent package is a harmless no-op.
        ruleDao.deleteRule(mac, "com.absent")
        assertEquals(1, ruleDao.getForWatch(mac).size)
    }

    @Test
    fun button_crud() = runTest {
        watchDao.upsert(watch(mac))
        buttonDao.upsert(button(mac, 0x10))
        assertEquals(1, buttonDao.getForWatch(mac).size)

        buttonDao.upsert(button(mac, 0x10).copy(modeType = "MUSIC_MULTIMODE"))
        assertEquals("MUSIC_MULTIMODE", buttonDao.getForWatch(mac).first().modeType)
        assertEquals(1, buttonDao.getForWatch(mac).size)

        buttonDao.deleteForWatch(mac)
        assertTrue(buttonDao.getForWatch(mac).isEmpty())
    }

    /** WP16d — single-row [ButtonMappingDao.deleteButton] removes only the matching buttonId. */
    @Test
    fun button_deleteSingleRow() = runTest {
        watchDao.upsert(watch(mac))
        buttonDao.upsert(button(mac, 0x10))
        buttonDao.upsert(button(mac, 0x20))
        assertEquals(2, buttonDao.getForWatch(mac).size)
        assertNotNull(buttonDao.getButton(mac, 0x10))

        buttonDao.deleteButton(mac, 0x10)
        val remaining = buttonDao.getForWatch(mac)
        assertEquals(1, remaining.size)
        assertEquals(0x20, remaining.first().buttonId)
        assertNull(buttonDao.getButton(mac, 0x10))

        // Deleting a non-existent buttonId is a harmless no-op.
        buttonDao.deleteButton(mac, 0xFF)
        assertEquals(1, buttonDao.getForWatch(mac).size)
    }
}
