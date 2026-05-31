package qhybrid.android.navcue

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import net.osmand.aidlapi.IOsmAndAidlCallback
import net.osmand.aidlapi.IOsmAndAidlInterface
import net.osmand.aidlapi.navigation.ADirectionInfo
import net.osmand.aidlapi.navigation.ANavigationUpdateParams

/**
 * WP-NAV — the V2 backend that speaks the `net.osmand.aidlapi.IOsmAndAidlInterface` binder.
 *
 * **Key fact (verified against OsmAnd source + on-device):** the shipped OsmAnd/OsmAnd+ service is
 * named `net.osmand.aidl.OsmandAidlServiceV2` (the OLD `aidl` package/action), but its `onBind`
 * returns a `net.osmand.aidlapi.IOsmAndAidlInterface.Stub` — i.e. it is bound via the legacy action
 * yet serves the NEW `aidlapi` interface descriptor. So this backend binds the LEGACY action/class
 * but uses the `aidlapi` stubs. (Binding with the legacy interface gave
 * "Binder invocation to an incorrect interface"; this is the correct pairing.) Everything funnels
 * through the shared [NavCueFeed].
 */
class AidlApiV2NavSource(
    context: Context,
    private val dispatcher: NavCueDispatcher,
) : NavUpdateSource {
    private val appContext = context.applicationContext

    @Volatile private var service: IOsmAndAidlInterface? = null
    @Volatile private var navCallbackId: Long = -1
    @Volatile private var started = false
    @Volatile private var boundPackage: String? = null

    override val id: String get() = "aidlapi-iface"

    override fun start(pkg: String): Boolean {
        if (started) return true
        boundPackage = pkg
        val ok = OsmAndBind.tryBind(appContext, pkg, ACTION, CLASS, connection, id)
        if (ok) started = true
        return ok
    }

    override fun stop() {
        if (!started) return
        unregister()
        runCatching { appContext.unbindService(connection) }
            .onFailure { Log.w(TAG, "unbind failed", it) }
        service = null
        started = false
    }

    private fun register() {
        val svc = service ?: return
        runCatching {
            val params = ANavigationUpdateParams().apply { setSubscribeToUpdates(true) }
            navCallbackId = svc.registerForNavigationUpdates(params, callback)
            NavCueDiagnostics.onRegistered(navCallbackId)
        }.onFailure {
            NavCueDiagnostics.error(NavCueDiagnostics.Stage.SOURCE, "[aidlapi] registerForNavigationUpdates failed: ${it.message}")
        }
    }

    private fun unregister() {
        val svc = service ?: return
        if (navCallbackId < 0) return
        runCatching {
            val params = ANavigationUpdateParams().apply {
                setSubscribeToUpdates(false)
                setCallbackId(navCallbackId)
            }
            svc.registerForNavigationUpdates(params, callback)
        }.onFailure { Log.w(TAG, "[aidlapi] unregister failed", it) }
        navCallbackId = -1
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = IOsmAndAidlInterface.Stub.asInterface(binder)
            NavCueDiagnostics.onBound(boundPackage, bound = true)
            register()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            NavCueDiagnostics.onBound(boundPackage, bound = false)
            service = null
            navCallbackId = -1
        }
    }

    private val callback = object : IOsmAndAidlCallback.Stub() {
        override fun updateNavigationInfo(directionInfo: ADirectionInfo?) {
            val info = directionInfo ?: run {
                NavCueDiagnostics.warn(NavCueDiagnostics.Stage.RAW, "[aidlapi] updateNavigationInfo(null)")
                return
            }
            NavCueFeed.onRaw(dispatcher, info.turnType, info.distanceTo, info.isLeftSide)
        }

        override fun onSearchComplete(resultSet: MutableList<net.osmand.aidlapi.search.SearchResult>?) {}
        override fun onUpdate() {}
        override fun onAppInitialized() {}
        override fun onGpxBitmapCreated(bitmap: net.osmand.aidlapi.gpx.AGpxBitmap?) {}
        override fun onContextMenuButtonClicked(buttonId: Int, pointId: String?, layerId: String?) {}
        override fun onVoiceRouterNotify(params: net.osmand.aidlapi.navigation.OnVoiceNavigationParams?) {}
        override fun onKeyEvent(params: android.view.KeyEvent?) {}
        override fun onLogcatMessage(params: net.osmand.aidlapi.logcat.OnLogcatMessageParams?) {}
    }

    companion object {
        private const val TAG = "FossilQ-NavCue"
        // The shipped service is named in the OLD `aidl` package even though it serves the
        // `aidlapi` interface (see class doc). Bind THIS action/class with the `aidlapi` stubs.
        const val ACTION = "net.osmand.aidl.OsmandAidlServiceV2"
        const val CLASS = "net.osmand.aidl.OsmandAidlServiceV2"
    }
}
