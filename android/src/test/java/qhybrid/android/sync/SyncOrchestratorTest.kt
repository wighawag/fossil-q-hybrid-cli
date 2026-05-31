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
    fun emptyConfigSkipsEverythingExceptNotificationFilter() {
        val up = FakeUploader()
        // No alarms/rules/buttons; settings all null. The notification filter STILL uploads (with no
        // user rules) so the reserved buzz entries reach the watch via the uploader's fold-in
        // (WP-BUZZ-PLAYONLY) — otherwise a fresh watch couldn't buzz. Everything else is skipped.
        val result = SyncOrchestrator.sync(
            SyncInput(watch = watch(), settings = SyncSettings(vibrationStrength = null)), up,
        )
        assertEquals(listOf(SyncSection.NOTIFICATION_FILTER), up.order)
        assertEquals(listOf(SyncSection.NOTIFICATION_FILTER), result.performed)
        // The (empty) user rule list is passed through; the uploader folds reserved entries on top.
        assertEquals(emptyList<NotificationFilterEntry>(), up.filterEntries)
        assertTrue(SyncSection.ALARMS in result.skipped)
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

    // ---- WP-ONBOARD: PROVISION mode (force-write empties to blank the watch) ---

    @Test
    fun provisionMode_emptyWatch_blanksAlarmsAndUploadsFilter() {
        val up = FakeUploader()
        // A brand-new watch with NO user config. PROVISION must still write the (empty) alarm file to
        // clear all 32 slots, and the notification filter (carrying the reserved buzz entries).
        val result = SyncOrchestrator.sync(
            SyncInput(watch = watch(), settings = SyncSettings(vibrationStrength = null)),
            up,
            mode = SyncMode.PROVISION,
        )
        assertTrue(SyncSection.ALARMS in up.order)        // empty alarm file force-written
        assertTrue(SyncSection.NOTIFICATION_FILTER in up.order) // filter (reserved entries) uploaded
        assertTrue(SyncSection.ALARMS in result.performed)
        assertTrue(SyncSection.NOTIFICATION_FILTER in result.performed)
        // WP-DEFAULTS: PROVISION now force-writes the (empty) BUTTON file too, to actively BLANK
        // any pre-existing buttons on the watch (mirrors the alarm force-write).
        assertTrue(SyncSection.BUTTONS in up.order)
        assertTrue(SyncSection.BUTTONS in result.performed)
        assertTrue(up.buttonBytes != null) // an empty button file was written (blank-to-seed)
    }

    // ---- WP-DEFAULTS: PROVISION force-writes buttons ------------------------

    @Test
    fun provisionMode_emptyButtons_forceWritesBlankFile_butReconcileSkips() {
        // PROVISION: empty buttons still write an (empty) button file to clear the watch.
        val upP = FakeUploader()
        val rP = SyncOrchestrator.sync(
            SyncInput(watch = watch(), settings = SyncSettings(vibrationStrength = null)),
            upP, mode = SyncMode.PROVISION,
        )
        assertTrue(SyncSection.BUTTONS in rP.performed)
        assertTrue(upP.buttonBytes != null)

        // RECONCILE: empty buttons are skipped (no write).
        val upR = FakeUploader()
        val rR = SyncOrchestrator.sync(
            SyncInput(watch = watch(), settings = SyncSettings(vibrationStrength = null)),
            upR, mode = SyncMode.RECONCILE,
        )
        assertFalse(SyncSection.BUTTONS in upR.order)
        assertTrue(SyncSection.BUTTONS in rR.skipped)
        assertNull(upR.buttonBytes)
    }

    @Test
    fun provisionMode_factoryButtons_forceWritesCompiledFile() {
        // PROVISION with the three factory buttons → the compiled (non-empty) button file is written.
        val up = FakeUploader()
        val r = SyncOrchestrator.sync(
            SyncInput(
                watch = watch(),
                buttons = listOf(
                    button(ButtonSlots.TOP, ButtonModes.SINGLE_ACTION, listOf(ButtonActions.STOPWATCH)),
                    button(ButtonSlots.MIDDLE, ButtonModes.CUSTOM_TOGGLE,
                        listOf(ButtonDialModes.TIMEZONE_2, ButtonDialModes.ALARM, ButtonDialModes.DATE)),
                    button(ButtonSlots.BOTTOM, ButtonModes.SINGLE_ACTION, listOf(ButtonActions.MUSIC_CONTROL)),
                ),
                settings = SyncSettings(vibrationStrength = null),
            ),
            up, mode = SyncMode.PROVISION,
        )
        assertTrue(SyncSection.BUTTONS in r.performed)
        val expected = ButtonConfigBuilder.build(
            arrayOf(ButtonConfigBuilder.entryFrom(ConfigPayload.STOPWATCH)),
            arrayOf(
                ButtonConfigBuilder.entryFrom(ConfigPayload.SECOND_TIMEZONE),
                ButtonConfigBuilder.ALARM_SEQUENCED_ENTRY,
                ButtonConfigBuilder.DATE_TOGGLE_ENTRY,
            ),
            arrayOf(ButtonConfigBuilder.entryFrom(ConfigPayload.FORWARD_TO_PHONE_MULTI)),
        )
        assertArrayEquals(expected, up.buttonBytes)
    }

    @Test
    fun reconcileMode_emptyWatch_skipsAlarmsButStillUploadsFilter() {
        val up = FakeUploader()
        // RECONCILE (the default ongoing sync): empty alarms are skipped, but the filter still
        // uploads (reserved buzz entries). This is the contract that keeps ongoing syncs minimal.
        val result = SyncOrchestrator.sync(
            SyncInput(watch = watch(), settings = SyncSettings(vibrationStrength = null)),
            up,
            mode = SyncMode.RECONCILE,
        )
        assertFalse(SyncSection.ALARMS in up.order)
        assertTrue(SyncSection.ALARMS in result.skipped)
        assertTrue(SyncSection.NOTIFICATION_FILTER in result.performed)
    }

    @Test
    fun provisionMode_writesWhole32SlotAlarmFile_overwritingWatch() {
        val up = FakeUploader()
        // Even ONE default alarm produces the whole 32-slot file (the rest cleared) — a full
        // overwrite that wipes any pre-existing watch alarms.
        val result = SyncOrchestrator.sync(
            SyncInput(watch = watch(), alarms = listOf(alarm(0)), settings = SyncSettings()),
            up,
            mode = SyncMode.PROVISION,
        )
        assertTrue(SyncSection.ALARMS in result.performed)
        assertTrue(up.alarmBytes != null)
    }

    // ---- WP-CLEARALARMS: targeted PROVISION clear ---------------------------

    @Test
    fun alarmsOnly_provisionMode_forceWritesEmptyFile_toBlankTheWatch() {
        // "Clear all alarms": a TARGETED ALARMS_ONLY pass in PROVISION mode must force-write the
        // (empty) alarm file so the watch's 32 slots are actively cleared — and must NOT touch any
        // other section.
        val up = FakeUploader()
        val result = SyncOrchestrator.sync(
            SyncInput(watch = watch(), settings = SyncSettings(vibrationStrength = null)),
            up, SyncSection.ALARMS_ONLY, mode = SyncMode.PROVISION,
        )
        assertEquals(listOf(SyncSection.ALARMS), up.order) // only alarms, force-written
        assertTrue(SyncSection.ALARMS in result.performed)
        assertTrue(up.alarmBytes != null)
        // No filter / buttons / settings touched by a targeted alarms clear.
        assertNull(up.filterEntries)
        assertNull(up.buttonBytes)
    }

    @Test
    fun alarmsOnly_reconcileMode_skipsEmpty_doesNotClearWatch() {
        // The contrast: an ordinary (RECONCILE) ALARMS_ONLY save of an empty set skip-empties — which
        // is exactly why "Clear all alarms" needs PROVISION mode (above) to actually blank the watch.
        val up = FakeUploader()
        val result = SyncOrchestrator.sync(
            SyncInput(watch = watch(), settings = SyncSettings(vibrationStrength = null)),
            up, SyncSection.ALARMS_ONLY, mode = SyncMode.RECONCILE,
        )
        assertFalse(SyncSection.ALARMS in up.order)
        assertTrue(SyncSection.ALARMS in result.skipped)
        assertNull(up.alarmBytes)
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
    fun disabledRulesAreExcludedFromTheUploadedFilter() {
        // A disabled rule is kept in the DB/UI but must NOT reach the watch's notification filter
        // (so it stops buzzing); enabled rules still upload.
        val up = FakeUploader()
        SyncOrchestrator.sync(
            SyncInput(watch = watch(),
                rules = listOf(
                    rule("com.whatsapp"),
                    rule("com.slack").copy(isEnabled = false),
                ),
                settings = SyncSettings()),
            up,
        )
        assertEquals(listOf("com.whatsapp"), up.filterEntries!!.map { it.packageName })
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
    fun musicControlActionCompilesToTheMultiWirePayload() {
        // WP12: MUSIC_CONTROL (and the legacy MULTI_FUNCTION placeholder) resolve to the SAME wire
        // payload (FORWARD_TO_PHONE_MULTI) via ButtonActions.payloadName — bytes UNCHANGED.
        val up = FakeUploader()
        SyncOrchestrator.sync(
            SyncInput(watch = watch(),
                buttons = listOf(button(ButtonSlots.TOP, ButtonModes.SINGLE_ACTION,
                    listOf(ButtonActions.MUSIC_CONTROL))),
                settings = SyncSettings()),
            up,
        )
        val expected = ButtonConfigBuilder.build(
            arrayOf(ButtonConfigBuilder.entryFrom(ConfigPayload.FORWARD_TO_PHONE_MULTI)),
            emptyArray(), emptyArray(),
        )
        assertArrayEquals(expected, up.buttonBytes)
        // A legacy DB row still holding MULTI_FUNCTION must compile to byte-identical output.
        val up2 = FakeUploader()
        SyncOrchestrator.sync(
            SyncInput(watch = watch(),
                buttons = listOf(button(ButtonSlots.TOP, ButtonModes.SINGLE_ACTION,
                    listOf(ButtonActions.MULTI_FUNCTION))),
                settings = SyncSettings()),
            up2,
        )
        assertArrayEquals(expected, up2.buttonBytes)
    }

    @Test
    fun trackerPath2ActionsCompileByteIdenticalToRingPhone() {
        // WP-TRACKER: LOG_WAYPOINT + SWITCH_MULTI_FUNCTION_MODE are button-aware single-press actions
        // that ride the SAME RING_PHONE (`01 01 0C 00`) payload. They must compile byte-for-byte
        // identically to a plain RING_PHONE button (distinguished app-side by the 0x08 event's
        // button id, NOT by wire bytes) — proving NO new wire bytes are invented.
        val expected = ButtonConfigBuilder.build(
            arrayOf(ButtonConfigBuilder.entryFrom(ConfigPayload.RING_PHONE)),
            emptyArray(), emptyArray(),
        )
        // Baseline: a plain RING_PHONE button.
        val ring = FakeUploader()
        SyncOrchestrator.sync(
            SyncInput(watch = watch(),
                buttons = listOf(button(ButtonSlots.TOP, ButtonModes.SINGLE_ACTION,
                    listOf(ButtonActions.RING_PHONE))),
                settings = SyncSettings()),
            ring,
        )
        assertArrayEquals(expected, ring.buttonBytes)
        // LOG_WAYPOINT → byte-identical.
        val log = FakeUploader()
        SyncOrchestrator.sync(
            SyncInput(watch = watch(),
                buttons = listOf(button(ButtonSlots.TOP, ButtonModes.SINGLE_ACTION,
                    listOf(ButtonActions.LOG_WAYPOINT))),
                settings = SyncSettings()),
            log,
        )
        assertArrayEquals(expected, log.buttonBytes)
        // SWITCH_MULTI_FUNCTION_MODE → byte-identical.
        val switch = FakeUploader()
        SyncOrchestrator.sync(
            SyncInput(watch = watch(),
                buttons = listOf(button(ButtonSlots.TOP, ButtonModes.SINGLE_ACTION,
                    listOf(ButtonActions.SWITCH_MULTI_FUNCTION_MODE))),
                settings = SyncSettings()),
            switch,
        )
        assertArrayEquals(expected, switch.buttonBytes)
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
