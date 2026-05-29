@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package qhybrid.android.calibration

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * WP16e — the Calibration screen (interactive hand alignment). State comes from
 * [CalibrationViewModel] (WP4 active watch + the IN-MEMORY, ephemeral calibration session);
 * intents delegate to the VM, whose "Apply" goes through the injectable [CalibrationSync] seam.
 *
 * **CALIBRATION IS EPHEMERAL — NOTHING IS PERSISTED.** The screen is a transient "set zero =
 * current hand position" handshake: the session is zeroed on enter and discarded on exit, and
 * re-opening always starts fresh. There is no Room entity / DAO / repository method for
 * calibration, and the UI makes the transient nature explicit (an "exit discards this session"
 * hint; nothing to save).
 *
 * **MODEL-AGNOSTIC by design:** the UI offers the flat [CalibrationHands] catalog (hour / minute /
 * sub-eye) and does NOT gate behind a watch-model lookup table. A watch that lacks one of these
 * hands simply ignores its move.
 *
 * **On-device verification pending:** the hand-select, nudge, and Apply effects can only be
 * confirmed on a device. The actual move-hands / save-calibration command to the watch is
 * **WP14 / WP F** ([CalibrationSync] reports it as not-yet-wired and the UI flags it).
 */
@Composable
fun CalibrationScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val vm: CalibrationViewModel = viewModel(factory = CalibrationViewModel.factory(context))
    val state by vm.uiState.collectAsStateWithLifecycle()

    CalibrationContent(
        state = state,
        onEnter = vm::enterCalibration,
        onExit = vm::exitCalibration,
        onSelectHand = vm::selectHand,
        onNudge = vm::nudge,
        onApply = { vm.apply() },
        modifier = modifier,
    )
}

/**
 * Stateless Calibration body — pure function of [CalibrationUiState] + intent lambdas, so it is
 * preview-/UI-testable with fake state and no VM/Room/BLE.
 */
@Composable
fun CalibrationContent(
    state: CalibrationUiState,
    onEnter: () -> Unit,
    onExit: () -> Unit,
    onSelectHand: (hand: String) -> Unit,
    onNudge: (hand: String, deltaDegrees: Int) -> Unit,
    onApply: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    var applyNote by remember { mutableStateOf<String?>(null) }

    Scaffold(modifier = modifier) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                !state.hasActiveWatch -> Text(
                    "No active watch — connect one to calibrate the hands.",
                    style = MaterialTheme.typography.bodyMedium,
                )

                !state.inProgress -> {
                    Text(
                        "Interactive hand calibration",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "Align the watch's hands to 12:00:00, then apply to set the reference. " +
                            "This session is transient — nothing is saved; closing or exiting " +
                            "discards it, and re-opening starts fresh.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = onEnter) { Text("Start calibration") }
                }

                else -> CalibrationSession(
                    state = state,
                    onExit = {
                        applyNote = null
                        onExit()
                    },
                    onSelectHand = onSelectHand,
                    onNudge = onNudge,
                    onApply = {
                        val wired = onApply()
                        applyNote = if (wired) {
                            "Applied to watch."
                        } else {
                            "Move-hands / save-calibration to the watch is pending (WP14 / WP F)."
                        }
                    },
                    applyNote = applyNote,
                )
            }
        }
    }
}

@Composable
private fun CalibrationSession(
    state: CalibrationUiState,
    onExit: () -> Unit,
    onSelectHand: (hand: String) -> Unit,
    onNudge: (hand: String, deltaDegrees: Int) -> Unit,
    onApply: () -> Unit,
    applyNote: String?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Calibrating", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = onExit) { Text("Exit (discard)") }
    }
    Text(
        "Transient session — nothing is saved. Nudge each hand until it points exactly at 12, " +
            "then Apply. Calibration is intentionally not persisted (it's a live reference set).",
        style = MaterialTheme.typography.labelSmall,
    )

    HorizontalDivider()

    // Hand selector (flat catalog; model-agnostic — not gated by model).
    Text("Hand", style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CalibrationHands.ALL.forEach { hand ->
            FilterChip(
                selected = hand == state.selectedHand,
                onClick = { onSelectHand(hand) },
                label = { Text(CalibrationHands.label(hand)) },
            )
        }
    }

    // Per-hand live degree readout + nudge controls for the selected hand.
    val selected = state.selectedHand
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "${CalibrationHands.label(selected)}: ${state.offsetOf(selected)}°",
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                "Coarse = ${HandDegrees.COARSE}° (one minute mark); fine = ${HandDegrees.FINE}°. " +
                    "Wraps around 0–359.",
                style = MaterialTheme.typography.labelSmall,
            )
            // Coarse nudges.
            NudgeRow(
                label = "Coarse",
                onMinus = { onNudge(selected, -HandDegrees.COARSE) },
                onPlus = { onNudge(selected, HandDegrees.COARSE) },
            )
            // Fine nudges.
            NudgeRow(
                label = "Fine",
                onMinus = { onNudge(selected, -HandDegrees.FINE) },
                onPlus = { onNudge(selected, HandDegrees.FINE) },
            )
        }
    }

    // All-hands readout so the user can see the whole session at a glance.
    Text("Session offsets", style = MaterialTheme.typography.labelLarge)
    CalibrationHands.ALL.forEach { hand ->
        Text(
            "${CalibrationHands.label(hand)}: ${state.offsetOf(hand)}°",
            style = MaterialTheme.typography.bodySmall,
        )
    }

    HorizontalDivider()

    Button(
        onClick = onApply,
        enabled = state.canCalibrate,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Apply to watch") }
    applyNote?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
}

@Composable
private fun NudgeRow(
    label: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 4.dp))
        OutlinedButton(onClick = onMinus, modifier = Modifier.fillMaxWidth(0.5f)) { Text("−") }
        OutlinedButton(onClick = onPlus, modifier = Modifier.fillMaxWidth()) { Text("+") }
    }
}
