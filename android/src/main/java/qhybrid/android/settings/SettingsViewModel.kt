package qhybrid.android.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import qhybrid.android.db.WatchEntity
import qhybrid.android.db.WatchRepository
import qhybrid.android.notifications.InstalledApp
import qhybrid.android.notifications.InstalledAppsProvider
import qhybrid.android.sync.GlobalSyncStateSource
import qhybrid.android.sync.SyncProgressUi
import qhybrid.android.sync.SyncStateSource

/**
 * WP16g — the Settings screen's immutable UI state. A combination of:
 *   - the WP4 active watch (observed) — for the **persisted** vibration strength
 *     ([WatchEntity.vibrationStrength]) and to know which watch is active (disable when none), and
 *   - the **app-level** prefs ([AppSettings]: nudge / second timezone / preferred music app) from
 *     the injectable [SettingsPrefs] seam.
 *
 * Mirrors WP16e/f's `combine(observeActiveWatch(), …)` structure exactly (an injectable seam with a
 * `*_WIRED=false` deferral flag for the live commands; a production [factory]).
 *
 * **PER-SETTING DATA SOURCE (drives the design):**
 *   - **Vibration strength** = PERSISTED per-watch: it round-trips through
 *     [WatchRepository.upsertWatch] ([WatchEntity.vibrationStrength], an existing WP4 field — NO new
 *     DB field added). Applying it live to the watch is a deferred command via [SettingsSync].
 *   - **Inactivity nudge** (enabled + minutes) and **second timezone** = APP PREF + deferred live
 *     command: persisted via [SettingsPrefs]; applied live via [SettingsSync]
 *     ([ServiceSettingsSync.SETTINGS_WIRED] = false until WP14).
 *   - **Preferred music app** = pure APP PREF via [SettingsPrefs] (never sent to the watch).
 *   - **Settings transfer** = the WP4 [WatchRepository.transferSettings] operation (reused, not
 *     reinvented).
 *   - **Log viewer** = the existing WP15 Debug Menu (navigation handled by the UI; no VM state).
 *
 * When there is no active watch the controls disable; the app-level prefs remain editable since
 * they are user-level (not watch-scoped).
 */
data class SettingsUiState(
    /** The WP4 active watch (observed), or null if none. */
    val activeWatch: WatchEntity? = null,
    /** The app-level prefs (nudge / second timezone / music app). */
    val appSettings: AppSettings = AppSettings(),
    /** The installed apps the music-app picker offers (loaded lazily via [loadInstalledApps]). */
    val installedApps: List<InstalledApp> = emptyList(),
) {
    val activeMac: String? get() = activeWatch?.macAddress
    val hasActiveWatch: Boolean get() = activeWatch != null

    /** The persisted per-watch vibration strength (0–100); the model-agnostic default if none. */
    val vibrationStrength: Int
        get() = activeWatch?.vibrationStrength?.let { SettingsVocabulary.normalizeVibration(it) }
            ?: SettingsVocabulary.VIBE_DEFAULT

    val nudgeEnabled: Boolean get() = appSettings.nudgeEnabled
    val nudgeMinutes: Int get() = SettingsVocabulary.normalizeNudgeMinutes(appSettings.nudgeMinutes)
    val secondTimezoneOffsetMinutes: Int
        get() = SettingsVocabulary.normalizeTzOffset(appSettings.secondTimezoneOffsetMinutes)
    val preferredMusicApp: String
        get() = SettingsVocabulary.normalizeMusicApp(appSettings.preferredMusicApp)
    /** WP13 — minutes the watch alarm rings BEFORE a calendar event (0 = at event time). */
    val calendarAlarmOffsetMinutes: Int
        get() = SettingsVocabulary.normalizeCalendarOffset(appSettings.calendarAlarmOffsetMinutes)

    /** True when a preferred music app is set. */
    val hasPreferredMusicApp: Boolean get() = preferredMusicApp != SettingsVocabulary.MUSIC_APP_NONE

    /**
     * WP-TRACKER — the GLOBAL multi-function role (MUSIC ⇄ TRACKER). Necessarily global because the
     * 0x05 gesture stream that this role re-interprets is button-blind (carries no button id).
     */
    val multiFunctionRole: String
        get() = SettingsVocabulary.normalizeMultiFunctionRole(appSettings.multiFunctionRole)

    /** WP-TRACKER — the loud "find my phone" ring auto-stop duration in seconds (default 60s). */
    val ringDurationSeconds: Int
        get() = SettingsVocabulary.normalizeRingDuration(appSettings.ringDurationSeconds)

    /** Whether the per-watch (persisted + live) settings controls should be enabled. */
    val canEditWatchSettings: Boolean get() = hasActiveWatch
}

/**
 * WP16g — observes the WP4 active watch (for the persisted [WatchEntity.vibrationStrength] +
 * disable-when-none) and combines the app-level [SettingsPrefs] into one [SettingsUiState].
 * Mirrors WP16e/f exactly: an active-watch [combine] over `observeActiveWatch`; injectable seams
 * (a [SettingsSync] for live commands with [ServiceSettingsSync.SETTINGS_WIRED] = false, a
 * [SettingsPrefs] for app prefs, an [InstalledAppsProvider] for the music picker, the WP4
 * [WatchRepository] for the persisted vibration strength + the settings-transfer surface); a
 * production [factory].
 */
open class SettingsViewModel(
    private val repo: WatchRepository,
    private val prefs: SettingsPrefs,
    private val sync: SettingsSync,
    private val appsProvider: InstalledAppsProvider,
    // WP-BUZZTEST: the manual "vibrate the watch now" test seam (fake in tests).
    private val vibration: VibrationSync = NoopVibrationSync,
    // WP-PULLSYNC: the manual "Sync all" seam (fake in tests).
    private val fullSync: FullSync = NoopFullSync,
    // WP-WATCHADMIN: the "remove / re-provision this watch" seam (fake in tests).
    private val watchAdmin: WatchAdminSync = NoopWatchAdminSync,
    // WP-DEFAULTS: the "apply defaults profile to this watch" seam (fake in tests).
    private val applyDefaults: ApplyDefaultsSync = NoopApplyDefaults,
    // WP-CLEARALARMS: the "clear all alarms on this watch" seam (fake in tests).
    private val clearAlarms: ClearAlarmsSync = NoopClearAlarms,
    // WP13: trigger a calendar re-map + silent push after the ring-offset changes (no-op in tests).
    private val calendarRefresh: () -> Unit = {},
    // Tests inject a real/Unconfined scope; production passes null → uses [viewModelScope].
    scope: CoroutineScope? = null,
    // WP-PROGRESS (sub-part 3): the process-wide sync signal the Save/Apply controls observe.
    syncSource: SyncStateSource = GlobalSyncStateSource(),
) : ViewModel() {

    private val coroutineScope: CoroutineScope = scope ?: viewModelScope

    /**
     * WP-PROGRESS (sub-part 3) — the Save/Apply progress state, mapped purely from the
     * process-wide [qhybrid.android.sync.SyncState] via [SyncProgressUi] (spinner + disable while
     * SYNCING; transient success/error note). Visual rendering is on-device-pending.
     */
    val syncProgress: StateFlow<SyncProgressUi> =
        syncSource.status
            .map { SyncProgressUi.from(it) }
            .stateIn(coroutineScope, SharingStarted.WhileSubscribed(5_000), SyncProgressUi.IDLE)

    /** In-memory mirror of the persisted app prefs so writes re-render immediately. */
    private val appSettings = MutableStateFlow(prefs.get())

    /** Lazily-loaded installed apps for the music picker (kept out of the prefs flow). */
    private val installedApps = MutableStateFlow<List<InstalledApp>>(emptyList())

    val uiState: StateFlow<SettingsUiState> =
        combine(repo.observeActiveWatch(), appSettings, installedApps) { active, settings, apps ->
            SettingsUiState(
                activeWatch = active,
                appSettings = settings,
                installedApps = apps,
            )
        }.stateIn(
            coroutineScope,
            SharingStarted.WhileSubscribed(5_000),
            SettingsUiState(appSettings = appSettings.value),
        )

    // ---- vibration strength (PERSISTED per-watch + deferred live command) -----

    /**
     * Persist the vibration strength (clamped 0–100) onto the active watch's WP4 row AND request a
     * live apply via the deferred [SettingsSync] seam. No-op (returns false) without an active
     * watch. Returns whether the LIVE command is wired yet (false until WP14; the persist always
     * happens).
     */
    fun setVibrationStrength(strength: Int): Boolean {
        val watch = uiState.value.activeWatch ?: return false
        val normalized = SettingsVocabulary.normalizeVibration(strength)
        coroutineScope.launch {
            repo.upsertWatch(watch.copy(vibrationStrength = normalized))
        }
        return sync.applyVibrationStrength(normalized)
    }

    // ---- manual "vibrate the watch now" test buttons (WP-BUZZTEST) ------------

    /**
     * WP-BUZZTEST — make the watch vibrate NOW with the given vibration [pattern] byte (a manual
     * on-device test buzz; 5 = strong single, 1 = triple). No-op (returns false) without an active
     * watch. Otherwise forwards to the injectable [VibrationSync] seam, which connects-then-buzzes
     * via the WP3 service and reports SyncState SYNCING → SUCCESS/ERROR. Returns whether the buzz
     * pipeline is wired (`true`).
     */
    fun vibrateWatch(pattern: Int): Boolean {
        if (uiState.value.activeWatch == null) return false
        return vibration.buzz(pattern)
    }

    /**
     * Diagnostic "put filter + send buzz": vibrate via the self-contained two-put path
     * (NOTIFICATION_FILTER + NOTIFICATION_PLAY) that works even when the reserved buzz filter isn't
     * on the watch — used to isolate whether a non-buzzing watch is missing the reserved filter.
     * No-op (returns false) without an active watch.
     */
    fun vibrateWatchWithFilter(pattern: Int): Boolean {
        if (uiState.value.activeWatch == null) return false
        return vibration.buzz(pattern, forceFilterPlay = true)
    }

    // ---- manual "Sync all" (WP-PULLSYNC) -------------------------------------

    /**
     * WP-PULLSYNC — push the ENTIRE saved config to the active watch (full reconcile). Sync is
     * user-initiated now (connect no longer auto-pushes), so this is the explicit escape hatch.
     * No-op (returns false) without an active watch. Otherwise forwards to the injectable
     * [FullSync] seam (connect-then-sync via the WP3 service; SyncState SYNCING → SUCCESS/ERROR).
     */
    fun syncAll(): Boolean {
        if (uiState.value.activeWatch == null) return false
        return fullSync.syncAll()
    }

    // ---- remove / re-provision this watch (WP-WATCHADMIN) ---------------------

    /**
     * WP-WATCHADMIN — remove the active watch from the app: delete its DB row (+ CASCADE children)
     * and clear its CDM association / presence / reconnect pointer, then disconnect. The next
     * connect then looks brand-new and re-runs the one-time provisioning sync (which uploads the
     * notification filter with the reserved buzz entries folded in). This is the user-facing
     * replacement for the old Debug-Menu wipe. No-op (returns false) without an active watch;
     * otherwise forwards to the injectable [WatchAdminSync] seam and returns whether it is wired.
     *
     * NOTE: this does NOT remove the OS Bluetooth bond — the UI advises the user to "Forget" the
     * device in Android Settings if they want a full unpair.
     */
    fun removeActiveWatch(): Boolean {
        val mac = uiState.value.activeMac ?: return false
        return watchAdmin.removeWatch(mac)
    }

    // ---- apply defaults to this watch (WP-DEFAULTS sub-part 3) ----------------

    /**
     * WP-DEFAULTS — push the app-level defaults profile's UNREADABLE sections (buttons + the
     * notification filter/rules) onto the already-added ACTIVE watch on demand: a FULL-OVERWRITE of
     * those per-watch sections (the watch ends up with exactly the profile's buttons + filter, same
     * as provisioning does). No-op (returns `false`) without an active watch. Otherwise forwards to
     * the injectable [ApplyDefaultsSync] seam (which persists the re-keyed rows + triggers a
     * targeted sync; SyncState SYNCING → SUCCESS/ERROR). Gate this behind a confirm dialog in the
     * UI — it overwrites the user's per-watch button/notification setup.
     */
    fun applyDefaultsToActiveWatch(): Boolean {
        if (uiState.value.activeWatch == null) return false
        return applyDefaults.applyDefaultsToActiveWatch()
    }

    // ---- clear all alarms (WP-CLEARALARMS) -----------------------------------

    /**
     * WP-CLEARALARMS — delete the active watch's standard alarms (slots 0–15) and push the blanked
     * alarm file to the watch (force-write so the watch is actively cleared, unlike a normal
     * skip-empties save). No-op (returns `false`) without an active watch. Otherwise forwards to the
     * injectable [ClearAlarmsSync] seam (SyncState SYNCING → SUCCESS/ERROR). Gate behind a confirm
     * dialog in the UI — it removes all the watch's alarms.
     */
    fun clearAlarmsOnActiveWatch(): Boolean {
        if (uiState.value.activeWatch == null) return false
        return clearAlarms.clearAlarmsOnActiveWatch()
    }

    // ---- inactivity nudge (APP PREF + deferred live command) -----------------

    /**
     * Persist the inactivity-nudge config (enabled + duration, clamped 1–255) AND request a live
     * apply via [SettingsSync]. Persists even with no active watch (it is a user-level pref).
     * Returns whether the LIVE command is wired yet (false until WP14).
     */
    fun setNudge(enabled: Boolean, minutes: Int): Boolean {
        val normalized = SettingsVocabulary.normalizeNudgeMinutes(minutes)
        prefs.setNudge(enabled, normalized)
        appSettings.value = appSettings.value.copy(nudgeEnabled = enabled, nudgeMinutes = normalized)
        // Only push to the watch when one is active; persist regardless.
        return if (uiState.value.hasActiveWatch) sync.applyNudge(enabled, normalized) else false
    }

    // ---- second timezone (APP PREF + deferred live command) ------------------

    /**
     * Persist the second-timezone offset (clamped to UTC−12..UTC+14) AND request a live apply via
     * [SettingsSync]. Persists even with no active watch. Returns whether the LIVE command is wired
     * yet (false until WP14).
     */
    fun setSecondTimezoneOffset(offsetMinutes: Int): Boolean {
        val normalized = SettingsVocabulary.normalizeTzOffset(offsetMinutes)
        prefs.setSecondTimezoneOffset(normalized)
        appSettings.value = appSettings.value.copy(secondTimezoneOffsetMinutes = normalized)
        return if (uiState.value.hasActiveWatch) sync.applySecondTimezone(normalized) else false
    }

    // ---- preferred music app (PURE APP PREF — never sent to the watch) -------

    /** Persist the preferred music-app package (blank/null clears it). Always app-side only. */
    fun setPreferredMusicApp(pkg: String?) {
        val normalized = SettingsVocabulary.normalizeMusicApp(pkg)
        prefs.setPreferredMusicApp(normalized)
        appSettings.value = appSettings.value.copy(preferredMusicApp = normalized)
    }

    // ---- multi-function role (WP-TRACKER — PURE GLOBAL APP PREF; never sent to the watch) ----

    /**
     * WP-TRACKER — persist the GLOBAL multi-function role (MUSIC ⇄ TRACKER). Pure app-side: it only
     * changes how the watch's button-blind 0x05 gesture stream is interpreted (media vs GPS-tracker
     * actions); NO wire bytes, NO live watch command. Necessarily global — the 0x05 stream carries
     * no button id, so the role can't be per-button.
     */
    fun setMultiFunctionRole(role: String?) {
        val normalized = SettingsVocabulary.normalizeMultiFunctionRole(role)
        prefs.setMultiFunctionRole(normalized)
        appSettings.value = appSettings.value.copy(multiFunctionRole = normalized)
    }

    /**
     * WP-TRACKER — persist the loud-ring auto-stop duration (seconds, clamped 5..300). Pure
     * app-side pref read by [qhybrid.android.tracker.SystemPhoneRinger] at ring time; NO wire bytes,
     * NO live watch command. Returns true (always succeeds — a local pref write).
     */
    fun setRingDurationSeconds(seconds: Int): Boolean {
        val normalized = SettingsVocabulary.normalizeRingDuration(seconds)
        prefs.setRingDurationSeconds(normalized)
        appSettings.value = appSettings.value.copy(ringDurationSeconds = normalized)
        return true
    }

    // ---- calendar alarm ring offset (WP13 — APP PREF, applied in CalendarRefresher) ----

    /**
     * WP13 — persist the calendar-alarm ring offset (minutes before the event, clamped 0..120) and
     * trigger a calendar refresh so the watch's slots 16–31 re-map to the new lead time (the
     * refresh silently re-pushes the alarm file if the rows changed). Pure app-side pref + the
     * existing refresh path; no new wire bytes. The [onChanged] callback is the injectable refresh
     * trigger (production pokes the WP3 service; tests pass a recorder / no-op).
     */
    fun setCalendarAlarmOffset(minutes: Int): Boolean {
        val normalized = SettingsVocabulary.normalizeCalendarOffset(minutes)
        prefs.setCalendarAlarmOffset(normalized)
        appSettings.value = appSettings.value.copy(calendarAlarmOffsetMinutes = normalized)
        calendarRefresh()
        return true
    }

    /**
     * WP13 — manually re-read the user's calendar and re-map the watch's calendar alarm slots
     * (16–31), then silently push the alarm file if the rows changed. The escape hatch for when the
     * user wants to force a refresh (e.g. just granted access, or added an event and doesn't want to
     * wait for the observer). Forwards to the same injectable [calendarRefresh] trigger
     * ([WatchConnectionService.refreshCalendarNow] in production). Always returns true (the trigger
     * is fire-and-forget; the refresh itself no-ops if calendar access isn't granted yet).
     */
    fun resyncCalendar(): Boolean {
        calendarRefresh()
        return true
    }

    /**
     * Load the installed-app list for the music picker (reuses the WP16c provider).
     *
     * The production [InstalledAppsProvider] enumerates EVERY launcher app and loads each icon via
     * `PackageManager` — a heavy, blocking call that must NEVER run on the main thread (it ANRs
     * the UI). [coroutineScope] is the Main-dispatched `viewModelScope` in production, so the
     * query is moved onto [Dispatchers.IO]; only the resulting list assignment hops back. Idempotent
     * — skips the work if the list is already loaded.
     */
    fun loadInstalledApps() {
        if (installedApps.value.isNotEmpty()) return
        coroutineScope.launch {
            val apps = withContext(Dispatchers.IO) { appsProvider.installedApps() }
            installedApps.value = apps
        }
    }

    // ---- settings transfer (WP4 — reuse, do NOT reinvent) --------------------

    /**
     * Clone all per-watch settings (alarms / rules / buttons) from [fromMac] onto [toMac] via the
     * WP4 [WatchRepository.transferSettings] surface. No-op (returns false) for a blank or
     * identical pair. Returns true when the transfer was dispatched.
     */
    fun transferSettings(fromMac: String, toMac: String): Boolean {
        val from = fromMac.trim()
        val to = toMac.trim()
        if (from.isEmpty() || to.isEmpty() || from.equals(to, ignoreCase = true)) return false
        coroutineScope.launch {
            repo.transferSettings(from, to)
        }
        return true
    }

    companion object {
        /** Production factory: real WP4 repo + SharedPreferences prefs + service-backed sync. */
        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    SettingsViewModel(
                        repo = WatchRepository(appContext),
                        prefs = SharedPreferencesSettingsPrefs(appContext),
                        sync = ServiceSettingsSync(appContext),
                        appsProvider =
                            qhybrid.android.notifications.SystemInstalledAppsProvider(appContext),
                        vibration = ServiceVibrationSync(appContext),
                        fullSync = ServiceFullSync(appContext),
                        watchAdmin = ServiceWatchAdminSync(
                            context = appContext,
                            removeFromDb = { mac -> WatchRepository(appContext).deleteWatchAndPromote(mac) },
                            launchDbRemoval = { block ->
                                kotlinx.coroutines.CoroutineScope(
                                    kotlinx.coroutines.Dispatchers.IO +
                                        kotlinx.coroutines.SupervisorJob()
                                ).launch { block() }
                            },
                        ),
                        applyDefaults = ServiceApplyDefaults.create(appContext) { block ->
                            kotlinx.coroutines.CoroutineScope(
                                kotlinx.coroutines.Dispatchers.IO +
                                    kotlinx.coroutines.SupervisorJob()
                            ).launch { block() }
                        },
                        clearAlarms = ServiceClearAlarms.create(appContext) { block ->
                            kotlinx.coroutines.CoroutineScope(
                                kotlinx.coroutines.Dispatchers.IO +
                                    kotlinx.coroutines.SupervisorJob()
                            ).launch { block() }
                        },
                        // WP13: re-read the calendar + re-map slots 16–31 with the new offset.
                        calendarRefresh = {
                            qhybrid.android.WatchConnectionService.refreshCalendarNow(appContext)
                        },
                    ) as T
            }
        }
    }
}
