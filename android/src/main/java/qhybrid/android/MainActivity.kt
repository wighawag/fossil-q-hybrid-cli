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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
// WP4 DEBUG — imports for the removable debug panel (delete with the panel).
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.layout.heightIn
import qhybrid.android.db.ButtonMappingEntity
import qhybrid.android.db.NotificationRuleEntity
import qhybrid.android.db.WatchAlarmEntity

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
                    HomeScreen()
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

            // ───────────────────────────────────────────────────────────────
            // WP4 DEBUG PANEL — REMOVE BEFORE MERGE.
            // Lets you verify multi-watch + clone/transfer on real hardware without
            // a sqlite3 binary on the device or the (future) WP16 screens. Pure DB:
            // the watch need not be connected for seed/transfer/dump.
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Wp4DebugPanel(activeMacHint = mac.trim())
            // ─────────────────────────── end WP4 DEBUG ─────────────────────────
        }
    }

    // ───────────────────────────────────────────────────────────────────────
    // WP4 DEBUG PANEL — REMOVE BEFORE MERGE (this whole @Composable + DBG tag).
    @Composable
    private fun Wp4DebugPanel(activeMacHint: String) {
        val scope = rememberCoroutineScope()
        val repo = remember { WatchRepository(applicationContext) }
        var dump by remember { mutableStateOf("(tap Dump DB)") }
        var fromMac by remember { mutableStateOf(activeMacHint) }
        var toMac by remember { mutableStateOf("") }

        fun refresh() = scope.launch {
            dump = buildDbDump(repo)
            Log.i("FossilQ-DB", "\n$dump")
        }

        Text("WP4 DEBUG — DB / multi-watch / clone", style = MaterialTheme.typography.titleMedium)
        Text(
            "Pure persistence test surface (no BLE). 'Seed A' writes sample " +
                "alarms/rules/buttons under FROM; 'Transfer' clones FROM→TO.",
            style = MaterialTheme.typography.bodySmall
        )

        OutlinedTextField(
            value = fromMac, onValueChange = { fromMac = it },
            label = { Text("FROM mac (source)") }, singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = toMac, onValueChange = { toMac = it },
            label = { Text("TO mac (target / your 2nd watch)") }, singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(0.5f),
                onClick = {
                    scope.launch {
                        val m = fromMac.trim().uppercase()
                        repo.registerWatch(m, name = "Source $m")
                        repo.upsertAlarm(
                            WatchAlarmEntity(m, slotId = 0, hour = 7, minute = 30,
                                isEnabled = true, daysMask = 0b0111110,
                                isRepeating = true, label = "Wake")
                        )
                        repo.upsertAlarm(
                            WatchAlarmEntity(m, slotId = 1, hour = 12, minute = 0,
                                isEnabled = true, daysMask = 0, isRepeating = false,
                                label = "Lunch")
                        )
                        repo.upsertRule(
                            NotificationRuleEntity(m, "com.whatsapp",
                                vibePattern = 2, hourHandDegrees = 90, minuteHandDegrees = 180)
                        )
                        repo.upsertButton(
                            ButtonMappingEntity(m, buttonId = 0x10,
                                modeType = "SINGLE_ACTION",
                                actionsJson = """[{"action":"MUSIC_PLAY"}]""")
                        )
                        refresh()
                    }
                }
            ) { Text("Seed A (FROM)") }

            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    scope.launch {
                        val f = fromMac.trim().uppercase()
                        val t = toMac.trim().uppercase()
                        if (t.isNotEmpty()) repo.registerWatch(t, name = "Target $t")
                        repo.transferSettings(fromMac = f, toMac = t)
                        refresh()
                    }
                }
            ) { Text("Transfer FROM→TO") }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(0.5f),
                onClick = { refresh() }
            ) { Text("Dump DB") }

            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    scope.launch {
                        repo.getAllWatches().forEach { repo.deleteWatch(it.macAddress) }
                        refresh()
                    }
                }
            ) { Text("Wipe all watches") }
        }

        Text(
            dump,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 320.dp)
                .verticalScroll(rememberScrollState())
        )
    }

    // WP4 DEBUG — render the whole DB as text (also goes to logcat -s FossilQ-DB).
    private suspend fun buildDbDump(repo: WatchRepository): String = buildString {
        val watches = repo.getAllWatches()
        append("watches (${watches.size}):\n")
        watches.forEach { w ->
            append("  ${w.macAddress}  \"${w.name}\"  active=${w.isActive}\n")
            repo.getAlarms(w.macAddress).forEach {
                append("      alarm slot=${it.slotId} ${it.hour}:${"%02d".format(it.minute)} ")
                append("days=0b${it.daysMask.toString(2)} repeat=${it.isRepeating} '${it.label}'\n")
            }
            repo.getRules(w.macAddress).forEach {
                append("      rule ${it.packageName} vibe=${it.vibePattern} ")
                append("hands=${it.hourHandDegrees}/${it.minuteHandDegrees}\n")
            }
            repo.getButtons(w.macAddress).forEach {
                append("      button 0x${it.buttonId.toString(16)} ${it.modeType} ${it.actionsJson}\n")
            }
        }
    }
    // ─────────────────────────── end WP4 DEBUG PANEL ───────────────────────

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
