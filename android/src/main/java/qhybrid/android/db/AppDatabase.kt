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
    ],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun watchDao(): WatchDao
    abstract fun watchAlarmDao(): WatchAlarmDao
    abstract fun notificationRuleDao(): NotificationRuleDao
    abstract fun buttonMappingDao(): ButtonMappingDao

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

        @Volatile
        private var INSTANCE: AppDatabase? = null

        /** Process-wide singleton. Safe for the WP3 service + UI to share. */
        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME,
                ).addMigrations(MIGRATION_1_2).build().also { INSTANCE = it }
            }
    }
}
