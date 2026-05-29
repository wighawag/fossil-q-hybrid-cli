package qhybrid.android.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

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
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun watchDao(): WatchDao
    abstract fun watchAlarmDao(): WatchAlarmDao
    abstract fun notificationRuleDao(): NotificationRuleDao
    abstract fun buttonMappingDao(): ButtonMappingDao

    companion object {
        private const val DB_NAME = "fossilq.db"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        /** Process-wide singleton. Safe for the WP3 service + UI to share. */
        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME,
                ).build().also { INSTANCE = it }
            }
    }
}
