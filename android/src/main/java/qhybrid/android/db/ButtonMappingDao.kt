package qhybrid.android.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** WP4 — DAO for [ButtonMappingEntity]. */
@Dao
interface ButtonMappingDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(mapping: ButtonMappingEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(mappings: List<ButtonMappingEntity>)

    @Delete
    suspend fun delete(mapping: ButtonMappingEntity)

    @Query("DELETE FROM button_mappings WHERE watchMac = :mac")
    suspend fun deleteForWatch(mac: String)

    /** WP16d — single-row delete (mirrors WP16c's [NotificationRuleDao.deleteRule]). */
    @Query("DELETE FROM button_mappings WHERE watchMac = :mac AND buttonId = :buttonId")
    suspend fun deleteButton(mac: String, buttonId: Int)

    @Query("SELECT * FROM button_mappings WHERE watchMac = :mac AND buttonId = :buttonId")
    suspend fun getButton(mac: String, buttonId: Int): ButtonMappingEntity?

    @Query("SELECT * FROM button_mappings WHERE watchMac = :mac ORDER BY buttonId")
    suspend fun getForWatch(mac: String): List<ButtonMappingEntity>

    @Query("SELECT * FROM button_mappings WHERE watchMac = :mac ORDER BY buttonId")
    fun observeForWatch(mac: String): Flow<List<ButtonMappingEntity>>
}
