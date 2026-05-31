package qhybrid.android.navcue

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import net.osmand.aidl.IOsmAndAidlCallback
import net.osmand.aidl.IOsmAndAidlInterface
import net.osmand.aidl.navigation.ADirectionInfo
import net.osmand.aidl.navigation.ANavigationUpdateParams

/**
 * WP-NAV — the LEGACY `net.osmand.aidl.*` backend. This is the namespace the currently-shipping
 * OsmAnd / OsmAnd+ actually exposes on many devices (verified on-device: the service is
 * `net.osmand.aidl.OsmandAidlServiceV2`, action `net.osmand.aidl.OsmandAidlServiceV2`). Uses the
 * legacy vendored AIDL stubs; everything funnels through the shared [NavCueFeed].
 */
class AidlLegacyNavSource(
    context: Context,
    private val dispatcher: NavCueDispatcher,
) : NavUpdateSource {
    private val appContext = context.applicationContext

    @Volatile private var service: IOsmAndAidlInterface? = null
    @Volatile private var navCallbackId: Long = -1
    @Volatile private var started = false
    @Volatile private var boundPackage: String? = null

    override val id: String get() = "aidl-v2 (legacy)"

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
            NavCueDiagnostics.error(NavCueDiagnostics.Stage.SOURCE, "[legacy] registerForNavigationUpdates failed: ${it.message}")
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
        }.onFailure { Log.w(TAG, "[legacy] unregister failed", it) }
        navCallbackId = -1
    }

    private val connection = object : android.content.ServiceConnection {
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
        @Throws(RemoteException::class)
        override fun updateNavigationInfo(directionInfo: ADirectionInfo?) {
            val info = directionInfo ?: run {
                NavCueDiagnostics.warn(NavCueDiagnostics.Stage.RAW, "[legacy] updateNavigationInfo(null)")
                return
            }
            NavCueFeed.onRaw(dispatcher, info.turnType, info.distanceTo, info.isLeftSide)
        }

        override fun onSearchComplete(resultSet: MutableList<net.osmand.aidl.search.SearchResult>?) {}
        override fun onUpdate() {}
        override fun onAppInitialized() {}
        override fun onGpxBitmapCreated(bitmap: net.osmand.aidl.gpx.AGpxBitmap?) {}
        override fun onContextMenuButtonClicked(buttonId: Int, pointId: String?, layerId: String?) {}
        override fun onVoiceRouterNotify(params: net.osmand.aidl.navigation.OnVoiceNavigationParams?) {}
    }

    companion object {
        private const val TAG = "FossilQ-NavCue"
        const val ACTION = "net.osmand.aidl.OsmandAidlServiceV2"
        const val CLASS = "net.osmand.aidl.OsmandAidlServiceV2"
    }
}
