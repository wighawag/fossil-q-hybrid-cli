package qhybrid.android.sync

import qhybrid.android.buttons.ButtonActions
import qhybrid.android.buttons.ButtonActionsJson
import qhybrid.android.buttons.ButtonDialModes
import qhybrid.android.buttons.ButtonMappingRules
import qhybrid.android.buttons.ButtonModes
import qhybrid.android.buttons.ButtonSlots
import qhybrid.android.db.ButtonMappingEntity
import qhybrid.android.db.NotificationRuleEntity
import qhybrid.android.db.WatchAlarmEntity
import qhybrid.protocol.ButtonConfigBuilder
import qhybrid.protocol.buttonconfig.ConfigPayload
import qhybrid.protocol.model.NotificationFilterEntry
import qhybrid.protocol.requests.fossil.alarm.AlarmCompiler
import qhybrid.protocol.requests.fossil.alarm.AlarmSlot

/**
 * WP14 — the **pure, injectable** sync-orchestration core.
 *
 * Given a [SyncInput] (one active watch's DB rows + app settings) it computes the compiled
 * payloads via the golden-tested WP5/6/7 compilers + the WP16g settings vocabulary, then calls
 * the [Uploader] seam in a **defined, guarded order**:
 *
 * 1. **alarms** (slots 0–15 + calendar 16–31 → [AlarmCompiler], 32-slot guard)
 * 2. **notification filter** ([NotificationFilterEntry] → 32-byte-per-entry file)
 * 3. **buttons** (per-button mappings → [ButtonConfigBuilder.compileButtons])
 * 4. **live settings** (vibration strength, inactivity nudge, second timezone)
 *
 * **Guards / tolerance:**
 * - **no active watch** → returns an empty [SyncResult] (nothing uploaded), never throws.
 * - **empty section** → that upload is skipped (we do not push an empty alarm/filter/button file).
 * - **32-slot guard** → delegated to [AlarmCompiler.compile] (throws on 33+; the orchestrator
 *   catches it, records an error for that section, and continues with the rest).
 * - a settings value that is null/absent is not applied.
 *
 * This class invents NO wire bytes — it reuses the protocol compilers/façade exclusively, and
 * hands already-compiled bytes / typed config to the [Uploader]. The real BLE write lives behind
 * the production uploader (WP14 sub-part 2), so this decision logic is fully JVM-testable with a
 * fake uploader.
 */
object SyncOrchestrator {

    /**
     * Run a full sync pass for [input] against [uploader]. Each section is independent: a failure
     * (e.g. too many alarms) is recorded and the remaining sections still run. Returns a
     * [SyncResult] describing what was attempted/performed/skipped (for logging).
     */
    fun sync(input: SyncInput, uploader: Uploader): SyncResult {
        if (!input.hasWatch) {
            return SyncResult(mac = null, performed = emptyList(), skipped = emptyList(), errors = emptyList())
        }
        val performed = mutableListOf<SyncSection>()
        val skipped = mutableListOf<SyncSection>()
        val errors = mutableListOf<SyncError>()

        // 1. Alarms ------------------------------------------------------------
        runSection(SyncSection.ALARMS, performed, skipped, errors) {
            val standard = input.alarms.map { it.toAlarmSlot() }
            val calendar = input.calendarAlarms.map { it.toAlarmSlot() }
            if (standard.isEmpty() && calendar.isEmpty()) return@runSection false
            // AlarmCompiler enforces the 32-slot guard + slot ranges (throws → recorded).
            val bytes = AlarmCompiler.compile(standard, calendar)
            // An all-disabled set compiles to 0 bytes — don't push an empty file.
            if (bytes.isEmpty()) return@runSection false
            uploader.uploadAlarms(bytes)
        }

        // 2. Notification filter ----------------------------------------------
        runSection(SyncSection.NOTIFICATION_FILTER, performed, skipped, errors) {
            if (input.rules.isEmpty()) return@runSection false
            val entries = input.rules.map { it.toFilterEntry() }
            uploader.uploadNotificationFilter(entries)
        }

        // 3. Buttons -----------------------------------------------------------
        runSection(SyncSection.BUTTONS, performed, skipped, errors) {
            if (input.buttons.isEmpty()) return@runSection false
            val bytes = compileButtons(input.buttons) ?: return@runSection false
            uploader.uploadButtons(bytes)
        }

        // 4. Live settings -----------------------------------------------------
        val s = input.settings
        s.vibrationStrength?.let {
            runSection(SyncSection.VIBRATION, performed, skipped, errors) {
                uploader.applyVibrationStrength(it)
            }
        }
        if (s.nudgeMinutes != null) {
            runSection(SyncSection.NUDGE, performed, skipped, errors) {
                uploader.applyInactivityNudge(
                    s.nudgeFromHour, s.nudgeFromMinute, s.nudgeToHour, s.nudgeToMinute,
                    s.nudgeMinutes, s.nudgeEnabled,
                )
            }
        }
        s.secondTimezoneOffsetMinutes?.let {
            runSection(SyncSection.SECOND_TIMEZONE, performed, skipped, errors) {
                uploader.applySecondTimezone(it)
            }
        }

        return SyncResult(input.mac, performed.toList(), skipped.toList(), errors.toList())
    }

    /**
     * Run one section's [block]; classify the outcome. [block] returns whether the upload was
     * performed (true), skipped (false), and may throw — which is captured as a [SyncError] so
     * the rest of the pass continues.
     */
    private inline fun runSection(
        section: SyncSection,
        performed: MutableList<SyncSection>,
        skipped: MutableList<SyncSection>,
        errors: MutableList<SyncError>,
        block: () -> Boolean,
    ) {
        try {
            if (block()) performed.add(section) else skipped.add(section)
        } catch (e: Exception) {
            errors.add(SyncError(section, e.message ?: e.javaClass.simpleName))
        }
    }

    // ---- row → protocol domain mapping (pure) --------------------------------

    /**
     * Map a WP4 [WatchAlarmEntity] to a WP5 [AlarmSlot]. The `daysMask` is the wire `days` byte
     * 1:1 (FINDINGS #12; same convention as [qhybrid.android.alarms.AlarmDays]) — no translation.
     */
    private fun WatchAlarmEntity.toAlarmSlot(): AlarmSlot =
        AlarmSlot(slotId, hour, minute, daysMask, isRepeating, isEnabled, label)

    /** Map a WP4 [NotificationRuleEntity] to a WP6 [NotificationFilterEntry]. */
    private fun NotificationRuleEntity.toFilterEntry(): NotificationFilterEntry =
        NotificationFilterEntry(
            packageName,
            vibePattern.toByte(),
            hourHandDegrees.toShort(),
            minuteHandDegrees.toShort(),
        )

    /**
     * Compile the per-button mappings into a multi-entry button-config file via WP7
     * [ButtonConfigBuilder.compileButtons]. Returns null when no button produces any entry
     * (e.g. only unknown actions) so the caller can skip the upload.
     *
     * Mapping rules (model-agnostic, mirrors WP16d vocabulary):
     * - [ButtonModes.CUSTOM_TOGGLE] → one [ButtonConfigBuilder.ButtonEntry] per dial mode listed
     *   in `actionsJson` (the dial-mode toggle cycles those sub-eye positions);
     * - other modes → one entry per [ConfigPayload] action listed in `actionsJson`.
     * Unknown ids are dropped (never throws).
     */
    private fun compileButtons(buttons: List<ButtonMappingEntity>): ByteArray? {
        val byId = buttons.associateBy { it.buttonId }
        val top = entriesFor(byId[ButtonSlots.TOP])
        val mid = entriesFor(byId[ButtonSlots.MIDDLE])
        val bot = entriesFor(byId[ButtonSlots.BOTTOM])
        if (top.isEmpty() && mid.isEmpty() && bot.isEmpty()) return null
        // ButtonConfigBuilder.build delegates 1:1 to the golden-tested WP7 ButtonCompiler
        // (same bytes as FossilController.compileButtons); no wire bytes invented here.
        return ButtonConfigBuilder.build(top, mid, bot)
    }

    /**
     * Resolve one mapping into its protocol [ButtonConfigBuilder.ButtonEntry] list (may be empty).
     *
     * **WP-BTN defensive collapse:** the id list is first run through [ButtonMappingRules.normalizeIds]
     * so a *legacy* DB row that still holds many ids for a single-action mode (incl. a legacy
     * `MUSIC_MULTIMODE` row, which normalizes to `SINGLE_ACTION`) compiles to **at most one entry**,
     * never a silent multi-entry cycle.
     * Only [ButtonModes.CUSTOM_TOGGLE] keeps the full list (the genuine dial-mode cycle). This is
     * the SAME rule [qhybrid.android.buttons.ButtonsViewModel.setSlot] applies before persisting,
     * so the editor and the compiler can never disagree about cardinality.
     */
    private fun entriesFor(mapping: ButtonMappingEntity?): Array<ButtonConfigBuilder.ButtonEntry> {
        if (mapping == null) return emptyArray()
        val mode = ButtonModes.normalize(mapping.modeType)
        val ids = ButtonMappingRules.normalizeIds(mode, ButtonActionsJson.decode(mapping.actionsJson))
        if (ids.isEmpty()) return emptyArray()
        return if (ButtonModes.usesDialModes(mode)) {
            ids.mapNotNull { dialEntry(it) }.toTypedArray()
        } else {
            ids.mapNotNull { actionEntry(it) }.toTypedArray()
        }
    }

    /**
     * Map an app-level action id to a button entry; null if unknown. The id is first resolved to
     * its backing [ConfigPayload] NAME via [ButtonActions.payloadName] so [ButtonActions.MULTI_FUNCTION]
     * (and the retained legacy aliases) compile to the correct golden payload — no wire bytes invented.
     */
    private fun actionEntry(actionId: String): ButtonConfigBuilder.ButtonEntry? {
        val payload = runCatching { ConfigPayload.valueOf(ButtonActions.payloadName(actionId)) }.getOrNull() ?: return null
        return ButtonConfigBuilder.entryFrom(payload)
    }

    /**
     * Map a dial-mode id (WP16d [ButtonDialModes], 1:1 with the protocol dial modes) to the
     * corresponding captured [ButtonConfigBuilder.ButtonEntry] (the SEQUENCED / TOGGLE variants
     * used inside a mode toggle). Null for unknown ids.
     */
    private fun dialEntry(dialId: String): ButtonConfigBuilder.ButtonEntry? = when (dialId) {
        ButtonDialModes.ALERT -> ButtonConfigBuilder.entryFrom(ConfigPayload.LAST_NOTIFICATION)
        ButtonDialModes.TIMEZONE_2 -> ButtonConfigBuilder.entryFrom(ConfigPayload.SECOND_TIMEZONE)
        ButtonDialModes.ALARM -> ButtonConfigBuilder.ALARM_SEQUENCED_ENTRY
        ButtonDialModes.DATE -> ButtonConfigBuilder.DATE_TOGGLE_ENTRY
        ButtonDialModes.TWENTY_FOUR_HOUR -> ButtonConfigBuilder.TWENTY_FOUR_HOUR_SEQ_ENTRY
        else -> null
    }
}

/** WP14 — the sections a sync pass can touch, in upload order. */
enum class SyncSection {
    ALARMS,
    NOTIFICATION_FILTER,
    BUTTONS,
    VIBRATION,
    NUDGE,
    SECOND_TIMEZONE,
}

/** WP14 — a per-section failure captured during a sync pass (so the rest can continue). */
data class SyncError(val section: SyncSection, val message: String)

/**
 * WP14 — the outcome of a [SyncOrchestrator.sync] pass: which sections were actually uploaded
 * ([performed]), which were skipped (empty / not-wired) ([skipped]), and which errored
 * ([errors]). Used for logging; the BLE effect itself is on-device-pending.
 */
data class SyncResult(
    val mac: String?,
    val performed: List<SyncSection>,
    val skipped: List<SyncSection>,
    val errors: List<SyncError>,
) {
    val isNoWatch: Boolean get() = mac == null
    val anyPerformed: Boolean get() = performed.isNotEmpty()
}
