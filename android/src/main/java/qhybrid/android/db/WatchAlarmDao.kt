package qhybrid.android.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** WP4 — DAO for [WatchAlarmEntity]. */
@Dao
interface WatchAlarmDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(alarm: WatchAlarmEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(alarms: List<WatchAlarmEntity>)

    @Delete
    suspend fun delete(alarm: WatchAlarmEntity)

    @Query("DELETE FROM watch_alarms WHERE watchMac = :mac")
    suspend fun deleteForWatch(mac: String)

    /**
     * WP-CLEARALARMS — delete only the STANDARD user alarm slots (0..14) for [mac], leaving the
     * reserved TIMER slot (15) and the calendar-sync slots (16..31, owned by WP9/WP13) intact.
     * Used by the Settings "Clear all alarms" action.
     *
     * **TIMER:** slot 15 is now reserved for the multi-function TIMER mode's "ring in N min"
     * one-shot; "Clear all alarms" must NOT cancel a pending timer the user just set.
     */
    @Query("DELETE FROM watch_alarms WHERE watchMac = :mac AND slotId BETWEEN 0 AND 14")
    suspend fun deleteStandardForWatch(mac: String)

    /**
     * TIMER — delete the reserved TIMER slot (15) for [mac]. Used to cancel/replace the pending
     * "ring in N min" one-shot before writing a fresh one.
     */
    @Query("DELETE FROM watch_alarms WHERE watchMac = :mac AND slotId = 15")
    suspend fun deleteTimerForWatch(mac: String)

    /**
     * WP13 — delete only the CALENDAR-sync slots (16..31, owned by WP9/WP13) for [mac], leaving the
     * standard user alarm slots (0..15) intact. Used by [WatchRepository.replaceCalendarAlarms] for
     * the calendar full-replace (mirror of [deleteStandardForWatch]).
     */
    @Query("DELETE FROM watch_alarms WHERE watchMac = :mac AND slotId BETWEEN 16 AND 31")
    suspend fun deleteCalendarForWatch(mac: String)

    @Query("DELETE FROM watch_alarms WHERE watchMac = :mac AND slotId = :slotId")
    suspend fun deleteSlot(mac: String, slotId: Int)

    @Query("SELECT * FROM watch_alarms WHERE watchMac = :mac ORDER BY slotId")
    suspend fun getForWatch(mac: String): List<WatchAlarmEntity>

    @Query("SELECT * FROM watch_alarms WHERE watchMac = :mac ORDER BY slotId")
    fun observeForWatch(mac: String): Flow<List<WatchAlarmEntity>>
}
