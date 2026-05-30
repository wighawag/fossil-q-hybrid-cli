package qhybrid.android.db

import android.content.Context
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * WP-SYNCSTATUS — proves [AppDatabase.MIGRATION_1_2] is additive: it adds the six synced-marker
 * columns with `DEFAULT 0` and DOES NOT wipe existing rows. We can't use Room's MigrationTestHelper
 * (it needs the exported schema JSON, and we keep `exportSchema = false`), so we build a minimal v1
 * schema by hand, insert a row, run the migration, and assert the row survives with the new columns
 * defaulting to 0.
 */
@RunWith(RobolectricTestRunner::class)
class Migration1To2Test {

    private lateinit var helper: SupportSQLiteOpenHelper

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // A throwaway v1 DB created with a raw callback (no Room) so we control the starting schema.
        val callback = object : SupportSQLiteOpenHelper.Callback(1) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE watches (macAddress TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, " +
                        "model TEXT, firmwareVersion TEXT, batteryLevel INTEGER NOT NULL, " +
                        "isActive INTEGER NOT NULL, stepGoal INTEGER NOT NULL, " +
                        "vibrationStrength INTEGER NOT NULL, lastSyncTime INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE watch_alarms (watchMac TEXT NOT NULL, slotId INTEGER NOT NULL, " +
                        "hour INTEGER NOT NULL, minute INTEGER NOT NULL, isEnabled INTEGER NOT NULL, " +
                        "daysMask INTEGER NOT NULL, isRepeating INTEGER NOT NULL, label TEXT, " +
                        "PRIMARY KEY(watchMac, slotId))"
                )
                db.execSQL(
                    "CREATE TABLE notification_rules (watchMac TEXT NOT NULL, packageName TEXT NOT NULL, " +
                        "vibePattern INTEGER NOT NULL, hourHandDegrees INTEGER NOT NULL, " +
                        "minuteHandDegrees INTEGER NOT NULL, PRIMARY KEY(watchMac, packageName))"
                )
                db.execSQL(
                    "CREATE TABLE button_mappings (watchMac TEXT NOT NULL, buttonId INTEGER NOT NULL, " +
                        "modeType TEXT NOT NULL, actionsJson TEXT NOT NULL, " +
                        "PRIMARY KEY(watchMac, buttonId))"
                )
            }

            override fun onUpgrade(
                db: SupportSQLiteDatabase,
                oldVersion: Int,
                newVersion: Int,
            ) = Unit
        }
        // name = null → a private in-memory SQLite DB (no on-disk file, so nothing leaks into
        // sibling Robolectric tests sharing the worker).
        helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(null)
                .callback(callback)
                .build()
        )
        // Force-create v1 and seed a row.
        helper.writableDatabase.execSQL(
            "INSERT INTO watches (macAddress, name, model, firmwareVersion, batteryLevel, isActive, " +
                "stepGoal, vibrationStrength, lastSyncTime) " +
                "VALUES ('AA:00:00:00:00:01', 'My Watch', 'HW.0.0', 'v3', 22, 1, 10000, 50, 0)"
        )
        helper.writableDatabase.execSQL(
            "INSERT INTO watch_alarms (watchMac, slotId, hour, minute, isEnabled, daysMask, " +
                "isRepeating, label) VALUES ('AA:00:00:00:00:01', 0, 7, 30, 1, 62, 1, 'Wake')"
        )
    }

    @After
    fun tearDown() {
        helper.close()
    }

    @Test
    fun migration1To2_addsColumnsWithDefaultZero_andKeepsExistingData() {
        val db = helper.writableDatabase
        AppDatabase.MIGRATION_1_2.migrate(db)

        // The seeded watch row survives, with the three new …SyncedAt columns defaulting to 0.
        db.query(
            "SELECT name, alarmsSyncedAt, notificationFilterSyncedAt, buttonsSyncedAt " +
                "FROM watches WHERE macAddress = 'AA:00:00:00:00:01'"
        ).use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals("My Watch", c.getString(0))
            assertEquals(0L, c.getLong(1))
            assertEquals(0L, c.getLong(2))
            assertEquals(0L, c.getLong(3))
        }

        // The seeded alarm row survives, with the new updatedAt column defaulting to 0.
        db.query(
            "SELECT label, updatedAt FROM watch_alarms WHERE watchMac = 'AA:00:00:00:00:01' AND slotId = 0"
        ).use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals("Wake", c.getString(0))
            assertEquals(0L, c.getLong(1))
        }
    }
}
