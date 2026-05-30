package qhybrid.android.sync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import qhybrid.android.buttons.ButtonActions
import qhybrid.android.buttons.ButtonActionsJson
import qhybrid.android.buttons.ButtonDialModes
import qhybrid.android.buttons.ButtonModes
import qhybrid.android.buttons.ButtonSlots
import qhybrid.android.db.ButtonMappingEntity
import qhybrid.android.db.NotificationRuleEntity
import qhybrid.android.db.WatchAlarmEntity
import qhybrid.android.db.WatchEntity
import qhybrid.protocol.ButtonConfigBuilder
import qhybrid.protocol.buttonconfig.ConfigPayload
import qhybrid.protocol.model.NotificationFilterEntry
import qhybrid.protocol.requests.fossil.alarm.AlarmCompiler
import qhybrid.protocol.requests.fossil.alarm.AlarmSlot

/**
 * WP14 sub-part 1 — headless tests for the pure [SyncOrchestrator] against a [FakeUploader].
 *
 * Asserts the right compiled payloads + the defined upload order + the guards (skip-empty,
 * 32-slot guard, no-watch tolerance) WITHOUT any Android service or BLE. The compiled bytes are
 * cross-checked against the SAME golden-tested protocol compilers (WP5/6/7) so the orchestrator
 * is proven to invent no wire bytes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SyncOrchestratorTest {

    /** Records every uploader call (payload + order). */
    private class FakeUploader(
        private val alarmsWired: Boolean = true,
        private val filterWired: Boolean = true,
        private val buttonsWired: Boolean = true,
        private val settingsWired: Boolean = true,
    ) : Uploader {
        val order = mutableListOf<SyncSection>()
        var alarmBytes: ByteArray? = null
        var filterEntries: List<NotificationFilterEntry>? = null
        var buttonBytes: ByteArray? = null
        var vibration: Int? = null
        var nudge: Sextet? = null
        var tzOffset: Int? = null

        data class Sextet(
            val fh: Int, val fm: Int, val th: Int, val tm: Int, val mins: Int, val on: Boolean,
        )

        override fun uploadAlarms(alarmFile: ByteArray): Boolean {
            order.add(SyncSection.ALARMS); alarmBytes = alarmFile; return alarmsWired
        }

        override fun uploadNotificationFilter(entries: List<NotificationFilterEntry>): Boolean {
            order.add(SyncSection.NOTIFICATION_FILTER); filterEntries = entries; return filterWired
        }

        override fun uploadButtons(buttonConfigFile: ByteArray): Boolean {
            order.add(SyncSection.BUTTONS); buttonBytes = buttonConfigFile; return buttonsWired
        }

        override fun applyVibrationStrength(strength: Int): Boolean {
            order.add(SyncSection.VIBRATION); vibration = strength; return settingsWired
        }

        override fun applyInactivityNudge(
            fromHour: Int, fromMinute: Int, toHour: Int, toMinute: Int,
            inactiveMinutes: Int, enabled: Boolean,
        ): Boolean {
            order.add(SyncSection.NUDGE)
            nudge = Sextet(fromHour, fromMinute, toHour, toMinute, inactiveMinutes, enabled)
            return settingsWired
        }

        override fun applySecondTimezone(offsetMinutes: Int): Boolean {
            order.add(SyncSection.SECOND_TIMEZONE); tzOffset = offsetMinutes; return settingsWired
        }
    }

    private fun watch(mac: String = "AA:00:00:00:00:01", vibe: Int = 50) =
        WatchEntity(
            macAddress = mac, name = "W", model = null, firmwareVersion = null,
            batteryLevel = 0, isActive = true, vibrationStrength = vibe,
        )

    private fun alarm(slot: Int, hour: Int = 7, minute: Int = 0, days: Int = 0x3E,
                      enabled: Boolean = true, repeating: Boolean = true) =
        WatchAlarmEntity("AA:00:00:00:00:01", slot, hour, minute, enabled, days, repeating, null)

    private fun rule(pkg: String, vibe: Int = 2, hourDeg: Int = 90, minDeg: Int = 180) =
        NotificationRuleEntity("AA:00:00:00:00:01", pkg, vibe, hourDeg, minDeg)

    private fun button(id: Int, mode: String, actions: List<String>) =
        ButtonMappingEntity("AA:00:00:00:00:01", id, mode, ButtonActionsJson.encode(actions))

    // ---- no watch -------------------------------------------------------------

    @Test
    fun noActiveWatchUploadsNothing() {
        val up = FakeUploader()
        val result = SyncOrchestrator.sync(SyncInput(watch = null), up)
        assertTrue(result.isNoWatch)
        assertTrue(up.order.isEmpty())
        assertFalse(result.anyPerformed)
    }

    // ---- empty / partial ------------------------------------------------------

    @Test
    fun emptyConfigSkipsEverythingExceptSettings() {
        val up = FakeUploader()
        // No alarms/rules/buttons; settings all null → nothing uploaded.
        val result = SyncOrchestrator.sync(
            SyncInput(watch = watch(), settings = SyncSettings(vibrationStrength = null)), up,
        )
        assertTrue(up.order.isEmpty())
        assertTrue(result.performed.isEmpty())
        assertTrue(SyncSection.ALARMS in result.skipped)
        assertTrue(SyncSection.NOTIFICATION_FILTER in result.skipped)
        assertTrue(SyncSection.BUTTONS in result.skipped)
    }

    @Test
    fun allDisabledAlarmsSkipped() {
        val up = FakeUploader()
        val result = SyncOrchestrator.sync(
            SyncInput(
                watch = watch(),
                alarms = listOf(alarm(0, enabled = false), alarm(1, enabled = false)),
                settings = SyncSettings(),
            ),
            up,
        )
        // Compiles to 0 bytes → skipped, not pushed empty.
        assertFalse(SyncSection.ALARMS in up.order)
        assertTrue(SyncSection.ALARMS in result.skipped)
    }

    // ---- order ----------------------------------------------------------------

    @Test
    fun fullSyncUploadsInDefinedOrder() {
        val up = FakeUploader()
        SyncOrchestrator.sync(
            SyncInput(
                watch = watch(vibe = 70),
                alarms = listOf(alarm(0)),
                rules = listOf(rule("com.whatsapp")),
                buttons = listOf(button(ButtonSlots.TOP, ButtonModes.SINGLE_ACTION,
                    listOf(ButtonActions.RING_PHONE))),
                settings = SyncSettings(
                    vibrationStrength = 70,
                    nudgeEnabled = true, nudgeMinutes = 30,
                    secondTimezoneOffsetMinutes = -480,
                ),
            ),
            up,
        )
        assertEquals(
            listOf(
                SyncSection.ALARMS,
                SyncSection.NOTIFICATION_FILTER,
                SyncSection.BUTTONS,
                SyncSection.VIBRATION,
                SyncSection.NUDGE,
                SyncSection.SECOND_TIMEZONE,
            ),
            up.order,
        )
    }

    // ---- WP-SYNCFIX: targeted sync (only the requested section) --------------

    private fun fullInput() = SyncInput(
        watch = watch(vibe = 70),
        alarms = listOf(alarm(0)),
        rules = listOf(rule("com.whatsapp")),
        buttons = listOf(button(ButtonSlots.TOP, ButtonModes.SINGLE_ACTION,
            listOf(ButtonActions.RING_PHONE))),
        settings = SyncSettings(
            vibrationStrength = 70,
            nudgeEnabled = true, nudgeMinutes = 30,
            secondTimezoneOffsetMinutes = -480,
        ),
    )

    @Test
    fun buttonsOnly_uploadsOnlyButtons_notAlarmsOrSettings() {
        // The reported bug: a Buttons-screen save must NOT also push alarms / vibration / etc.
        val up = FakeUploader()
        val result = SyncOrchestrator.sync(fullInput(), up, SyncSection.BUTTONS_ONLY)
        assertEquals(listOf(SyncSection.BUTTONS), up.order)
        assertEquals(listOf(SyncSection.BUTTONS), result.performed)
        // Untouched sections are neither performed nor skipped — they're not part of this pass.
        assertFalse(SyncSection.ALARMS in result.skipped)
        assertFalse(SyncSection.VIBRATION in result.performed)
        assertNull(up.alarmBytes)
        assertNull(up.vibration)
    }

    @Test
    fun alarmsOnly_uploadsOnlyAlarms() {
        val up = FakeUploader()
        SyncOrchestrator.sync(fullInput(), up, SyncSection.ALARMS_ONLY)
        assertEquals(listOf(SyncSection.ALARMS), up.order)
        assertNull(up.buttonBytes)
        assertNull(up.vibration)
    }

    @Test
    fun settingsOnly_uploadsOnlyTheThreeSettings_noFiles() {
        val up = FakeUploader()
        SyncOrchestrator.sync(fullInput(), up, SyncSection.SETTINGS_ONLY)
        assertEquals(
            listOf(SyncSection.VIBRATION, SyncSection.NUDGE, SyncSection.SECOND_TIMEZONE),
            up.order,
        )
        assertNull(up.alarmBytes)
        assertNull(up.buttonBytes)
    }

    @Test
    fun defaultSync_isFullReconcile() {
        // No sections arg → ALL (back-compat with connect / periodic reconcile).
        val up = FakeUploader()
        SyncOrchestrator.sync(fullInput(), up)
        assertEquals(6, up.order.size)
        assertTrue(SyncSection.BUTTONS in up.order)
        assertTrue(SyncSection.ALARMS in up.order)
    }

    // ---- payloads match the golden compilers ---------------------------------

    @Test
    fun alarmBytesMatchAlarmCompiler() {
        val up = FakeUploader()
        val a0 = alarm(0, hour = 7, minute = 30, days = 0x3E)
        val a16 = alarm(16, hour = 10, minute = 15, days = 0x20) // calendar
        SyncOrchestrator.sync(
            SyncInput(watch = watch(), alarms = listOf(a0), calendarAlarms = listOf(a16),
                settings = SyncSettings()),
            up,
        )
        val expected = AlarmCompiler.compile(
            listOf(AlarmSlot(0, 7, 30, 0x3E, true, true, null)),
            listOf(AlarmSlot(16, 10, 15, 0x20, true, true, null)),
        )
        assertArrayEquals(expected, up.alarmBytes)
    }

    @Test
    fun filterEntriesMatchRows() {
        val up = FakeUploader()
        SyncOrchestrator.sync(
            SyncInput(watch = watch(),
                rules = listOf(rule("com.whatsapp", vibe = 2, hourDeg = 90, minDeg = 180)),
                settings = SyncSettings()),
            up,
        )
        val e = up.filterEntries!!.single()
        assertEquals("com.whatsapp", e.packageName)
        assertEquals(2.toByte(), e.vibe)
        assertEquals(90.toShort(), e.hourDeg)
        assertEquals(180.toShort(), e.minDeg)
    }

    @Test
    fun buttonBytesMatchButtonCompilerForSingleAction() {
        val up = FakeUploader()
        SyncOrchestrator.sync(
            SyncInput(watch = watch(),
                buttons = listOf(button(ButtonSlots.TOP, ButtonModes.SINGLE_ACTION,
                    listOf(ButtonActions.STOPWATCH))),
                settings = SyncSettings()),
            up,
        )
        val expected = ButtonConfigBuilder.build(
            arrayOf(ButtonConfigBuilder.entryFrom(ConfigPayload.STOPWATCH)),
            emptyArray(), emptyArray(),
        )
        assertArrayEquals(expected, up.buttonBytes)
    }

    @Test
    fun multiFunctionActionCompilesToTheMultiWirePayload() {
        // WP-BTN: MULTI_FUNCTION (and the legacy MUSIC_CONTROL alias) resolve to the SAME wire
        // payload (FORWARD_TO_PHONE_MULTI == MUSIC_CONTROL bytes) via ButtonActions.payloadName.
        val up = FakeUploader()
        SyncOrchestrator.sync(
            SyncInput(watch = watch(),
                buttons = listOf(button(ButtonSlots.TOP, ButtonModes.SINGLE_ACTION,
                    listOf(ButtonActions.MULTI_FUNCTION))),
                settings = SyncSettings()),
            up,
        )
        val expected = ButtonConfigBuilder.build(
            arrayOf(ButtonConfigBuilder.entryFrom(ConfigPayload.FORWARD_TO_PHONE_MULTI)),
            emptyArray(), emptyArray(),
        )
        assertArrayEquals(expected, up.buttonBytes)
        // The legacy MUSIC_CONTROL id must compile to byte-identical output.
        val up2 = FakeUploader()
        SyncOrchestrator.sync(
            SyncInput(watch = watch(),
                buttons = listOf(button(ButtonSlots.TOP, ButtonModes.SINGLE_ACTION,
                    listOf(ButtonActions.MUSIC_CONTROL))),
                settings = SyncSettings()),
            up2,
        )
        assertArrayEquals(expected, up2.buttonBytes)
    }

    @Test
    fun customToggleMapsDialModesToSequencedEntries() {
        val up = FakeUploader()
        // Tapped non-canonically; the cycle compiles in CANONICAL order
        // (ALERT, TIMEZONE_2, ALARM, DATE, TWENTY_FOUR_HOUR) → here: TIMEZONE_2, ALARM, DATE.
        SyncOrchestrator.sync(
            SyncInput(watch = watch(),
                buttons = listOf(button(ButtonSlots.MIDDLE, ButtonModes.CUSTOM_TOGGLE,
                    listOf(ButtonDialModes.TIMEZONE_2, ButtonDialModes.DATE, ButtonDialModes.ALARM))),
                settings = SyncSettings()),
            up,
        )
        val expected = ButtonConfigBuilder.build(
            emptyArray(),
            arrayOf(
                ButtonConfigBuilder.entryFrom(ConfigPayload.SECOND_TIMEZONE),
                ButtonConfigBuilder.ALARM_SEQUENCED_ENTRY,
                ButtonConfigBuilder.DATE_TOGGLE_ENTRY,
            ),
            emptyArray(),
        )
        assertArrayEquals(expected, up.buttonBytes)
    }

    @Test
    fun legacyMultiIdSingleActionRowCollapsesToOneEntry() {
        // WP-BTN defensive collapse: a legacy DB row that stored MANY ids for a SINGLE_ACTION
        // button must compile to AT MOST ONE entry (the first), NOT a silent multi-entry cycle.
        val up = FakeUploader()
        SyncOrchestrator.sync(
            SyncInput(watch = watch(),
                buttons = listOf(button(ButtonSlots.TOP, ButtonModes.SINGLE_ACTION,
                    listOf(ButtonActions.STOPWATCH, ButtonActions.DATE, ButtonActions.RING_PHONE))),
                settings = SyncSettings()),
            up,
        )
        // Identical bytes to a clean single-action STOPWATCH button via the WP7 golden compiler.
        val expected = ButtonConfigBuilder.build(
            arrayOf(ButtonConfigBuilder.entryFrom(ConfigPayload.STOPWATCH)),
            emptyArray(), emptyArray(),
        )
        assertArrayEquals(expected, up.buttonBytes)
    }

    @Test
    fun legacyMusicMultimodeRowCompilesAsSingleActionFirstId() {
        // WP-BTN: a legacy MUSIC_MULTIMODE modeType normalizes to SINGLE_ACTION and the multi-id
        // list collapses to its first id — one entry, matching the golden compiler.
        val up = FakeUploader()
        SyncOrchestrator.sync(
            SyncInput(watch = watch(),
                buttons = listOf(button(ButtonSlots.BOTTOM, ButtonModes.LEGACY_MUSIC_MULTIMODE,
                    listOf(ButtonActions.MULTI_FUNCTION, ButtonActions.STOPWATCH))),
                settings = SyncSettings()),
            up,
        )
        val expected = ButtonConfigBuilder.build(
            emptyArray(), emptyArray(),
            arrayOf(ButtonConfigBuilder.entryFrom(ConfigPayload.FORWARD_TO_PHONE_MULTI)),
        )
        assertArrayEquals(expected, up.buttonBytes)
    }

    @Test
    fun customToggleKeepsTheWholeCycleAfterCollapseGuard() {
        // CUSTOM_TOGGLE is the genuine multi-id cycle — the defensive collapse must NOT shorten it,
        // and the cycle is emitted in CANONICAL order (TIMEZONE_2, ALARM, DATE here).
        val up = FakeUploader()
        SyncOrchestrator.sync(
            SyncInput(watch = watch(),
                buttons = listOf(button(ButtonSlots.MIDDLE, ButtonModes.CUSTOM_TOGGLE,
                    listOf(ButtonDialModes.TIMEZONE_2, ButtonDialModes.DATE, ButtonDialModes.ALARM))),
                settings = SyncSettings()),
            up,
        )
        val expected = ButtonConfigBuilder.build(
            emptyArray(),
            arrayOf(
                ButtonConfigBuilder.entryFrom(ConfigPayload.SECOND_TIMEZONE),
                ButtonConfigBuilder.ALARM_SEQUENCED_ENTRY,
                ButtonConfigBuilder.DATE_TOGGLE_ENTRY,
            ),
            emptyArray(),
        )
        assertArrayEquals(expected, up.buttonBytes)
    }

    @Test
    fun buttonsWithOnlyUnknownActionsSkipped() {
        val up = FakeUploader()
        val result = SyncOrchestrator.sync(
            SyncInput(watch = watch(),
                buttons = listOf(button(ButtonSlots.TOP, ButtonModes.SINGLE_ACTION,
                    listOf("NOT_A_REAL_ACTION"))),
                settings = SyncSettings()),
            up,
        )
        assertFalse(SyncSection.BUTTONS in up.order)
        assertTrue(SyncSection.BUTTONS in result.skipped)
    }

    // ---- settings -------------------------------------------------------------

    @Test
    fun settingsForwardedWithCorrectValues() {
        val up = FakeUploader()
        SyncOrchestrator.sync(
            SyncInput(watch = watch(vibe = 80),
                settings = SyncSettings(
                    vibrationStrength = 80,
                    nudgeEnabled = true, nudgeMinutes = 45,
                    nudgeFromHour = 9, nudgeFromMinute = 0, nudgeToHour = 17, nudgeToMinute = 30,
                    secondTimezoneOffsetMinutes = 330,
                )),
            up,
        )
        assertEquals(80, up.vibration)
        assertEquals(FakeUploader.Sextet(9, 0, 17, 30, 45, true), up.nudge)
        assertEquals(330, up.tzOffset)
    }

    @Test
    fun nullSettingsNotApplied() {
        val up = FakeUploader()
        SyncOrchestrator.sync(
            SyncInput(watch = watch(),
                settings = SyncSettings(
                    vibrationStrength = null, nudgeMinutes = null, secondTimezoneOffsetMinutes = null,
                )),
            up,
        )
        assertFalse(SyncSection.VIBRATION in up.order)
        assertFalse(SyncSection.NUDGE in up.order)
        assertFalse(SyncSection.SECOND_TIMEZONE in up.order)
    }

    // ---- 32-slot guard --------------------------------------------------------

    @Test
    fun tooManyAlarmsRecordedAsErrorAndOthersStillRun() {
        val up = FakeUploader()
        // 17 standard alarms is fine for AlarmCompiler ranges? No — standard slots are 0..15.
        // Use 33 across the legal ranges to trip the 32-slot guard: 16 standard + 17 calendar.
        val standard = (0..15).map { alarm(it) }
        val calendar = (16..32).map { alarm(it) } // slot 32 is out of calendar range → throws
        val result = SyncOrchestrator.sync(
            SyncInput(watch = watch(), alarms = standard, calendarAlarms = calendar,
                rules = listOf(rule("com.whatsapp")), settings = SyncSettings()),
            up,
        )
        // Alarms errored, but the filter still uploaded.
        assertTrue(result.errors.any { it.section == SyncSection.ALARMS })
        assertTrue(SyncSection.NOTIFICATION_FILTER in up.order)
    }

    @Test
    fun resultReportsPerformedVsSkipped() {
        val up = FakeUploader(filterWired = false)
        val result = SyncOrchestrator.sync(
            SyncInput(watch = watch(),
                alarms = listOf(alarm(0)),
                rules = listOf(rule("com.whatsapp")),
                settings = SyncSettings(vibrationStrength = 50)),
            up,
        )
        assertTrue(SyncSection.ALARMS in result.performed)
        assertTrue(SyncSection.VIBRATION in result.performed)
        // filter uploader returned false (not wired) → classified as skipped.
        assertTrue(SyncSection.NOTIFICATION_FILTER in result.skipped)
    }
}
