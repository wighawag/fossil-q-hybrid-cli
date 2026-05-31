package qhybrid.android.navcue

import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log

/**
 * WP-NAV — the coordinator that binds OsmAnd's AIDL service and feeds nav updates to a
 * [NavCueDispatcher], supporting BOTH AIDL namespaces that different OsmAnd builds expose:
 *   - [AidlLegacyNavSource] — `net.osmand.aidl.*` (the namespace many shipping OsmAnd/OsmAnd+ builds
 *     actually serve; verified on-device),
 *   - [AidlApiV2NavSource] — `net.osmand.aidlapi.*` (the newer namespace).
 *
 * **Backend selection ([NavCueBackend], a config knob on the diagnostics screen):**
 *   - [NavCueBackend.AUTO] (default) — try each backend in turn; the first whose `bindService`
 *     succeeds wins. Order: legacy `aidl` first (most common on real devices), then `aidlapi`.
 *   - [NavCueBackend.AIDL_LEGACY] / [NavCueBackend.AIDLAPI_V2] — force one backend.
 *
 * **Multi-flavour bind:** OsmAnd ships under several package names — we use the first installed of
 * `net.osmand.plus` (OsmAnd+), `net.osmand`, `net.osmand.dev`.
 *
 * **Lifecycle:** [start] picks a package + backend(s) and binds; [stop] unbinds. The dispatcher is
 * [NavCueDispatcher.reset] on each start so a new route cues cleanly.
 *
 * **No GMS, no Google API key, no remote dependency** — both AIDL surfaces are vendored.
 */
class OsmAndNavSource(
    context: Context,
    private val dispatcher: NavCueDispatcher,
    private val backendPref: NavCueBackend = NavCueBackend.AUTO,
) {
    private val appContext = context.applicationContext

    @Volatile private var active: NavUpdateSource? = null
    @Volatile private var boundPackage: String? = null
    @Volatile private var started = false

    /** The OsmAnd flavour we bound (for logging/UI), or null if not bound. */
    val activePackage: String? get() = boundPackage

    /** Which OsmAnd flavour (if any) is installed, in our preference order; null if none. */
    fun installedOsmAndPackage(): String? = OSMAND_PACKAGES.firstOrNull { isPackageInstalled(it) }

    /** True iff some OsmAnd flavour is installed (so the feature can work at all). */
    fun isOsmAndInstalled(): Boolean = installedOsmAndPackage() != null

    /**
     * Bind the best-available OsmAnd flavour using the configured backend(s). Returns true iff a
     * backend's `bindService` was accepted (the actual connection + registration happen async and
     * are reported via [NavCueDiagnostics]). Idempotent.
     */
    fun start(): Boolean {
        if (started) return true
        val pkg = installedOsmAndPackage()
        if (pkg == null) {
            NavCueDiagnostics.warn(
                NavCueDiagnostics.Stage.SOURCE,
                "no OsmAnd flavour installed (${OSMAND_PACKAGES.joinToString()})",
            )
            return false
        }
        dispatcher.reset()
        NavCueDiagnostics.info(NavCueDiagnostics.Stage.SOURCE, "backend pref = $backendPref, package = $pkg")

        for (backend in backendPref.candidates()) {
            val src = backend.create(appContext, dispatcher)
            if (src.start(pkg)) {
                active = src
                boundPackage = pkg
                started = true
                NavCueDiagnostics.info(NavCueDiagnostics.Stage.SOURCE, "using backend: ${src.id} ($pkg)")
                Log.i(TAG, "nav source bound: ${src.id} ($pkg)")
                return true
            }
            // This backend's bind failed; try the next candidate.
        }
        NavCueDiagnostics.warn(NavCueDiagnostics.Stage.SOURCE, "no backend could bind $pkg")
        return false
    }

    /** Unbind. Idempotent. */
    fun stop() {
        if (!started) return
        runCatching { active?.stop() }.onFailure { Log.w(TAG, "stop failed", it) }
        active = null
        boundPackage = null
        started = false
        Log.i(TAG, "stopped OsmAnd nav cues")
    }

    private fun isPackageInstalled(pkg: String): Boolean = try {
        appContext.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    companion object {
        private const val TAG = "FossilQ-NavCue"

        /** OsmAnd flavours we try to bind, in preference order (OsmAnd+ first — the user's app). */
        val OSMAND_PACKAGES = listOf("net.osmand.plus", "net.osmand", "net.osmand.dev")

        /** Capture a monotonic timestamp for the dispatcher clock. */
        fun nowMs(): Long = SystemClock.elapsedRealtime()
    }
}

/**
 * WP-NAV — which OsmAnd AIDL namespace/backend to use. Persisted as a string pref; selectable on the
 * diagnostics screen. [AUTO] probes both (legacy first), so it works whatever a given OsmAnd build
 * exposes.
 */
enum class NavCueBackend {
    AUTO,
    AIDLAPI_IFACE,
    AIDL_LEGACY;

    /**
     * The ordered backend candidates this preference will try. Both bind the SAME shipped service
     * (`net.osmand.aidl.OsmandAidlServiceV2`); they differ only in the BINDER INTERFACE they expect.
     * AUTO tries the modern `aidlapi` interface FIRST (what current OsmAnd serves) then the legacy
     * interface (very old builds). NOTE: today AUTO selects on bindService success, which both
     * achieve; the correct one is confirmed only when registration succeeds. If AUTO lands on the
     * wrong one (see "incorrect interface" in the log), force [AIDLAPI_IFACE] or [AIDL_LEGACY].
     */
    fun candidates(): List<NavCueBackend> = when (this) {
        AUTO -> listOf(AIDLAPI_IFACE, AIDL_LEGACY)
        AIDLAPI_IFACE -> listOf(AIDLAPI_IFACE)
        AIDL_LEGACY -> listOf(AIDL_LEGACY)
    }

    /** Instantiate the concrete [NavUpdateSource] for this backend (AUTO must not reach here). */
    fun create(context: Context, dispatcher: NavCueDispatcher): NavUpdateSource = when (this) {
        AIDLAPI_IFACE -> AidlApiV2NavSource(context, dispatcher)
        AIDL_LEGACY -> AidlLegacyNavSource(context, dispatcher)
        AUTO -> error("AUTO is not a concrete backend")
    }

    /** Human label for the diagnostics dropdown. */
    fun label(): String = when (this) {
        AUTO -> "Auto"
        AIDLAPI_IFACE -> "aidlapi interface"
        AIDL_LEGACY -> "legacy interface"
    }

    companion object {
        /** Parse a persisted name; unknown/blank → [AUTO]. Never throws. */
        fun parse(name: String?): NavCueBackend =
            entries.firstOrNull { it.name == name?.trim()?.uppercase() } ?: AUTO
    }
}
