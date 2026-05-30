package qhybrid.android.db

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import qhybrid.android.onboard.ConfigToSeed
import qhybrid.protocol.FossilQAdapter

/**
 * WP-ONBOARD (sub-part 3) — the new-watch persistence path must store the watch's READ-BACK
 * readable settings (vibration / step goal), NOT the constant defaults (50 / 10000). This exercises
 * the seam the service uses on the provisioning success path: [ConfigToSeed.seed] → a seeded
 * [WatchEntity] → [WatchRepository.registerSeededWatch].
 *
 * The BLE read itself (`controller.readConfig()`) is on-device; here we feed the same kind of
 * [FossilQAdapter.ConfigEntry] list the watch returns and prove the persisted row carries the real
 * values, and that an empty read falls back to constants (best-effort onboarding).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RegisterSeededWatchTest : DbTestBase() {

    private val mac = "AA:BB:CC:DD:EE:FF"

    private fun entry(id: Int, name: String, vararg raw: Int) =
        FossilQAdapter.ConfigEntry(id, name, ByteArray(raw.size) { raw[it].toByte() }, "<fmt>")

    @Test
    fun seedFromWatchConfig_persistsRealReadableValues() = runTest {
        // The watch reports vibration 80% and a 7500-step goal (NOT the 50 / 10000 defaults).
        val entries = listOf(
            entry(0x0A, "VIBE_STRENGTH", 80),
            entry(0x03, "DAILY_STEP_GOAL", 0x4C, 0x1D, 0x00, 0x00), // 7500 LE
        )
        val seededSettings = ConfigToSeed.seed(
            entries,
            ConfigToSeed.DeviceInfo(model = "HW2.0", firmwareVersion = "1.2.3", batteryLevel = 88),
        )
        val seeded = WatchEntity(
            macAddress = mac,
            name = mac,
            model = seededSettings.deviceInfo.model,
            firmwareVersion = seededSettings.deviceInfo.firmwareVersion,
            batteryLevel = seededSettings.deviceInfo.batteryLevel ?: 0,
            vibrationStrength = seededSettings.vibrationStrength,
            stepGoal = seededSettings.stepGoal,
        )

        repo.registerSeededWatch(seeded)

        val row = watchDao.getByMac(mac)!!
        assertEquals("re-added watch must show the watch's ACTUAL vibration, not 50", 80, row.vibrationStrength)
        assertEquals("re-added watch must show the watch's ACTUAL step goal, not 10000", 7500, row.stepGoal)
        assertEquals("HW2.0", row.model)
        assertEquals("1.2.3", row.firmwareVersion)
        assertEquals(88, row.batteryLevel)
        assertTrue("seeded watch is the active watch", row.isActive)
    }

    @Test
    fun emptyConfigRead_fallsBackToConstants() = runTest {
        // An empty/failed read-back must still add the watch, with the hardcoded constants.
        val seededSettings = ConfigToSeed.seed(emptyList())
        val seeded = WatchEntity(
            macAddress = mac,
            name = mac,
            model = null,
            firmwareVersion = null,
            batteryLevel = 0,
            vibrationStrength = seededSettings.vibrationStrength,
            stepGoal = seededSettings.stepGoal,
        )

        repo.registerSeededWatch(seeded)

        val row = watchDao.getByMac(mac)!!
        assertEquals(50, row.vibrationStrength)
        assertEquals(10000, row.stepGoal)
        assertTrue(row.isActive)
    }

    @Test
    fun registerSeededWatch_normalizesMacAndOverwritesStaleRow() = runTest {
        // A stale row with default vibration exists; re-provisioning seeds the real value.
        watchDao.upsert(watch(mac.uppercase()).copy(vibrationStrength = 50))

        val seeded = WatchEntity(
            macAddress = mac.lowercase(), // lower-case input must be normalized to match the row PK
            name = mac,
            model = null,
            firmwareVersion = null,
            batteryLevel = 0,
            vibrationStrength = 90,
            stepGoal = 5000,
        )
        repo.registerSeededWatch(seeded)

        assertEquals(1, watchDao.getAll().size)
        val row = watchDao.getByMac(mac.uppercase())!!
        assertEquals(90, row.vibrationStrength)
        assertEquals(5000, row.stepGoal)
    }
}
