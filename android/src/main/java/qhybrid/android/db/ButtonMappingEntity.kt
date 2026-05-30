package qhybrid.android.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey

/**
 * WP4 — per-watch physical-button mapping (ANDROID-PLAN §3, entity #4).
 *
 * Composite PK [watchMac, buttonId]; CASCADE-deleted with its parent watch.
 * [buttonId]: 0x10=TOP, 0x20=MIDDLE, 0x30=BOTTOM.
 */
@Entity(
    tableName = "button_mappings",
    primaryKeys = ["watchMac", "buttonId"],
    foreignKeys = [ForeignKey(
        entity = WatchEntity::class,
        parentColumns = ["macAddress"],
        childColumns = ["watchMac"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class ButtonMappingEntity(
    @ColumnInfo(index = true) val watchMac: String,
    val buttonId: Int,            // 0x10=TOP, 0x20=MIDDLE, 0x30=BOTTOM
    val modeType: String,         // "SINGLE_ACTION" vs "CUSTOM_TOGGLE" (legacy "MUSIC_MULTIMODE" tolerated)
    val actionsJson: String,      // JSON list of actions / payloads (DATE, MUSIC, PHONE_RING, etc.)
    // WP-SYNCSTATUS — when this row was last written to the DB (stamped by WatchRepository on every
    // upsert path). Compared against WatchEntity.buttonsSyncedAt to decide "on watch?".
    // IGNORED by the WP14 compilers (no wire bytes change).
    val updatedAt: Long = 0
)
