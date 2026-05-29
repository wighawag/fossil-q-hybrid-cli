package qhybrid.android.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey

/**
 * WP4 — per-watch alarm slot (ANDROID-PLAN §3, entity #2).
 *
 * Composite PK [watchMac, slotId]; CASCADE-deleted with its parent watch.
 * [slotId] is 0..15 (standard user alarms; slots 16..31 are calendar-sync, owned by WP9/WP13).
 */
@Entity(
    tableName = "watch_alarms",
    primaryKeys = ["watchMac", "slotId"],
    foreignKeys = [ForeignKey(
        entity = WatchEntity::class,
        parentColumns = ["macAddress"],
        childColumns = ["watchMac"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class WatchAlarmEntity(
    @ColumnInfo(index = true) val watchMac: String,
    val slotId: Int,             // 0 to 15 (Standard User Alarms)
    val hour: Int,
    val minute: Int,
    val isEnabled: Boolean,
    val daysMask: Int,           // Day bitmask (bit0=Sun, bit1=Mon ... bit6=Sat)
    val isRepeating: Boolean,    // true = repeats weekly, false = one-shot
    val label: String? = null
)
