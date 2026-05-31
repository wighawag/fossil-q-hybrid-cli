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
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.CalendarContract
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import qhybrid.android.calendar.CalendarAccess
import qhybrid.android.calendar.CalendarRefresher
import qhybrid.android.calendar.ServiceCalendarPush
import qhybrid.android.calendar.SystemCalendarSource
import qhybrid.android.db.WatchRepository
import qhybrid.android.sync.CoroutineDebouncer
import qhybrid.android.sync.Debouncer
import qhybrid.android.settings.SharedPreferencesSettingsPrefs
import qhybrid.android.sync.ConnectSyncDecider
import qhybrid.android.sync.SyncDataLoader
import qhybrid.android.sync.SyncInput
import qhybrid.android.sync.SyncMode
import qhybrid.android.sync.SyncOrchestrator
import qhybrid.android.sync.SyncSection
import qhybrid.android.sync.SyncSettings
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
        // WP-ONBOARD: forget = disconnect AND do NOT auto-reconnect (used by Remove watch). Without
        // this the disconnect callback re-arms presence and the watch reconnects — fighting removal.
        const val ACTION_FORGET = "qhybrid.android.action.FORGET"
        const val ACTION_SYNC_NOW = "qhybrid.android.action.SYNC_NOW"
        const val ACTION_REQUEST_ACTIVITY = "qhybrid.android.action.REQUEST_ACTIVITY"
        const val ACTION_BUZZ = "qhybrid.android.action.BUZZ"
        // WP11: a posted phone notification matched a per-app rule — play it on the watch (play-only
        // by package; the watch already holds the per-app vibe+hands in its NOTIFICATION_FILTER).
        const val ACTION_PLAY_NOTIFICATION = "qhybrid.android.action.PLAY_NOTIFICATION"
        // WP13: re-read the user's calendar and full-replace alarm slots 16-31, then silently push
        // the alarm file if the rows changed (no SYNCING modal). Driven by the ContentObserver,
        // the on-connect hook, and the permission (re-)grant in Setup.
        const val ACTION_REFRESH_CALENDAR = "qhybrid.android.action.REFRESH_CALENDAR"
        const val ACTION_DEVICE_APPEARED = "qhybrid.android.action.DEVICE_APPEARED"
        const val ACTION_STOP = "qhybrid.android.action.STOP"
        const val EXTRA_MAC = "mac"
        const val EXTRA_KEEP = "keep"
        // WP-BUZZTEST: which vibration pattern byte a manual "vibrate the watch now" should play.
        const val EXTRA_PATTERN = "pattern"
        // Force the self-contained two-put buzz (NOTIFICATION_FILTER + NOTIFICATION_PLAY) instead of
        // the single play-only put — a diagnostic path that works even if the reserved filter is
        // missing from the watch ("put filter + send buzz").
        const val EXTRA_FORCE_FILTER = "force_filter"
        // WP11: the package name of the matched notification to play on the watch.
        const val EXTRA_PACKAGE = "package"
        // WP11: SystemClock.elapsedRealtime() deadline after which a pending connect-then-play is
        // dropped as stale (so a notification queued during a long disconnect doesn't buzz late).
        const val EXTRA_PLAY_DEADLINE = "play_deadline"
        // WP11: how long (ms) a connect-then-play stays valid before it is dropped as stale.
        const val PLAY_STALE_AFTER_MS = 30_000L
        // WP13: debounce window coalescing a burst of calendar provider changes into ONE refresh
        // (the provider can fire several onChange callbacks for a single user edit / sync).
        private const val CALENDAR_DEBOUNCE_MS = 1_500L
        // WP13: a user-initiated resync re-checks a few times in case the provider's Instances
        // table is still expanding a just-added/synced event (which can lag by minutes).
        private const val CALENDAR_SETTLE_RETRIES = 3
        private const val CALENDAR_SETTLE_RETRY_MS = 4_000L
        // WP-SYNCFIX: which sync sections an explicit Save requested (section names). Absent =
        // full reconcile (connect / periodic). Present = targeted save (e.g. just BUTTONS).
        const val EXTRA_SECTIONS = "sections"
        // WP-CLEARALARMS: run the targeted sync in PROVISION mode (force-write empties), so a
        // "Clear all alarms" pushes the EMPTY 32-slot file to actively blank the watch instead of
        // the RECONCILE skip-empties default (which would leave the watch's alarms intact).
        const val EXTRA_FORCE_PROVISION = "force_provision"

        private const val INIT_TIMEOUT_MS = 60_000L
        // How long after a Remove-watch we suppress auto-reconnect, so the disconnect + CDM teardown
        // settles before any later (re-)add is allowed to reconnect.
        private const val FORGET_GRACE_MS = 4_000L

        // HYBRID-AUTOCONNECT: after an unexpected drop (or a user/save connect that timed out
        // because the watch was asleep), we hand the keep-alive reconnect to the OS BLE controller
        // via connect(mac, autoConnect=true) — a battery-friendly, non-timing-out pending connect
        // that fires when the directed-advertising watch reappears (see [armAutoReconnect]). This
        // REPLACES the old app-level exponential-backoff connect loop (which fought the controller
        // and flooded the single BLE control channel). The controller owns the retry cadence; we
        // keep only ONE minimal fallback timer:
        //
        // If registering the auto-connect itself fails (connect(...,true) returns false — e.g. BT
        // momentarily off), re-arm it ONCE after this delay. We do NOT keep polling/connecting on a
        // timer while an auto-connect is pending, so the controller and the app never both drive
        // connects at the same time.
        private const val AUTO_RECONNECT_FALLBACK_MS = 15_000L

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
        fun syncNow(
            context: Context,
            sections: Set<SyncSection>? = null,
            forceProvision: Boolean = false,
        ) {
            val intent = Intent(context, WatchConnectionService::class.java).apply {
                action = ACTION_SYNC_NOW
                if (!sections.isNullOrEmpty()) {
                    putExtra(EXTRA_SECTIONS, sections.map { it.name }.toTypedArray())
                }
                if (forceProvision) putExtra(EXTRA_FORCE_PROVISION, true)
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
        fun buzzNow(context: Context, pattern: Int, forceFilterPlay: Boolean = false) {
            val intent = Intent(context, WatchConnectionService::class.java).apply {
                action = ACTION_BUZZ
                putExtra(EXTRA_PATTERN, pattern)
                putExtra(EXTRA_FORCE_FILTER, forceFilterPlay)
            }
            ContextCompatStartForeground(context, intent)
        }

        /**
         * WP11 — a posted phone notification matched a per-app rule; play it on the watch. A
         * **play-only-by-package** put: the watch already holds the per-app vibration pattern + hand
         * degrees in its NOTIFICATION_FILTER (written at init/provisioning and on WP14 rule edits),
         * so the runtime play only names the package and the watch applies the configured behavior.
         *
         * Like [buzzNow] this is a **connect-then-play** when the link is down — EXCEPT a passive
         * notification must not buzz late: a pending play is dropped once it is older than
         * [PLAY_STALE_AFTER_MS] (the deadline is captured here, off the elapsedRealtime clock).
         * Unlike [buzzNow] it publishes NO [SyncState] (no modal — it is a silent background effect)
         * and never surfaces a user-facing error. Reuses [FossilController.playNotification] —
         * invents NO new wire bytes.
         */
        fun playNotificationNow(context: Context, packageName: String) {
            val intent = Intent(context, WatchConnectionService::class.java).apply {
                action = ACTION_PLAY_NOTIFICATION
                putExtra(EXTRA_PACKAGE, packageName)
                putExtra(EXTRA_PLAY_DEADLINE, SystemClock.elapsedRealtime() + PLAY_STALE_AFTER_MS)
            }
            ContextCompatStartForeground(context, intent)
        }

        /**
         * WP13 — re-read the user's calendar and full-replace alarm slots 16-31 (then SILENTLY push
         * the alarm file if the rows changed). Used by the permission (re-)grant in Setup (the
         * ContentObserver + on-connect hook drive the same [refreshCalendar] internally). Cheap
         * no-op if `READ_CALENDAR` isn't granted yet.
         */
        fun refreshCalendarNow(context: Context) {
            val intent = Intent(context, WatchConnectionService::class.java).apply {
                action = ACTION_REFRESH_CALENDAR
            }
            ContextCompatStartForeground(context, intent)
        }

        fun disconnect(context: Context) = start(context, ACTION_DISCONNECT)

        /** Remove watch: disconnect WITHOUT re-arming auto-reconnect (the caller cleared the assoc). */
        fun forget(context: Context) = start(context, ACTION_FORGET)

        /**
         * WP-ONBOARD — user-initiated **cancel** of a brand-new watch's provisioning that hung
         * ("Adding your watch…" stuck with no terminal outcome). This is the escape hatch the
         * timed-out modal calls: it tears the in-flight attempt down so the user is never trapped
         * and the half-added watch does NOT silently re-provision behind a dismissed modal.
         *
         * Mirrors [qhybrid.android.settings.ServiceWatchAdminSync] minus the DB delete (a watch
         * mid-provision has no Room row yet): suppress auto-reconnect + disconnect ([forget]), clear
         * the CDM association / presence / reconnect pointer for [mac], and force the
         * [qhybrid.android.onboard.ProvisioningState] back to IDLE so the modal closes. All steps are
         * best-effort/tolerant. The user can re-add the watch afterwards (a clean slate).
         */
        fun cancelProvisioning(context: Context, mac: String?) {
            val appContext = context.applicationContext
            Log.i(TAG, "cancelProvisioning(${mac ?: "<none>"}): tearing down the stuck provisioning attempt")
            // 1. Drop the link + suppress auto-reconnect so it doesn't immediately re-provision.
            runCatching { forget(appContext) }
            // 2. Clear the optimistic association/presence/pointer set in MainActivity.onAssociated.
            if (mac != null) {
                runCatching { CompanionManager.stopObserving(appContext, mac) }
                runCatching { CompanionManager.disassociate(appContext, mac) }
            }
            runCatching { CompanionManager.setAssociatedMac(appContext, null) }
            // 3. Close the modal (works even while PROVISIONING).
            qhybrid.android.onboard.ProvisioningState.forceIdle()
        }

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

    // WP12: turns the watch's music-gesture events (onEventJson) into Android media-session /
    // AudioManager actions, with the preferred-music-app launch fallback. Lazy so the system
    // services are resolved against the running service context. Pure logic is unit-tested
    // ([MusicController]/[MusicDispatcher]); the live media dispatch is on-device-pending.
    private val musicDispatch by lazy { qhybrid.android.music.ServiceMusicDispatch(applicationContext) }

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
    // WP-CLEARALARMS: whether the pending (connect-then-sync) pass should run in PROVISION mode
    // (force-write empties), e.g. a "Clear all alarms" that must blank the watch's 32-slot file.
    private val pendingSyncProvision = AtomicBoolean(false)

    // WP-BUZZTEST: set when a manual "vibrate the watch now" was requested while the link was down,
    // so a connect-then-buzz runs and a CONNECT FAILURE is surfaced honestly as a SyncState ERROR
    // ("watch not reachable") rather than silently dropping the buzz. The held value is the
    // vibration pattern byte to play on connect (null = no buzz pending). Cleared once the connect
    // attempt resolves (success runs the buzz; failure publishes the error).
    private val pendingBuzzPattern = AtomicReference<Int?>(null)
    // Whether the pending connect-then-buzz should force the self-contained filter+play path.
    private val pendingBuzzForceFilter = AtomicBoolean(false)
    // WP11: set when a matched notification arrived while the link was down, so a connect-then-play
    // runs on the next connect. The held value is the package to play (null = none pending). Unlike
    // the buzz, a CONNECT FAILURE is NOT surfaced (a passive notification is best-effort). Cleared
    // once the connect resolves, and dropped if older than [pendingPlayDeadline].
    private val pendingPlayPackage = AtomicReference<String?>(null)
    // WP11: elapsedRealtime() after which the pending play is stale and must be dropped (so a
    // notification queued during a long disconnect doesn't buzz minutes late).
    private val pendingPlayDeadline = AtomicReference<Long>(0L)
    // WP-ONBOARD: set while a Remove-watch is tearing down, to suppress the disconnect callback's
    // auto-reconnect re-arm and ignore a stray DEVICE_APPEARED for the watch being removed.
    private val forgetting = AtomicBoolean(false)

    // HYBRID-AUTOCONNECT: a single-thread scheduler used ONLY as the minimal fallback that re-arms
    // an auto-connect if REGISTERING it failed (see [AUTO_RECONNECT_FALLBACK_MS]). It does NOT run
    // the old exponential-backoff connect loop — the OS BLE controller owns the keep-alive retry
    // once an auto-connect is registered. [pendingReconnect] holds the in-flight fallback future so
    // a new arm supersedes the old.
    private val reconnectScheduler =
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "ble-reconnect") }
    private val pendingReconnect =
        AtomicReference<java.util.concurrent.ScheduledFuture<*>?>(null)
    // HYBRID-AUTOCONNECT: the controller/transport currently driving a background autoConnect=true
    // keep-alive (pending or live), so we never register a second pending GATT and an intentional
    // disconnect can tear it down. Distinct from [controllerRef] (the active/foreground controller);
    // an auto-connect promotes itself into [controllerRef] only once its link actually establishes.
    private val autoReconnectController = AtomicReference<FossilController?>(null)
    // True while an autoConnect=true keep-alive is registered & pending (or live). Guards against
    // BOTH double-driving (a timer firing submitConnect while the controller has a pending connect)
    // AND registering multiple pending auto-connects.
    private val autoReconnectArmed = AtomicBoolean(false)

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

    // ---- WP13 calendar sync seams -------------------------------------------

    /** Scope for the calendar [ContentObserver]-driven refresh (off the main thread). */
    private val calendarScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Coalesces a burst of calendar change notifications into ONE refresh (reuses WP-SYNCSTATUS). */
    private val calendarDebouncer: Debouncer by lazy {
        CoroutineDebouncer(calendarScope, windowMillis = CALENDAR_DEBOUNCE_MS)
    }

    /** The provider observer; registered when READ_CALENDAR is granted, unregistered in onDestroy. */
    private var calendarObserver: ContentObserver? = null

    // ---- lifecycle ----------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // Enter foreground immediately so we satisfy the FGS start contract even on the
        // boot-restart path before we have any device info.
        startForegroundCompat(buildNotification("Idle", "Waiting for watch"))
        // WP13: observe the calendar provider for changes (debounced refresh of slots 16-31). Only
        // registers if READ_CALENDAR is already granted; a later grant in Setup pokes
        // ACTION_REFRESH_CALENDAR which (re-)registers via ensureCalendarObserver().
        ensureCalendarObserver()
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
                if (forgetting.get()) {
                    Log.d(TAG, "device appeared but a remove is in progress — ignoring")
                } else if (mac != null) {
                    if (isLinkUp()) {
                        Log.d(TAG, "device appeared but link already up — ignoring")
                    } else {
                        submitConnect(mac)
                    }
                }
            }
            ACTION_SYNC_NOW -> submitSync(
                parseSections(intent),
                forceProvision = intent?.getBooleanExtra(EXTRA_FORCE_PROVISION, false) == true,
            )
            ACTION_REQUEST_ACTIVITY -> submitRequestActivity(intent.getBooleanExtra(EXTRA_KEEP, false))
            ACTION_BUZZ -> submitBuzz(
                intent.getIntExtra(EXTRA_PATTERN, 5),
                intent.getBooleanExtra(EXTRA_FORCE_FILTER, false),
            )
            ACTION_PLAY_NOTIFICATION -> {
                val pkg = intent.getStringExtra(EXTRA_PACKAGE)
                if (pkg.isNullOrBlank()) {
                    Log.w(TAG, "ACTION_PLAY_NOTIFICATION with no package")
                } else {
                    submitPlayNotification(
                        pkg,
                        intent.getLongExtra(EXTRA_PLAY_DEADLINE, SystemClock.elapsedRealtime() + PLAY_STALE_AFTER_MS),
                    )
                }
            }
            ACTION_REFRESH_CALENDAR -> {
                // WP13: a user-initiated "Resync calendar now" or a permission (re-)grant in Setup.
                // (Re-)register the observer + refresh with a short settle-retry window (the
                // Instances table can still be expanding right after a server/laptop sync).
                ensureCalendarObserver()
                scheduleCalendarRefreshWithRetries()
            }
            ACTION_DISCONNECT -> submitDisconnect(stopAfter = false)
            ACTION_FORGET -> {
                // Remove watch: suppress auto-reconnect, then disconnect. The flag is cleared after
                // a short grace period so a later (re-)add can reconnect normally.
                forgetting.set(true)
                submitDisconnect(stopAfter = false)
                worker.execute {
                    try { Thread.sleep(FORGET_GRACE_MS) } catch (_: InterruptedException) {}
                    forgetting.set(false)
                }
            }
            ACTION_STOP -> submitDisconnect(stopAfter = true)
            else -> Log.d(TAG, "unhandled action $action")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        // WP13: unregister the calendar observer + cancel its refresh scope.
        calendarObserver?.let { runCatching { contentResolver.unregisterContentObserver(it) } }
        calendarObserver = null
        calendarScope.cancel()
        // HYBRID-AUTOCONNECT: tear down the background auto-connect keep-alive (cancels its pending
        // GATT so the controller won't silently reconnect) + stop the fallback scheduler.
        cancelReconnect()
        reconnectScheduler.shutdownNow()
        runCatching { controllerRef.getAndSet(null)?.disconnect() }
        transportRef.set(null)
        worker.shutdownNow()
    }

    // ---- WP13 calendar refresh ----------------------------------------------

    /**
     * Register the calendar [ContentObserver] (idempotent) if `READ_CALENDAR` is granted. The
     * observer debounces a burst of provider changes into ONE [scheduleCalendarRefresh]. No-op when
     * the permission isn't granted yet (the Setup grant pokes ACTION_REFRESH_CALENDAR to re-try).
     */
    private fun ensureCalendarObserver() {
        if (!CalendarAccess.isGranted(this)) {
            Log.d(TAG, "calendar: READ_CALENDAR not granted — observer not registered")
            return
        }
        if (calendarObserver != null) return
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                Log.d(TAG, "calendar changed (uri=$uri) — scheduling debounced refresh")
                scheduleCalendarRefresh()
            }
        }
        runCatching {
            // Observe the TOP-LEVEL calendar URI with notifyForDescendants=true so we catch changes
            // to BOTH the Events table (where a new/edited event lands IMMEDIATELY) and the
            // Instances table (the lazily-expanded occurrences). Observing only Instances meant we
            // sometimes only woke once the provider finished its async expansion (minutes later);
            // waking on the Events change lets the next read force-expand via Instances.query(...).
            contentResolver.registerContentObserver(
                CalendarContract.CONTENT_URI, /* notifyForDescendants */ true, observer,
            )
            calendarObserver = observer
            Log.i(TAG, "calendar observer registered")
        }.onFailure { Log.w(TAG, "calendar observer registration failed", it) }
    }

    /** Schedule a debounced calendar refresh (coalesces a burst into one read+map+replace+push). */
    private fun scheduleCalendarRefresh() {
        calendarDebouncer.schedule { calendarScope.launch { runCalendarRefresh() } }
    }

    /**
     * WP13 — a USER-INITIATED "Resync calendar now" (or a permission (re-)grant). Runs the refresh
     * immediately, then RE-RUNS it a few times spaced [CALENDAR_SETTLE_RETRY_MS] apart — but STOPS
     * EARLY as soon as a read succeeds AND finds at least one event, because the provider's
     * Instances table can be mid-expansion right after a server/laptop sync (a read can briefly come
     * back empty even via Instances.query(...)). Stopping early once events appear avoids both BLE
     * churn and the wipe-then-restore window. A FAILED read never wipes (CalendarRefresher guards
     * it); a genuinely-empty calendar simply uses up the retries harmlessly. Observer/connect
     * refreshes do NOT retry (the Events observer re-fires them naturally).
     */
    private fun scheduleCalendarRefreshWithRetries() {
        calendarScope.launch {
            val first = runCalendarRefresh()
            if (first != null && first.readOk && first.rowCount > 0) return@launch
            repeat(CALENDAR_SETTLE_RETRIES) {
                kotlinx.coroutines.delay(CALENDAR_SETTLE_RETRY_MS)
                val r = runCalendarRefresh()
                if (r != null && r.readOk && r.rowCount > 0) return@launch
            }
        }
    }

    /**
     * Read the calendar via [SystemCalendarSource], map via the pure WP9 path, full-replace slots
     * 16-31, and SILENTLY push the alarm file if the rows changed ([ServiceCalendarPush] — no
     * SYNCING modal). No-op (cheap, returns null) when READ_CALENDAR isn't granted. A FAILED
     * provider read leaves the existing slots 16–31 UNTOUCHED (never wiped).
     */
    private suspend fun runCalendarRefresh(): CalendarRefresher.Result? {
        if (!CalendarAccess.isGranted(this)) return null
        return runCatching {
            val refresher = CalendarRefresher(
                WatchRepository(applicationContext),
                SystemCalendarSource(applicationContext),
                // WP13: ring this many minutes before each event (Settings; default 1). Read each
                // refresh so a Settings change takes effect on the next observer/connect refresh.
                offsetMinutes = {
                    SharedPreferencesSettingsPrefs(applicationContext).get().calendarAlarmOffsetMinutes
                },
            )
            val result = refresher.refreshAndMaybePush(ServiceCalendarPush(applicationContext))
            Log.i(TAG, "calendar refresh: ok=${result.readOk} changed=${result.changed} rows=${result.rowCount}")
            result
        }.onFailure { Log.w(TAG, "calendar refresh failed", it) }.getOrNull()
    }

    // ---- work ----------------------------------------------------------------

    private fun isLinkUp(): Boolean = transportRef.get()?.isConnected() == true

    /**
     * Resolve the MAC to connect-then-do against (Save / buzz / play while the link is down).
     *
     * The CDM association ([CompanionManager.getAssociatedMac], a SharedPreferences blob) is NOT
     * the only source of truth for "which watch": the active watch lives in the Room DB, and the
     * two can legitimately disagree (e.g. a watch added via the already-bonded path, or the CDM
     * pref cleared/not yet written). MainActivity's Connect already falls back to the DB active
     * watch (connectActiveOrField), which is why Connect works while a Save reported
     * "No watch associated" — the save paths only looked at the CDM pref. Mirror that fallback here
     * so a save/buzz/play resolves the same watch Connect would. Runs on the ble-worker; the
     * suspending DB read is done with [runBlocking] safely (never the main thread).
     */
    private fun resolveTargetMac(): String? =
        CompanionManager.getAssociatedMac(this)
            ?: runCatching { runBlocking { WatchRepository(applicationContext).getActiveWatch()?.macAddress } }
                .getOrNull()

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

    /**
     * HYBRID-AUTOCONNECT — after an unexpected drop (or a user/save connect that timed out because
     * the watch was asleep), keep the link alive so a watch-initiated event (music control /
     * find-phone) always has a live control channel AND a connect-then-do that couldn't land yet
     * (e.g. a Save while the watch was asleep) eventually completes when the watch returns.
     *
     * The Fossil Q only directed-advertises to its bond and cannot ask the phone to reconnect. We
     * REPLACE the old app-level exponential-backoff connect loop with a SINGLE OS-managed BLE
     * auto-connect: build a fresh controller/transport and call connect(mac, autoConnect=true) on
     * the worker. {@code connectGatt(autoConnect=true)} does NOT time out — the controller keeps the
     * pending connect and fires when the watch reappears, at which point the transport's connection
     * callback (up=true) runs the on-link-up work via [onAutoConnectLinkUp]. This is battery-
     * friendly and never floods the single BLE control channel with retry connects.
     *
     * No double-driving: while an auto-connect is armed ([autoReconnectArmed]) we do NOT also fire
     * timed submitConnect()s. The ONLY timer kept is a minimal one-shot fallback that re-arms the
     * auto-connect if REGISTERING it failed (connect(...,true) returned false — e.g. BT momentarily
     * off). Suppressed while removing a watch ([forgetting]); cancelled on link-up / disconnect.
     */
    private fun armAutoReconnect(mac: String) {
        if (forgetting.get()) return
        if (isLinkUp()) return
        // Don't register a second pending auto-connect if one is already armed.
        if (!autoReconnectArmed.compareAndSet(false, true)) {
            Log.d(TAG, "armAutoReconnect($mac): already armed — skipping")
            return
        }
        Log.i(TAG, "armAutoReconnect($mac): registering OS BLE auto-connect (controller-managed)")
        worker.execute {
            if (forgetting.get() || isLinkUp()) {
                autoReconnectArmed.set(false)
                return@execute
            }
            // Build a fresh controller/transport for the background keep-alive and wire its
            // callbacks (drop → re-arm; link-up → run the on-link-up work). Replace any previous
            // auto-connect controller (get-and-disconnect-old) so only ONE pending GATT exists.
            val transport = AndroidBleTransport(applicationContext)
            val controller = FossilController(transport)
            wireConnectionCallbacks(controller, transport, mac, auto = true)
            autoReconnectController.getAndSet(controller)?.let { runCatching { it.disconnect() } }

            val registered = runCatching { controller.connect(mac, /*autoConnect=*/ true) }
                .getOrElse { e -> Log.w(TAG, "auto-connect register threw", e); false }
            if (!registered) {
                // Registering the auto-connect itself failed (e.g. BT off). Drop it and re-arm ONCE
                // after a short delay — the SINGLE fallback timer we keep. We never poll/connect on
                // a timer while a pending auto-connect exists, so the two mechanisms never both
                // drive connects.
                Log.w(TAG, "auto-connect failed to register for $mac — scheduling one fallback re-arm")
                autoReconnectController.compareAndSet(controller, null)
                runCatching { controller.disconnect() }
                autoReconnectArmed.set(false)
                scheduleAutoReconnectFallback(mac)
            }
        }
    }

    /**
     * HYBRID-AUTOCONNECT — the ONLY retry timer kept from the old machinery: a single one-shot that
     * re-arms the OS auto-connect if REGISTERING it failed (see [armAutoReconnect]). It does NOT run
     * the old 5-step exponential backoff. A new schedule supersedes the old.
     */
    private fun scheduleAutoReconnectFallback(mac: String) {
        if (forgetting.get()) return
        val future = reconnectScheduler.schedule({
            if (forgetting.get() || isLinkUp()) return@schedule
            val target = resolveTargetMac() ?: return@schedule
            armAutoReconnect(target)
        }, AUTO_RECONNECT_FALLBACK_MS, TimeUnit.MILLISECONDS)
        pendingReconnect.getAndSet(future)?.cancel(false)
    }

    /**
     * Cancel the background auto-connect keep-alive: tear down its pending GATT (so the controller
     * does NOT silently reconnect — BluetoothGatt.disconnect()+close() cancels a pending
     * autoConnect=true), cancel the fallback timer, and clear the armed flag. Called on link-up
     * (the foreground connect took over), on intentional disconnect, and on forget/onDestroy.
     */
    private fun cancelReconnect() {
        pendingReconnect.getAndSet(null)?.cancel(false)
        autoReconnectArmed.set(false)
        autoReconnectController.getAndSet(null)?.let { runCatching { it.disconnect() } }
    }

    /**
     * Wire the connection / auth / activity callbacks onto a freshly-built [controller]/[transport].
     * Shared by BOTH the foreground (autoConnect=false, [connectAndInit]) and background
     * (autoConnect=true, [armAutoReconnect]) paths so the on-link-up + on-drop handling is identical.
     *
     * On DROP: reflect Disconnected, drop the dead controller refs, and (unless forgetting) re-arm
     * CDM presence + a fresh OS BLE auto-connect keep-alive.
     *
     * On LINK-UP: only matters for the AUTO path. The foreground blocking connect() returns true and
     * runs init + on-link-up INLINE in [connectAndInit], so its accept(true) here is a no-op for
     * that path. For the auto path the link establishes asynchronously (possibly long after
     * connect(...) returned), so accept(true) is what drives init + the pending work — marshalled
     * onto [worker] (the transport is blocking; the callback arrives on the ble-gatt thread).
     */
    private fun wireConnectionCallbacks(
        controller: FossilController,
        transport: AndroidBleTransport,
        mac: String,
        auto: Boolean,
    ) {
        transport.setConnectionCallback { up ->
            if (up) {
                if (auto) onAutoConnectLinkUp(controller, transport, mac)
                return@setConnectionCallback
            }
            // up == false: unexpected drop OR intentional disconnect.
            publish(
                WatchState.LinkState.DISCONNECTED,
                message = "Disconnected",
                clearDeviceInfo = true,
            )
            // Drop the dead controller reference so isLinkUp()/controllerRef reflect the loss
            // (otherwise a stale controller lingers and a queued Save/connect thinks the link is
            // still being managed). Covers BOTH the foreground and the auto-reconnect controllers.
            controllerRef.compareAndSet(controller, null)
            autoReconnectController.compareAndSet(controller, null)
            if (auto) autoReconnectArmed.set(false)
            // WP-ONBOARD: do NOT re-arm auto-reconnect while removing a watch (Remove watch),
            // otherwise the just-removed watch immediately reconnects and appears un-removed.
            if (!forgetting.get()) {
                // Event-driven reconnect (CDM presence wakes us when the watch reappears) — the
                // complementary OS-level mechanism, kept alongside the auto-connect. Only the CDM
                // pref drives presence (it is a CDM API), so guard it on getAssociatedMac.
                CompanionManager.getAssociatedMac(this)?.let { CompanionManager.startObserving(this, it) }
                // HYBRID-AUTOCONNECT: hand the keep-alive to the OS BLE controller via
                // connect(mac, autoConnect=true) instead of an app-level backoff loop (see
                // [armAutoReconnect]). Resolve the watch the SAME way Connect/Save do (CDM pref OR
                // the Room active watch) so a DB-only watch still gets a background reconnect.
                // Idempotent + a no-op if the link is already back up.
                resolveTargetMac()?.let { armAutoReconnect(it) }
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
        // (same callback the CLI `activity` command uses).
        controller.onActivityData { bytes -> onActivityBytes(bytes) }
        // WP12: feed the watch's event JSON (music gestures emitted by a MUSIC_CONTROL button) into
        // the music dispatcher. Wired for BOTH the foreground and the auto-connect controllers so a
        // gesture is handled whichever controller owns the live link. The callback arrives on the
        // ble-gatt thread; [ServiceMusicDispatch] marshals the actual media calls onto the main
        // looper. NO new wire bytes — the JSON contract is already emitted by the adapter.
        controller.onEventJson { json -> musicDispatch.onEventJson(json) }
    }

    /**
     * HYBRID-AUTOCONNECT — the deferred background auto-connect link came UP. Promote this
     * controller to the foreground [controllerRef], run init, then the shared on-link-up work
     * ([runOnLinkUp]) — the SAME init + pending-sync/buzz/play + activity + calendar refresh the
     * foreground path runs. The transport's connection callback arrives on the ble-gatt thread, so
     * we marshal everything onto the single-thread [worker] (the transport is blocking).
     */
    private fun onAutoConnectLinkUp(
        controller: FossilController,
        transport: AndroidBleTransport,
        mac: String,
    ) {
        worker.execute {
            Log.i(TAG, "auto-connect link up for $mac — promoting + initializing")
            controllerRef.getAndSet(controller)?.let { prev ->
                if (prev !== controller) runCatching { prev.disconnect() }
            }
            transportRef.set(transport)
            autoReconnectController.compareAndSet(controller, null)
            autoReconnectArmed.set(false)
            try {
                publish(WatchState.LinkState.INITIALIZING, mac = mac, message = "Connected. Initializing…")
                controller.init(false)
                if (controller.isFossilProtocol()) {
                    if (!controller.waitForInit(INIT_TIMEOUT_MS)) {
                        Log.w(TAG, "auto-connect init may not have completed fully")
                    }
                }
                runOnLinkUp(controller, mac)
            } catch (e: Exception) {
                Log.e(TAG, "auto-connect init failed", e)
                publish(
                    WatchState.LinkState.DISCONNECTED,
                    message = "Error: ${e.message}",
                    clearDeviceInfo = true,
                )
                failPendingSync("Could not sync: ${e.message ?: e.javaClass.simpleName}")
                runCatching { controller.disconnect() }
                controllerRef.compareAndSet(controller, null)
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

        // HYBRID-AUTOCONNECT: user-initiated / first connects use the FAST bounded (autoConnect=false)
        // path — an unreachable watch fails honestly + quickly. The background keep-alive after a
        // drop uses autoConnect=true (see [armAutoReconnect]).
        wireConnectionCallbacks(controller, transport, mac, auto = false)

        try {
            if (!controller.connect(mac, /*autoConnect=*/ false)) {
                publish(
                    WatchState.LinkState.DISCONNECTED,
                    message = "Failed to connect (out of range / BT off / phone still bonded?)",
                    clearDeviceInfo = true,
                )
                // WP-SYNCFIX: a Save-to-watch that triggered this connect must NOT report success
                // when the watch is unreachable — surface an honest sync error instead.
                failPendingSync("Watch not reachable (out of range / Bluetooth off?)")
                controllerRef.compareAndSet(controller, null)
                // HYBRID-AUTOCONNECT: a fast (autoConnect=false) connect that TIMED OUT never fires
                // the drop callback, so arm the background OS auto-connect keep-alive here — the
                // watch may simply be asleep / briefly out of range and will accept the directed
                // connect when it wakes (so a save while the watch was asleep still eventually
                // lands). The immediate honest error was already reported to any pending save above.
                // Suppressed while removing a watch.
                if (!forgetting.get()) {
                    resolveTargetMac()?.let { armAutoReconnect(it) }
                }
                return
            }
            publish(WatchState.LinkState.INITIALIZING, mac = mac, message = "Connected. Initializing…")

            controller.init(false)
            if (controller.isFossilProtocol()) {
                if (!controller.waitForInit(INIT_TIMEOUT_MS)) {
                    Log.w(TAG, "init may not have completed fully")
                }
            }
            runOnLinkUp(controller, mac)
        } catch (e: Exception) {
            Log.e(TAG, "connect/init failed", e)
            publish(
                WatchState.LinkState.DISCONNECTED,
                message = "Error: ${e.message}",
                clearDeviceInfo = true,
            )
            // WP-SYNCFIX: surface the connect/init failure to a pending Save-to-watch.
            failPendingSync("Could not sync: ${e.message ?: e.javaClass.simpleName}")
            // WP-ONBOARD: if we were mid-provisioning a brand-new watch, the connect dropped before
            // it could finish — report FAILED so the add-watch modal resolves (watch NOT added).
            if (qhybrid.android.onboard.ProvisioningState.status.value.isProvisioning) {
                qhybrid.android.onboard.ProvisioningState.publish(
                    qhybrid.android.onboard.ProvisioningState.Phase.FAILED,
                    errorMessage = "Lost connection while setting up. Move the watch closer and try again.",
                    nowMillis = System.currentTimeMillis(),
                )
            }
            runCatching { controller.disconnect() }
            controllerRef.compareAndSet(controller, null)
        }
    }

    /**
     * Shared on-LINK-UP work, run for BOTH the foreground ([connectAndInit]) and background
     * ([onAutoConnectLinkUp]) paths once the link is established + init has run. Always on [worker].
     *
     * Publishes INITIALIZED, then runs the [ConnectSyncDecider] (provisioning / pending user sync),
     * any pending connect-then-buzz / connect-then-play, the on-connect activity fetch, and the
     * calendar refresh — and cancels the auto-connect keep-alive (the link is up now).
     */
    private fun runOnLinkUp(controller: FossilController, mac: String) {
            val initialized = controller.isFossilProtocol()
            if (initialized) {
                // HYBRID-AUTOCONNECT: link is up — tear down any pending auto-connect keep-alive
                // (a foreground connect or the auto-connect itself took over).
                cancelReconnect()
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
                val pendingProvision = pendingSyncProvision.getAndSet(false)
                val newWatch = isNewWatch(mac)
                when (val d = ConnectSyncDecider.decide(hadPendingSync, requestedSections, newWatch)) {
                    is ConnectSyncDecider.Decision.Sync -> {
                        if (newWatch && !hadPendingSync) {
                            // WP-ONBOARD: a brand-new watch (no DB row) is PROVISIONED here — a
                            // one-time, force-write pass that blanks the unreadable sections and lands
                            // the reserved buzz filter. The watch is "added" (row written) ONLY if the
                            // buzz-critical notification filter upload SUCCEEDS; otherwise we leave it
                            // unregistered so the next connect re-provisions (no half-added watch).
                            Log.i(TAG, "provisioning new watch $mac")
                            qhybrid.android.onboard.ProvisioningState.publish(
                                qhybrid.android.onboard.ProvisioningState.Phase.PROVISIONING,
                                mac = mac,
                                nowMillis = System.currentTimeMillis(),
                            )
                            val seeded = provisionNewWatch(controller, mac)
                            if (seeded != null) {
                                // WP-ONBOARD: persist the row SEEDED FROM THE WATCH'S read-back
                                // (real vibration/step goal) instead of registerWatch's constant
                                // defaults. Falls back to constants inside provisionNewWatch if the
                                // read-back failed/was empty (best-effort — never blocks adding).
                                // WP-DEFAULTS: also persist the re-keyed unreadable child rows that
                                // were pushed (alarms / rules / buttons), atomically with the parent.
                                registerSeededWatchRow(seeded)
                                qhybrid.android.onboard.ProvisioningState.publish(
                                    qhybrid.android.onboard.ProvisioningState.Phase.ADDED,
                                    mac = mac,
                                    nowMillis = System.currentTimeMillis(),
                                )
                            } else {
                                Log.w(TAG, "provisioning $mac did not confirm the reserved filter — NOT marking added (will retry next connect)")
                                qhybrid.android.onboard.ProvisioningState.publish(
                                    qhybrid.android.onboard.ProvisioningState.Phase.FAILED,
                                    mac = mac,
                                    errorMessage = "Couldn't set up the watch. Make sure it stays close and try again.",
                                    nowMillis = System.currentTimeMillis(),
                                )
                            }
                        } else {
                            // A user-requested sync that was pending while the link was down.
                            Log.i(TAG, "on-connect sync: ${d.reason} sections=${d.sections} forceProvision=$pendingProvision")
                            runOnConnectSync(controller, d.sections, forceProvision = pendingProvision)
                            // This is an already-known watch (the user asked to sync it); keep its row.
                            registerWatchRow(mac)
                            // Not a fresh provision — clear any optimistic "Adding…" modal.
                            clearOptimisticProvisioning()
                        }
                    }
                    ConnectSyncDecider.Decision.None -> {
                        Log.i(TAG, "known watch $mac — no auto-sync on connect (sync is user-initiated)")
                        // A plain reconnect of an already-added watch — if the UI optimistically
                        // showed an "Adding…" modal (it can't tell new-vs-known), clear it now.
                        clearOptimisticProvisioning()
                    }
                }
                // WP-BUZZTEST: a manual buzz requested while the link was down connects here, then
                // buzzes (we're already on the ble-worker). Runs AFTER any pending sync so it is
                // sequenced behind those writes on the single control channel.
                pendingBuzzPattern.getAndSet(null)?.let { pattern ->
                    runBuzz(controller, pattern, forceFilterPlay = pendingBuzzForceFilter.getAndSet(false))
                }
                // WP11: a notification that matched a rule while the link was down plays here, once
                // connected — UNLESS it has gone stale (older than PLAY_STALE_AFTER_MS), in which case
                // it is silently dropped so we never buzz minutes late for a dismissed notification.
                pendingPlayPackage.getAndSet(null)?.let { pkg ->
                    if (SystemClock.elapsedRealtime() <= pendingPlayDeadline.getAndSet(0L)) {
                        runPlayNotification(controller, pkg)
                    } else {
                        Log.i(TAG, "on-connect play: dropping stale notification play for $pkg")
                    }
                }
                // WP-ACTIVITY: also pull the activity file on connect so the Sleep screen +
                // Dashboard steps are populated hands-free. We are already on the ble-worker, so
                // drive the existing fetch path directly; the result is published by
                // onActivityData → onActivityBytes. Failures are non-fatal (logged).
                if (controller.isFossilProtocol()) {
                    runCatching { controller.requestActivity(false) }
                        .onFailure { Log.w(TAG, "on-connect activity fetch failed", it) }
                }
                // WP13: refresh the calendar on connect (mirror of WP11's on-connect rule-cache
                // refresh) so slots 16-31 track the user's calendar hands-free. Debounced + off the
                // ble-worker; a changed refresh silently pushes the alarm file (no modal).
                scheduleCalendarRefresh()
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
        // WP-CLEARALARMS: PROVISION force-writes empty sections (so a targeted clear blanks the
        // watch); the default RECONCILE skip-empties for ordinary saves.
        forceProvision: Boolean = false,
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
                SyncOrchestrator.sync(
                    input, ServiceUploader(controller), sections,
                    mode = if (forceProvision) SyncMode.PROVISION else SyncMode.RECONCILE,
                )
            }
            if (result != null) {
                Log.i(
                    TAG,
                    "sync done mac=${result.mac} performed=${result.performed} " +
                        "skipped=${result.skipped} errors=${result.errors}",
                )
                // WP-SYNCSTATUS: stamp each PERFORMED unreadable section's per-watch `…SyncedAt` so
                // the UI can mark rows "on watch". `performed` means the orchestrator ran the put
                // (our truest signal — the sections are unreadable, so there's no read-back). The
                // timestamp is captured AFTER the sync pass completes, so it is >= any row
                // `updatedAt` written earlier in the same connect (seed-then-sync stays on-watch).
                val mac = result.mac
                if (mac != null) {
                    val syncedAt = System.currentTimeMillis()
                    val repo = WatchRepository(applicationContext)
                    runBlocking {
                        if (SyncSection.ALARMS in result.performed) repo.setAlarmsSyncedAt(mac, syncedAt)
                        if (SyncSection.NOTIFICATION_FILTER in result.performed) repo.setNotificationFilterSyncedAt(mac, syncedAt)
                        if (SyncSection.BUTTONS in result.performed) repo.setButtonsSyncedAt(mac, syncedAt)
                    }
                }
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
     * WP-ONBOARD (Phase 1) — provision a BRAND-NEW watch (no DB row yet) on its first connect.
     *
     * Runs a one-time PROVISION sync that force-writes the unreadable sections to blank the watch to
     * the seed and, critically, uploads the notification filter — which folds in the reserved buzz
     * entries so a manual play-only buzz works. Phase 1 seeds an EMPTY config (no alarms/rules/
     * buttons yet — the WP-DEFAULTS profile + readable-settings read-back are deferred); the empty
     * alarm file still blanks the watch's 32 slots.
     *
     * Builds the [SyncInput] for [mac] directly (NOT via the DB loader, which reads the *active*
     * watch — a new watch has no row yet). Returns TRUE only if the buzz-critical NOTIFICATION_FILTER
     * upload was performed: the caller marks the watch "added" only then, so a failed provision
     * leaves no row and re-provisions next connect. Runs on the ble-worker.
     */
    /**
     * WP-ONBOARD — clear an OPTIMISTIC "Adding your watch…" modal back to IDLE. The add-watch UI
     * publishes PROVISIONING on tap (so the spinner is instant) without knowing whether the watch is
     * actually new; if the connect resolves to an already-added watch (no provisioning), we dismiss
     * the modal here. Only acts while still PROVISIONING (never overwrites a real ADDED/FAILED).
     */
    private fun clearOptimisticProvisioning() {
        if (qhybrid.android.onboard.ProvisioningState.status.value.isProvisioning) {
            qhybrid.android.onboard.ProvisioningState.publish(
                qhybrid.android.onboard.ProvisioningState.Phase.IDLE,
                nowMillis = System.currentTimeMillis(),
            )
        }
    }

    /**
     * Provision a brand-new watch (one-time, on first connect). Force-writes the UNREADABLE sections
     * (blank-to-seed) and uploads the reserved buzz notification filter via a PROVISION sync.
     *
     * WP-ONBOARD read-back: the READABLE settings (vibration strength, step goal, …) are READ FROM
     * THE WATCH via [FossilController.readConfig] and mapped by [ConfigToSeed], so re-adding a watch
     * shows its ACTUAL values — NOT the constant 50 / 10000. The read-back is BEST-EFFORT: a
     * failed/empty read falls back to the hardcoded constants and never blocks onboarding.
     *
     * @return the seeded [WatchEntity] to persist on success (the notification-filter upload
     *   succeeded — the unchanged WP-ONBOARD success gate), or `null` if provisioning failed (no row
     *   is written; the next connect re-provisions).
     */
    /**
     * WP-DEFAULTS — the seeded watch row PLUS the re-keyed unreadable child rows that were pushed,
     * so the caller can persist the parent + children atomically (the parent must exist before the
     * FK-bound children).
     */
    private data class ProvisionResult(
        val entity: qhybrid.android.db.WatchEntity,
        val seed: qhybrid.android.defaults.DefaultsToSeed.Seed,
    )

    private fun provisionNewWatch(controller: FossilController, mac: String): ProvisionResult? {
        return try {
            val model = controller.modelNumber
            val firmware = controller.firmwareVersion
            val battery = controller.batteryLevel

            // Phase 1: a minimal seed so the orchestrator has a target. The orchestrator runs the
            // PROVISION force-write (blanks the unreadable sections + lands the reserved buzz filter).
            val seed = qhybrid.android.db.WatchEntity(
                macAddress = mac.uppercase(),
                name = mac.uppercase(),
                model = model,
                firmwareVersion = firmware,
                batteryLevel = battery,
            )
            // WP-DEFAULTS: seed the UNREADABLE sections (alarms 0–15 / rules / buttons) from the
            // app-level defaults profile, re-keyed to this mac. Empty sections → empty seed (blanked
            // on the watch); the factory profile ships the three default buttons. This is the only
            // change to what provisioning pushes — the readable read-back is unchanged below.
            val profile = qhybrid.android.defaults.SharedPreferencesDefaultsProfileStore(applicationContext).get()
            val defaultsSeed = qhybrid.android.defaults.DefaultsToSeed.seed(profile, mac.uppercase())
            Log.i(
                TAG,
                "provision $mac: defaults seed — alarms=${defaultsSeed.alarms.size} " +
                    "rules=${defaultsSeed.rules.size} buttons=${defaultsSeed.buttons.size}",
            )
            val input = SyncInput(
                watch = seed,
                alarms = defaultsSeed.alarms,
                rules = defaultsSeed.rules,
                buttons = defaultsSeed.buttons,
                settings = SyncSettings(vibrationStrength = null),
            )
            val result = SyncStateReporter.reportAround(System::currentTimeMillis) {
                SyncOrchestrator.sync(
                    input,
                    ServiceUploader(controller),
                    SyncSection.ALL,
                    mode = SyncMode.PROVISION,
                )
            }
            val filterOk = result != null && SyncSection.NOTIFICATION_FILTER in result.performed
            Log.i(TAG, "provision $mac: performed=${result?.performed} filterOk=$filterOk")
            if (!filterOk) return null

            // WP-ONBOARD read-back: READ the watch's actual readable settings (best-effort). On
            // failure/empty, ConfigToSeed falls back to the hardcoded constants — never blocks.
            val entries = runCatching { controller.readConfig() }
                .onFailure { Log.w(TAG, "provision $mac: readConfig failed (using constants)", it) }
                .getOrDefault(emptyList())
            val seededSettings = qhybrid.android.onboard.ConfigToSeed.seed(
                entries,
                qhybrid.android.onboard.ConfigToSeed.DeviceInfo(
                    model = model,
                    firmwareVersion = firmware,
                    batteryLevel = battery,
                ),
            )
            Log.i(
                TAG,
                "provision $mac: read-back seed vibration=${seededSettings.vibrationStrength} " +
                    "stepGoal=${seededSettings.stepGoal} (configEntries=${entries.size})",
            )
            // WP-ONBOARD: seed the app-level (global) nudge / 2nd-timezone prefs from the read-back
            // — but ONLY when the watch actually HAS them set (read wins). A watch reporting nudge
            // OFF / 2nd-tz UNSET must NOT clobber an existing global pref (PrefSeedDecision policy).
            // Best-effort: a failure here never blocks adding the watch.
            runCatching { seedAppPrefsFromWatch(seededSettings) }
                .onFailure { Log.w(TAG, "provision $mac: seeding app prefs failed (non-fatal)", it) }

            // Persist the per-watch READABLE fields read from the watch (vibration + step goal),
            // plus the live device info. Absent on the watch → constants (handled by ConfigToSeed).
            ProvisionResult(
                entity = seed.copy(
                    vibrationStrength = seededSettings.vibrationStrength,
                    stepGoal = seededSettings.stepGoal,
                ),
                seed = defaultsSeed,
            )
        } catch (e: Exception) {
            Log.e(TAG, "provisionNewWatch($mac) failed", e)
            null
        }
    }

    /**
     * WP-ONBOARD — seed the app-level (global) nudge / 2nd-timezone prefs from a new watch's
     * read-back, applying the conservative [qhybrid.android.onboard.PrefSeedDecision] policy: write
     * ONLY values the watch actually has set (nudge enabled / a concrete tz offset); a watch's
     * "off"/"unset" never clobbers an existing global pref. Idempotent + best-effort.
     */
    private fun seedAppPrefsFromWatch(seeded: qhybrid.android.onboard.ConfigToSeed.SeededSettings) {
        val prefs = SharedPreferencesSettingsPrefs(applicationContext)
        val decision = qhybrid.android.onboard.PrefSeedDecision.decide(seeded, prefs.get())
        if (!decision.writesAnything) {
            Log.i(TAG, "provision: no app-pref seeding (watch has nudge off / 2nd-tz unset)")
            return
        }
        decision.nudge?.let { prefs.setNudge(it.enabled, it.minutes) }
        decision.secondTimezoneOffsetMinutes?.let { prefs.setSecondTimezoneOffset(it) }
        Log.i(
            TAG,
            "provision: seeded app prefs from watch — nudge=${decision.nudge} " +
                "secondTz=${decision.secondTimezoneOffsetMinutes}",
        )
    }

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
     * WP-ONBOARD — persist the new-watch row SEEDED FROM THE WATCH'S read-back (real vibration /
     * step goal + live device info), and mark it active. Used only on the new-watch provisioning
     * success path; a failure to persist is logged but does not crash provisioning (the watch is
     * already provisioned on-device). Runs on the ble-worker.
     */
    private fun registerSeededWatchRow(seeded: ProvisionResult) {
        runCatching {
            runBlocking {
                val repo = WatchRepository(applicationContext)
                // Upsert the parent watch row + mark active FIRST, then full-replace the FK-bound
                // unreadable child rows so the app DB reflects exactly what PROVISION pushed.
                repo.registerSeededWatch(seeded.entity)
                repo.replaceDefaultsSections(
                    seeded.entity.macAddress,
                    alarms = seeded.seed.alarms,
                    rules = seeded.seed.rules,
                    buttons = seeded.seed.buttons,
                )
            }
        }.onFailure { Log.w(TAG, "registerSeededWatch failed", it) }
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
    private fun submitSync(sections: Set<SyncSection>? = null, forceProvision: Boolean = false) {
        val target = sections ?: SyncSection.ALL
        worker.execute {
            val c = controllerRef.get()
            Log.i(TAG, "syncNow: controller=${c != null} linkUp=${isLinkUp()} sections=$target forceProvision=$forceProvision")
            if (c != null && isLinkUp()) {
                runCatching { runOnConnectSync(c, target, forceProvision = forceProvision) }
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
            // Link is down — connect first, then sync on connect. Resolve the watch the SAME way
            // Connect does (CDM pref OR the Room active watch), so a Save never reports
            // "No watch associated" while Connect works.
            val mac = resolveTargetMac()
            if (mac == null) {
                Log.w(TAG, "syncNow: no associated watch (no CDM pref + no active DB watch)")
                SyncState.publish(
                    SyncState.SyncPhase.ERROR,
                    errorMessage = "No watch associated.",
                    nowMillis = System.currentTimeMillis(),
                )
                return@execute
            }
            Log.i(TAG, "syncNow: link down — connecting then syncing ($mac) sections=$target forceProvision=$forceProvision")
            pendingSyncSections.set(target)
            pendingSyncProvision.set(forceProvision)
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
    private fun submitBuzz(pattern: Int, forceFilterPlay: Boolean = false) {
        worker.execute {
            val c = controllerRef.get()
            Log.i(TAG, "buzzNow: controller=${c != null} linkUp=${isLinkUp()} pattern=$pattern forceFilter=$forceFilterPlay")
            if (c != null && isLinkUp()) {
                runBuzz(c, pattern, forceFilterPlay)
                return@execute
            }
            // Link is down — connect first, then buzz on connect. Resolve the watch the SAME way
            // Connect does (CDM pref OR the Room active watch).
            val mac = resolveTargetMac()
            if (mac == null) {
                Log.w(TAG, "buzzNow: no associated watch (no CDM pref + no active DB watch)")
                SyncState.publish(
                    SyncState.SyncPhase.ERROR,
                    errorMessage = "No watch associated.",
                    nowMillis = System.currentTimeMillis(),
                )
                return@execute
            }
            Log.i(TAG, "buzzNow: link down — connecting then buzzing ($mac) pattern=$pattern forceFilter=$forceFilterPlay")
            pendingBuzzPattern.set(pattern)
            pendingBuzzForceFilter.set(forceFilterPlay)
            submitConnect(mac)
        }
    }

    /**
     * WP11 — play a matched notification on the watch. If the link is up, play immediately on the
     * ble-worker; if it is DOWN, do a **connect-then-play** (mirroring [submitBuzz]) by holding the
     * package and kicking a connect, EXCEPT the held play is dropped if it goes stale (past
     * [deadline]) so a passive notification never buzzes minutes late. With no associated watch the
     * play is dropped (no error surfaced — a notification play is best-effort and silent).
     */
    private fun submitPlayNotification(packageName: String, deadline: Long) {
        worker.execute {
            val c = controllerRef.get()
            Log.i(TAG, "playNotification: controller=${c != null} linkUp=${isLinkUp()} package=$packageName")
            if (c != null && isLinkUp()) {
                runPlayNotification(c, packageName)
                return@execute
            }
            val mac = resolveTargetMac()
            if (mac == null) {
                Log.w(TAG, "playNotification: no associated watch (no CDM pref + no active DB watch) — dropping play for $packageName")
                return@execute
            }
            Log.i(TAG, "playNotification: link down — connecting then playing ($mac) package=$packageName")
            pendingPlayPackage.set(packageName)
            pendingPlayDeadline.set(deadline)
            submitConnect(mac)
        }
    }

    /**
     * WP11 — perform the actual notification play on the ble-worker via a SINGLE play-only put
     * ([FossilController.playNotification]): the watch already holds this package's per-app vibe +
     * hand degrees in its NOTIFICATION_FILTER (written at init/provisioning and on WP14 rule edits),
     * so it matches the play file's package CRC to that entry and applies the configured behavior.
     * Invents NO new wire bytes. Publishes NO [SyncState] (a silent background effect); failures are
     * non-fatal (logged). Always called on the ble-worker (from [submitPlayNotification] or the
     * on-connect hook).
     */
    private fun runPlayNotification(controller: FossilController, packageName: String) {
        Log.i(TAG, "play notification (play-only): package=$packageName")
        runCatching { controller.playNotification(packageName) }
            .onFailure { e -> Log.e(TAG, "play notification failed for $packageName", e) }
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
    private fun runBuzz(controller: FossilController, pattern: Int, forceFilterPlay: Boolean = false) {
        val now = System.currentTimeMillis()
        SyncState.publish(SyncState.SyncPhase.SYNCING, nowMillis = now)
        runCatching {
            val reserved = qhybrid.protocol.requests.fossil.notification.BuzzPatterns.isReservedPattern(pattern)
            if (reserved && !forceFilterPlay) {
                Log.i(TAG, "buzz (play-only): pattern=$pattern")
                controller.buzzPlayOnly(pattern)
            } else {
                // forceFilterPlay (diagnostic "put filter + send buzz") OR a non-reserved pattern:
                // the self-contained two-put path (NOTIFICATION_FILTER + NOTIFICATION_PLAY) that
                // works even when the reserved buzz filter isn't on the watch.
                Log.i(TAG, "buzz (filter+play): pattern=$pattern reserved=$reserved force=$forceFilterPlay")
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
        // HYBRID-AUTOCONNECT: an INTENTIONAL disconnect / ACTION_STOP / Remove-watch must not be
        // fought by the background auto-connect. cancelReconnect() tears down the pending
        // autoConnect=true GATT (disconnect()+close() cancels it) so the controller won't silently
        // re-establish the link, and cancels the fallback timer.
        cancelReconnect()
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
