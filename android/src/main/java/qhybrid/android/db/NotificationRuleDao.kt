package qhybrid.android.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** WP4 — DAO for [NotificationRuleEntity]. */
@Dao
interface NotificationRuleDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rule: NotificationRuleEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rules: List<NotificationRuleEntity>)

    @Delete
    suspend fun delete(rule: NotificationRuleEntity)

    @Query("DELETE FROM notification_rules WHERE watchMac = :mac")
    suspend fun deleteForWatch(mac: String)

    @Query("SELECT * FROM notification_rules WHERE watchMac = :mac AND packageName = :pkg")
    suspend fun getRule(mac: String, pkg: String): NotificationRuleEntity?

    @Query("SELECT * FROM notification_rules WHERE watchMac = :mac ORDER BY packageName")
    suspend fun getForWatch(mac: String): List<NotificationRuleEntity>

    @Query("SELECT * FROM notification_rules WHERE watchMac = :mac ORDER BY packageName")
    fun observeForWatch(mac: String): Flow<List<NotificationRuleEntity>>
}
