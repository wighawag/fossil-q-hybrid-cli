package qhybrid.android.db

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import qhybrid.android.buttons.ButtonSlots

/**
 * Regression — updating a [WatchEntity] field (e.g. vibration strength) must NOT wipe the watch's
 * alarms / notification rules / button mappings.
 *
 * The bug: [WatchDao.upsert] used `@Insert(onConflict = REPLACE)`, and SQLite's `INSERT OR REPLACE`
 * DELETEs the conflicting row before re-inserting — which fired the child tables' `ON DELETE
 * CASCADE` and silently cleared every alarm/rule/button the instant any watch field was saved
 * (alarms "disappeared from the list" after setting vibration, though the watch still had them).
 * The fix switches the DAO to `@Upsert` (in-place UPDATE on conflict), so the row identity and its
 * CASCADE children survive.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WatchUpsertPreservesChildrenTest : DbTestBase() {

    private val mac = "AA:BB:CC:DD:EE:FF"

    @Test
    fun updatingWatchFieldKeepsChildren() = runTest {
        repo.upsertWatch(watch(mac).copy(vibrationStrength = 50))
        alarmDao.upsert(alarm(mac, 0))
        alarmDao.upsert(alarm(mac, 1))
        ruleDao.upsert(rule(mac, "com.whatsapp"))
        buttonDao.upsert(button(mac, ButtonSlots.TOP))

        // Simulate the Settings "set vibration strength" path: re-upsert the SAME watch row.
        val existing = watchDao.getByMac(mac)!!
        repo.upsertWatch(existing.copy(vibrationStrength = 80))

        // The field updated...
        assertEquals(80, watchDao.getByMac(mac)!!.vibrationStrength)
        // ...and NONE of the children were cascade-deleted.
        assertEquals(2, alarmDao.getForWatch(mac).size)
        assertEquals(1, ruleDao.getForWatch(mac).size)
        assertEquals(1, buttonDao.getForWatch(mac).size)
    }

    @Test
    fun upsertStillInsertsNewWatch() = runTest {
        // @Upsert must still create a brand-new row when there's no conflict.
        repo.upsertWatch(watch(mac).copy(vibrationStrength = 30))
        assertEquals(30, watchDao.getByMac(mac)!!.vibrationStrength)
    }
}
