package qhybrid.android.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** WP4 — DAO for the [WatchEntity] registry (multi-watch + active selection). */
@Dao
interface WatchDao {

    /**
     * Insert a new watch, or UPDATE the existing row in place on a [macAddress] conflict.
     *
     * **MUST be [Upsert], NOT `@Insert(onConflict = REPLACE)`.** `INSERT OR REPLACE` deletes the
     * conflicting row and re-inserts it, which fires the child tables' `ON DELETE CASCADE` and
     * **silently wipes the watch's alarms / notification rules / button mappings**. That caused
     * alarms to vanish from the app the moment any watch-row field was updated (e.g. setting
     * vibration strength), while the watch itself still held them. [Upsert] performs an in-place
     * UPDATE on conflict, so the row identity — and its CASCADE children — survive.
     */
    @Upsert
    suspend fun upsert(watch: WatchEntity)

    @Update
    suspend fun update(watch: WatchEntity)

    @Delete
    suspend fun delete(watch: WatchEntity)

    @Query("DELETE FROM watches WHERE macAddress = :mac")
    suspend fun deleteByMac(mac: String)

    @Query("SELECT * FROM watches WHERE macAddress = :mac")
    suspend fun getByMac(mac: String): WatchEntity?

    @Query("SELECT * FROM watches ORDER BY name")
    suspend fun getAll(): List<WatchEntity>

    @Query("SELECT * FROM watches ORDER BY name")
    fun observeAll(): Flow<List<WatchEntity>>

    @Query("SELECT * FROM watches WHERE isActive = 1 LIMIT 1")
    suspend fun getActive(): WatchEntity?

    @Query("SELECT * FROM watches WHERE isActive = 1 LIMIT 1")
    fun observeActive(): Flow<WatchEntity?>

    @Query("UPDATE watches SET isActive = 0")
    suspend fun clearActiveFlag()

    @Query("UPDATE watches SET isActive = 1 WHERE macAddress = :mac")
    suspend fun setActiveFlag(mac: String)

    /**
     * Make [mac] the single active watch: clears the flag on every row, then sets it on
     * [mac]. Runs in one transaction so there is never more than one active watch.
     */
    @Transaction
    suspend fun setActive(mac: String) {
        clearActiveFlag()
        setActiveFlag(mac)
    }
}
