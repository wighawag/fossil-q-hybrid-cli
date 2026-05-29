@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package qhybrid.android.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
 * WP16a — the app's home Dashboard. Connection status + battery + steps/goal (steps is a
 * clearly-marked WP16f placeholder), an active-watch selector (from WP4 observeWatches),
 * Connect / Disconnect / Sync buttons, and a Find Watch button.
 *
 * State comes from [DashboardViewModel] (WP3 live status combined with the WP4 active-watch
 * row); intents delegate to the injectable [WatchActions] seam (service in production, fake
 * in tests). On-device verification pending: live rendering and the Find Watch choreography.
 */
@Composable
fun DashboardScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val vm: DashboardViewModel = viewModel(factory = DashboardViewModel.factory(context))
    val state by vm.uiState.collectAsStateWithLifecycle()

    DashboardContent(
        state = state,
        onSelectWatch = vm::setActiveWatch,
        onConnect = { vm.connect() },
        onDisconnect = vm::disconnect,
        onSync = vm::sync,
        onFindWatch = vm::findWatch,
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
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(20.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
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
        )
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
                // WP16f-pending: live activity data (WP8 parsing → DB) is not wired yet.
                Text(
                    "— / ${state.stepGoal}",
                    style = MaterialTheme.typography.headlineSmall,
                )
                LinearProgressIndicator(progress = { 0f }, modifier = Modifier.fillMaxWidth())
                Text(
                    "Step data not wired yet (WP16f — activity sync pending).",
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
) {
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
