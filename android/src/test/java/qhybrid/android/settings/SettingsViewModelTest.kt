package qhybrid.android.settings

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import qhybrid.android.db.DbTestBase
import qhybrid.android.notifications.InstalledApp
import qhybrid.android.notifications.InstalledAppsProvider
import qhybrid.android.sync.FakeSyncStateSource
import qhybrid.android.sync.SyncProgressUi
import qhybrid.android.sync.SyncState

/**
 * WP16g — headless tests for the Settings state holder. Reuses the WP4 [DbTestBase] in-memory Room
 * harness for the persisted vibration strength (a [qhybrid.android.db.WatchEntity] field) + the
 * active-watch observation + the WP4 settings-transfer surface; the app-level prefs and the live
 * commands are replaced by in-memory fakes. Verifies:
 *   - the UiState reflects the active watch (persisted vibration strength) + disables when none,
 *   - a persisted setting (vibration strength) round-trips through the WP4 repo,
 *   - an app-pref setting (nudge / timezone / music) round-trips through the prefs fake,
 *   - a live-command setting hits the [SettingsSync] fake + reports the SETTINGS_WIRED pending flag,
 *   - settings-transfer hits the WP4 [qhybrid.android.db.WatchRepository.transferSettings] surface,
 *   - empty/partial tolerance (no crash with no watch / blank or identical transfer pair).
 *
 * Like [qhybrid.android.calibration.CalibrationViewModelTest], the VM is given a REAL
 * [CoroutineScope] and the combined [StateFlow] is polled with a bounded [awaitState] because
 * Room's reactive Flows re-emit on Room's own executor (virtual-time would not observe them).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SettingsViewModelTest : DbTestBase() {

    private val vmScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())

    // ---- fakes ---------------------------------------------------------------

    private class FakePrefs(initial: AppSettings = AppSettings()) : SettingsPrefs {
        var current = initial
        var nudgeWrites = 0
        var tzWrites = 0
        var musicWrites = 0
        var calOffsetWrites = 0
        var roleWrites = 0
        override fun get(): AppSettings = current
        override fun setNudge(enabled: Boolean, minutes: Int) {
            nudgeWrites++
            current = current.copy(
                nudgeEnabled = enabled,
                nudgeMinutes = SettingsVocabulary.normalizeNudgeMinutes(minutes),
            )
        }
        override fun setSecondTimezoneOffset(minutes: Int) {
            tzWrites++
            current = current.copy(
                secondTimezoneOffsetMinutes = SettingsVocabulary.normalizeTzOffset(minutes),
            )
        }
        override fun setPreferredMusicApp(pkg: String?) {
            musicWrites++
            current = current.copy(preferredMusicApp = SettingsVocabulary.normalizeMusicApp(pkg))
        }
        override fun setCalendarAlarmOffset(minutes: Int) {
            calOffsetWrites++
            current = current.copy(
                calendarAlarmOffsetMinutes = SettingsVocabulary.normalizeCalendarOffset(minutes),
            )
        }
        override fun setMultiFunctionRole(role: String?) {
            roleWrites++
            current = current.copy(
                multiFunctionRole = SettingsVocabulary.normalizeMultiFunctionRole(role),
            )
        }
        override fun setMultiFunctionRotation(modes: List<String>?) {
            current = current.copy(
                multiFunctionRotation = SettingsVocabulary.normalizeRotation(modes),
                multiFunctionActiveIndex = 0,
            )
        }
        override fun setMultiFunctionActiveIndex(index: Int) {
            current = current.copy(
                multiFunctionActiveIndex =
                    SettingsVocabulary.clampIndex(current.multiFunctionRotation, index),
            )
        }
        override fun setLyrionServer(host: String?, port: Int) {
            current = current.copy(
                lyrionServerHost = SettingsVocabulary.normalizeLyrionHost(host),
                lyrionServerPort = SettingsVocabulary.normalizeLyrionPort(port),
            )
        }
        override fun setLyrionPlayer(id: String?, name: String?) {
            current = current.copy(
                lyrionPlayerId = SettingsVocabulary.normalizeLyrionPlayerId(id),
                lyrionPlayerName = name?.trim().orEmpty(),
            )
        }
        override fun setLyrionEmptyQueueFallback(fallback: String?) {
            current = current.copy(
                lyrionEmptyQueueFallback = SettingsVocabulary.normalizeLyrionFallback(fallback),
            )
        }
        override fun setLyrionFavoriteId(id: String?) {
            current = current.copy(
                lyrionFavoriteId = SettingsVocabulary.normalizeLyrionFavoriteId(id),
            )
        }
        override fun setRingDurationSeconds(seconds: Int) {
            current = current.copy(
                ringDurationSeconds = SettingsVocabulary.normalizeRingDuration(seconds),
            )
        }
    }

    private class FakeSync(private val wired: Boolean = false) : SettingsSync {
        var vibeCount = 0
        var nudgeCount = 0
        var tzCount = 0
        var lastVibe: Int? = null
        var lastNudgeEnabled: Boolean? = null
        var lastNudgeMinutes: Int? = null
        var lastTz: Int? = null
        override fun applyVibrationStrength(strength: Int): Boolean {
            vibeCount++; lastVibe = strength; return wired
        }
        override fun applyNudge(enabled: Boolean, minutes: Int): Boolean {
            nudgeCount++; lastNudgeEnabled = enabled; lastNudgeMinutes = minutes; return wired
        }
        override fun applySecondTimezone(offsetMinutes: Int): Boolean {
            tzCount++; lastTz = offsetMinutes; return wired
        }
    }

    private class FakeDiscovery(
        private val players: List<qhybrid.android.music.lyrion.LyrionCommands.LyrionPlayer> = emptyList(),
        private val favorites: List<qhybrid.android.music.lyrion.LyrionCommands.LyrionFavorite> = emptyList(),
        private val servers: List<qhybrid.android.music.lyrion.LyrionDiscoveryCodec.DiscoveredServer> = emptyList(),
    ) : qhybrid.android.music.lyrion.LyrionDiscovery {
        override fun players(host: String, port: Int) = players
        override fun favorites(host: String, port: Int) = favorites
        override fun discoverServers(timeoutMs: Int) = servers
    }

    private class FakeAppsProvider(private val apps: List<InstalledApp>) : InstalledAppsProvider {
        override fun installedApps(): List<InstalledApp> = apps
    }

    private class FakeVibration(private val wired: Boolean = true) : VibrationSync {
        var count = 0
        val patterns = mutableListOf<Int>()
        val forceFilterFlags = mutableListOf<Boolean>()
        override fun buzz(pattern: Int, forceFilterPlay: Boolean): Boolean {
            count++; patterns.add(pattern); forceFilterFlags.add(forceFilterPlay); return wired
        }
    }

    private class FakeFullSync(private val wired: Boolean = true) : FullSync {
        var count = 0
        override fun syncAll(): Boolean { count++; return wired }
    }

    private class FakeApplyDefaults(private val wired: Boolean = true) : ApplyDefaultsSync {
        var count = 0
        override fun applyDefaultsToActiveWatch(): Boolean { count++; return wired }
    }

    private class FakeClearAlarms(private val wired: Boolean = true) : ClearAlarmsSync {
        var count = 0
        override fun clearAlarmsOnActiveWatch(): Boolean { count++; return wired }
    }

    private class FakeWatchAdmin(private val wired: Boolean = true) : WatchAdminSync {
        var count = 0
        val removed = mutableListOf<String>()
        override fun removeWatch(mac: String): Boolean {
            count++; removed.add(mac); return wired
        }
    }

    private fun vm(
        prefs: SettingsPrefs = FakePrefs(),
        sync: SettingsSync = FakeSync(),
        apps: InstalledAppsProvider = FakeAppsProvider(emptyList()),
        discovery: qhybrid.android.music.lyrion.LyrionDiscovery = FakeDiscovery(),
        syncSource: FakeSyncStateSource = FakeSyncStateSource(),
        vibration: VibrationSync = FakeVibration(),
        fullSync: FullSync = FakeFullSync(),
        watchAdmin: WatchAdminSync = FakeWatchAdmin(),
        applyDefaults: ApplyDefaultsSync = FakeApplyDefaults(),
        clearAlarms: ClearAlarmsSync = FakeClearAlarms(),
        calendarRefresh: () -> Unit = {},
    ) = SettingsViewModel(
        repo = repo,
        prefs = prefs,
        sync = sync,
        appsProvider = apps,
        lyrionDiscovery = discovery,
        vibration = vibration,
        fullSync = fullSync,
        watchAdmin = watchAdmin,
        applyDefaults = applyDefaults,
        clearAlarms = clearAlarms,
        calendarRefresh = calendarRefresh,
        scope = vmScope,
        syncSource = syncSource,
    )

    private fun awaitState(
        flow: StateFlow<SettingsUiState>,
        predicate: (SettingsUiState) -> Boolean,
    ): SettingsUiState = runBlocking {
        withTimeout(5_000) { flow.first { predicate(it) } }
    }

    // ---- active-watch observation (persisted vibration strength) -------------

    @Test
    fun reflectsActiveWatchVibrationStrength() {
        runBlocking {
            watchDao.upsert(
                watch("AA:00:00:00:00:01", name = "One", active = true).copy(vibrationStrength = 80)
            )
        }
        val model = vm()
        val s = awaitState(model.uiState) { it.hasActiveWatch }
        assertEquals("AA:00:00:00:00:01", s.activeMac)
        assertEquals(80, s.vibrationStrength)
        assertTrue(s.canEditWatchSettings)
    }

    @Test
    fun disabledWhenNoActiveWatch() {
        runBlocking { watchDao.upsert(watch("AA:00:00:00:00:01", active = false)) }
        val model = vm()
        val s = awaitState(model.uiState) { true }
        assertNull(s.activeWatch)
        assertFalse(s.hasActiveWatch)
        assertFalse(s.canEditWatchSettings)
        // Falls back to the model-agnostic default vibration strength when no watch.
        assertEquals(SettingsVocabulary.VIBE_DEFAULT, s.vibrationStrength)
    }

    // ---- vibration strength round-trips through the WP4 repo ------------------

    @Test
    fun vibrationStrengthRoundTripsThroughRepoAndHitsSync() {
        runBlocking { watchDao.upsert(watch("AA:00:00:00:00:01", active = true)) }
        val sync = FakeSync(wired = false)
        val model = vm(sync = sync)
        awaitState(model.uiState) { it.hasActiveWatch }

        val wired = model.setVibrationStrength(95)
        // Live apply hit the fake + reports SETTINGS_WIRED pending.
        assertEquals(1, sync.vibeCount)
        assertEquals(95, sync.lastVibe)
        assertFalse(wired)

        // Persisted to the WP4 row (re-render).
        val s = awaitState(model.uiState) { it.vibrationStrength == 95 }
        assertEquals(95, s.vibrationStrength)
        // Verified directly in the DB too.
        val persisted = runBlocking { watchDao.getByMac("AA:00:00:00:00:01") }
        assertEquals(95, persisted?.vibrationStrength)
    }

    @Test
    fun vibrationStrengthClampsOutOfRange() {
        runBlocking { watchDao.upsert(watch("AA:00:00:00:00:01", active = true)) }
        val model = vm()
        awaitState(model.uiState) { it.hasActiveWatch }
        model.setVibrationStrength(500)
        val s = awaitState(model.uiState) { it.vibrationStrength == 100 }
        assertEquals(100, s.vibrationStrength)
    }

    @Test
    fun vibrationStrengthNoOpWithoutActiveWatch() {
        runBlocking { watchDao.upsert(watch("AA:00:00:00:00:01", active = false)) }
        val sync = FakeSync()
        val model = vm(sync = sync)
        awaitState(model.uiState) { !it.hasActiveWatch }
        assertFalse(model.setVibrationStrength(60))
        assertEquals(0, sync.vibeCount)
    }

    // ---- manual "vibrate the watch now" test buttons (WP-BUZZTEST) -----------

    @Test
    fun vibrateWatchHitsSeamWithSinglePattern() {
        runBlocking { watchDao.upsert(watch("AA:00:00:00:00:01", active = true)) }
        val vibe = FakeVibration(wired = true)
        val model = vm(vibration = vibe)
        awaitState(model.uiState) { it.hasActiveWatch }

        val wired = model.vibrateWatch(VibrationSync.PATTERN_SINGLE)
        assertTrue(wired)
        assertEquals(1, vibe.count)
        assertEquals(5, vibe.patterns.last())
    }

    @Test
    fun vibrateWatchHitsSeamWithTriplePattern() {
        runBlocking { watchDao.upsert(watch("AA:00:00:00:00:01", active = true)) }
        val vibe = FakeVibration(wired = true)
        val model = vm(vibration = vibe)
        awaitState(model.uiState) { it.hasActiveWatch }

        model.vibrateWatch(VibrationSync.PATTERN_TRIPLE)
        assertEquals(1, vibe.count)
        assertEquals(1, vibe.patterns.last())
    }

    @Test
    fun vibrateWatchNoOpWithoutActiveWatch() {
        runBlocking { watchDao.upsert(watch("AA:00:00:00:00:01", active = false)) }
        val vibe = FakeVibration()
        val model = vm(vibration = vibe)
        awaitState(model.uiState) { !it.hasActiveWatch }
        assertFalse(model.vibrateWatch(VibrationSync.PATTERN_SINGLE))
        assertEquals(0, vibe.count)
    }

    @Test
    fun vibrateWatchPlainUsesPlayOnlyPath() {
        runBlocking { watchDao.upsert(watch("AA:00:00:00:00:01", active = true)) }
        val vibe = FakeVibration(wired = true)
        val model = vm(vibration = vibe)
        awaitState(model.uiState) { it.hasActiveWatch }
        model.vibrateWatch(VibrationSync.PATTERN_SINGLE)
        assertEquals(1, vibe.count)
        assertFalse(vibe.forceFilterFlags.last()) // default play-only (no forced filter)
    }

    @Test
    fun vibrateWatchWithFilterForcesFilterPlay() {
        runBlocking { watchDao.upsert(watch("AA:00:00:00:00:01", active = true)) }
        val vibe = FakeVibration(wired = true)
        val model = vm(vibration = vibe)
        awaitState(model.uiState) { it.hasActiveWatch }
        val wired = model.vibrateWatchWithFilter(VibrationSync.PATTERN_SINGLE)
        assertTrue(wired)
        assertEquals(1, vibe.count)
        assertTrue(vibe.forceFilterFlags.last()) // diagnostic path forces filter+play
    }

    @Test
    fun vibrateWatchWithFilterNoOpWithoutActiveWatch() {
        runBlocking { watchDao.upsert(watch("AA:00:00:00:00:01", active = false)) }
        val vibe = FakeVibration()
        val model = vm(vibration = vibe)
        awaitState(model.uiState) { !it.hasActiveWatch }
        assertFalse(model.vibrateWatchWithFilter(VibrationSync.PATTERN_SINGLE))
        assertEquals(0, vibe.count)
    }

    // ---- WP-SYNCSTATUS: vibration-pattern dropdown (reserved patterns, play-only) ----

    @Test
    fun dropdownOffersExactlyTheReservedPatterns() {
        // The Settings dropdown's source: distinct useful patterns only (no silent 0/9, no 4≡3 dup).
        assertEquals(
            listOf(1, 2, 3, 5, 6, 7, 8),
            qhybrid.protocol.requests.fossil.notification.BuzzPatterns.RESERVED_PATTERNS.toList(),
        )
    }

    @Test
    fun eachReservedPatternDrivesPlayOnlyBuzz() {
        // The Buzz button calls vibrateWatch(selected) (play-only) for whatever reserved pattern
        // the dropdown selected — all 7 are already on the watch in the reserved filter.
        runBlocking { watchDao.upsert(watch("AA:00:00:00:00:01", active = true)) }
        for (p in qhybrid.protocol.requests.fossil.notification.BuzzPatterns.RESERVED_PATTERNS) {
            val vibe = FakeVibration(wired = true)
            val model = vm(vibration = vibe)
            awaitState(model.uiState) { it.hasActiveWatch }
            model.vibrateWatch(p)
            assertEquals(1, vibe.count)
            assertEquals(p, vibe.patterns.last())
            assertFalse("reserved buzz is play-only (no forced filter)", vibe.forceFilterFlags.last())
        }
    }

    @Test
    fun productionVibrationIsWired() {
        assertTrue(ServiceVibrationSync.VIBRATION_WIRED)
    }

    // ---- manual "Sync all" (WP-PULLSYNC) -------------------------------------

    @Test
    fun syncAllHitsSeamWithActiveWatch() {
        runBlocking { watchDao.upsert(watch("AA:00:00:00:00:01", active = true)) }
        val full = FakeFullSync(wired = true)
        val model = vm(fullSync = full)
        awaitState(model.uiState) { it.hasActiveWatch }
        assertTrue(model.syncAll())
        assertEquals(1, full.count)
    }

    @Test
    fun syncAllNoOpWithoutActiveWatch() {
        runBlocking { watchDao.upsert(watch("AA:00:00:00:00:01", active = false)) }
        val full = FakeFullSync()
        val model = vm(fullSync = full)
        awaitState(model.uiState) { !it.hasActiveWatch }
        assertFalse(model.syncAll())
        assertEquals(0, full.count)
    }

    // ---- remove / re-provision this watch (WP-WATCHADMIN) --------------------

    @Test
    fun removeActiveWatchHitsSeamWithActiveMac() {
        runBlocking { watchDao.upsert(watch("AA:00:00:00:00:01", active = true)) }
        val admin = FakeWatchAdmin(wired = true)
        val model = vm(watchAdmin = admin)
        awaitState(model.uiState) { it.hasActiveWatch }

        assertTrue(model.removeActiveWatch())
        assertEquals(1, admin.count)
        assertEquals("AA:00:00:00:00:01", admin.removed.last())
    }

    @Test
    fun removeActiveWatchNoOpWithoutActiveWatch() {
        runBlocking { watchDao.upsert(watch("AA:00:00:00:00:01", active = false)) }
        val admin = FakeWatchAdmin()
        val model = vm(watchAdmin = admin)
        awaitState(model.uiState) { !it.hasActiveWatch }
        assertFalse(model.removeActiveWatch())
        assertEquals(0, admin.count)
    }

    // ---- apply defaults to this watch (WP-DEFAULTS sub-part 3) ----------------

    @Test
    fun applyDefaultsHitsSeamWithActiveWatch() {
        runBlocking { watchDao.upsert(watch("AA:00:00:00:00:01", active = true)) }
        val apply = FakeApplyDefaults(wired = true)
        val model = vm(applyDefaults = apply)
        awaitState(model.uiState) { it.hasActiveWatch }
        assertTrue(model.applyDefaultsToActiveWatch())
        assertEquals(1, apply.count)
    }

    @Test
    fun applyDefaultsNoOpWithoutActiveWatch() {
        runBlocking { watchDao.upsert(watch("AA:00:00:00:00:01", active = false)) }
        val apply = FakeApplyDefaults()
        val model = vm(applyDefaults = apply)
        awaitState(model.uiState) { !it.hasActiveWatch }
        assertFalse(model.applyDefaultsToActiveWatch())
        assertEquals(0, apply.count)
    }

    // ---- clear all alarms (WP-CLEARALARMS) -----------------------------------

    @Test
    fun clearAlarmsHitsSeamWithActiveWatch() {
        runBlocking { watchDao.upsert(watch("AA:00:00:00:00:01", active = true)) }
        val clear = FakeClearAlarms(wired = true)
        val model = vm(clearAlarms = clear)
        awaitState(model.uiState) { it.hasActiveWatch }
        assertTrue(model.clearAlarmsOnActiveWatch())
        assertEquals(1, clear.count)
    }

    @Test
    fun clearAlarmsNoOpWithoutActiveWatch() {
        runBlocking { watchDao.upsert(watch("AA:00:00:00:00:01", active = false)) }
        val clear = FakeClearAlarms()
        val model = vm(clearAlarms = clear)
        awaitState(model.uiState) { !it.hasActiveWatch }
        assertFalse(model.clearAlarmsOnActiveWatch())
        assertEquals(0, clear.count)
    }

    @Test
    fun productionWatchAdminIsWired() {
        assertTrue(WatchAdminSync.WATCHADMIN_WIRED)
    }

    // ---- nudge: app pref + deferred live command -----------------------------

    @Test
    fun nudgeRoundTripsThroughPrefsAndHitsSync() {
        runBlocking { watchDao.upsert(watch("AA:00:00:00:00:01", active = true)) }
        val prefs = FakePrefs()
        val sync = FakeSync(wired = false)
        val model = vm(prefs = prefs, sync = sync)
        awaitState(model.uiState) { it.hasActiveWatch }

        val wired = model.setNudge(enabled = true, minutes = 45)
        assertEquals(1, prefs.nudgeWrites)
        assertTrue(prefs.current.nudgeEnabled)
        assertEquals(45, prefs.current.nudgeMinutes)
        // Live apply hit the fake + reports pending.
        assertEquals(1, sync.nudgeCount)
        assertEquals(true, sync.lastNudgeEnabled)
        assertEquals(45, sync.lastNudgeMinutes)
        assertFalse(wired)

        val s = awaitState(model.uiState) { it.nudgeEnabled && it.nudgeMinutes == 45 }
        assertTrue(s.nudgeEnabled)
        assertEquals(45, s.nudgeMinutes)
    }

    @Test
    fun nudgePersistsButDoesNotSyncWithoutActiveWatch() {
        runBlocking { watchDao.upsert(watch("AA:00:00:00:00:01", active = false)) }
        val prefs = FakePrefs()
        val sync = FakeSync()
        val model = vm(prefs = prefs, sync = sync)
        awaitState(model.uiState) { !it.hasActiveWatch }
        // Persists app-side regardless...
        assertFalse(model.setNudge(enabled = true, minutes = 9999))
        assertEquals(1, prefs.nudgeWrites)
        assertEquals(255, prefs.current.nudgeMinutes) // clamped
        // ...but no live command without an active watch.
        assertEquals(0, sync.nudgeCount)
    }

    // ---- second timezone: app pref + deferred live command -------------------

    @Test
    fun secondTimezoneRoundTripsAndHitsSync() {
        runBlocking { watchDao.upsert(watch("AA:00:00:00:00:01", active = true)) }
        val prefs = FakePrefs()
        val sync = FakeSync()
        val model = vm(prefs = prefs, sync = sync)
        awaitState(model.uiState) { it.hasActiveWatch }

        val wired = model.setSecondTimezoneOffset(330)
        assertEquals(1, prefs.tzWrites)
        assertEquals(330, prefs.current.secondTimezoneOffsetMinutes)
        assertEquals(1, sync.tzCount)
        assertEquals(330, sync.lastTz)
        assertFalse(wired)

        val s = awaitState(model.uiState) { it.secondTimezoneOffsetMinutes == 330 }
        assertEquals(330, s.secondTimezoneOffsetMinutes)
    }

    @Test
    fun secondTimezoneClampsOutOfRange() {
        runBlocking { watchDao.upsert(watch("AA:00:00:00:00:01", active = true)) }
        val prefs = FakePrefs()
        val model = vm(prefs = prefs)
        awaitState(model.uiState) { it.hasActiveWatch }
        model.setSecondTimezoneOffset(99999)
        val s = awaitState(model.uiState) {
            it.secondTimezoneOffsetMinutes == SettingsVocabulary.TZ_MAX_OFFSET_MINUTES
        }
        assertEquals(SettingsVocabulary.TZ_MAX_OFFSET_MINUTES, s.secondTimezoneOffsetMinutes)
    }

    // ---- preferred music app: pure app pref (never synced) -------------------

    @Test
    fun preferredMusicAppRoundTripsAndNeverSyncs() {
        runBlocking { watchDao.upsert(watch("AA:00:00:00:00:01", active = true)) }
        val prefs = FakePrefs()
        val sync = FakeSync()
        val model = vm(prefs = prefs, sync = sync)
        awaitState(model.uiState) { it.hasActiveWatch }

        model.setPreferredMusicApp("com.spotify.music")
        assertEquals(1, prefs.musicWrites)
        val s = awaitState(model.uiState) { it.preferredMusicApp == "com.spotify.music" }
        assertTrue(s.hasPreferredMusicApp)
        assertEquals("com.spotify.music", s.preferredMusicApp)
        // Music app is phone-side only — no live watch command ever fires.
        assertEquals(0, sync.vibeCount + sync.nudgeCount + sync.tzCount)

        // Clearing it returns to NONE.
        model.setPreferredMusicApp("  ")
        val cleared = awaitState(model.uiState) { !it.hasPreferredMusicApp }
        assertEquals(SettingsVocabulary.MUSIC_APP_NONE, cleared.preferredMusicApp)
    }

    // ---- multi-function role: pure GLOBAL app pref (never synced) ------------

    @Test
    fun multiFunctionRoleRoundTripsAndNeverSyncs() {
        runBlocking { watchDao.upsert(watch("AA:00:00:00:00:01", active = true)) }
        val prefs = FakePrefs()
        val sync = FakeSync()
        val model = vm(prefs = prefs, sync = sync)
        awaitState(model.uiState) { it.hasActiveWatch }
        // Default is MUSIC (preserves WP12).
        assertEquals(SettingsVocabulary.MULTI_FUNCTION_ROLE_MUSIC, model.uiState.value.multiFunctionRole)

        model.setMultiFunctionRole(SettingsVocabulary.MULTI_FUNCTION_ROLE_TRACKER)
        assertEquals(1, prefs.roleWrites)
        val s = awaitState(model.uiState) {
            it.multiFunctionRole == SettingsVocabulary.MULTI_FUNCTION_ROLE_TRACKER
        }
        assertEquals(SettingsVocabulary.MULTI_FUNCTION_ROLE_TRACKER, s.multiFunctionRole)
        // Pure phone-side — no live watch command ever fires.
        assertEquals(0, sync.vibeCount + sync.nudgeCount + sync.tzCount)

        // Unknown value folds back to the default MUSIC.
        model.setMultiFunctionRole("BOGUS")
        val back = awaitState(model.uiState) {
            it.multiFunctionRole == SettingsVocabulary.MULTI_FUNCTION_ROLE_MUSIC
        }
        assertEquals(SettingsVocabulary.MULTI_FUNCTION_ROLE_MUSIC, back.multiFunctionRole)
    }

    // ---- L0: configurable multi-function rotation ----------------------------

    @Test
    fun rotationSetToggleAndActiveIndex() {
        runBlocking { watchDao.upsert(watch("AA:00:00:00:00:01", active = true)) }
        val model = vm()
        awaitState(model.uiState) { it.hasActiveWatch }
        // Default rotation = phone media only.
        assertEquals(
            listOf(SettingsVocabulary.MODE_MUSIC_PHONE),
            model.uiState.value.multiFunctionRotation,
        )

        // Set a full rotation; first entry is active.
        model.setMultiFunctionRotation(
            listOf(
                SettingsVocabulary.MODE_MUSIC_LYRION,
                SettingsVocabulary.MODE_MUSIC_PHONE,
                SettingsVocabulary.MODE_TRACKER,
            )
        )
        val s = awaitState(model.uiState) { it.multiFunctionRotation.size == 3 }
        assertEquals(SettingsVocabulary.MODE_MUSIC_LYRION, s.activeMode)
        assertEquals(0, s.multiFunctionActiveIndex)
        assertTrue(s.lyrionInRotation)

        // Advancing the active index moves through the rotation.
        model.setMultiFunctionActiveIndex(2)
        val t = awaitState(model.uiState) { it.multiFunctionActiveIndex == 2 }
        assertEquals(SettingsVocabulary.MODE_TRACKER, t.activeMode)

        // Toggling a present mode removes it (and resets index to 0).
        model.toggleMultiFunctionMode(SettingsVocabulary.MODE_TRACKER)
        val u = awaitState(model.uiState) { !it.multiFunctionRotation.contains(SettingsVocabulary.MODE_TRACKER) }
        assertEquals(2, u.multiFunctionRotation.size)
        assertEquals(0, u.multiFunctionActiveIndex)
    }

    @Test
    fun rotationNeverEmpty() {
        runBlocking { watchDao.upsert(watch("AA:00:00:00:00:01", active = true)) }
        val model = vm()
        awaitState(model.uiState) { it.hasActiveWatch }
        // Start from a single mode; toggling it off is refused (can't empty the rotation).
        model.setMultiFunctionRotation(listOf(SettingsVocabulary.MODE_MUSIC_PHONE))
        awaitState(model.uiState) { it.multiFunctionRotation.size == 1 }
        model.toggleMultiFunctionMode(SettingsVocabulary.MODE_MUSIC_PHONE)
        assertEquals(
            listOf(SettingsVocabulary.MODE_MUSIC_PHONE),
            model.uiState.value.multiFunctionRotation,
        )
    }

    // ---- L1: Lyrion config round-trips (pure app-side) -----------------------

    @Test
    fun lyrionConfigRoundTrips() {
        runBlocking { watchDao.upsert(watch("AA:00:00:00:00:01", active = true)) }
        val sync = FakeSync()
        val model = vm(sync = sync)
        awaitState(model.uiState) { it.hasActiveWatch }

        model.setLyrionServer(" 192.168.1.10 ", 9090)
        val s1 = awaitState(model.uiState) { it.lyrionServerHost == "192.168.1.10" }
        assertEquals(9090, s1.lyrionServerPort)

        model.setLyrionPlayer(" 00:04:20:aa:bb:cc ", " Kitchen ")
        val s2 = awaitState(model.uiState) { it.hasLyrionPlayer }
        assertEquals("00:04:20:aa:bb:cc", s2.lyrionPlayerId)
        assertEquals("Kitchen", s2.lyrionPlayerName)

        model.setLyrionEmptyQueueFallback("random")
        val s3 = awaitState(model.uiState) {
            it.lyrionEmptyQueueFallback == SettingsVocabulary.LYRION_FALLBACK_RANDOM
        }
        assertEquals(SettingsVocabulary.LYRION_FALLBACK_RANDOM, s3.lyrionEmptyQueueFallback)

        model.setLyrionFavoriteId(" fav42 ")
        val s4 = awaitState(model.uiState) { it.lyrionFavoriteId == "fav42" }
        assertEquals("fav42", s4.lyrionFavoriteId)

        // Pure phone-side — no live watch command ever fires.
        assertEquals(0, sync.vibeCount + sync.nudgeCount + sync.tzCount)
    }

    // ---- L6/L7: Lyrion network pickers (players / favourites / discovery) -----

    @Test
    fun loadLyrionPlayersPopulatesPicker() {
        runBlocking { watchDao.upsert(watch("AA:00:00:00:00:01", active = true)) }
        val discovery = FakeDiscovery(
            players = listOf(
                qhybrid.android.music.lyrion.LyrionCommands.LyrionPlayer("00:04:20:aa:bb:cc", "Kitchen"),
                qhybrid.android.music.lyrion.LyrionCommands.LyrionPlayer("00:04:20:dd:ee:ff", "Office"),
            )
        )
        val model = vm(discovery = discovery)
        awaitState(model.uiState) { it.hasActiveWatch }
        // Blank host -> no-op.
        model.loadLyrionPlayers("", 9000)
        assertTrue(model.uiState.value.lyrionPlayers.isEmpty())
        // Passing the live host auto-saves it AND populates the picker (no "Save server" needed).
        model.loadLyrionPlayers("192.168.1.10", 9000)
        val s = awaitState(model.uiState) { it.lyrionPlayers.size == 2 }
        assertEquals("Kitchen", s.lyrionPlayers[0].name)
        assertEquals("192.168.1.10", s.lyrionServerHost) // auto-saved
        assertFalse(s.lyrionLoading)
    }

    @Test
    fun loadLyrionFavoritesPopulatesPicker() {
        runBlocking { watchDao.upsert(watch("AA:00:00:00:00:01", active = true)) }
        val discovery = FakeDiscovery(
            favorites = listOf(
                qhybrid.android.music.lyrion.LyrionCommands.LyrionFavorite("fav.1", "Morning Mix"),
            )
        )
        val model = vm(discovery = discovery)
        awaitState(model.uiState) { it.hasActiveWatch }
        model.loadLyrionFavorites("192.168.1.10", 9000)
        val s = awaitState(model.uiState) { it.lyrionFavorites.size == 1 }
        assertEquals("Morning Mix", s.lyrionFavorites[0].name)
        assertEquals("192.168.1.10", s.lyrionServerHost)
    }

    @Test
    fun discoverLyrionServersPopulatesList() {
        runBlocking { watchDao.upsert(watch("AA:00:00:00:00:01", active = true)) }
        val discovery = FakeDiscovery(
            servers = listOf(
                qhybrid.android.music.lyrion.LyrionDiscoveryCodec.DiscoveredServer(
                    name = "Living Room (192.168.1.10)", jsonPort = 9000, host = "192.168.1.10",
                ),
            )
        )
        val model = vm(discovery = discovery)
        awaitState(model.uiState) { it.hasActiveWatch }
        model.discoverLyrionServers()
        val s = awaitState(model.uiState) { it.lyrionDiscoveredServers.size == 1 }
        assertEquals("192.168.1.10", s.lyrionDiscoveredServers[0].host)
        assertEquals(9000, s.lyrionDiscoveredServers[0].jsonPort)
    }

    @Test
    fun loadInstalledAppsReusesProvider() {
        runBlocking { watchDao.upsert(watch("AA:00:00:00:00:01", active = true)) }
        val apps = listOf(
            InstalledApp("com.spotify.music", "Spotify"),
            InstalledApp("com.bandcamp", "Bandcamp"),
        )
        val model = vm(apps = FakeAppsProvider(apps))
        awaitState(model.uiState) { it.hasActiveWatch }
        model.loadInstalledApps()
        val s = awaitState(model.uiState) { it.installedApps.isNotEmpty() }
        assertEquals(2, s.installedApps.size)
        assertEquals("com.spotify.music", s.installedApps.first().packageName)
    }

    // ---- settings transfer hits the WP4 surface ------------------------------

    @Test
    fun settingsTransferHitsWp4Surface() {
        // Seed source watch A with one alarm/rule/button; create target watch B.
        runBlocking {
            watchDao.upsert(watch("AA:00:00:00:00:0A", name = "A", active = true))
            watchDao.upsert(watch("BB:00:00:00:00:0B", name = "B", active = false))
            alarmDao.upsert(alarm("AA:00:00:00:00:0A", slot = 0))
            ruleDao.upsert(rule("AA:00:00:00:00:0A", pkg = "com.whatsapp"))
            buttonDao.upsert(button("AA:00:00:00:00:0A", buttonId = 0x10))
        }
        val model = vm()
        awaitState(model.uiState) { it.hasActiveWatch }

        val dispatched = model.transferSettings("AA:00:00:00:00:0A", "BB:00:00:00:00:0B")
        assertTrue(dispatched)

        // The WP4 transfer copied the rows onto B (source untouched).
        runBlocking {
            withTimeout(5_000) {
                // Poll until the copy lands (transfer runs on the VM scope).
                var copied = false
                repeat(50) {
                    val bAlarms = alarmDao.getForWatch("BB:00:00:00:00:0B")
                    if (bAlarms.isNotEmpty()) { copied = true; return@repeat }
                    kotlinx.coroutines.delay(20)
                }
                assertTrue("transfer should copy alarms onto B", copied)
            }
            assertEquals(1, alarmDao.getForWatch("BB:00:00:00:00:0B").size)
            assertEquals(1, ruleDao.getForWatch("BB:00:00:00:00:0B").size)
            assertEquals(1, buttonDao.getForWatch("BB:00:00:00:00:0B").size)
            // Source A is unchanged.
            assertEquals(1, alarmDao.getForWatch("AA:00:00:00:00:0A").size)
        }
    }

    @Test
    fun settingsTransferNoOpForBlankOrIdenticalPair() {
        runBlocking { watchDao.upsert(watch("AA:00:00:00:00:01", active = true)) }
        val model = vm()
        awaitState(model.uiState) { it.hasActiveWatch }
        assertFalse(model.transferSettings("", "BB:00:00:00:00:02"))
        assertFalse(model.transferSettings("AA:00:00:00:00:01", "  "))
        assertFalse(model.transferSettings("AA:00:00:00:00:01", "aa:00:00:00:00:01")) // same MAC
    }

    // ---- empty/partial tolerance ---------------------------------------------

    // ---- calendar alarm ring offset (WP13 — app pref + refresh trigger) ------

    @Test
    fun calendarOffsetRoundTripsThroughPrefsAndTriggersRefresh() {
        val prefs = FakePrefs()
        var refreshes = 0
        val model = vm(prefs = prefs, calendarRefresh = { refreshes++ })
        awaitState(model.uiState) { true }

        assertTrue(model.setCalendarAlarmOffset(15))
        assertEquals(1, prefs.calOffsetWrites)
        assertEquals(15, prefs.current.calendarAlarmOffsetMinutes)
        assertEquals(1, refreshes) // changing the offset re-maps + re-pushes the calendar slots

        val s = awaitState(model.uiState) { it.calendarAlarmOffsetMinutes == 15 }
        assertEquals(15, s.calendarAlarmOffsetMinutes)
    }

    @Test
    fun resyncCalendar_triggersRefresh() {
        var refreshes = 0
        val model = vm(calendarRefresh = { refreshes++ })
        awaitState(model.uiState) { true }
        assertTrue(model.resyncCalendar())
        assertEquals(1, refreshes)
    }

    @Test
    fun calendarOffsetClampedToRange() {
        val prefs = FakePrefs()
        val model = vm(prefs = prefs)
        awaitState(model.uiState) { true }
        model.setCalendarAlarmOffset(9999)
        assertEquals(SettingsVocabulary.CAL_OFFSET_MAX_MINUTES, prefs.current.calendarAlarmOffsetMinutes)
        model.setCalendarAlarmOffset(-5)
        assertEquals(SettingsVocabulary.CAL_OFFSET_MIN_MINUTES, prefs.current.calendarAlarmOffsetMinutes)
    }

    @Test
    fun calendarOffsetDefaultIsOneMinute() {
        val model = vm(prefs = FakePrefs())
        val s = awaitState(model.uiState) { true }
        assertEquals(1, s.calendarAlarmOffsetMinutes)
        assertEquals(1, SettingsVocabulary.CAL_OFFSET_DEFAULT_MINUTES)
    }

    @Test
    fun emptyDbNoCrash() {
        val prefs = FakePrefs()
        val model = vm(prefs = prefs)
        val s = awaitState(model.uiState) { true }
        assertNull(s.activeWatch)
        assertFalse(s.hasActiveWatch)
        assertEquals(SettingsVocabulary.VIBE_DEFAULT, s.vibrationStrength)
        assertEquals(SettingsVocabulary.NUDGE_DEFAULT_MINUTES, s.nudgeMinutes)
        assertEquals(SettingsVocabulary.MUSIC_APP_NONE, s.preferredMusicApp)
    }

    @Test
    fun productionSettingsApplyIsWired() {
        // WP14 sub-part 4: the live vibration / nudge / second-timezone commands
        // (persist-then-sync through the WP3 service's SyncOrchestrator) are wired.
        assertTrue(ServiceSettingsSync.SETTINGS_WIRED)
    }

    @Test
    fun syncProgressReflectsSyncingThenSuccess() {
        // WP-PROGRESS: each setting apply pokes a sync; the status-row progress flow maps it.
        val source = FakeSyncStateSource()
        val model = vm(syncSource = source)
        assertFalse(model.syncProgress.value.syncing)

        source.set(SyncState.SyncPhase.SYNCING)
        val syncing = runBlocking {
            withTimeout(5_000) { model.syncProgress.first { it.syncing } }
        }
        assertTrue(syncing.syncing)

        source.set(
            SyncState.SyncPhase.SUCCESS,
            result = qhybrid.android.sync.SyncResult(
                "AA:00:00:00:00:01",
                listOf(qhybrid.android.sync.SyncSection.VIBRATION),
                emptyList(),
                emptyList(),
            ),
        )
        val done = runBlocking {
            withTimeout(5_000) { model.syncProgress.first { it.tone == SyncProgressUi.Tone.SUCCESS } }
        }
        assertFalse(done.syncing)
    }
}
