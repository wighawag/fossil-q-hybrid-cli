package qhybrid.android.settings.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import qhybrid.android.db.ButtonMappingEntity
import qhybrid.android.db.NotificationRuleEntity
import qhybrid.android.db.WatchAlarmEntity
import qhybrid.android.settings.AppSettings
import qhybrid.android.settings.SettingsVocabulary

/**
 * BACKUP/RESTORE — the pure codec must (1) round-trip every field (encode→decode is identity for a
 * fully-populated payload), (2) survive an app uninstall by being self-contained JSON, and (3) be
 * tolerant: blank / garbage / foreign JSON decodes to null (so the caller leaves state untouched),
 * not a crash. Robolectric only for Android's bundled `org.json`.
 */
@RunWith(RobolectricTestRunner::class)
class BackupCodecTest {

    // ---- app-wide settings ---------------------------------------------------

    @Test
    fun appSettings_roundTripsAllFields() {
        val original = AppSettings(
            nudgeEnabled = true,
            nudgeMinutes = 45,
            secondTimezoneOffsetMinutes = 330,
            preferredMusicApp = "com.bandcamp",
            calendarAlarmOffsetMinutes = 10,
            multiFunctionRole = SettingsVocabulary.MULTI_FUNCTION_ROLE_TRACKER,
            multiFunctionRotation = listOf(
                SettingsVocabulary.MODE_TRACKER,
                SettingsVocabulary.MODE_TIMER,
                SettingsVocabulary.MODE_MUSIC_PHONE,
            ),
            multiFunctionActiveIndex = 2,
            multiFunctionSwitchBuzz = mapOf(
                SettingsVocabulary.MODE_TRACKER to 1,
                SettingsVocabulary.MODE_TIMER to 5,
            ),
            reservedBuzzMoveHands = true,
            lyrionServerHost = "192.168.1.50",
            lyrionServerPort = 9000,
            lyrionPlayerId = "aa:bb:cc:dd:ee:ff",
            lyrionPlayerName = "Kitchen",
            lyrionEmptyQueueFallback = "RANDOM",
            lyrionFavoriteId = "fav123",
            ringDurationSeconds = 90,
            navCueEnabled = true,
            navCueSoonMeters = 50,
            navCueNowMeters = 12,
            navCueBackend = "AIDL_LEGACY",
        )

        val decoded = AppSettingsBackup.fromBytes(AppSettingsBackup.toBytes(original))
        assertEquals(original, decoded)
    }

    @Test
    fun appSettings_decodeRejectsBlankGarbageAndForeign() {
        assertNull(AppSettingsBackup.decode(null))
        assertNull(AppSettingsBackup.decode(""))
        assertNull(AppSettingsBackup.decode("   "))
        assertNull(AppSettingsBackup.decode("not json"))
        // Valid JSON but missing our kind marker → rejected (won't masquerade as settings).
        assertNull(AppSettingsBackup.decode("""{"foo":1}"""))
        // A watch-config blob is NOT an app-settings blob.
        assertNull(AppSettingsBackup.decode("""{"kind":"fossilq-watch-config"}"""))
    }

    @Test
    fun appSettings_absentFieldsFallBackToDefaults() {
        // A minimal valid blob (just the marker) decodes to defaults, not a crash.
        val decoded = AppSettingsBackup.decode("""{"kind":"fossilq-app-settings","version":1}""")
        assertEquals(AppSettings(), decoded)
    }

    // ---- per-watch config ----------------------------------------------------

    private fun sampleConfig(mac: String) = WatchConfig(
        macAddress = mac,
        name = "My Commuter",
        model = "Q Commuter",
        firmwareVersion = "HW0.0.2.9",
        stepGoal = 12000,
        vibrationStrength = 70,
        alarms = listOf(
            WatchAlarmEntity(mac, slotId = 0, hour = 7, minute = 30, isEnabled = true,
                daysMask = 0b0111110, isRepeating = true, label = "Wake"),
            WatchAlarmEntity(mac, slotId = 1, hour = 22, minute = 0, isEnabled = false,
                daysMask = 0, isRepeating = false, label = null),
        ),
        rules = listOf(
            NotificationRuleEntity(mac, packageName = "com.whatsapp", vibePattern = 2,
                hourHandDegrees = 90, minuteHandDegrees = 180, isEnabled = true),
            NotificationRuleEntity(mac, packageName = "com.slack", vibePattern = 5,
                hourHandDegrees = 0, minuteHandDegrees = 0, isEnabled = false),
        ),
        buttons = listOf(
            ButtonMappingEntity(mac, buttonId = 0x10, modeType = "SINGLE_ACTION",
                actionsJson = """[{"action":"STOPWATCH"}]"""),
            ButtonMappingEntity(mac, buttonId = 0x20, modeType = "CUSTOM_TOGGLE",
                actionsJson = """[{"action":"TIMEZONE_2"},{"action":"DATE"}]"""),
        ),
    )

    @Test
    fun watchConfig_roundTripsAllFieldsIgnoringUpdatedAt() {
        val original = sampleConfig("AA:BB:CC:DD:EE:FF")
        val decoded = WatchConfigBackup.fromBytes(WatchConfigBackup.toBytes(original))!!
        assertEquals(original.macAddress, decoded.macAddress)
        assertEquals(original.name, decoded.name)
        assertEquals(original.model, decoded.model)
        assertEquals(original.firmwareVersion, decoded.firmwareVersion)
        assertEquals(original.stepGoal, decoded.stepGoal)
        assertEquals(original.vibrationStrength, decoded.vibrationStrength)
        // Child rows compare ignoring updatedAt (a sync-status field not part of the backup).
        assertEquals(original.alarms.map { it.copy(updatedAt = 0) }, decoded.alarms.map { it.copy(updatedAt = 0) })
        assertEquals(original.rules.map { it.copy(updatedAt = 0) }, decoded.rules.map { it.copy(updatedAt = 0) })
        assertEquals(original.buttons.map { it.copy(updatedAt = 0) }, decoded.buttons.map { it.copy(updatedAt = 0) })
    }

    @Test
    fun watchConfig_decodeRejectsBlankGarbageForeign() {
        assertNull(WatchConfigBackup.decode(null))
        assertNull(WatchConfigBackup.decode(""))
        assertNull(WatchConfigBackup.decode("nonsense"))
        assertNull(WatchConfigBackup.decode("""{"kind":"fossilq-app-settings"}"""))
    }

    @Test
    fun watchConfig_childRowsAreRekeyedToTheDecodedMac() {
        // The decoded child rows carry the backup's macAddress (the repo re-keys them onto the
        // TARGET watch on import — this just proves the codec keeps them consistent, not foreign).
        val decoded = WatchConfigBackup.fromBytes(WatchConfigBackup.toBytes(sampleConfig("11:22:33:44:55:66")))!!
        assertTrue(decoded.alarms.all { it.watchMac == "11:22:33:44:55:66" })
        assertTrue(decoded.rules.all { it.watchMac == "11:22:33:44:55:66" })
        assertTrue(decoded.buttons.all { it.watchMac == "11:22:33:44:55:66" })
    }

    @Test
    fun watchConfig_skipsMalformedChildRowsButKeepsValidOnes() {
        // A rule with a blank package + a button with no id are dropped; the valid rows survive.
        val json = """
            {"kind":"fossilq-watch-config","version":1,"macAddress":"AA","name":"x",
             "stepGoal":10000,"vibrationStrength":50,
             "alarms":[{"slotId":0,"hour":6,"minute":0,"isEnabled":true,"daysMask":0,"isRepeating":false}],
             "rules":[{"packageName":"","vibePattern":1},{"packageName":"com.ok","vibePattern":3}],
             "buttons":[{"modeType":"SINGLE_ACTION"},{"buttonId":16,"modeType":"SINGLE_ACTION","actionsJson":"[{\"action\":\"DATE\"}]"}]}
        """.trimIndent()
        val decoded = WatchConfigBackup.decode(json)!!
        assertEquals(1, decoded.alarms.size)
        assertEquals(1, decoded.rules.size)
        assertEquals("com.ok", decoded.rules[0].packageName)
        assertEquals(1, decoded.buttons.size)
        assertEquals(0x10, decoded.buttons[0].buttonId)
        assertFalse(decoded.rules[0].packageName.isBlank())
    }
}
