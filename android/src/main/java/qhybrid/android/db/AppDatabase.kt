package qhybrid.android.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * WP4 — the app's local SQLite store (Room). Pure persistence, no BLE.
 *
 * Foreign-key enforcement (so a watch delete CASCADEs to its alarms/rules/buttons) is
 * ON by default in Room. Tests assert the cascade explicitly.
 */
@Database(
    entities = [
        WatchEntity::class,
        WatchAlarmEntity::class,
        NotificationRuleEntity::class,
        ButtonMappingEntity::class,
        WaypointEntity::class,
    ],
    version = 4,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun watchDao(): WatchDao
    abstract fun watchAlarmDao(): WatchAlarmDao
    abstract fun notificationRuleDao(): NotificationRuleDao
    abstract fun buttonMappingDao(): ButtonMappingDao
    abstract fun waypointDao(): WaypointDao

    companion object {
        private const val DB_NAME = "fossilq.db"

        /**
         * WP-SYNCSTATUS — migration 1→2: add the synced-marker columns. Six new `NOT NULL DEFAULT 0`
         * columns across four tables: each child row's `updatedAt` ("last written to the DB at") and
         * the parent watch's three per-section `…SyncedAt` stamps ("last pushed to the watch at").
         * Pure additive ALTERs — existing rows keep their data and default to 0 (= never synced /
         * pending until the next sync pass stamps the section), so no install is wiped.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE watch_alarms ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE notification_rules ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE button_mappings ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE watches ADD COLUMN alarmsSyncedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE watches ADD COLUMN notificationFilterSyncedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE watches ADD COLUMN buttonsSyncedAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * Migration 2→3: add `notification_rules.isEnabled` so a per-app rule can be DISABLED
         * (kept/listed/editable but not uploaded to the watch's notification filter), mirroring the
         * alarm enable/disable switch. Additive `NOT NULL DEFAULT 1` — existing rules stay enabled.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notification_rules ADD COLUMN isEnabled INTEGER NOT NULL DEFAULT 1")
            }
        }

        /**
         * WP-TRACKER — migration 3→4: add the new `waypoints` table (GPS waypoint log). Purely
         * additive — a brand-new table, so no existing row is touched and no install is wiped. The
         * table is NOT foreign-key-bound to `watches` (a waypoint outlives its watch); `watchMac` is
         * an optional informational column with an index for fast filtering. `id` is
         * INTEGER PRIMARY KEY AUTOINCREMENT to match Room's autoGenerate id contract.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS waypoints (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "watchMac TEXT, " +
                        "kind TEXT NOT NULL, " +
                        "lat REAL NOT NULL, " +
                        "lon REAL NOT NULL, " +
                        "accuracyM REAL, " +
                        "capturedAt INTEGER NOT NULL, " +
                        "note TEXT)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_waypoints_watchMac ON waypoints(watchMac)")
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        /** Process-wide singleton. Safe for the WP3 service + UI to share. */
        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME,
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build().also { INSTANCE = it }
            }
    }
}
