package qhybrid.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.activity.result.contract.ActivityResultContracts
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
import qhybrid.protocol.FossilController
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * WP2 transport-bring-up harness.
 *
 * Drives the protocol through the platform-agnostic [FossilController] façade
 * (NOT the raw FossilQAdapter) over the hardened [AndroidBleTransport]. Proves the
 * whole stack on real hardware: connect → auth (button press on fresh bond, or the
 * 03 07 01 fast-path when already bonded) → read battery/firmware → INITIALIZED,
 * reliably across connect/disconnect/reconnect.
 *
 * Still deliberately UI-light (no DB, no features) — that is WP3+/WP16.
 */
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "FossilQ-WP2"
        // Prefilled default for convenience; editable on screen.
        private const val DEFAULT_MAC = "D9:20:71:11:74:2A"
    }

    // The live controller/transport for the current session (so Disconnect can act).
    private val controllerRef = AtomicReference<FossilController?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    HarnessScreen()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Make sure we never leak a live GATT connection across activity teardown.
        controllerRef.getAndSet(null)?.let { c ->
            thread(name = "ble-teardown") { runCatching { c.disconnect() } }
        }
    }

    @Composable
    private fun HarnessScreen() {
        var mac by remember { mutableStateOf(DEFAULT_MAC) }
        var status by remember {
            mutableStateOf(
                if (hasPermissions()) "Permissions granted. Ready to Connect."
                else "Grant Bluetooth permission, then Connect."
            )
        }
        // Live connection-state line, updated from the transport's connection callback.
        var link by remember { mutableStateOf("Disconnected") }
        var busy by remember { mutableStateOf(false) }
        var connected by remember { mutableStateOf(false) }

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
            Text("Fossil Q — Transport Harness (WP2)", style = MaterialTheme.typography.titleLarge)

            Text("Link: $link", style = MaterialTheme.typography.labelLarge)

            OutlinedTextField(
                value = mac,
                onValueChange = { mac = it },
                label = { Text("Watch MAC") },
                singleLine = true,
                enabled = !busy && !connected,
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = { permissionLauncher.launch(requiredPermissions()) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Request Bluetooth permission") }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    enabled = !busy && !connected,
                    modifier = Modifier.fillMaxWidth(0.5f),
                    onClick = {
                        if (!hasPermissions()) {
                            status = "Missing Bluetooth permission — tap 'Request' first."
                            return@Button
                        }
                        busy = true
                        status = "Connecting to $mac…"
                        connectAndInit(
                            mac.trim(),
                            onStatus = { status = it },
                            onLink = { up -> link = if (up) "Connected" else "Disconnected"; connected = up },
                            onDone = { busy = false }
                        )
                    }
                ) { Text(if (busy && !connected) "Connecting…" else "Connect") }

                OutlinedButton(
                    enabled = connected && !busy,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        busy = true
                        status = "Disconnecting…"
                        disconnect(
                            onStatus = { status = it },
                            onDone = { busy = false }
                        )
                    }
                ) { Text("Disconnect") }
            }

            Text(status, style = MaterialTheme.typography.bodyLarge)
        }
    }

    /** Runs the blocking BLE connect + FossilController init off the main thread. */
    private fun connectAndInit(
        mac: String,
        onStatus: (String) -> Unit,
        onLink: (Boolean) -> Unit,
        onDone: () -> Unit
    ) {
        thread(name = "ble-connect") {
            val transport = AndroidBleTransport(applicationContext)
            val controller = FossilController(transport)
            controllerRef.set(controller)
            try {
                // Reflect link state changes (including unexpected out-of-range drops)
                // straight to the UI.
                transport.setConnectionCallback { up -> runOnUiThread { onLink(up) } }

                // Show the confirm prompt ONLY when the watch actively requests
                // authorization (it vibrates). On the already-bonded fast-path
                // (03 07 01) this never fires and init proceeds silently.
                controller.onAuthRequired {
                    post(onStatus,
                        "⌚ Authorization requested — the watch is vibrating.\n\n" +
                            "Hold the TOP button to CONFIRM (within 30s).")
                }
                controller.onConfigSynced {
                    Log.i(TAG, "Config synced")
                }

                if (!controller.connect(mac)) {
                    post(onStatus, "❌ Failed to connect to $mac (out of range / BT off / phone-still-bonded?)")
                    controllerRef.compareAndSet(controller, null)
                    return@thread
                }
                post(onStatus, "Connected. Initializing…")

                // Minimal init: file versions + auth. FossilController.init(false)
                // is the auth-only path (no config/filter upload).
                controller.init(false)
                if (controller.isFossilProtocol()) {
                    // 60s allows time for the auth button press on a fresh bond.
                    if (!controller.waitForInit(60_000)) {
                        Log.w(TAG, "init may not have completed fully")
                    }
                }

                val initialized = controller.isFossilProtocol()
                val info = buildString {
                    append(if (initialized) "✅ INITIALIZED — " else "⚠️ connected (not Fossil 2.x) — ")
                    append(mac).append("\n")
                    append("Model:    ").append(controller.modelNumber ?: "?").append("\n")
                    append("Firmware: ").append(controller.firmwareVersion ?: "?").append("\n")
                    append("Battery:  ").append(controller.batteryLevel).append("%\n")
                    append("Protocol: ")
                        .append(if (initialized) "Fossil (2.x)" else "Misfit")
                }
                Log.i(TAG, info)
                post(onStatus, info)
                // Stay connected so reconnect/teardown can be exercised via Disconnect.
            } catch (e: Exception) {
                Log.e(TAG, "connect/init failed", e)
                post(onStatus, "❌ Error: ${e.message}")
                runCatching { controller.disconnect() }
                controllerRef.compareAndSet(controller, null)
            } finally {
                runOnUiThread { onDone() }
            }
        }
    }

    private fun disconnect(onStatus: (String) -> Unit, onDone: () -> Unit) {
        thread(name = "ble-disconnect") {
            try {
                controllerRef.getAndSet(null)?.disconnect()
                post(onStatus, "Disconnected. Ready to Connect.")
            } catch (e: Exception) {
                Log.w(TAG, "disconnect failed", e)
                post(onStatus, "Disconnect error: ${e.message}")
            } finally {
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
