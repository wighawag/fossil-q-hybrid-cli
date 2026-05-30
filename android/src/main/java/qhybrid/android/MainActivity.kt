package qhybrid.android

import android.Manifest
import android.content.IntentSender
import android.content.pm.PackageManager
import android.companion.CompanionDeviceManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import qhybrid.android.alarms.AlarmsScreen
import qhybrid.android.buttons.ButtonsScreen
import qhybrid.android.calibration.CalibrationScreen
import qhybrid.android.settings.SettingsScreen
import qhybrid.android.sleep.SleepActivityScreen
import qhybrid.android.log.LogConsole
import qhybrid.android.dashboard.DashboardScreen
import qhybrid.android.notifications.NotificationsScreen
import qhybrid.android.debug.DebugMenu
import qhybrid.android.debug.DebugMenuScreen
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * WP3 thin client.
 *
 * Ownership of the FossilController/transport has moved into [WatchConnectionService];
 * this Activity now only:
 *   - requests Bluetooth permission,
 *   - drives CompanionDeviceManager association (the one-time pairing entry point),
 *   - offers a battery-optimization exemption prompt,
 *   - fires "connect now" / "sync now" / "disconnect" service actions,
 *   - observes [WatchState.status] (StateFlow) and renders link/battery/firmware,
 * reusing WP2's "auth prompt only when the watch requests it" behaviour (now surfaced
 * as the AUTH_REQUIRED link state, also shown in the persistent notification).
 */
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "FossilQ-WP3"
    }

    // Registered before STARTED (field init runs during onCreate before super) so it is
    // valid to launch from onCreate.
    private val notifPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 13+: the foreground-service notification needs runtime POST_NOTIFICATIONS.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppScaffold()
                }
            }
        }
    }

    /**
     * Hosts the home screen plus a top-right gear that opens the Debug Menu. The gear is
     * shown ONLY in debug builds ([DebugMenu.isEnabled] == BuildConfig.DEBUG) so the
     * developer surface never ships enabled in a release build (WP15 requirement).
     */
    /**
     * WP16: the bottom-nav home tabs (Dashboard = WP16a, Alarms = WP16b,
     * Notifications = WP16c, Buttons = WP16d, Calibration = WP16e, Sleep = WP16f,
     * Settings = WP16g — the seventh and last user-facing screen).
     */
    private enum class HomeTab { DASHBOARD, ALARMS, NOTIFICATIONS, BUTTONS, CALIBRATION, SLEEP, SETTINGS }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun AppScaffold() {
        var showDebug by remember { mutableStateOf(false) }
        // WP16a: the Dashboard is the home content; the WP3 setup flow (permissions /
        // CDM associate / battery exemption) moves behind a top-right gear so it stays
        // reachable for first-run pairing without cluttering the dashboard.
        var showSetup by remember { mutableStateOf(false) }
        // WP16g: the per-watch Settings tab (WP16g) has a "View logs" entry that opens the
        // existing WP15 log viewer (LogConsole) as an overlay surface — NOT a second viewer,
        // and reachable in release builds too (the Debug Menu stays release-gated).
        var showLogs by remember { mutableStateOf(false) }
        // WP-DEFAULTS: the Settings tab has a "Defaults for new watches" entry that opens the
        // app-level defaults editor as an overlay surface (same overlay pattern as the log viewer).
        var showDefaults by remember { mutableStateOf(false) }
        // WP16b: bottom-nav between the Dashboard and the Alarms screen. The Setup/Debug
        // gears overlay on top of whichever home tab is selected.
        var tab by remember { mutableStateOf(HomeTab.DASHBOARD) }
        val onHome = !showDebug && !showSetup && !showLogs && !showDefaults
        // WP-SYNCSTATUS (Step 3, approach a): ONE shared guard the active editable screen
        // (Alarms/Notifications/Buttons) publishes its pending-to-watch count + Save action into.
        // The host consults it before navigating away (tab switch OR system back) and, when there
        // are unsaved-to-watch changes, defers the navigation and shows a "Save to watch?" prompt.
        val leaveGuard = remember { qhybrid.android.sync.LeaveGuardState() }
        // The navigation to run once the user resolves the leave-prompt (null = no prompt pending).
        var pendingNav by remember { mutableStateOf<(() -> Unit)?>(null) }
        // Gate a navigation through the leave-prompt: defer it when the current screen has pending
        // changes, otherwise run it immediately.
        val requestLeave: (() -> Unit) -> Unit = { action ->
            if (leaveGuard.shouldPrompt) pendingNav = action else action()
        }
        // Bumped whenever an association completes so the "already paired" bonded-watch list (and
        // anything else watch-registry-derived) recomputes WITHOUT needing an app relaunch.
        var bondedRefresh by remember { mutableStateOf(0) }

        // Hoisted CDM association launcher so BOTH the Dashboard "Add watch" CTA and the Setup
        // screen's "Associate watch" can start the OS device chooser. The chosen device's MAC is
        // persisted + the watch registered; null/cancelled is ignored.
        val associateLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult()
        ) { result ->
            val chosenMac = extractChosenMac(result.data)
            if (chosenMac != null) {
                Log.i(TAG, "Associated with $chosenMac")
                onAssociated(chosenMac)
                bondedRefresh++ // drop the just-added watch from the "already paired" list
            } else {
                Log.w(TAG, "Association cancelled / no device (resultCode=${result.resultCode})")
            }
        }
        // Add a watch by SCAN (no MAC). Fossil-only is the default; "show all" is the fallback when
        // the Fossil filter finds nothing (e.g. the watch's advertised name changed after a prior
        // pairing). Manual-MAC entry lives in Setup (the reliable path for an already-bonded watch).
        val addWatchByScan: () -> Unit = {
            startAssociate(null, associateLauncher::launch, CompanionManager.ScanMode.FOSSIL)
        }
        val addWatchShowAll: () -> Unit = {
            startAssociate(null, associateLauncher::launch, CompanionManager.ScanMode.ALL)
        }
        val openSetupForMac: () -> Unit = { showSetup = true; showDebug = false; showLogs = false; showDefaults = false }
        // WP-DEFAULTS: system-back closes the defaults editor / log overlays back to the home tabs.
        androidx.activity.compose.BackHandler(enabled = showDefaults) { showDefaults = false }
        // WP-SYNCSTATUS (Step 3): system-back on an editable tab WITH unsaved-to-watch changes
        // prompts (Save / Leave / Cancel) instead of leaving silently. We defer the back action
        // (go to the Dashboard tab) until the user resolves the prompt. Enabled only when the guard
        // says so AND we're on a home tab (the overlay back-handlers above take precedence).
        androidx.activity.compose.BackHandler(enabled = onHome && leaveGuard.shouldPrompt) {
            pendingNav = { tab = HomeTab.DASHBOARD }
        }
        // Already-bonded (OS-paired) Fossil watches that aren't added in the app yet — offered for
        // one-tap add so the user need not "Forget" + re-pair. Recomputed when entering the home
        // surface (cheap; reads the OS bonded-device list). Adding one associates by its exact MAC.
        val bondedWatches: List<Pair<String, String>> = remember(onHome, bondedRefresh) {
            if (hasPermissions()) {
                CompanionManager.bondedFossilWatches(this).map { it.mac to it.name }
            } else emptyList()
        }
        // The watch is ALREADY OS-bonded, so CDM association (which does a BLE SCAN to confirm the
        // device) can't be used: a bonded watch directed-advertises and the scan never sees it (the
        // chooser would spin forever / silently do nothing). We don't need CDM to bond it — it's
        // already bonded — so we adopt it directly: persist the associated MAC, arm presence, and
        // connect by MAC (the same direct-connect path the CLI uses). The service then provisions it
        // (no DB row yet) so the reserved buzz filter is uploaded.
        val addBondedWatch: (String) -> Unit = { mac ->
            Log.i(TAG, "Adopting already-bonded watch $mac (skip CDM scan)")
            onAssociated(mac)
            bondedRefresh++
        }
        val title = when {
            showDebug -> "Debug Menu"
            showSetup -> "Setup"
            showLogs -> "Logs"
            showDefaults -> "Defaults for new watches"
            tab == HomeTab.ALARMS -> "Alarms"
            tab == HomeTab.NOTIFICATIONS -> "Notifications"
            tab == HomeTab.BUTTONS -> "Buttons"
            tab == HomeTab.CALIBRATION -> "Calibration"
            tab == HomeTab.SLEEP -> "Sleep & Activity"
            tab == HomeTab.SETTINGS -> "Settings"
            else -> "Fossil Q"
        }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(title) },
                    actions = {
                        IconButton(onClick = { showSetup = !showSetup; showDebug = false; showLogs = false; showDefaults = false }) {
                            Icon(Icons.Filled.Settings, contentDescription = "Setup")
                        }
                        if (DebugMenu.isEnabled()) {
                            IconButton(onClick = { showDebug = !showDebug; showSetup = false; showLogs = false; showDefaults = false }) {
                                Icon(Icons.Filled.Build, contentDescription = "Debug menu")
                            }
                        }
                    },
                )
            },
            bottomBar = {
                // Only show the home tabs while on the home surface (not Setup/Debug).
                if (onHome) {
                    NavigationBar {
                        NavigationBarItem(
                            selected = tab == HomeTab.DASHBOARD,
                            onClick = { requestLeave { tab = HomeTab.DASHBOARD } },
                            icon = { Icon(Icons.Filled.Home, contentDescription = "Dashboard") },
                            label = { Text("Dashboard") },
                        )
                        NavigationBarItem(
                            selected = tab == HomeTab.ALARMS,
                            onClick = { requestLeave { tab = HomeTab.ALARMS } },
                            icon = { Icon(Icons.Filled.Notifications, contentDescription = "Alarms") },
                            label = { Text("Alarms") },
                        )
                        NavigationBarItem(
                            selected = tab == HomeTab.NOTIFICATIONS,
                            onClick = { requestLeave { tab = HomeTab.NOTIFICATIONS } },
                            icon = { Icon(Icons.Filled.Email, contentDescription = "Notifications") },
                            label = { Text("Notifications") },
                        )
                        NavigationBarItem(
                            selected = tab == HomeTab.BUTTONS,
                            onClick = { requestLeave { tab = HomeTab.BUTTONS } },
                            icon = { Icon(Icons.Filled.Star, contentDescription = "Buttons") },
                            label = { Text("Buttons") },
                        )
                        NavigationBarItem(
                            selected = tab == HomeTab.CALIBRATION,
                            onClick = { requestLeave { tab = HomeTab.CALIBRATION } },
                            icon = { Icon(Icons.Filled.Refresh, contentDescription = "Calibration") },
                            label = { Text("Calibrate") },
                        )
                        NavigationBarItem(
                            selected = tab == HomeTab.SLEEP,
                            onClick = { requestLeave { tab = HomeTab.SLEEP } },
                            icon = { Icon(Icons.Filled.DateRange, contentDescription = "Sleep & Activity") },
                            label = { Text("Sleep") },
                        )
                        NavigationBarItem(
                            selected = tab == HomeTab.SETTINGS,
                            onClick = { requestLeave { tab = HomeTab.SETTINGS } },
                            icon = { Icon(Icons.Filled.Info, contentDescription = "Settings") },
                            label = { Text("Settings") },
                        )
                    }
                }
            },
        ) { padding ->
            Box(modifier = Modifier.padding(padding)) {
                when {
                    showDebug && DebugMenu.isEnabled() -> DebugMenuScreen()
                    showSetup -> HomeScreen()
                    showLogs -> LogConsole()
                    showDefaults -> qhybrid.android.defaults.DefaultsScreen()
                    tab == HomeTab.ALARMS -> AlarmsScreen(leaveGuard = leaveGuard)
                    tab == HomeTab.NOTIFICATIONS -> NotificationsScreen(leaveGuard = leaveGuard)
                    tab == HomeTab.BUTTONS -> ButtonsScreen(leaveGuard = leaveGuard)
                    tab == HomeTab.CALIBRATION -> CalibrationScreen()
                    tab == HomeTab.SLEEP -> SleepActivityScreen()
                    tab == HomeTab.SETTINGS -> SettingsScreen(
                        onOpenLogs = { showLogs = true },
                        onOpenDefaults = { showDefaults = true },
                    )
                    else -> DashboardScreen(
                        onAddWatch = addWatchByScan,
                        onShowAllDevices = addWatchShowAll,
                        onEnterMacManually = openSetupForMac,
                        bondedWatches = bondedWatches,
                        onAddBondedWatch = addBondedWatch,
                    )
                }

                // WP-SYNCSTATUS (Step 3): the leave-with-pending prompt. When a navigation was
                // deferred (pendingNav != null) because the current editable screen has
                // unsaved-to-watch changes, offer Save (push then leave) / Leave (rows stay in the
                // DB) / Cancel (stay). NO silent auto-save, NO background-save.
                pendingNav?.let { nav ->
                    val save = leaveGuard.save
                    AlertDialog(
                        onDismissRequest = { pendingNav = null },
                        title = { Text("Save to watch?") },
                        text = {
                            Text(
                                "You have changes that aren't on the watch yet. Save them to the " +
                                    "watch before leaving, or leave (your changes stay saved in the app)."
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                // Push to the watch (the existing blocking SyncSavingDialog shows on
                                // the screen), then leave. The targeted save is fire-and-forget; the
                                // rows are already in the DB so leaving is safe regardless.
                                save?.invoke()
                                pendingNav = null
                                nav()
                            }) { Text("Save") }
                        },
                        dismissButton = {
                            Row {
                                TextButton(onClick = { pendingNav = null; nav() }) { Text("Leave") }
                                TextButton(onClick = { pendingNav = null }) { Text("Cancel") }
                            }
                        },
                    )
                }
            }
        }
    }

    @Composable
    private fun HomeScreen() {
        val status by WatchState.status.collectAsStateWithLifecycle()
        // Prefill with the already-associated watch's MAC if any; otherwise EMPTY (the user adds a
        // watch by scanning for Fossil watches, or types a MAC to re-pair a known/bonded one).
        var mac by remember {
            mutableStateOf(CompanionManager.getAssociatedMac(this) ?: "")
        }
        var permMsg by remember {
            mutableStateOf(
                if (hasPermissions()) "Permissions granted."
                else "Grant Bluetooth permission first."
            )
        }

        val permissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result ->
            permMsg = if (result.values.all { it }) "Permissions granted."
            else "Permissions DENIED — connect will fail."
        }

        // Fires the CDM chooser IntentSender; on success we persist the chosen MAC,
        // arm presence, and connect.
        val associateLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult()
        ) { result ->
            val chosenMac = extractChosenMac(result.data)
            if (chosenMac != null) {
                Log.i(TAG, "Associated with $chosenMac")
                mac = chosenMac
                onAssociated(chosenMac)
            } else {
                Log.w(TAG, "Association cancelled / no device (resultCode=${result.resultCode})")
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Fossil Q — Background Link (WP3)", style = MaterialTheme.typography.titleLarge)

            Text("Link: ${status.link}", style = MaterialTheme.typography.labelLarge)
            status.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Text(
                buildString {
                    append("Model: ").append(status.model ?: "—").append("   ")
                    append("FW: ").append(status.firmware ?: "—").append("   ")
                    append("Batt: ").append(status.battery?.let { "$it%" } ?: "—")
                },
                style = MaterialTheme.typography.bodySmall
            )

            Button(
                onClick = { permissionLauncher.launch(requiredPermissions()) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Request Bluetooth permission") }
            Text(permMsg, style = MaterialTheme.typography.bodySmall)

            // Primary add path: scan for Fossil watches (no MAC) — the OS chooser is name-filtered.
            Button(
                onClick = { startAssociate(null, associateLauncher::launch) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Scan for Fossil watch") }

            Text(
                "Or enter a MAC to re-pair a known watch (an already-paired watch won't show in a " +
                    "scan because it only advertises to its bonded device):",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = mac,
                onValueChange = { mac = it },
                label = { Text("Watch MAC (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedButton(
                onClick = { startAssociate(mac.trim().ifEmpty { null }, associateLauncher::launch) },
                enabled = mac.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Associate by MAC") }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    modifier = Modifier.fillMaxWidth(0.5f),
                    onClick = {
                        if (!hasPermissions()) {
                            permMsg = "Missing Bluetooth permission — tap 'Request' first."
                            return@Button
                        }
                        WatchConnectionService.connectNow(this@MainActivity, mac.trim().ifEmpty { null })
                    }
                ) { Text("Connect now") }

                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { WatchConnectionService.disconnect(this@MainActivity) }
                ) { Text("Disconnect") }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(0.5f),
                    onClick = { WatchConnectionService.syncNow(this@MainActivity) }
                ) { Text("Sync now") }

                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { CompanionManager.requestIgnoreBatteryOptimizations(this@MainActivity) }
                ) { Text("Battery exempt") }
            }

            Text(
                if (CompanionManager.isIgnoringBatteryOptimizations(this@MainActivity))
                    "Battery optimization: exempt ✅"
                else "Battery optimization: NOT exempt — Doze may kill the link",
                style = MaterialTheme.typography.bodySmall
            )

            // WP10/WP11 — Notification Access (the special "notification listener" permission). Not
            // a runtime permission: the user toggles it in system Settings. We detect the grant
            // state and deep-link to that screen. Tap the button, toggle it on, come back — the
            // status refreshes on return (the launcher's result callback re-reads the grant).
            var notifAccess by remember {
                mutableStateOf(qhybrid.android.notifications.NotificationAccess.isGranted(this@MainActivity))
            }
            val notifAccessLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult()
            ) {
                // The Settings screen returns no result; just re-read the grant state on return.
                notifAccess = qhybrid.android.notifications.NotificationAccess.isGranted(this@MainActivity)
            }
            OutlinedButton(
                onClick = {
                    notifAccessLauncher.launch(
                        qhybrid.android.notifications.NotificationAccess.settingsIntent()
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Grant notification access") }
            Text(
                if (notifAccess)
                    "Notification access: granted ✅ — app notifications can buzz the watch"
                else "Notification access: NOT granted — per-app notification rules won't fire",
                style = MaterialTheme.typography.bodySmall
            )

            // WP13/WP10 — Calendar access (READ_CALENDAR). A NORMAL runtime permission (unlike
            // notification access), so it is requested via the permissions API. On grant we poke
            // the WP3 service to read the calendar + fill alarm slots 16-31 (silent push). The
            // state re-reads from the permission result.
            var calendarAccess by remember {
                mutableStateOf(qhybrid.android.calendar.CalendarAccess.isGranted(this@MainActivity))
            }
            val calendarPermLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { granted ->
                calendarAccess = qhybrid.android.calendar.CalendarAccess.isGranted(granted)
                if (granted) {
                    // (Re-)register the observer + do an immediate calendar refresh of slots 16-31.
                    WatchConnectionService.refreshCalendarNow(this@MainActivity)
                }
            }
            OutlinedButton(
                onClick = {
                    calendarPermLauncher.launch(qhybrid.android.calendar.CalendarAccess.PERMISSION)
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Grant calendar access") }
            Text(
                if (calendarAccess)
                    "Calendar access: granted ✅ — upcoming events fill the watch's calendar alarms"
                else "Calendar access: NOT granted — calendar events won't sync to the watch",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }

    /**
     * Begin CDM association; the chooser IntentSender is fired via [launch].
     *
     * [mac] null/blank → scan for watches ([scanMode] = Fossil-only by default, or ALL devices as a
     * fallback when the Fossil filter shows nothing). A valid MAC → a single-device request for that
     * exact address (re-pair a known/bonded watch). An invalid MAC string falls back to the scan.
     */
    private fun startAssociate(
        mac: String?,
        launch: (IntentSenderRequest) -> Unit,
        scanMode: CompanionManager.ScanMode = CompanionManager.ScanMode.FOSSIL,
    ) {
        if (!hasPermissions()) {
            Log.w(TAG, "associate: missing Bluetooth permission")
        }
        val filterMac = mac?.takeIf { android.bluetooth.BluetoothAdapter.checkBluetoothAddress(it) }
        CompanionManager.associate(
            context = this,
            mac = filterMac,
            scanMode = scanMode,
            callback = object : CompanionDeviceManager.Callback() {
                override fun onDeviceFound(chooserLauncher: IntentSender) {
                    // May arrive on a binder thread — bounce to main to fire the launcher.
                    runOnUiThread {
                        launch(IntentSenderRequest.Builder(chooserLauncher).build())
                    }
                }

                override fun onFailure(error: CharSequence?) {
                    Log.e(TAG, "CDM association failed: $error")
                }
            },
            onAlreadyAssociated = { existing ->
                runOnUiThread { onAssociated(existing) }
            }
        )
    }

    private fun onAssociated(mac: String) {
        // Show the "Adding your watch…" spinner IMMEDIATELY (not ~10s later when the service finally
        // connects). This is published OPTIMISTICALLY for any associate; the SERVICE is the source
        // of truth and will (a) drive it ADDED/FAILED if it really provisions, or (b) CLEAR it back
        // to IDLE if the watch turns out to be already-added (a plain reconnect). So we don't need a
        // fragile main-thread "is this new?" check here.
        qhybrid.android.onboard.ProvisioningState.publish(
            qhybrid.android.onboard.ProvisioningState.Phase.PROVISIONING,
            mac = mac,
            nowMillis = System.currentTimeMillis(),
        )
        CompanionManager.setAssociatedMac(this, mac)
        CompanionManager.startObserving(this, mac)
        ReconnectFallback.arm(this, mac)
        // Connect + init. The SERVICE registers the Room row itself, AFTER deciding newness
        // (isNewWatch) so a brand-new watch still gets its one-time provisioning sync (which uploads
        // the notification filter incl. the reserved buzz entries). We deliberately do NOT register
        // the row here: doing so raced the connect and could mark the watch "known" before the
        // newness check, skipping provisioning — which left a freshly-added watch unable to buzz
        // (the play-only buzz needs the reserved filter that provisioning writes).
        WatchConnectionService.connectNow(this, mac)
    }

    /** Pull the chosen device's MAC from the chooser result, across API variants. */
    private fun extractChosenMac(data: android.content.Intent?): String? {
        data ?: return null
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val assoc = data.getParcelableExtra(
                    CompanionDeviceManager.EXTRA_ASSOCIATION,
                    android.companion.AssociationInfo::class.java
                )
                assoc?.deviceMacAddress?.toString()?.uppercase()
            } else {
                @Suppress("DEPRECATION")
                when (val extra = data.getParcelableExtra<android.os.Parcelable>(
                    CompanionDeviceManager.EXTRA_DEVICE
                )) {
                    is android.bluetooth.BluetoothDevice -> extra.address?.uppercase()
                    is android.bluetooth.le.ScanResult -> extra.device?.address?.uppercase()
                    else -> null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "extractChosenMac failed", e)
            null
        }
    }

    private fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun hasPermissions(): Boolean = requiredPermissions().all {
        checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }
}
