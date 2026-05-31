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
 * WP-TRACKER — proves [AppDatabase.MIGRATION_3_4] is additive: it CREATEs the new `waypoints` table
 * and touches nothing else (no existing row wiped). We hand-build a minimal v3 DB with one
 * notification_rules row, run the migration, and assert the old row survives AND the new table is
 * usable (insert + read back).
 */
@RunWith(RobolectricTestRunner::class)
class Migration3To4Test {

    private lateinit var helper: SupportSQLiteOpenHelper

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val callback = object : SupportSQLiteOpenHelper.Callback(3) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                // A minimal v3 notification_rules table (post 2→3 shape).
                db.execSQL(
                    "CREATE TABLE notification_rules (watchMac TEXT NOT NULL, packageName TEXT NOT NULL, " +
                        "vibePattern INTEGER NOT NULL, hourHandDegrees INTEGER NOT NULL, " +
                        "minuteHandDegrees INTEGER NOT NULL, updatedAt INTEGER NOT NULL DEFAULT 0, " +
                        "isEnabled INTEGER NOT NULL DEFAULT 1, PRIMARY KEY(watchMac, packageName))"
                )
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(null).callback(callback).build()
        )
        helper.writableDatabase.execSQL(
            "INSERT INTO notification_rules (watchMac, packageName, vibePattern, hourHandDegrees, " +
                "minuteHandDegrees, updatedAt, isEnabled) VALUES ('AA:00:00:00:00:01', 'com.whatsapp', 2, 90, 180, 1234, 1)"
        )
    }

    @After
    fun tearDown() {
        helper.close()
    }

    @Test
    fun migration3To4_addsWaypointsTable_andKeepsExistingData() {
        val db = helper.writableDatabase
        AppDatabase.MIGRATION_3_4.migrate(db)

        // Pre-existing row survives untouched.
        db.query("SELECT packageName, updatedAt FROM notification_rules").use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals("com.whatsapp", c.getString(0))
            assertEquals(1234L, c.getLong(1))
        }

        // The new waypoints table exists and is usable.
        db.execSQL(
            "INSERT INTO waypoints (watchMac, kind, lat, lon, accuracyM, capturedAt, note) " +
                "VALUES ('AA:00:00:00:00:01', 'MINOR', 48.85, 2.29, 5.0, 1000, NULL)"
        )
        db.query("SELECT kind, lat, lon, capturedAt FROM waypoints").use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals("MINOR", c.getString(0))
            assertEquals(48.85, c.getDouble(1), 0.0001)
            assertEquals(2.29, c.getDouble(2), 0.0001)
            assertEquals(1000L, c.getLong(3))
        }
    }
}
