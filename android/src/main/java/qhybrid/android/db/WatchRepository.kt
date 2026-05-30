package qhybrid.android.db

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow

/**
 * WP4 — thin repository over the Room DAOs. This is the surface the rest of the app
 * (UI in WP16, the WP3 service when DB-driven sync lands in WP14) consumes; callers
 * never touch DAOs directly.
 *
 * NOTE on the associated-MAC relationship with WP3: [WatchEntity] is the source of truth
 * for watch identity/state. WP3's CompanionManager SharedPreferences pref stays as the
 * lightweight "which MAC to auto-reconnect to" pointer (read at boot), untouched. Use
 * [registerWatch] to mirror an association into the DB and mark it active.
 */
class WatchRepository(
    private val db: AppDatabase,
    private val watchDao: WatchDao = db.watchDao(),
    private val alarmDao: WatchAlarmDao = db.watchAlarmDao(),
    private val ruleDao: NotificationRuleDao = db.notificationRuleDao(),
    private val buttonDao: ButtonMappingDao = db.buttonMappingDao(),
) {

    constructor(context: Context) : this(AppDatabase.get(context))

    // ---- watches -------------------------------------------------------------

    suspend fun upsertWatch(watch: WatchEntity) = watchDao.upsert(watch)
    suspend fun getWatch(mac: String): WatchEntity? = watchDao.getByMac(mac)
    suspend fun getAllWatches(): List<WatchEntity> = watchDao.getAll()
    fun observeWatches(): Flow<List<WatchEntity>> = watchDao.observeAll()
    suspend fun deleteWatch(mac: String) = watchDao.deleteByMac(mac)

    /**
     * Delete [mac]'s row (+ CASCADE children) and, if it was the active watch, PROMOTE one of the
     * remaining watches to active so the app is never left "watch-less" while other watches still
     * exist. Runs in a single transaction so there is never a window with zero/multiple active rows.
     *
     * Fixes the Settings-screen symptom where removing the active watch (with another watch still
     * registered) left no active watch — disabling the per-watch controls (incl. "Remove watch")
     * even though a remaining watch could be administered. The promoted watch is the
     * first by name ([WatchDao.getAll] is ordered by name), matching the registry's ordering.
     */
    suspend fun deleteWatchAndPromote(mac: String) {
        val normalized = mac.uppercase()
        db.withTransaction {
            val wasActive = watchDao.getByMac(normalized)?.isActive == true
            watchDao.deleteByMac(normalized)
            if (wasActive) {
                val next = watchDao.getAll().firstOrNull()
                if (next != null) watchDao.setActive(next.macAddress)
            }
        }
    }

    suspend fun getActiveWatch(): WatchEntity? = watchDao.getActive()
    fun observeActiveWatch(): Flow<WatchEntity?> = watchDao.observeActive()
    suspend fun setActiveWatch(mac: String) = watchDao.setActive(mac)

    /**
     * Mirror a CDM association into the DB: create the watch row if missing (preserving
     * any existing details) and make it the single active watch. Idempotent.
     */
    suspend fun registerWatch(mac: String, name: String) {
        val normalized = mac.uppercase()
        val existing = watchDao.getByMac(normalized)
        if (existing == null) {
            watchDao.upsert(
                WatchEntity(
                    macAddress = normalized,
                    name = name,
                    model = null,
                    firmwareVersion = null,
                    batteryLevel = 0,
                )
            )
        }
        watchDao.setActive(normalized)
    }

    /**
     * WP-ONBOARD — register a brand-new watch whose READABLE settings were READ BACK from the watch
     * (vibration strength / step goal + live model/firmware/battery), instead of [registerWatch]'s
     * constant defaults. Upserts the seeded row (overwriting any stale row) and makes it the single
     * active watch. The MAC is normalized to upper-case to match [registerWatch].
     */
    suspend fun registerSeededWatch(watch: WatchEntity) {
        val normalized = watch.macAddress.uppercase()
        watchDao.upsert(watch.copy(macAddress = normalized))
        watchDao.setActive(normalized)
    }

    // ---- per-watch child settings -------------------------------------------

    suspend fun getAlarms(mac: String) = alarmDao.getForWatch(mac)
    fun observeAlarms(mac: String) = alarmDao.observeForWatch(mac)
    suspend fun upsertAlarm(alarm: WatchAlarmEntity) = alarmDao.upsert(alarm)
    suspend fun deleteAlarmSlot(mac: String, slotId: Int) = alarmDao.deleteSlot(mac, slotId)

    /**
     * WP-CLEARALARMS — delete ALL of [mac]'s standard user alarms (slots 0..15), leaving calendar
     * slots 16..31 untouched. The mac is normalized to upper-case to match the row PK. The caller
     * then pushes the (now empty) alarm section to the watch in PROVISION mode to blank it.
     */
    suspend fun clearStandardAlarms(mac: String) = alarmDao.deleteStandardForWatch(mac.uppercase())

    suspend fun getRules(mac: String) = ruleDao.getForWatch(mac)
    fun observeRules(mac: String) = ruleDao.observeForWatch(mac)
    suspend fun upsertRule(rule: NotificationRuleEntity) = ruleDao.upsert(rule)
    suspend fun deleteRule(mac: String, pkg: String) = ruleDao.deleteRule(mac, pkg)

    suspend fun getButtons(mac: String) = buttonDao.getForWatch(mac)
    fun observeButtons(mac: String) = buttonDao.observeForWatch(mac)
    suspend fun upsertButton(mapping: ButtonMappingEntity) = buttonDao.upsert(mapping)
    suspend fun getButton(mac: String, buttonId: Int) = buttonDao.getButton(mac, buttonId)
    suspend fun deleteButton(mac: String, buttonId: Int) = buttonDao.deleteButton(mac, buttonId)

    // ---- WP-DEFAULTS: full-replace the unreadable sections ------------------

    /**
     * WP-DEFAULTS — full-REPLACE a watch's UNREADABLE child sections (alarms 0–15 / notification
     * rules / button mappings) with the supplied rows, in one transaction. Unlike
     * [transferSettings] (a bulk REPLACE that LEAVES non-colliding rows in place), this DELETES the
     * existing rows for [mac] in each replaced section first, so the watch ends up with EXACTLY the
     * supplied rows — the same full-overwrite contract the PROVISION sync applies on the wire.
     *
     * Pass `replaceAlarms = false` to leave the standard alarm slots untouched (the apply-defaults
     * action overwrites buttons + rules but the alarms section is optional). Calendar alarms (slots
     * 16–31) are never touched here — [WatchAlarmDao.deleteForWatch] removes only the rows that
     * exist, and the seed never carries calendar slots.
     *
     * Each row's `watchMac` is re-keyed to the normalized (upper-case) [mac] so a lower-case caller
     * can never write a row that mismatches the [WatchEntity] PK.
     */
    suspend fun replaceDefaultsSections(
        mac: String,
        alarms: List<WatchAlarmEntity>,
        rules: List<NotificationRuleEntity>,
        buttons: List<ButtonMappingEntity>,
        replaceAlarms: Boolean = true,
    ) {
        val normalized = mac.uppercase()
        db.withTransaction {
            if (replaceAlarms) {
                alarmDao.deleteForWatch(normalized)
                alarmDao.upsertAll(alarms.map { it.copy(watchMac = normalized) })
            }
            ruleDao.deleteForWatch(normalized)
            ruleDao.upsertAll(rules.map { it.copy(watchMac = normalized) })
            buttonDao.deleteForWatch(normalized)
            buttonDao.upsertAll(buttons.map { it.copy(watchMac = normalized) })
        }
    }

    // ---- clone / transfer ----------------------------------------------------

    /**
     * Copy all alarms, notification rules, and button mappings from [fromMac] onto
     * [toMac] (ANDROID-PLAN §3 "Settings Transfer / Clone"). Each row is re-keyed to
     * [toMac] and bulk-inserted with REPLACE; the source watch is never modified.
     *
     * Runs in a single transaction so a partial failure can't leave [toMac] half-cloned.
     * Pre-existing rows on [toMac] that collide on PK are overwritten; non-colliding rows
     * on [toMac] are left in place (matches the documented "bulk-insert with REPLACE").
     */
    suspend fun transferSettings(fromMac: String, toMac: String) {
        db.withTransaction {
            val alarms = alarmDao.getForWatch(fromMac).map { it.copy(watchMac = toMac) }
            val rules = ruleDao.getForWatch(fromMac).map { it.copy(watchMac = toMac) }
            val buttons = buttonDao.getForWatch(fromMac).map { it.copy(watchMac = toMac) }
            alarmDao.upsertAll(alarms)
            ruleDao.upsertAll(rules)
            buttonDao.upsertAll(buttons)
        }
    }
}
