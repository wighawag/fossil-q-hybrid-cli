package qhybrid.android

import android.Manifest
import android.content.IntentSender
import android.content.pm.PackageManager
import android.companion.CompanionDeviceManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import qhybrid.android.db.WatchRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Home
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import qhybrid.android.alarms.AlarmsScreen
import qhybrid.android.buttons.ButtonsScreen
import qhybrid.android.calibration.CalibrationScreen
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
        private const val DEFAULT_MAC = "D9:20:71:11:74:2A"
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
     * Notifications = WP16c, Buttons = WP16d, Calibration = WP16e).
     */
    private enum class HomeTab { DASHBOARD, ALARMS, NOTIFICATIONS, BUTTONS, CALIBRATION }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun AppScaffold() {
        var showDebug by remember { mutableStateOf(false) }
        // WP16a: the Dashboard is the home content; the WP3 setup flow (permissions /
        // CDM associate / battery exemption) moves behind a top-right gear so it stays
        // reachable for first-run pairing without cluttering the dashboard.
        var showSetup by remember { mutableStateOf(false) }
        // WP16b: bottom-nav between the Dashboard and the Alarms screen. The Setup/Debug
        // gears overlay on top of whichever home tab is selected.
        var tab by remember { mutableStateOf(HomeTab.DASHBOARD) }
        val onHome = !showDebug && !showSetup
        val title = when {
            showDebug -> "Debug Menu"
            showSetup -> "Setup"
            tab == HomeTab.ALARMS -> "Alarms"
            tab == HomeTab.NOTIFICATIONS -> "Notifications"
            tab == HomeTab.BUTTONS -> "Buttons"
            tab == HomeTab.CALIBRATION -> "Calibration"
            else -> "Fossil Q"
        }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(title) },
                    actions = {
                        IconButton(onClick = { showSetup = !showSetup; showDebug = false }) {
                            Icon(Icons.Filled.Settings, contentDescription = "Setup")
                        }
                        if (DebugMenu.isEnabled()) {
                            IconButton(onClick = { showDebug = !showDebug; showSetup = false }) {
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
                            onClick = { tab = HomeTab.DASHBOARD },
                            icon = { Icon(Icons.Filled.Home, contentDescription = "Dashboard") },
                            label = { Text("Dashboard") },
                        )
                        NavigationBarItem(
                            selected = tab == HomeTab.ALARMS,
                            onClick = { tab = HomeTab.ALARMS },
                            icon = { Icon(Icons.Filled.Notifications, contentDescription = "Alarms") },
                            label = { Text("Alarms") },
                        )
                        NavigationBarItem(
                            selected = tab == HomeTab.NOTIFICATIONS,
                            onClick = { tab = HomeTab.NOTIFICATIONS },
                            icon = { Icon(Icons.Filled.Email, contentDescription = "Notifications") },
                            label = { Text("Notifications") },
                        )
                        NavigationBarItem(
                            selected = tab == HomeTab.BUTTONS,
                            onClick = { tab = HomeTab.BUTTONS },
                            icon = { Icon(Icons.Filled.Star, contentDescription = "Buttons") },
                            label = { Text("Buttons") },
                        )
                        NavigationBarItem(
                            selected = tab == HomeTab.CALIBRATION,
                            onClick = { tab = HomeTab.CALIBRATION },
                            icon = { Icon(Icons.Filled.Refresh, contentDescription = "Calibration") },
                            label = { Text("Calibrate") },
                        )
                    }
                }
            },
        ) { padding ->
            Box(modifier = Modifier.padding(padding)) {
                when {
                    showDebug && DebugMenu.isEnabled() -> DebugMenuScreen()
                    showSetup -> HomeScreen()
                    tab == HomeTab.ALARMS -> AlarmsScreen()
                    tab == HomeTab.NOTIFICATIONS -> NotificationsScreen()
                    tab == HomeTab.BUTTONS -> ButtonsScreen()
                    tab == HomeTab.CALIBRATION -> CalibrationScreen()
                    else -> DashboardScreen()
                }
            }
        }
    }

    @Composable
    private fun HomeScreen() {
        val status by WatchState.status.collectAsStateWithLifecycle()
        var mac by remember {
            mutableStateOf(CompanionManager.getAssociatedMac(this) ?: DEFAULT_MAC)
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

            OutlinedTextField(
                value = mac,
                onValueChange = { mac = it },
                label = { Text("Watch MAC") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = { permissionLauncher.launch(requiredPermissions()) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Request Bluetooth permission") }
            Text(permMsg, style = MaterialTheme.typography.bodySmall)

            Button(
                onClick = { startAssociate(mac.trim(), associateLauncher::launch) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Associate watch (CompanionDeviceManager)") }

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
                        WatchConnectionService.connectNow(this@MainActivity, mac.trim())
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
        }
    }

    /** Begin CDM association; the chooser IntentSender is fired via [launch]. */
    private fun startAssociate(mac: String, launch: (IntentSenderRequest) -> Unit) {
        if (!hasPermissions()) {
            Log.w(TAG, "associate: missing Bluetooth permission")
        }
        val filterMac = mac.takeIf { android.bluetooth.BluetoothAdapter.checkBluetoothAddress(it) }
        CompanionManager.associate(
            context = this,
            mac = filterMac,
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
        CompanionManager.setAssociatedMac(this, mac)
        CompanionManager.startObserving(this, mac)
        ReconnectFallback.arm(this, mac)
        WatchConnectionService.connectNow(this, mac)
        // WP4: mirror the association into the Room registry and mark it the active
        // watch. Fire-and-forget on IO so it never touches the WP3 connect path above
        // (CompanionManager's SharedPreferences pref remains the CDM reconnect pointer).
        val appContext = applicationContext
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            runCatching { WatchRepository(appContext).registerWatch(mac, name = mac) }
                .onFailure { Log.w(TAG, "WP4 registerWatch failed", it) }
        }
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
