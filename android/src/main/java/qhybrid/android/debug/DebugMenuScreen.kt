@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package qhybrid.android.debug

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import qhybrid.android.BuildConfig
import qhybrid.android.WatchConnectionService
import qhybrid.android.WatchState
import qhybrid.android.log.LogConsole
import qhybrid.android.db.WatchRepository

/**
 * WP15 — the Debug Menu: a developer/testing surface that hosts the in-app log console
 * plus DB (WP4) and BLE/protocol (WP3) tools. **Release-gated** by [BuildConfig.DEBUG];
 * callers must check the flag before navigating here (see [isEnabled]). All actions log
 * via SLF4J so their output lands in the console below (and in logcat).
 *
 * On-device verification pending: the action wiring is exercised here against the real
 * [WatchRepository] / [WatchConnectionService] / [WatchState], but their full effect
 * (live DB rows, a live link) can only be confirmed on a device. The pure pieces (ring
 * buffer / filter / export) are unit-tested headlessly in `:protocol`.
 */
object DebugMenu {
    /** True only in debug builds — the gate that keeps this out of release. */
    fun isEnabled(): Boolean = BuildConfig.DEBUG
}

@Composable
fun DebugMenuScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val tools = remember { DebugTools(context.applicationContext) }
    val status by WatchState.status.collectAsStateWithLifecycle()

    var fromMac by remember { mutableStateOf(WatchState.status.value.mac ?: "") }
    var toMac by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Debug Menu", style = MaterialTheme.typography.titleLarge)
        Text(
            "DEBUG build only · actions log to the console below",
            style = MaterialTheme.typography.labelSmall,
        )

        // ---- BLE / protocol tools (WP3) -------------------------------------
        SectionLabel("BLE / Protocol (WP3)")
        Text(
            "Link: ${status.link}   MTU: ${if (status.mtu > 0) status.mtu else "—"}",
            style = MaterialTheme.typography.bodySmall,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                tools.log.info("Debug: connect-now requested")
                WatchConnectionService.connectNow(context)
            }) { Text("Connect now") }
            OutlinedButton(onClick = {
                tools.log.info("Debug: sync-now requested")
                WatchConnectionService.syncNow(context)
            }) { Text("Sync now") }
            OutlinedButton(onClick = {
                tools.log.info("Debug: disconnect requested")
                WatchConnectionService.disconnect(context)
            }) { Text("Disconnect") }
            OutlinedButton(onClick = { tools.dumpLinkState() }) { Text("Dump link state") }
        }

        HorizontalDivider()

        // ---- DB tools (WP4) -------------------------------------------------
        SectionLabel("Database (WP4)")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { tools.dumpDatabase() }) { Text("Dump DB") }
            OutlinedButton(onClick = { tools.seedSampleData() }) { Text("Seed sample") }
            OutlinedButton(onClick = { tools.listWatches() }) { Text("List watches") }
        }

        OutlinedTextField(
            value = fromMac,
            onValueChange = { fromMac = it },
            label = { Text("From MAC (clone/transfer source · wipe · activate)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = toMac,
            onValueChange = { toMac = it },
            label = { Text("To MAC (clone/transfer target)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { tools.transfer(fromMac.trim(), toMac.trim()) }) {
                Text("Clone from→to")
            }
            OutlinedButton(onClick = { tools.setActive(fromMac.trim()) }) { Text("Activate from") }
            OutlinedButton(onClick = { tools.wipe(fromMac.trim()) }) { Text("Wipe from (CASCADE)") }
        }

        HorizontalDivider()

        // ---- Misc -----------------------------------------------------------
        SectionLabel("Misc")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { tools.dumpBuildInfo() }) { Text("Build/version") }
            OutlinedButton(onClick = { tools.dumpPermissions() }) { Text("Permissions") }
            OutlinedButton(onClick = { tools.dumpAssociations() }) { Text("CDM associations") }
        }

        HorizontalDivider()

        // ---- log console ----------------------------------------------------
        SectionLabel("Log Console")
        // Bounded height so the console scrolls inside the menu's outer scroll.
        LogConsole(modifier = Modifier.fillMaxWidth().height(360.dp))
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium)
}
