package qhybrid.android.navcue

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.util.Log

/**
 * WP-NAV — shared OsmAnd service-bind helper used by both AIDL backends. `bindService` returns
 * **false** (rather than throwing) when nothing resolves — typically the Android 11+
 * package-visibility filter or a wrong action/component. So we try several intent shapes and log
 * each attempt + result to the diagnostics screen.
 */
object OsmAndBind {
    private const val TAG = "FossilQ-NavCue"

    /**
     * Try to bind [pkg]'s service for a backend, trying (in order) an explicit component, an
     * action+package intent, and an action-as-component intent. Returns true iff some attempt's
     * `bindService` returned true (the [connection] will then receive `onServiceConnected`).
     */
    fun tryBind(
        context: Context,
        pkg: String,
        action: String,
        serviceClass: String,
        connection: ServiceConnection,
        backendId: String,
    ): Boolean {
        val attempts = listOf(
            "explicit ($pkg/$serviceClass)" to
                Intent().apply { component = ComponentName(pkg, serviceClass) },
            "action+pkg ($action @ $pkg)" to
                Intent(action).apply { setPackage(pkg) },
            "action-as-component ($pkg/$action)" to
                Intent().apply { component = ComponentName(pkg, action) },
        )
        for ((desc, intent) in attempts) {
            val ok = try {
                context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            } catch (e: SecurityException) {
                NavCueDiagnostics.warn(NavCueDiagnostics.Stage.SOURCE, "[$backendId] bind denied via $desc: ${e.message}")
                false
            }
            NavCueDiagnostics.info(NavCueDiagnostics.Stage.SOURCE, "[$backendId] bind try [$desc] → $ok")
            if (ok) {
                Log.i(TAG, "[$backendId] bound via $desc")
                return true
            }
            // A false return still registers the connection — clear it before the next attempt.
            runCatching { context.unbindService(connection) }
        }
        NavCueDiagnostics.warn(NavCueDiagnostics.Stage.SOURCE, "[$backendId] all bind attempts to $pkg failed")
        return false
    }
}
