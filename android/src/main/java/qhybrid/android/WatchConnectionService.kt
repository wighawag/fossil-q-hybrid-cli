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
import kotlinx.coroutines.runBlocking
import qhybrid.android.db.WatchRepository
import qhybrid.android.settings.SharedPreferencesSettingsPrefs
import qhybrid.android.sync.ConnectSyncDecider
import qhybrid.android.sync.SyncDataLoader
import qhybrid.android.sync.SyncOrchestrator
import qhybrid.android.sync.SyncSection
import qhybrid.android.sync.SyncState
import qhybrid.android.sync.SyncStateReporter
import qhybrid.android.sync.Uploader
import qhybrid.protocol.FossilController
import qhybrid.protocol.model.NotificationFilterEntry
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
        const val ACTION_REQUEST_ACTIVITY = "qhybrid.android.action.REQUEST_ACTIVITY"
        const val ACTION_BUZZ = "qhybrid.android.action.BUZZ"
        const val ACTION_DEVICE_APPEARED = "qhybrid.android.action.DEVICE_APPEARED"
        const val ACTION_STOP = "qhybrid.android.action.STOP"
        const val EXTRA_MAC = "mac"
        const val EXTRA_KEEP = "keep"
        // WP-BUZZTEST: which vibration pattern byte a manual "vibrate the watch now" should play.
        const val EXTRA_PATTERN = "pattern"
        // WP-SYNCFIX: which sync sections an explicit Save requested (section names). Absent =
        // full reconcile (connect / periodic). Present = targeted save (e.g. just BUTTONS).
        const val EXTRA_SECTIONS = "sections"

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

        /**
         * Re-run the sync operations. [sections] limits the pass to those sections (a targeted
         * "Save to watch" from one screen); null/empty = a full reconcile (connect / periodic).
         * WP-SYNCFIX: targeting avoids re-pushing unrelated sections the user never changed and
         * keeps a single file-put in flight per pass.
         */
        fun syncNow(context: Context, sections: Set<SyncSection>? = null) {
            val intent = Intent(context, WatchConnectionService::class.java).apply {
                action = ACTION_SYNC_NOW
                if (!sections.isNullOrEmpty()) {
                    putExtra(EXTRA_SECTIONS, sections.map { it.name }.toTypedArray())
                }
            }
            ContextCompatStartForeground(context, intent)
        }

        /**
         * WP-ACTIVITY — read the watch's activity file (BLE read on the ble-worker), parse it via
         * the WP8 surface, and publish the result into [qhybrid.android.sleep.ActivityState] which
         * the Sleep screen + Dashboard observe. Reuses the SAME fetch path the CLI `activity`
         * command drives (`FossilController.requestActivity` → `onActivityData`); invents NO wire
         * bytes. By default the watch deletes the file after reading (official-app behaviour);
         * pass [keep] = true to retain it on the watch.
         */
        fun requestActivity(context: Context, keep: Boolean = false) {
            val intent = Intent(context, WatchConnectionService::class.java).apply {
                action = ACTION_REQUEST_ACTIVITY
                putExtra(EXTRA_KEEP, keep)
            }
            ContextCompatStartForeground(context, intent)
        }

        /**
         * WP-BUZZTEST — make the watch vibrate NOW with the given vibration [pattern] byte (a
         * manual on-device test buzz; e.g. 5 = ONE_SHORT_VIBE strong single, 1 = CALL triple).
         * Like [syncNow] this is a **connect-then-do**: if the link is down it connects first, then
         * buzzes; an unreachable watch surfaces an honest [SyncState] ERROR rather than silently
         * dropping the request. Reuses the golden NOTIFICATION_FILTER + NOTIFICATION_PLAY path via
         * [FossilController.buzz] — invents NO new wire bytes.
         */
        fun buzzNow(context: Context, pattern: Int) {
            val intent = Intent(context, WatchConnectionService::class.java).apply {
                action = ACTION_BUZZ
                putExtra(EXTRA_PATTERN, pattern)
            }
            ContextCompatStartForeground(context, intent)
        }

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

    // WP-SYNCFIX: set when an explicit user "Save to watch" requested a sync while the link was
    // down, so a connect-then-sync runs and a CONNECT FAILURE is surfaced honestly as a SyncState
    // ERROR ("watch not reachable") rather than silently dropping the write. The held value is the
    // TARGETED section set to run on connect (null = full reconcile). Cleared once the connect
    // attempt resolves (success runs the targeted sync; failure publishes the error).
    private val pendingSyncOnConnect = AtomicBoolean(false)
    private val pendingSyncSections = AtomicReference<Set<SyncSection>?>(null)

    // WP-BUZZTEST: set when a manual "vibrate the watch now" was requested while the link was down,
    // so a connect-then-buzz runs and a CONNECT FAILURE is surfaced honestly as a SyncState ERROR
    // ("watch not reachable") rather than silently dropping the buzz. The held value is the
    // vibration pattern byte to play on connect (null = no buzz pending). Cleared once the connect
    // attempt resolves (success runs the buzz; failure publishes the error).
    private val pendingBuzzPattern = AtomicReference<Int?>(null)

    /** WP-SYNCFIX: decode the requested sync sections from a SYNC_NOW intent (null = reconcile). */
    private fun parseSections(intent: Intent?): Set<SyncSection>? {
        val names = intent?.getStringArrayExtra(EXTRA_SECTIONS) ?: return null
        val parsed = names.mapNotNull { runCatching { SyncSection.valueOf(it) }.getOrNull() }.toSet()
        return parsed.ifEmpty { null }
    }

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
                if (mac != null) {
                    // WP-PULLSYNC: no periodic safety-sync any more (sync is user-initiated).
                    submitConnect(mac)
                } else {
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
            ACTION_SYNC_NOW -> submitSync(parseSections(intent))
            ACTION_REQUEST_ACTIVITY -> submitRequestActivity(intent.getBooleanExtra(EXTRA_KEEP, false))
            ACTION_BUZZ -> submitBuzz(intent.getIntExtra(EXTRA_PATTERN, 5))
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
        // WP-ACTIVITY: parse + publish the activity file as soon as the watch delivers it
        // (same callback the CLI `activity` command uses). The fetch is triggered by
        // ACTION_REQUEST_ACTIVITY or the on-connect poke below; this only handles the result.
        controller.onActivityData { bytes -> onActivityBytes(bytes) }

        try {
            if (!controller.connect(mac)) {
                publish(
                    WatchState.LinkState.DISCONNECTED,
                    message = "Failed to connect (out of range / BT off / phone still bonded?)",
                    clearDeviceInfo = true,
                )
                // WP-SYNCFIX: a Save-to-watch that triggered this connect must NOT report success
                // when the watch is unreachable — surface an honest sync error instead.
                failPendingSync("Watch not reachable (out of range / Bluetooth off?)")
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
                    // WP15: surface the negotiated MTU for the Debug Menu (read-only; no
                    // wire change — the transport already negotiated this in connect()).
                    mtu = transportRef.get()?.getMtu() ?: 0,
                    message = "Connected",
                )
                // A connect requested by an explicit Save-to-watch is about to run the sync via
                // the on-connect hook below; clear the pending flag so the failure paths don't
                // also publish an error. Pick up the TARGETED sections it requested (null = full
                // reconcile, the default for a plain on-connect).
                // WP-PULLSYNC: connecting NO LONGER auto-pushes the full config. Sync is now an
                // explicit user action (per-screen Save-to-watch, or the Settings "Sync all"
                // button) — the watch keeps its config across disconnects, so re-pushing
                // everything on every reconnect was redundant AND flooded the single BLE control
                // channel (it broke the manual buzz). On connect we only:
                //   (a) run a sync the user explicitly requested while the link was down
                //       ([pendingSyncOnConnect] — a Save-to-watch or "Sync all"), OR
                //   (b) do a ONE-TIME full provisioning sync for a BRAND-NEW watch (no Room row
                //       yet) so a freshly-added watch gets its config; known watches sync never.
                val requestedSections = pendingSyncSections.getAndSet(null)
                val hadPendingSync = pendingSyncOnConnect.getAndSet(false)
                when (val d = ConnectSyncDecider.decide(hadPendingSync, requestedSections, isNewWatch(mac))) {
                    is ConnectSyncDecider.Decision.Sync -> {
                        Log.i(TAG, "on-connect sync: ${d.reason} sections=${d.sections}")
                        runOnConnectSync(controller, d.sections)
                    }
                    ConnectSyncDecider.Decision.None ->
                        Log.i(TAG, "known watch $mac — no auto-sync on connect (sync is user-initiated)")
                }
                // WP-PULLSYNC: register the watch row AFTER deciding newness so the next connect
                // is treated as "known" (no repeated provisioning). Idempotent + marks it active.
                registerWatchRow(mac)
                // WP-BUZZTEST: a manual buzz requested while the link was down connects here, then
                // buzzes (we're already on the ble-worker). Runs AFTER any pending sync so it is
                // sequenced behind those writes on the single control channel.
                pendingBuzzPattern.getAndSet(null)?.let { pattern -> runBuzz(controller, pattern) }
                // WP-ACTIVITY: also pull the activity file on connect so the Sleep screen +
                // Dashboard steps are populated hands-free. We are already on the ble-worker, so
                // drive the existing fetch path directly; the result is published by
                // onActivityData → onActivityBytes. Failures are non-fatal (logged).
                if (controller.isFossilProtocol()) {
                    runCatching { controller.requestActivity(false) }
                        .onFailure { Log.w(TAG, "on-connect activity fetch failed", it) }
                }
            } else {
                publish(
                    WatchState.LinkState.INITIALIZED,
                    mac = mac,
                    message = "Connected (not Fossil 2.x)",
                )
                // WP-SYNCFIX: a non-Fossil watch can't receive the config — don't leave a pending
                // Save-to-watch spinning; report it honestly.
                failPendingSync("Connected, but this watch isn't a Fossil Q Hybrid 2.x.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "connect/init failed", e)
            publish(
                WatchState.LinkState.DISCONNECTED,
                message = "Error: ${e.message}",
                clearDeviceInfo = true,
            )
            // WP-SYNCFIX: surface the connect/init failure to a pending Save-to-watch.
            failPendingSync("Could not sync: ${e.message ?: e.javaClass.simpleName}")
            runCatching { controller.disconnect() }
            controllerRef.compareAndSet(controller, null)
        }
    }

    /**
     * WP-SYNCFIX — if an explicit Save-to-watch ([submitSync]) requested a sync while the link was
     * down and the subsequent connect attempt FAILED, publish a SyncState ERROR so the Save button
     * shows an honest failure (and does NOT claim the config was saved to the watch). No-op if no
     * sync was pending. Clears the pending flag.
     */
    private fun failPendingSync(message: String) {
        // WP-BUZZTEST: a manual buzz pending on this failed connect must also report honestly.
        val hadPending = pendingSyncOnConnect.getAndSet(false) or (pendingBuzzPattern.getAndSet(null) != null)
        if (hadPending) {
            SyncState.publish(
                SyncState.SyncPhase.ERROR,
                errorMessage = message,
                nowMillis = System.currentTimeMillis(),
            )
        }
    }

    /**
     * WP14 — sync-on-connect. Loads the active watch's configuration (WP4 rows + WP16g app
     * prefs) via [SyncDataLoader] and runs the pure [SyncOrchestrator] against a production
     * [ServiceUploader] over [controller]. ALWAYS runs on the ble-worker thread (the caller —
     * [connectAndInit] / [submitSync] — is already on it), so the suspending DB read is done
     * with [runBlocking] safely (never the main thread).
     *
     * BLE effect is on-device-pending; the decision logic + payload compilation are unit-tested
     * (WP14 sub-part 1). Reuses the golden-tested protocol compilers/façade — no wire bytes
     * invented.
     */
    private fun runOnConnectSync(
        controller: FossilController,
        sections: Set<SyncSection> = SyncSection.ALL,
    ) {
        try {
            val input = runBlocking { loader().load() }
            if (!input.hasWatch) {
                Log.i(TAG, "sync: no active watch — nothing to upload")
                return
            }
            Log.i(TAG, "sync: sections=$sections")
            // WP-PROGRESS (sub-part 2): the WP3 service is the SINGLE writer of the process-wide
            // SyncState. Delegate the phase choreography (SYNCING before the pass — the Save
            // buttons spin + disable — then SUCCESS from the result, or ERROR if the pass throws
            // before producing one) to the pure, unit-tested SyncStateReporter. We only mark
            // SYNCING once we know there IS an active watch, so a no-watch poke stays IDLE. The
            // BLE effect itself is on-device-pending; section-level failures are carried through
            // honestly via SyncResult.errors (see SyncState.SyncStatus.hadSectionErrors).
            val result = SyncStateReporter.reportAround(System::currentTimeMillis) {
                SyncOrchestrator.sync(input, ServiceUploader(controller), sections)
            }
            if (result != null) {
                Log.i(
                    TAG,
                    "sync done mac=${result.mac} performed=${result.performed} " +
                        "skipped=${result.skipped} errors=${result.errors}",
                )
            }
        } catch (e: Exception) {
            // A failure loading the input (before the sync pass) — reportAround handles failures
            // of the pass itself. Record ERROR so the UI does not hang on a stale SYNCING.
            Log.e(TAG, "sync failed", e)
            SyncState.publish(
                SyncState.SyncPhase.ERROR,
                errorMessage = e.message ?: e.javaClass.simpleName,
                nowMillis = System.currentTimeMillis(),
            )
        }
    }

    private fun loader(): SyncDataLoader =
        SyncDataLoader(
            WatchRepository(applicationContext),
            SharedPreferencesSettingsPrefs(applicationContext),
        )

    /**
     * WP-PULLSYNC — true when [mac] has no Room row yet (a brand-new association). A new watch
     * gets a one-time full provisioning sync on its first successful connect; a known watch never
     * auto-syncs (sync is user-initiated). Runs on the ble-worker; the suspending DB read is done
     * with [runBlocking] safely (never the main thread).
     */
    private fun isNewWatch(mac: String): Boolean =
        runCatching { runBlocking { WatchRepository(applicationContext).getWatch(mac) == null } }
            .getOrDefault(false)

    /**
     * WP-PULLSYNC — mirror the association into the Room registry (create-if-missing + mark
     * active), so the next connect is treated as a KNOWN watch (no repeated provisioning).
     * Idempotent. Runs on the ble-worker.
     */
    private fun registerWatchRow(mac: String) {
        runCatching { runBlocking { WatchRepository(applicationContext).registerWatch(mac, name = mac) } }
            .onFailure { Log.w(TAG, "registerWatch failed", it) }
    }

    /**
     * WP-SYNCFIX — "Save to watch". If the link is already up, run the sync immediately. If it is
     * DOWN, do a **connect-then-sync** rather than silently dropping the request: mark a pending
     * sync, then kick a connect for the associated mac. On a successful connect the on-connect
     * hook ([connectAndInit] → [runOnConnectSync]) performs the sync (publishing SUCCESS/ERROR
     * from the result); on a FAILED connect [failPendingSync] publishes a SyncState ERROR so the
     * UI reports honestly that nothing was saved to the watch. With no associated watch at all, we
     * publish an immediate error.
     *
     * The app-side already published SYNCING (see [qhybrid.android.sync.ServiceSaveToWatch]) so the
     * Save button shows the spinner the instant the user taps it, even before the link is up.
     */
    private fun submitSync(sections: Set<SyncSection>? = null) {
        val target = sections ?: SyncSection.ALL
        worker.execute {
            val c = controllerRef.get()
            Log.i(TAG, "syncNow: controller=${c != null} linkUp=${isLinkUp()} sections=$target")
            if (c != null && isLinkUp()) {
                runCatching { runOnConnectSync(c, target) }
                    .onFailure {
                        Log.e(TAG, "sync failed", it)
                        SyncState.publish(
                            SyncState.SyncPhase.ERROR,
                            errorMessage = it.message ?: it.javaClass.simpleName,
                            nowMillis = System.currentTimeMillis(),
                        )
                    }
                return@execute
            }
            // Link is down — connect first, then sync on connect.
            val mac = CompanionManager.getAssociatedMac(this)
            if (mac == null) {
                Log.w(TAG, "syncNow: no associated watch")
                SyncState.publish(
                    SyncState.SyncPhase.ERROR,
                    errorMessage = "No watch associated.",
                    nowMillis = System.currentTimeMillis(),
                )
                return@execute
            }
            Log.i(TAG, "syncNow: link down — connecting then syncing ($mac) sections=$target")
            pendingSyncSections.set(target)
            pendingSyncOnConnect.set(true)
            submitConnect(mac)
        }
    }

    /**
     * WP-ACTIVITY — request the watch's activity file on the ble-worker. The actual parse +
     * publish happens in [onActivityBytes] (wired via `controller.onActivityData` above) when the
     * file transfer completes — exactly mirroring the CLI `activity` command's
     * `setOnActivityData(...)` + `fetchActivity(keep)` sequence. NO new wire bytes are invented;
     * this only drives the existing `FossilController.requestActivity` path.
     */
    private fun submitRequestActivity(keep: Boolean) {
        worker.execute {
            val c = controllerRef.get()
            if (c == null || !isLinkUp()) {
                Log.d(TAG, "requestActivity ignored — not connected")
                return@execute
            }
            runCatching {
                Log.i(TAG, "requesting activity file (keep=$keep)")
                c.requestActivity(keep)
            }.onFailure { Log.e(TAG, "requestActivity failed", it) }
        }
    }

    /**
     * WP-BUZZTEST — make the watch vibrate NOW (a manual on-device test buzz). If the link is up,
     * buzz immediately; if it is DOWN, do a **connect-then-buzz** (mirroring [submitSync]): hold the
     * requested [pattern], kick a connect for the associated mac, and let the on-connect hook run
     * the buzz. A FAILED connect surfaces an honest [SyncState] ERROR via [failPendingSync] (the
     * buzz button shows the failure instead of pretending the watch vibrated). With no associated
     * watch we publish an immediate error. The app-side already published SYNCING (see
     * [qhybrid.android.sync.ServiceBuzz]) so the blocking "Buzzing…" modal appears on tap.
     */
    private fun submitBuzz(pattern: Int) {
        worker.execute {
            val c = controllerRef.get()
            Log.i(TAG, "buzzNow: controller=${c != null} linkUp=${isLinkUp()} pattern=$pattern")
            if (c != null && isLinkUp()) {
                runBuzz(c, pattern)
                return@execute
            }
            // Link is down — connect first, then buzz on connect.
            val mac = CompanionManager.getAssociatedMac(this)
            if (mac == null) {
                Log.w(TAG, "buzzNow: no associated watch")
                SyncState.publish(
                    SyncState.SyncPhase.ERROR,
                    errorMessage = "No watch associated.",
                    nowMillis = System.currentTimeMillis(),
                )
                return@execute
            }
            Log.i(TAG, "buzzNow: link down — connecting then buzzing ($mac) pattern=$pattern")
            pendingBuzzPattern.set(pattern)
            submitConnect(mac)
        }
    }

    /**
     * WP-BUZZ-PLAYONLY — perform the actual buzz on the ble-worker via a SINGLE play-file put
     * ([FossilController.buzzPlayOnly]): the reserved buzz filter is already on the watch (written by
     * new-watch provisioning and folded into every notification sync), so the watch matches the play
     * file's package CRC to the reserved entry and picks the pattern — no per-buzz NOTIFICATION_FILTER
     * put. This halves the per-buzz BLE work and removes the two-put sequencing. Falls back to the two-put
     * [FossilController.buzz] for a non-reserved pattern. Publishes [SyncState] SYNCING →
     * SUCCESS/ERROR. Always called on the ble-worker (from [submitBuzz] or the on-connect hook).
     */
    private fun runBuzz(controller: FossilController, pattern: Int) {
        val now = System.currentTimeMillis()
        SyncState.publish(SyncState.SyncPhase.SYNCING, nowMillis = now)
        runCatching {
            if (qhybrid.protocol.requests.fossil.notification.BuzzPatterns.isReservedPattern(pattern)) {
                Log.i(TAG, "buzz (play-only): pattern=$pattern")
                controller.buzzPlayOnly(pattern)
            } else {
                Log.i(TAG, "buzz (filter+play, non-reserved pattern): pattern=$pattern")
                controller.buzz(pattern)
            }
        }.onSuccess {
            SyncState.publish(SyncState.SyncPhase.SUCCESS, nowMillis = System.currentTimeMillis())
        }.onFailure { e ->
            Log.e(TAG, "buzz failed", e)
            SyncState.publish(
                SyncState.SyncPhase.ERROR,
                errorMessage = e.message ?: e.javaClass.simpleName,
                nowMillis = System.currentTimeMillis(),
            )
        }
    }

    /**
     * WP-ACTIVITY — handle a delivered activity file: parse it via the pure WP8-backed
     * [qhybrid.android.sleep.ActivityFetcher] (tolerant of empty/partial/malformed — the watch
     * sends `byte[0]` when there is no data) and publish the result into the process-wide
     * [qhybrid.android.sleep.ActivityState] holder that the Sleep screen + Dashboard observe.
     * Runs on the ble-worker callback thread; only touches in-memory state (no DB, no UI).
     */
    private fun onActivityBytes(bytes: ByteArray?) {
        val size = bytes?.size ?: 0
        Log.i(TAG, "activity file delivered: $size bytes — parsing")
        val chart = qhybrid.android.sleep.ActivityFetcher.parse(bytes, java.time.ZoneId.systemDefault())
        qhybrid.android.sleep.ActivityState.publish(chart, System.currentTimeMillis())
        Log.i(
            TAG,
            "activity parsed: ${chart.days.size} day(s), ${chart.totalSteps} steps, " +
                "${chart.sleep.size} sleep session(s)",
        )
    }

    /**
     * WP14 — production [Uploader] over the WP3-owned [FossilController]. Reuses the golden-tested
     * façade upload + settings methods (WP5/6/7 + ConfigurationPutRequest items); invents NO wire
     * bytes. Every call runs on the ble-worker thread (this is only ever constructed/called from
     * [runOnConnectSync], which is already on the worker). The alarm upload waits (bounded) on the
     * adapter's CompletableFuture so the pass is sequenced; the others are fire-and-forward like
     * the CLI/init path.
     */
    private class ServiceUploader(private val controller: FossilController) : Uploader {
        override fun uploadAlarms(alarmFile: ByteArray): Boolean {
            val future = CompletableFuture<Boolean>()
            controller.setAlarms(alarmFile, future)
            return runCatching { future.get(UPLOAD_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
                .getOrElse {
                    Log.w(TAG, "alarm upload timed out / failed", it)
                    false
                }
        }

        override fun uploadNotificationFilter(entries: List<NotificationFilterEntry>): Boolean {
            // WP-BUZZ-PLAYONLY: the notification filter is a WHOLE FILE — uploading the user's rules
            // would otherwise wipe the reserved buzz entries. Fold the reserved entries in so a
            // single play-file buzz still matches after a notification sync. (De-dupe by package so
            // a user rule for a reserved name wins.) This fold-in plus new-watch provisioning are the
            // ONLY ways the reserved entries reach the watch — no per-connect/per-buzz standalone put.
            val reserved = qhybrid.protocol.requests.fossil.notification.BuzzPatterns.reservedEntries()
            val userPackages = entries.map { it.packageName }.toSet()
            val merged = entries + reserved.filter { it.packageName !in userPackages }
            controller.uploadNotificationFilter(merged)
            return true
        }

        override fun uploadButtons(buttonConfigFile: ByteArray): Boolean {
            Log.i(TAG, "uploadButtons: ${buttonConfigFile.size} bytes — writing SETTINGS_BUTTONS")
            // WAIT on the file-put (bounded) so the BLE link is held open until the watch acks.
            val future = CompletableFuture<Boolean>()
            controller.setButtons(buttonConfigFile, future)
            val ok = runCatching { future.get(UPLOAD_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
                .getOrElse {
                    Log.w(TAG, "button upload timed out / failed", it)
                    false
                }
            Log.i(TAG, "uploadButtons result=$ok")
            return ok
        }

        override fun applyVibrationStrength(strength: Int): Boolean {
            controller.setVibrationStrength(strength.toShort())
            return true
        }

        override fun applyInactivityNudge(
            fromHour: Int, fromMinute: Int, toHour: Int, toMinute: Int,
            inactiveMinutes: Int, enabled: Boolean,
        ): Boolean {
            controller.setInactivityNudge(fromHour, fromMinute, toHour, toMinute, inactiveMinutes, enabled)
            return true
        }

        override fun applySecondTimezone(offsetMinutes: Int): Boolean {
            controller.setSecondTimezone(offsetMinutes.toShort())
            return true
        }

        companion object {
            // Bounded so a watch that never acks a file-put can't hang the sync pass (and the UI
            // spinner) for half a minute. A real put completes in well under this on a live link.
            private const val UPLOAD_TIMEOUT_MS = 12_000L
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
        mtu: Int? = null,
        message: String? = null,
        clearDeviceInfo: Boolean = false,
    ) {
        WatchState.update(
            link = link, mac = mac, battery = battery, firmware = firmware,
            model = model, mtu = mtu, message = message, clearDeviceInfo = clearDeviceInfo,
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
