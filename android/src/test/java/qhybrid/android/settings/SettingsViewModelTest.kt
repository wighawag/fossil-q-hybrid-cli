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

    private class FakeAppsProvider(private val apps: List<InstalledApp>) : InstalledAppsProvider {
        override fun installedApps(): List<InstalledApp> = apps
    }

    private class FakeVibration(private val wired: Boolean = true) : VibrationSync {
        var count = 0
        val patterns = mutableListOf<Int>()
        override fun buzz(pattern: Int): Boolean {
            count++; patterns.add(pattern); return wired
        }
    }

    private class FakeFullSync(private val wired: Boolean = true) : FullSync {
        var count = 0
        override fun syncAll(): Boolean { count++; return wired }
    }

    private fun vm(
        prefs: SettingsPrefs = FakePrefs(),
        sync: SettingsSync = FakeSync(),
        apps: InstalledAppsProvider = FakeAppsProvider(emptyList()),
        syncSource: FakeSyncStateSource = FakeSyncStateSource(),
        vibration: VibrationSync = FakeVibration(),
        fullSync: FullSync = FakeFullSync(),
    ) = SettingsViewModel(repo, prefs, sync, apps, vibration, fullSync, vmScope, syncSource)

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
