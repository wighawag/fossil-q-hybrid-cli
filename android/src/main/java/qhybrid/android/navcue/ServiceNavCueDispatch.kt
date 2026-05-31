package qhybrid.android.navcue

import android.content.Context
import android.os.SystemClock
import android.util.Log
import qhybrid.android.WatchConnectionService
import qhybrid.android.settings.SharedPreferencesSettingsPrefs
import qhybrid.android.settings.SettingsVocabulary

/**
 * WP-NAV — the production Android shell that turns OsmAnd's turn-by-turn stream into watch cues
 * (buzz + point both hands). Mirrors WP-TRACKER's [qhybrid.android.tracker.ServiceTrackerDispatch]:
 * it owns the pure [NavCueDispatcher] over a [SystemNavCueEffects] seam, and an [OsmAndNavSource]
 * that binds OsmAnd / OsmAnd+ and feeds the dispatcher.
 *
 * **Activation:** [start] is a no-op unless the GLOBAL
 * [qhybrid.android.settings.AppSettings.navCueEnabled] toggle is on. When it is, it binds the
 * best-available OsmAnd flavour and begins cueing; [stop] unbinds. The thresholds come from the
 * Settings prefs (soon/now distances), so the user-tunable timing flows straight into the pure
 * dispatcher's [NavCueDispatcher.Config].
 *
 * **No new wire bytes, no GMS, no Google API key.** The cue is `FossilController.buzz(pattern,h,m)`
 * via [WatchConnectionService.navCueNow]; the nav data is OsmAnd's vendored AIDL.
 */
class ServiceNavCueDispatch(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = SharedPreferencesSettingsPrefs(appContext)

    private val effects = SystemNavCueEffects()

    // Built lazily on start() from the current prefs so threshold edits take effect on next start.
    @Volatile private var dispatcher: NavCueDispatcher? = null
    @Volatile private var source: OsmAndNavSource? = null
    @Volatile private var running = false

    /** True iff the global toggle is on (read fresh). The hardware flag is checked in [start]. */
    fun isEnabled(): Boolean = prefs.get().navCueEnabled

    /** True iff some OsmAnd flavour is installed (so the feature can actually source turns). */
    fun isOsmAndInstalled(): Boolean =
        OsmAndNavSource.OSMAND_PACKAGES.any { pkg ->
            runCatching { appContext.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)
        }

    /**
     * Begin nav cues if enabled + wired. Returns true if cueing started, false otherwise (toggle
     * off, not wired yet, or no OsmAnd installed). Idempotent. Safe to call on app/service start
     * and whenever the toggle changes.
     */
    fun start(): Boolean {
        if (running) return true
        val s = prefs.get()
        NavCueDiagnostics.onLifecycle(
            "start() requested", enabled = s.navCueEnabled, osmAndInstalled = isOsmAndInstalled(),
        )
        if (!s.navCueEnabled) {
            Log.i(TAG, "nav cues not started: toggle off")
            NavCueDiagnostics.warn(NavCueDiagnostics.Stage.LIFECYCLE, "not started: toggle is OFF")
            return false
        }
        val config = NavCueDispatcher.Config(
            soonMeters = SettingsVocabulary.normalizeNavCueSoonMeters(s.navCueSoonMeters),
            nowMeters = SettingsVocabulary.normalizeNavCueNowMeters(s.navCueNowMeters),
        )
        val disp = NavCueDispatcher(effects, config, clock = { SystemClock.elapsedRealtime() })
        val backend = NavCueBackend.parse(s.navCueBackend)
        val src = OsmAndNavSource(appContext, disp, backend)
        dispatcher = disp
        source = src
        // WP-NAV: the RESERVED nav-cue filter entries reach the watch via any NOTIFICATION_FILTER
        // sync (the uploader folds NavCuePatterns.reservedEntries() in, like the reserved buzz
        // entries) or new-watch provisioning — so no special push is needed here. If cues silently
        // no-op on an older watch (provisioned before this feature), just (re)save the notification
        // filter once from the Notifications screen and the reserved entries upload with it.
        val ok = src.start()
        if (ok) {
            running = true
            Log.i(TAG, "nav cues started (OsmAnd=${src.activePackage}, soon=${config.soonMeters}m now=${config.nowMeters}m)")
            NavCueDiagnostics.info(
                NavCueDiagnostics.Stage.LIFECYCLE,
                "started: thresholds soon=${config.soonMeters}m now=${config.nowMeters}m",
            )
        } else {
            Log.w(TAG, "nav cues could not start (OsmAnd not installed / bind failed)")
            NavCueDiagnostics.warn(NavCueDiagnostics.Stage.LIFECYCLE, "could not start (OsmAnd not installed / bind failed)")
            dispatcher = null
            source = null
        }
        return ok
    }

    /** Stop nav cues + unbind OsmAnd. Idempotent. */
    fun stop() {
        if (!running) return
        runCatching { source?.stop() }.onFailure { Log.w(TAG, "source.stop failed", it) }
        source = null
        dispatcher = null
        running = false
        Log.i(TAG, "nav cues stopped")
        NavCueDiagnostics.onLifecycle("stopped")
    }

    /**
     * WP-NAV (diagnostics) — fire ONE test cue NOW (a given direction at the NOW stage), bypassing
     * OsmAnd, so the watch-side path can be verified independently. Used by the diagnostics screen's
     * "Send test cue" buttons. Logs the decision + the watch send like a real cue.
     */
    fun sendTestCue(maneuver: TurnCueMapper.Maneuver) {
        val cue = TurnCueMapper.decide(maneuver, TurnCueMapper.Stage.NOW)
        if (cue == null) {
            NavCueDiagnostics.warn(NavCueDiagnostics.Stage.DECISION, "test cue: $maneuver has no pose (no-op)")
            return
        }
        NavCueDiagnostics.onDecision("TEST $maneuver → hands ${cue.hourDeg}° vibe ${cue.buzzPattern}", isCue = true)
        effects.buzzAndPoint(cue.hourDeg, cue.minDeg, cue.buzzPattern)
    }

    /** Re-read the toggle/thresholds: stop then (if still enabled) start with fresh config. */
    fun refresh() {
        stop()
        start()
    }

    /**
     * Production [NavCueEffects] — one cue = one [WatchConnectionService.navCueNow] (buzz + point
     * both hands), the existing golden `FossilController.buzz(pattern,h,m)` path. Best-effort +
     * silent; dropped if the link is down (a turn cue is useless late).
     */
    private inner class SystemNavCueEffects : NavCueEffects {
        override fun buzzAndPoint(hourDeg: Int, minDeg: Int, pattern: Int) {
            WatchConnectionService.navCueNow(appContext, pattern, hourDeg, minDeg)
        }
    }

    private companion object {
        private const val TAG = "FossilQ-NavCue"
    }
}
