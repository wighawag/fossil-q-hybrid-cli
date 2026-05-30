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
 * Proves [AppDatabase.MIGRATION_2_3] is additive: it adds `notification_rules.isEnabled` with
 * `DEFAULT 1` (so pre-existing rules stay ENABLED) and DOES NOT wipe existing rows. Like
 * [Migration1To2Test], we hand-build a minimal v2 schema (we keep `exportSchema = false`), seed a
 * rule, run the migration, and assert the row survives defaulting to enabled.
 */
@RunWith(RobolectricTestRunner::class)
class Migration2To3Test {

    private lateinit var helper: SupportSQLiteOpenHelper

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // A throwaway v2 DB (post MIGRATION_1_2 shape: includes updatedAt / …SyncedAt columns).
        val callback = object : SupportSQLiteOpenHelper.Callback(2) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE notification_rules (watchMac TEXT NOT NULL, packageName TEXT NOT NULL, " +
                        "vibePattern INTEGER NOT NULL, hourHandDegrees INTEGER NOT NULL, " +
                        "minuteHandDegrees INTEGER NOT NULL, " +
                        "updatedAt INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(watchMac, packageName))"
                )
            }

            override fun onUpgrade(
                db: SupportSQLiteDatabase,
                oldVersion: Int,
                newVersion: Int,
            ) = Unit
        }
        helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(null)
                .callback(callback)
                .build()
        )
        helper.writableDatabase.execSQL(
            "INSERT INTO notification_rules (watchMac, packageName, vibePattern, hourHandDegrees, " +
                "minuteHandDegrees, updatedAt) VALUES ('AA:00:00:00:00:01', 'com.whatsapp', 2, 90, 180, 1234)"
        )
    }

    @After
    fun tearDown() {
        helper.close()
    }

    @Test
    fun migration2To3_addsIsEnabledDefaultingTrue_andKeepsExistingData() {
        val db = helper.writableDatabase
        AppDatabase.MIGRATION_2_3.migrate(db)

        db.query(
            "SELECT packageName, updatedAt, isEnabled FROM notification_rules " +
                "WHERE watchMac = 'AA:00:00:00:00:01' AND packageName = 'com.whatsapp'"
        ).use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals("com.whatsapp", c.getString(0))
            assertEquals(1234L, c.getLong(1))
            // Pre-existing rules default to ENABLED (1).
            assertEquals(1, c.getInt(2))
        }
    }
}
