package qhybrid.android.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey

/**
 * WP4 — per-app notification rule for a watch (ANDROID-PLAN §3, entity #3).
 *
 * Composite PK [watchMac, packageName]; CASCADE-deleted with its parent watch.
 */
@Entity(
    tableName = "notification_rules",
    primaryKeys = ["watchMac", "packageName"],
    foreignKeys = [ForeignKey(
        entity = WatchEntity::class,
        parentColumns = ["macAddress"],
        childColumns = ["watchMac"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class NotificationRuleEntity(
    @ColumnInfo(index = true) val watchMac: String,
    val packageName: String,      // App package ID (e.g. "com.whatsapp")
    val vibePattern: Int,         // 0 to 9 (0=AUTO, 1=CALL, 2=TEXT, 3=EMAIL, 4=DEFAULT, etc.)
    val hourHandDegrees: Int,     // 0 to 359 (precise hand location)
    val minuteHandDegrees: Int,   // 0 to 359
    // WP-SYNCSTATUS — when this row was last written to the DB (stamped by WatchRepository on every
    // upsert path). Compared against WatchEntity.notificationFilterSyncedAt to decide "on watch?".
    // IGNORED by the WP14 compilers (no wire bytes change).
    val updatedAt: Long = 0
)
