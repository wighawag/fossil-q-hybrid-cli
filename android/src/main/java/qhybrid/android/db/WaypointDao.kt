package qhybrid.android.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** WP-TRACKER \u2014 DAO for [WaypointEntity] (GPS waypoint log). */
@Dao
interface WaypointDao {

    /** Insert a new waypoint; returns the auto-generated row id. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(waypoint: WaypointEntity): Long

    @Query("SELECT * FROM waypoints ORDER BY capturedAt DESC")
    suspend fun getAll(): List<WaypointEntity>

    @Query("SELECT * FROM waypoints ORDER BY capturedAt ASC")
    suspend fun getAllChronological(): List<WaypointEntity>

    @Query("SELECT * FROM waypoints ORDER BY capturedAt DESC")
    fun observeAll(): Flow<List<WaypointEntity>>

    @Query("SELECT COUNT(*) FROM waypoints")
    suspend fun count(): Int

    @Query("DELETE FROM waypoints WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM waypoints")
    suspend fun clear()
}
