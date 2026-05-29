@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package qhybrid.android.sleep

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * WP16f — the Sleep/Activity charts screen (READ-ONLY analytics). State comes from
 * [SleepActivityViewModel] (WP4 active watch + the injectable [ActivitySource] data); the only
 * intent is a [SleepActivityViewModel.refresh] that goes through the deferred source seam.
 *
 * **READ-ONLY + MODEL-AGNOSTIC by design:** the screen only *displays* parsed activity/sleep data
 * (per-day steps/calories + a sleep timeline + a quality summary). There is no write/upload path
 * and no per-model branching — every watch emits the same minute-record format WP8 already decodes.
 * Charts are drawn with Compose primitives only (Canvas / Box bars — NO charting dependency).
 *
 * **On-device verification pending:** the actual activity-file fetch + parse pipeline (BLE read of
 * the watch's activity file → `ActivityParser` → state) is deferred behind
 * [ServiceActivitySource.ACTIVITY_WIRED] = false (a later WP). Until then the screen renders empty
 * for a connected watch and flags the data as not-yet-fetched.
 */
@Composable
fun SleepActivityScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val vm: SleepActivityViewModel = viewModel(factory = SleepActivityViewModel.factory(context))
    val state by vm.uiState.collectAsStateWithLifecycle()

    SleepActivityContent(
        state = state,
        onRefresh = { vm.refresh() },
        modifier = modifier,
    )
}

/**
 * Stateless Sleep/Activity body — pure function of [SleepActivityUiState] + the refresh intent, so
 * it is preview-/UI-testable with fake state and no VM/Room/BLE.
 */
@Composable
fun SleepActivityContent(
    state: SleepActivityUiState,
    onRefresh: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    var refreshNote by remember { mutableStateOf<String?>(null) }

    Scaffold(modifier = modifier) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Sleep & Activity", style = MaterialTheme.typography.titleLarge)
            Text(
                "Read-only analytics from the watch's on-device activity tracking " +
                    "(steps, calories, sleep). Nothing here is sent to the watch.",
                style = MaterialTheme.typography.labelSmall,
            )

            when {
                !state.hasActiveWatch -> Text(
                    "No active watch — connect one to see its sleep & activity.",
                    style = MaterialTheme.typography.bodyMedium,
                )

                state.isEmpty -> {
                    Text(
                        "No activity data yet for this watch.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    RefreshRow(state, onRefresh) { refreshNote = it }
                    refreshNote?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                }

                else -> {
                    ActivitySummaryCard(state)
                    DailyStepsChart(state)
                    SleepSummaryCard(state)
                    SleepTimeline(state)
                    HorizontalDivider()
                    RefreshRow(state, onRefresh) { refreshNote = it }
                    refreshNote?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                }
            }
        }
    }
}

@Composable
private fun RefreshRow(
    state: SleepActivityUiState,
    onRefresh: () -> Boolean,
    onNote: (String) -> Unit,
) {
    OutlinedButton(
        enabled = state.canRefresh,
        onClick = {
            val wired = onRefresh()
            onNote(
                if (wired) "Refreshing activity data from the watch…"
                else "Reading the activity file from the watch is on-device-pending " +
                    "(later WP wires the fetch→parse pipeline).",
            )
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Refresh") }
}

// ---- summary cards --------------------------------------------------------

@Composable
private fun ActivitySummaryCard(state: SleepActivityUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Activity", style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Stat("Steps", state.totalSteps.toString())
                Stat("Calories", state.totalCalories.toString())
                Stat("Active", SleepActivityFormat.durationLabel(state.totalActiveMinutes))
            }
            if (state.stepGoal > 0) {
                Text(
                    "Daily step goal: ${state.stepGoal}",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun SleepSummaryCard(state: SleepActivityUiState) {
    val sum = state.sleepSummary
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Sleep", style = MaterialTheme.typography.titleMedium)
            if (!sum.hasSleep) {
                Text(SleepQuality.label(SleepQuality.NONE), style = MaterialTheme.typography.bodyMedium)
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Stat("Total", SleepActivityFormat.durationLabel(sum.totalMinutes))
                    Stat("Restful", SleepActivityFormat.durationLabel(sum.deepMinutes))
                    Stat("Restless", SleepActivityFormat.durationLabel(sum.restlessMinutes))
                }
                Text(
                    "Quality: ${SleepQuality.label(sum.quality)}" +
                        if (sum.sessionCount > 1) "  (${sum.sessionCount} sessions)" else "",
                    style = MaterialTheme.typography.labelLarge,
                )
                // Restful/restless ratio bar (Compose primitive — no charting dep).
                StackedBar(
                    leftFraction = if (sum.totalMinutes > 0)
                        sum.deepMinutes.toFloat() / sum.totalMinutes.toFloat() else 0f,
                    leftColor = MaterialTheme.colorScheme.primary,
                    rightColor = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

// ---- charts (Compose primitives only) -------------------------------------

/** Per-day step bars drawn with [Canvas] (no charting dependency). Read-only. */
@Composable
private fun DailyStepsChart(state: SleepActivityUiState) {
    val days = state.days
    if (days.isEmpty()) return
    val maxSteps = (days.maxOfOrNull { it.steps } ?: 0).coerceAtLeast(1)
    val barColor = MaterialTheme.colorScheme.primary
    val goalColor = MaterialTheme.colorScheme.tertiary
    val goal = state.stepGoal

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Steps per day", style = MaterialTheme.typography.titleMedium)
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
            ) {
                val n = days.size
                val gap = size.width * 0.02f
                val barWidth = (size.width - gap * (n + 1)) / n
                // Goal line (model-agnostic threshold from the WP4 row).
                if (goal > 0 && goal <= maxSteps) {
                    val y = size.height * (1f - goal.toFloat() / maxSteps.toFloat())
                    drawLine(
                        color = goalColor,
                        start = androidx.compose.ui.geometry.Offset(0f, y),
                        end = androidx.compose.ui.geometry.Offset(size.width, y),
                        strokeWidth = 2f,
                    )
                }
                days.forEachIndexed { i, day ->
                    val frac = day.steps.toFloat() / maxSteps.toFloat()
                    val barHeight = size.height * frac
                    val left = gap + i * (barWidth + gap)
                    drawRect(
                        color = barColor,
                        topLeft = androidx.compose.ui.geometry.Offset(left, size.height - barHeight),
                        size = androidx.compose.ui.geometry.Size(barWidth, barHeight),
                    )
                }
            }
            // Compact legend / per-day readout (so values are visible without axes).
            days.forEach { day ->
                Text(
                    "${day.date}: ${day.steps} steps · ${day.calories} cal · " +
                        SleepActivityFormat.durationLabel(day.activeMinutes) + " active",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** Sleep timeline: one stacked restful/restless bar per detected session (Compose primitives). */
@Composable
private fun SleepTimeline(state: SleepActivityUiState) {
    val sessions = state.sleep
    if (sessions.isEmpty()) return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Sleep timeline", style = MaterialTheme.typography.titleMedium)
            val maxDur = (sessions.maxOfOrNull { it.durationMinutes } ?: 1).coerceAtLeast(1)
            sessions.forEachIndexed { i, s ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Session ${i + 1}: ${SleepActivityFormat.durationLabel(s.durationMinutes)}" +
                            " · ${SleepQuality.label(s.quality)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    // Width proportional to duration; split restful/restless.
                    val widthFraction = s.durationMinutes.toFloat() / maxDur.toFloat()
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(widthFraction.coerceIn(0.05f, 1f)),
                    ) {
                        StackedBar(
                            leftFraction = if (s.durationMinutes > 0)
                                s.restfulMinutes.toFloat() / s.durationMinutes.toFloat() else 0f,
                            leftColor = MaterialTheme.colorScheme.primary,
                            rightColor = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            Text(
                "Blue = restful · red = restless. Width ∝ duration.",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/** A two-colour stacked horizontal bar (Compose Box primitives — no charting dependency). */
@Composable
private fun StackedBar(
    leftFraction: Float,
    leftColor: Color,
    rightColor: Color,
) {
    val l = leftFraction.coerceIn(0f, 1f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(14.dp)
            .background(rightColor, RoundedCornerShape(7.dp)),
    ) {
        if (l > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(l)
                    .height(14.dp)
                    .background(leftColor, RoundedCornerShape(7.dp)),
            )
        }
    }
}
