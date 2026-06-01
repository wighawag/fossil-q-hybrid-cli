package qhybrid.android.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey

/**
 * WP4 — per-watch alarm slot (ANDROID-PLAN §3, entity #2).
 *
 * Composite PK [watchMac, slotId]; CASCADE-deleted with its parent watch.
 * [slotId] layout (15/1/16 split):
 *   - 0..14  standard user alarms (the WP16b Alarms screen),
 *   - 15     reserved TIMER slot (multi-function "ring in N min" one-shot; owned by the phone-side
 *            timer dispatch, NOT the Alarms screen),
 *   - 16..31 calendar-sync slots (owned by WP9/WP13).
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
    val slotId: Int,             // 0..14 user alarms; 15 = TIMER; 16..31 calendar
    val hour: Int,
    val minute: Int,
    val isEnabled: Boolean,
    val daysMask: Int,           // Day bitmask (bit0=Sun, bit1=Mon ... bit6=Sat)
    val isRepeating: Boolean,    // true = repeats weekly, false = one-shot
    val label: String? = null,
    // WP-SYNCSTATUS — when this row was last written to the DB (stamped by WatchRepository on every
    // upsert path). Compared against WatchEntity.alarmsSyncedAt to decide "on watch?".
    // IGNORED by the WP14 compilers (no wire bytes change).
    val updatedAt: Long = 0
)
