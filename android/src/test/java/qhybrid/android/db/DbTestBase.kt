package qhybrid.android.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Before

/**
 * WP4 — shared in-memory Room setup for headless DAO/transfer tests.
 *
 * Uses Robolectric's application context with [Room.inMemoryDatabaseBuilder] so the whole
 * persistence layer (real SQLite, FK enforcement, KSP-generated DAOs) is exercised on the
 * JVM with no emulator. Main-thread queries are allowed purely to keep tests synchronous;
 * production code uses suspend functions off the main thread.
 */
abstract class DbTestBase {

    protected lateinit var db: AppDatabase
    protected lateinit var repo: WatchRepository

    protected val watchDao get() = db.watchDao()
    protected val alarmDao get() = db.watchAlarmDao()
    protected val ruleDao get() = db.notificationRuleDao()
    protected val buttonDao get() = db.buttonMappingDao()

    @Before
    fun setUpDb() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        repo = WatchRepository(db)
    }

    @After
    fun tearDownDb() {
        db.close()
    }

    // ---- fixtures ------------------------------------------------------------

    protected fun watch(mac: String, name: String = "Watch $mac", active: Boolean = false) =
        WatchEntity(
            macAddress = mac,
            name = name,
            model = "HW.0.0",
            firmwareVersion = "HW0.0.2.9r.v3",
            batteryLevel = 22,
            isActive = active,
        )

    protected fun alarm(mac: String, slot: Int) =
        WatchAlarmEntity(
            watchMac = mac,
            slotId = slot,
            hour = 7,
            minute = 30 + slot,
            isEnabled = true,
            daysMask = 0b0111110, // Mon-Fri
            isRepeating = true,
            label = "Alarm $slot",
        )

    protected fun rule(mac: String, pkg: String) =
        NotificationRuleEntity(
            watchMac = mac,
            packageName = pkg,
            vibePattern = 2,
            hourHandDegrees = 90,
            minuteHandDegrees = 180,
        )

    protected fun button(mac: String, buttonId: Int) =
        ButtonMappingEntity(
            watchMac = mac,
            buttonId = buttonId,
            modeType = "SINGLE_ACTION",
            actionsJson = """[{"action":"MUSIC_PLAY"}]""",
        )
}
