package qhybrid.android.db

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * WP4 — transferSettings clones A's alarms/rules/buttons onto B under B's MAC,
 * leaving A untouched (ANDROID-PLAN §3 Settings Transfer / Clone).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TransferSettingsTest : DbTestBase() {

    private val a = "AA:AA:AA:AA:AA:AA"
    private val b = "BB:BB:BB:BB:BB:BB"

    @Test
    fun transfer_copiesAllRowsUnderTargetMac_andLeavesSourceUntouched() = runTest {
        watchDao.upsert(watch(a, name = "Source"))
        watchDao.upsert(watch(b, name = "Target"))

        val n = 4
        alarmDao.upsertAll((0 until n).map { alarm(a, it) })
        ruleDao.upsertAll(listOf("com.whatsapp", "com.slack", "com.signal").map { rule(a, it) })
        buttonDao.upsertAll(listOf(0x10, 0x20, 0x30).map { button(a, it) })

        repo.transferSettings(fromMac = a, toMac = b)

        // B has identical rows, re-keyed under B's MAC.
        val aAlarms = alarmDao.getForWatch(a)
        val bAlarms = alarmDao.getForWatch(b)
        assertEquals(n, bAlarms.size)
        assertTrue(bAlarms.all { it.watchMac == b })
        assertEquals(
            aAlarms.map { it.copy(watchMac = b) },
            bAlarms,
        )

        val aRules = ruleDao.getForWatch(a)
        val bRules = ruleDao.getForWatch(b)
        assertEquals(3, bRules.size)
        assertTrue(bRules.all { it.watchMac == b })
        assertEquals(aRules.map { it.copy(watchMac = b) }, bRules)

        val aButtons = buttonDao.getForWatch(a)
        val bButtons = buttonDao.getForWatch(b)
        assertEquals(3, bButtons.size)
        assertTrue(bButtons.all { it.watchMac == b })
        assertEquals(aButtons.map { it.copy(watchMac = b) }, bButtons)

        // A is untouched: same counts and still keyed under A.
        assertEquals(n, aAlarms.size)
        assertTrue(aAlarms.all { it.watchMac == a })
        assertEquals(3, aRules.size)
        assertEquals(3, aButtons.size)
    }

    @Test
    fun transfer_replacesCollidingRowsOnTarget() = runTest {
        watchDao.upsert(watch(a))
        watchDao.upsert(watch(b))

        // B already has slot 0 with different data; A's slot 0 should overwrite it.
        alarmDao.upsert(alarm(a, 0).copy(hour = 6))
        alarmDao.upsert(alarm(b, 0).copy(hour = 23))

        repo.transferSettings(a, b)

        assertEquals(1, alarmDao.getForWatch(b).size)
        assertEquals(6, alarmDao.getForWatch(b).first().hour)
    }
}
