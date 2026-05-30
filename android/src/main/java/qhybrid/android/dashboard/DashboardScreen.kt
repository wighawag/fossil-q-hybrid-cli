@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package qhybrid.android.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import qhybrid.android.WatchState
import qhybrid.android.db.WatchEntity

/**
 * WP16a — the app's home Dashboard. Connection status + battery + steps/goal (steps is now
 * LIVE from the WP-ACTIVITY fetch; — shown until the first fetch completes), an active-watch
 * selector (from WP4 observeWatches),
 * Connect / Disconnect / Sync buttons, and a Find Watch button.
 *
 * State comes from [DashboardViewModel] (WP3 live status combined with the WP4 active-watch
 * row); intents delegate to the injectable [WatchActions] seam (service in production, fake
 * in tests). On-device verification pending: live rendering and the Find Watch choreography.
 */
@Composable
fun DashboardScreen(
    modifier: Modifier = Modifier,
    // Add a watch by scanning for Fossil watches (the OS chooser). Hosted by MainActivity because
    // CDM association needs an Activity for the IntentSender; default no-op for previews.
    onAddWatch: () -> Unit = {},
    // Fallback: scan showing ALL nearby BLE devices (when the Fossil filter finds nothing).
    onShowAllDevices: () -> Unit = {},
    // Fallback: open Setup to type a MAC (the reliable path for an already-bonded watch).
    onEnterMacManually: () -> Unit = {},
    // Already-bonded (OS-paired) Fossil watches not yet added in the app (mac to label).
    bondedWatches: List<Pair<String, String>> = emptyList(),
    // Add a specific already-bonded watch by its MAC (one-tap, no scan).
    onAddBondedWatch: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val vm: DashboardViewModel = viewModel(factory = DashboardViewModel.factory(context))
    val state by vm.uiState.collectAsStateWithLifecycle()

    // WP-ONBOARD: a blocking "Adding your watch…" modal while a brand-new watch is being
    // provisioned (the connect + filter/alarm write takes a few seconds), resolving to Added/Failed.
    val provisioning by qhybrid.android.onboard.ProvisioningState.status.collectAsStateWithLifecycle()
    ProvisioningDialog(provisioning)

    DashboardContent(
        state = state,
        onSelectWatch = vm::setActiveWatch,
        onConnect = { vm.connect() },
        onDisconnect = vm::disconnect,
        onSync = vm::sync,
        onFindWatch = vm::findWatch,
        onAddWatch = onAddWatch,
        onShowAllDevices = onShowAllDevices,
        onEnterMacManually = onEnterMacManually,
        bondedWatches = bondedWatches,
        onAddBondedWatch = onAddBondedWatch,
        modifier = modifier,
    )
}

/**
 * Stateless Dashboard body — pure function of [DashboardUiState] + intent lambdas, so it can
 * be previewed / UI-tested with fake state without a ViewModel/Room/BLE.
 */
@Composable
fun DashboardContent(
    state: DashboardUiState,
    onSelectWatch: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onSync: () -> Unit,
    onFindWatch: () -> Unit,
    modifier: Modifier = Modifier,
    onAddWatch: () -> Unit = {},
    onShowAllDevices: () -> Unit = {},
    onEnterMacManually: () -> Unit = {},
    bondedWatches: List<Pair<String, String>> = emptyList(),
    onAddBondedWatch: (String) -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(20.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (state.watches.isEmpty()) {
            // First-run / no-watch state: a prominent CTA to add a watch (replaces the old passive
            // hint + the hardcoded-MAC setup field), with fallbacks for when the scan finds nothing.
            NoWatchCard(onAddWatch, onShowAllDevices, onEnterMacManually, bondedWatches, onAddBondedWatch)
            return@Column
        }
        StatusCard(state)
        StepsCard(state)
        ActiveWatchSelector(
            watches = state.watches,
            selectedMac = state.selectedMac,
            onSelectWatch = onSelectWatch,
        )
        ActionButtons(
            state = state,
            onConnect = onConnect,
            onDisconnect = onDisconnect,
            onSync = onSync,
            onFindWatch = onFindWatch,
            onAddWatch = onAddWatch,
            onShowAllDevices = onShowAllDevices,
            onEnterMacManually = onEnterMacManually,
            bondedWatches = bondedWatches,
            onAddBondedWatch = onAddBondedWatch,
        )
    }
}

/**
 * WP-ONBOARD — blocking modal shown while a brand-new watch is being provisioned on its first
 * connect ("Adding your watch…"), then a brief Added / Failed outcome the user can dismiss. While
 * [ProvisioningState.Phase.PROVISIONING] the dialog cannot be dismissed (the work is in flight);
 * the terminal phases offer an OK button. IDLE renders nothing.
 */
@Composable
private fun ProvisioningDialog(status: qhybrid.android.onboard.ProvisioningState.Status) {
    val phase = status.phase
    if (phase == qhybrid.android.onboard.ProvisioningState.Phase.IDLE) return
    val provisioning = phase == qhybrid.android.onboard.ProvisioningState.Phase.PROVISIONING
    // Acknowledging a terminal outcome CLEARS the process-wide state (back to IDLE) so it never
    // re-appears when navigating back to the Dashboard — the stale-modal bug.
    val ack = { qhybrid.android.onboard.ProvisioningState.acknowledge() }
    AlertDialog(
        onDismissRequest = { if (!provisioning) ack() },
        confirmButton = {
            if (!provisioning) {
                TextButton(onClick = ack) { Text("OK") }
            }
        },
        title = {
            Text(
                when (phase) {
                    qhybrid.android.onboard.ProvisioningState.Phase.PROVISIONING -> "Adding your watch…"
                    qhybrid.android.onboard.ProvisioningState.Phase.ADDED -> "Watch added"
                    qhybrid.android.onboard.ProvisioningState.Phase.FAILED -> "Couldn't add the watch"
                    else -> ""
                }
            )
        },
        text = {
            when (phase) {
                qhybrid.android.onboard.ProvisioningState.Phase.PROVISIONING -> Row(
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(20.dp), strokeWidth = 2.dp,
                    )
                    Text(
                        "Setting up your watch. This takes a few seconds while it connects and " +
                            "saves the initial settings.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                qhybrid.android.onboard.ProvisioningState.Phase.ADDED -> Text(
                    "Your watch is ready to use.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                qhybrid.android.onboard.ProvisioningState.Phase.FAILED -> Text(
                    status.errorMessage ?: "Setup didn't finish. Keep the watch close and try again.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                else -> {}
            }
        },
    )
}

/**
 * Empty-state card shown when no watch is registered: explains the app needs a watch and offers a
 * primary "Add a watch" scan (Fossil-filtered) plus two fallbacks — "Show all Bluetooth devices"
 * (when the Fossil filter finds nothing because the advertised name changed) and "Enter MAC
 * manually" (the reliable path for a watch that's already paired/bonded and so won't show in any
 * scan — FINDINGS #7).
 */
@Composable
private fun NoWatchCard(
    onAddWatch: () -> Unit,
    onShowAllDevices: () -> Unit,
    onEnterMacManually: () -> Unit,
    bondedWatches: List<Pair<String, String>>,
    onAddBondedWatch: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("No watch yet", style = MaterialTheme.typography.titleMedium)
            if (bondedWatches.isEmpty()) {
                Text(
                    "Add your Fossil Q hybrid watch to get started. Make sure it's nearby, " +
                        "Bluetooth is on, and the watch is NOT already paired to another phone or " +
                        "computer (a paired watch won't show up in a scan).",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            AddWatchOptions(
                onAddWatch = onAddWatch,
                onShowAllDevices = onShowAllDevices,
                onEnterMacManually = onEnterMacManually,
                bondedWatches = bondedWatches,
                onAddBondedWatch = onAddBondedWatch,
            )
        }
    }
}

/**
 * Shared add-a-watch option buttons (used by the empty-state card AND the "Add another watch"
 * expander): the already-paired (OS-bonded) Fossil watches for one-tap add, the Fossil scan, and
 * the "show all devices" / "enter MAC" fallbacks. Pure layout over the intent lambdas.
 */
@Composable
private fun AddWatchOptions(
    onAddWatch: () -> Unit,
    onShowAllDevices: () -> Unit,
    onEnterMacManually: () -> Unit,
    bondedWatches: List<Pair<String, String>>,
    onAddBondedWatch: (String) -> Unit,
) {
    // Already-paired (OS-bonded) Fossil watches: one-tap add, no scan/forget needed.
    if (bondedWatches.isNotEmpty()) {
        Text("Already paired with this phone — tap to add:", style = MaterialTheme.typography.labelLarge)
        bondedWatches.forEach { (mac, label) ->
            Button(
                onClick = { onAddBondedWatch(mac) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                // Show name + MAC so two same-named "Fossil" watches are distinguishable.
                Text(if (label.equals(mac, ignoreCase = true)) "Add $mac" else "Add $label ($mac)")
            }
        }
        Text("Or add a different watch:", style = MaterialTheme.typography.labelLarge)
    }
    Button(onClick = onAddWatch, modifier = Modifier.fillMaxWidth()) {
        Text("Scan for Fossil watch")
    }
    Text("Watch not showing up?", style = MaterialTheme.typography.labelLarge)
    OutlinedButton(onClick = onShowAllDevices, modifier = Modifier.fillMaxWidth()) {
        Text("Show all Bluetooth devices")
    }
    OutlinedButton(onClick = onEnterMacManually, modifier = Modifier.fillMaxWidth()) {
        Text("Enter MAC manually")
    }
}

@Composable
private fun StatusCard(state: DashboardUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Connection", style = MaterialTheme.typography.titleMedium)
            Text(linkLabel(state.link), style = MaterialTheme.typography.headlineSmall)
            state.statusMessage?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                LabeledValue("Battery", state.batteryPercent?.let { "$it%" } ?: "—")
                LabeledValue("Model", state.model ?: "—")
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                LabeledValue("Firmware", state.firmware ?: "—")
                LabeledValue("MTU", if (state.mtu > 0) "${state.mtu}" else "—")
            }
        }
    }
}

@Composable
private fun StepsCard(state: DashboardUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Steps", style = MaterialTheme.typography.titleMedium)
            val steps = state.steps
            if (steps == null) {
                // WP-ACTIVITY: steps are wired, but no activity file has been fetched yet
                // (connect or refresh on the Sleep screen populates this).
                Text(
                    "— / ${state.stepGoal}",
                    style = MaterialTheme.typography.headlineSmall,
                )
                LinearProgressIndicator(progress = { 0f }, modifier = Modifier.fillMaxWidth())
                Text(
                    "No activity data fetched yet — connect or refresh to pull it.",
                    style = MaterialTheme.typography.labelSmall,
                )
            } else {
                Text("$steps / ${state.stepGoal}", style = MaterialTheme.typography.headlineSmall)
                val progress = if (state.stepGoal > 0) {
                    (steps.toFloat() / state.stepGoal).coerceIn(0f, 1f)
                } else 0f
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun ActiveWatchSelector(
    watches: List<WatchEntity>,
    selectedMac: String?,
    onSelectWatch: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Active watch", style = MaterialTheme.typography.titleMedium)
            if (watches.isEmpty()) {
                Text(
                    "No watches registered yet — associate one to get started.",
                    style = MaterialTheme.typography.bodySmall,
                )
                return@Column
            }

            var expanded by remember { mutableStateOf(false) }
            val selected = watches.firstOrNull { it.macAddress == selectedMac } ?: watches.first()
            val selectedText = "${selected.name} (${selected.macAddress})"

            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it },
            ) {
                OutlinedTextField(
                    value = selectedText,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Receiving live notifications") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                ) {
                    watches.forEach { w ->
                        DropdownMenuItem(
                            text = { Text("${w.name} (${w.macAddress})") },
                            onClick = {
                                expanded = false
                                if (w.macAddress != selectedMac) onSelectWatch(w.macAddress)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionButtons(
    state: DashboardUiState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onSync: () -> Unit,
    onFindWatch: () -> Unit,
    onAddWatch: () -> Unit = {},
    onShowAllDevices: () -> Unit = {},
    onEnterMacManually: () -> Unit = {},
    bondedWatches: List<Pair<String, String>> = emptyList(),
    onAddBondedWatch: (String) -> Unit = {},
) {
    var showAddOptions by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                modifier = Modifier.fillMaxWidth(0.5f),
                enabled = !state.isConnected && !state.isBusy,
                onClick = onConnect,
            ) { Text(if (state.isBusy) "Connecting…" else "Connect") }

            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                enabled = state.isConnected || state.isBusy,
                onClick = onDisconnect,
            ) { Text("Disconnect") }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(0.5f),
                enabled = state.isConnected,
                onClick = onSync,
            ) { Text("Sync now") }

            FilledTonalButton(
                modifier = Modifier.fillMaxWidth(),
                enabled = state.isConnected,
                onClick = onFindWatch,
            ) { Text("Find Watch") }
        }
        // Add another watch (multi-watch registry; single active at a time). Expands into the SAME
        // options as the empty state — incl. one-tap add of already-paired (OS-bonded) watches — so
        // a previously-paired watch can be added without scanning.
        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = { showAddOptions = !showAddOptions },
        ) { Text(if (showAddOptions) "Cancel adding" else "Add another watch") }
        if (showAddOptions) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    AddWatchOptions(
                        onAddWatch = onAddWatch,
                        onShowAllDevices = onShowAllDevices,
                        onEnterMacManually = onEnterMacManually,
                        bondedWatches = bondedWatches,
                        onAddBondedWatch = onAddBondedWatch,
                    )
                }
            }
        }
        // On-device-pending note: Find Watch choreography (phone→watch) is a WP16a stub.
        Text(
            "Find Watch is wired but the on-watch alert choreography is pending on-device work.",
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun LabeledValue(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun linkLabel(link: WatchState.LinkState): String = when (link) {
    WatchState.LinkState.IDLE -> "Idle"
    WatchState.LinkState.CONNECTING -> "Connecting…"
    WatchState.LinkState.INITIALIZING -> "Initializing…"
    WatchState.LinkState.AUTH_REQUIRED -> "Authorize on watch"
    WatchState.LinkState.INITIALIZED -> "Connected"
    WatchState.LinkState.DISCONNECTED -> "Disconnected"
}
