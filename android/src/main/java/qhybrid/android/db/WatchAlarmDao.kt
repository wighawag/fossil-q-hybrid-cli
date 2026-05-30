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
     * WP-CLEARALARMS — delete only the STANDARD user alarm slots (0..15) for [mac], leaving the
     * calendar-sync slots (16..31, owned by WP9/WP13) intact. Used by the Settings "Clear all
     * alarms" action.
     */
    @Query("DELETE FROM watch_alarms WHERE watchMac = :mac AND slotId BETWEEN 0 AND 15")
    suspend fun deleteStandardForWatch(mac: String)

    @Query("DELETE FROM watch_alarms WHERE watchMac = :mac AND slotId = :slotId")
    suspend fun deleteSlot(mac: String, slotId: Int)

    @Query("SELECT * FROM watch_alarms WHERE watchMac = :mac ORDER BY slotId")
    suspend fun getForWatch(mac: String): List<WatchAlarmEntity>

    @Query("SELECT * FROM watch_alarms WHERE watchMac = :mac ORDER BY slotId")
    fun observeForWatch(mac: String): Flow<List<WatchAlarmEntity>>
}
