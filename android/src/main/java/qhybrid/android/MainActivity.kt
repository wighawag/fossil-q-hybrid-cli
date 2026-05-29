package qhybrid.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import qhybrid.linux.FossilQAdapter
import kotlin.concurrent.thread

/**
 * WP0.5 Walking Skeleton.
 *
 * Smallest possible runnable app that proves the whole toolchain end-to-end on a
 * real phone: request Bluetooth permission, connect to the watch via a real
 * [AndroidBleTransport] + the shared :protocol [FossilQAdapter], and show live
 * device info (battery %, firmware). No DB, no features — just "the setup works".
 */
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "FossilQ-WP0.5"
        // Prefilled default for convenience; editable on screen.
        private const val DEFAULT_MAC = "D9:20:71:11:74:2A"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    WalkingSkeletonScreen()
                }
            }
        }
    }

    @Composable
    private fun WalkingSkeletonScreen() {
        val context = LocalContext.current
        var mac by remember { mutableStateOf(DEFAULT_MAC) }
        var status by remember {
            mutableStateOf(
                if (hasPermissions()) "Permissions granted. Ready to Connect."
                else "Grant Bluetooth permission, then Connect."
            )
        }
        var busy by remember { mutableStateOf(false) }

        val permissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result ->
            val granted = result.values.all { it }
            status = if (granted) "Permissions granted. Ready to Connect."
            else "Permissions DENIED — Connect will fail."
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Fossil Q — Walking Skeleton", style = MaterialTheme.typography.titleLarge)

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

            Button(
                enabled = !busy,
                onClick = {
                    if (!hasPermissions()) {
                        status = "Missing Bluetooth permission — tap 'Request' first."
                        return@Button
                    }
                    busy = true
                    status = "Connecting to $mac…"
                    connectAndReadInfo(mac.trim(),
                        onUpdate = { status = it },
                        onDone = { busy = false }
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (busy) "Connecting…" else "Connect") }

            Text(status, style = MaterialTheme.typography.bodyLarge)
        }
    }

    /** Runs the blocking BLE connect+init off the main thread, then reports info. */
    private fun connectAndReadInfo(
        mac: String,
        onUpdate: (String) -> Unit,
        onDone: () -> Unit
    ) {
        thread(name = "ble-connect") {
            val transport = AndroidBleTransport(applicationContext)
            try {
                if (!transport.connect(mac)) {
                    post(onUpdate, "❌ Failed to connect to $mac (out of range / BT off?)")
                    return@thread
                }
                post(onUpdate, "Connected. Initializing…")

                val adapter = FossilQAdapter(transport)
                // Only prompt for the button when the watch ACTUALLY requests
                // authorization (it vibrates). If it is already authorized, init
                // proceeds straight to reading info with no prompt.
                adapter.setOnAuthRequired {
                    post(onUpdate, "⌚ Authorization requested — the watch is vibrating.\n\n" +
                        "Hold the TOP button to CONFIRM, or the BOTTOM button to CANCEL (within 30s).")
                }
                adapter.initialize(false) // minimal init: file versions + auth
                if (adapter.isFossilProtocol()) {
                    // 60s allows time for the auth button press on first bond.
                    if (!adapter.waitForInit(60_000)) {
                        Log.w(TAG, "init may not have completed fully")
                    }
                }

                val info = buildString {
                    append("✅ Connected to ").append(mac).append("\n")
                    append("Model:    ").append(adapter.modelNumber ?: "?").append("\n")
                    append("Firmware: ").append(adapter.firmwareVersion ?: "?").append("\n")
                    append("Battery:  ").append(adapter.batteryLevel).append("%\n")
                    append("Protocol: ")
                        .append(if (adapter.isFossilProtocol()) "Fossil (2.x)" else "Misfit")
                }
                Log.i(TAG, info)
                post(onUpdate, info)
                adapter.shutdown()
            } catch (e: Exception) {
                Log.e(TAG, "connect/init failed", e)
                post(onUpdate, "❌ Error: ${e.message}")
            } finally {
                try { transport.disconnect() } catch (_: Exception) {}
                runOnUiThread { onDone() }
            }
        }
    }

    private fun post(cb: (String) -> Unit, msg: String) = runOnUiThread { cb(msg) }

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
