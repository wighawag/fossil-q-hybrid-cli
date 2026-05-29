package qhybrid.android.db

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** WP4 — deleting a watch CASCADEs to its alarms/rules/buttons (ForeignKey.CASCADE). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CascadeDeleteTest : DbTestBase() {

    private val mac = "AA:BB:CC:DD:EE:FF"

    @Test
    fun deletingWatch_removesAllChildren() = runTest {
        watchDao.upsert(watch(mac))
        alarmDao.upsertAll(listOf(alarm(mac, 0), alarm(mac, 1), alarm(mac, 2)))
        ruleDao.upsertAll(listOf(rule(mac, "com.whatsapp"), rule(mac, "com.slack")))
        buttonDao.upsertAll(listOf(button(mac, 0x10), button(mac, 0x20)))

        assertEquals(3, alarmDao.getForWatch(mac).size)
        assertEquals(2, ruleDao.getForWatch(mac).size)
        assertEquals(2, buttonDao.getForWatch(mac).size)

        watchDao.deleteByMac(mac)

        assertTrue("alarms should cascade", alarmDao.getForWatch(mac).isEmpty())
        assertTrue("rules should cascade", ruleDao.getForWatch(mac).isEmpty())
        assertTrue("buttons should cascade", buttonDao.getForWatch(mac).isEmpty())
    }
}
