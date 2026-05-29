package qhybrid.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import qhybrid.protocol.FossilController
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * WP3 — Foreground service that OWNS the [FossilController] + [AndroidBleTransport] for
 * the whole session (ownership moved out of MainActivity). START_STICKY + persistent
 * notification so the link survives the app being closed.
 *
 * All blocking transport work runs on a single-thread "ble-worker" executor (the
 * transport is blocking and must never touch the main thread). Connection state is
 * published to [WatchState] (the single source of truth other WPs observe).
 *
 * Reconnect is event-driven only: [WatchPresenceService] (CDM, API 31+) or
 * [ReconnectFallback] (26–30) deliver ACTION_DEVICE_APPEARED here. NO continuous scanning.
 */
class WatchConnectionService : Service() {

    companion object {
        private const val TAG = "FossilQ-Svc"

        private const val CHANNEL_ID = "fossilq_link"
        private const val NOTIF_ID = 0x05511 // arbitrary stable id

        const val ACTION_CONNECT = "qhybrid.android.action.CONNECT"
        const val ACTION_DISCONNECT = "qhybrid.android.action.DISCONNECT"
        const val ACTION_SYNC_NOW = "qhybrid.android.action.SYNC_NOW"
        const val ACTION_DEVICE_APPEARED = "qhybrid.android.action.DEVICE_APPEARED"
        const val ACTION_STOP = "qhybrid.android.action.STOP"
        const val EXTRA_MAC = "mac"

        private const val INIT_TIMEOUT_MS = 60_000L

        // ---- static entry points other WPs / receivers call --------------

        private fun start(context: Context, action: String, mac: String? = null) {
            val intent = Intent(context, WatchConnectionService::class.java).apply {
                this.action = action
                if (mac != null) putExtra(EXTRA_MAC, mac)
            }
            ContextCompatStartForeground(context, intent)
        }

        /** Connect+init to [mac] (or the associated mac if null). */
        fun connectNow(context: Context, mac: String? = null) =
            start(context, ACTION_CONNECT, mac ?: CompanionManager.getAssociatedMac(context))

        /** Re-run the sync-on-connect operations (WP5/6/9 fill these in). */
        fun syncNow(context: Context) = start(context, ACTION_SYNC_NOW)

        fun disconnect(context: Context) = start(context, ACTION_DISCONNECT)

        /** Event-driven reconnect trigger (from CDM presence / fallback). */
        fun onDeviceAppeared(context: Context, mac: String) =
            start(context, ACTION_DEVICE_APPEARED, mac)

        fun stop(context: Context) = start(context, ACTION_STOP)

        private fun ContextCompatStartForeground(context: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    // Single-thread serializer for ALL blocking transport ops.
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "ble-worker") }

    private val controllerRef = AtomicReference<FossilController?>(null)
    private val transportRef = AtomicReference<AndroidBleTransport?>(null)

    // Guards against overlapping connect attempts (single-link device).
    private val connecting = AtomicBoolean(false)

    // ---- binding (thin client reads state / triggers actions) ---------------

    inner class LocalBinder : Binder() {
        val service: WatchConnectionService get() = this@WatchConnectionService
    }

    private val binder = LocalBinder()
    override fun onBind(intent: Intent?): IBinder = binder

    // ---- lifecycle ----------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // Enter foreground immediately so we satisfy the FGS start contract even on the
        // boot-restart path before we have any device info.
        startForegroundCompat(buildNotification("Idle", "Waiting for watch"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.i(TAG, "onStartCommand action=$action")
        when (action) {
            ACTION_CONNECT -> {
                val mac = intent.getStringExtra(EXTRA_MAC) ?: CompanionManager.getAssociatedMac(this)
                if (mac != null) submitConnect(mac) else {
                    Log.w(TAG, "ACTION_CONNECT with no mac and no associated mac")
                }
            }
            ACTION_DEVICE_APPEARED -> {
                val mac = intent.getStringExtra(EXTRA_MAC) ?: CompanionManager.getAssociatedMac(this)
                if (mac != null) {
                    if (isLinkUp()) {
                        Log.d(TAG, "device appeared but link already up — ignoring")
                    } else {
                        submitConnect(mac)
                    }
                }
            }
            ACTION_SYNC_NOW -> submitSync()
            ACTION_DISCONNECT -> submitDisconnect(stopAfter = false)
            ACTION_STOP -> submitDisconnect(stopAfter = true)
            else -> Log.d(TAG, "unhandled action $action")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { controllerRef.getAndSet(null)?.disconnect() }
        transportRef.set(null)
        worker.shutdownNow()
    }

    // ---- work ----------------------------------------------------------------

    private fun isLinkUp(): Boolean = transportRef.get()?.isConnected() == true

    private fun submitConnect(mac: String) {
        if (!connecting.compareAndSet(false, true)) {
            Log.d(TAG, "connect already in progress — skipping")
            return
        }
        worker.execute {
            try {
                connectAndInit(mac)
            } finally {
                connecting.set(false)
            }
        }
    }

    private fun connectAndInit(mac: String) {
        // If already connected to this mac, nothing to do.
        if (isLinkUp()) {
            Log.d(TAG, "already connected — skipping connect")
            return
        }
        publish(WatchState.LinkState.CONNECTING, mac = mac, message = "Connecting to $mac…")

        val transport = AndroidBleTransport(applicationContext)
        val controller = FossilController(transport)
        controllerRef.getAndSet(controller)?.let { runCatching { it.disconnect() } }
        transportRef.set(transport)

        transport.setConnectionCallback { up ->
            if (!up) {
                // Unexpected drop OR intentional disconnect. Reflect Disconnected and
                // re-arm presence so we auto-reconnect when the watch reappears.
                publish(
                    WatchState.LinkState.DISCONNECTED,
                    message = "Disconnected",
                    clearDeviceInfo = true,
                )
                CompanionManager.getAssociatedMac(this)?.let {
                    CompanionManager.startObserving(this, it)
                }
            }
        }
        controller.onAuthRequired {
            publish(
                WatchState.LinkState.AUTH_REQUIRED,
                message = "Authorization requested — hold the TOP button (30s)",
            )
        }
        controller.onConfigSynced { Log.i(TAG, "Config synced") }

        try {
            if (!controller.connect(mac)) {
                publish(
                    WatchState.LinkState.DISCONNECTED,
                    message = "Failed to connect (out of range / BT off / phone still bonded?)",
                    clearDeviceInfo = true,
                )
                controllerRef.compareAndSet(controller, null)
                return
            }
            publish(WatchState.LinkState.INITIALIZING, mac = mac, message = "Connected. Initializing…")

            controller.init(false)
            if (controller.isFossilProtocol()) {
                if (!controller.waitForInit(INIT_TIMEOUT_MS)) {
                    Log.w(TAG, "init may not have completed fully")
                }
            }

            val initialized = controller.isFossilProtocol()
            if (initialized) {
                publish(
                    WatchState.LinkState.INITIALIZED,
                    mac = mac,
                    battery = controller.batteryLevel,
                    firmware = controller.firmwareVersion,
                    model = controller.modelNumber,
                    message = "Connected",
                )
                // WP3 sync-on-connect hook (WP5/6/9 fill in alarm/filter/calendar uploads).
                runOnConnectSync(controller)
            } else {
                publish(
                    WatchState.LinkState.INITIALIZED,
                    mac = mac,
                    message = "Connected (not Fossil 2.x)",
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "connect/init failed", e)
            publish(
                WatchState.LinkState.DISCONNECTED,
                message = "Error: ${e.message}",
                clearDeviceInfo = true,
            )
            runCatching { controller.disconnect() }
            controllerRef.compareAndSet(controller, null)
        }
    }

    /**
     * Sync-on-connect entry point. WP3 keeps this at the WP2 auth-only baseline; WP5/6/9
     * will add alarm/filter/calendar uploads here, and WP14 will call [syncNow] to re-run it.
     */
    private fun runOnConnectSync(controller: FossilController) {
        // Intentionally minimal for WP3. Placeholder for future WPs.
    }

    private fun submitSync() {
        worker.execute {
            val c = controllerRef.get()
            if (c == null || !isLinkUp()) {
                Log.d(TAG, "syncNow ignored — not connected")
                return@execute
            }
            runCatching { runOnConnectSync(c) }
        }
    }

    private fun submitDisconnect(stopAfter: Boolean) {
        worker.execute {
            runCatching { controllerRef.getAndSet(null)?.disconnect() }
            transportRef.set(null)
            publish(
                WatchState.LinkState.DISCONNECTED,
                message = "Disconnected",
                clearDeviceInfo = true,
            )
            if (stopAfter) {
                stopForegroundCompat()
                stopSelf()
            }
        }
    }

    // ---- state + notification -----------------------------------------------

    private fun publish(
        link: WatchState.LinkState,
        mac: String? = null,
        battery: Int? = null,
        firmware: String? = null,
        model: String? = null,
        message: String? = null,
        clearDeviceInfo: Boolean = false,
    ) {
        WatchState.update(
            link = link, mac = mac, battery = battery, firmware = firmware,
            model = model, message = message, clearDeviceInfo = clearDeviceInfo,
        )
        val s = WatchState.status.value
        val title = when (link) {
            WatchState.LinkState.CONNECTING -> "Connecting…"
            WatchState.LinkState.INITIALIZING -> "Initializing…"
            WatchState.LinkState.AUTH_REQUIRED -> "Authorize on watch"
            WatchState.LinkState.INITIALIZED ->
                "Watch connected" + (s.battery?.let { " · $it%" } ?: "")
            WatchState.LinkState.DISCONNECTED -> "Disconnected"
            WatchState.LinkState.IDLE -> "Idle"
        }
        updateNotification(title, s.message ?: "")
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel(
                CHANNEL_ID, "Watch link",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Keeps the Fossil watch link alive in the background" }
            mgr.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(title: String, text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(title: String, text: String) {
        val mgr = getSystemService(NotificationManager::class.java)
        mgr?.notify(NOTIF_ID, buildNotification(title, text))
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }
}
