@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package qhybrid.android.tracker

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import qhybrid.android.db.WaypointEntity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * WP-TRACKER — a minimal GPS-waypoint viewer: the logged waypoints (newest first), an "Export GPX"
 * share action (via the existing WP15 FileProvider authority), and a "Clear all". Rendering is
 * on-device-pending; the state mapping + GPX serialization are unit-tested ([WaypointsViewModel] /
 * [GpxWriter]).
 */
@Composable
fun WaypointsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val vm: WaypointsViewModel = viewModel(factory = WaypointsViewModel.factory(context))
    val state by vm.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // SAF "create document" launcher: the user picks WHERE the .gpx file lands (Downloads, a
    // folder, Drive, …) and we write the GPX bytes into the chosen URI. This is the "export the
    // file" flow (save-to-disk), complementing the share-sheet "Export GPX" (send-to-app) below.
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(WaypointsViewModel.MIME_TYPE)
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult // user cancelled the picker
        scope.launch {
            val gpx = vm.buildGpx()
            saveGpxToUri(context, uri, gpx)
        }
    }

    WaypointsContent(
        state = state,
        onExport = {
            scope.launch {
                val gpx = vm.buildGpx()
                exportGpx(context, gpx)
            }
        },
        onSaveFile = { saveLauncher.launch(WaypointsViewModel.EXPORT_FILENAME) },
        onClear = vm::clearAll,
        onDelete = vm::delete,
        modifier = modifier,
    )
}

/** Write the GPX string to the user-chosen [uri] (Storage Access Framework). Best-effort + toast. */
private fun saveGpxToUri(context: android.content.Context, uri: android.net.Uri, gpx: String) {
    runCatching {
        context.contentResolver.openOutputStream(uri)?.use { out ->
            out.write(gpx.toByteArray(Charsets.UTF_8))
        } ?: error("could not open the chosen location for writing")
    }.onSuccess {
        Toast.makeText(context, "Saved GPX file", Toast.LENGTH_SHORT).show()
    }.onFailure {
        Toast.makeText(context, "Save failed: ${it.message}", Toast.LENGTH_LONG).show()
    }
}

/** Export the GPX string to a cache file + open the share-sheet via the app's FileProvider. */
private fun exportGpx(context: android.content.Context, gpx: String) {
    runCatching {
        val dir = File(context.cacheDir, "waypoints").apply { mkdirs() }
        val file = File(dir, WaypointsViewModel.EXPORT_FILENAME)
        file.writeText(gpx)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val share = Intent(Intent.ACTION_SEND).apply {
            type = WaypointsViewModel.MIME_TYPE
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(share, "Export waypoints").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }.onFailure {
        Toast.makeText(context, "Export failed: ${it.message}", Toast.LENGTH_LONG).show()
    }
}

/** Stateless body — a pure function of state + intents, preview-/UI-testable with fake state. */
@Composable
fun WaypointsContent(
    state: WaypointsUiState,
    onExport: () -> Unit,
    onSaveFile: () -> Unit,
    onClear: () -> Unit,
    onDelete: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(modifier = modifier) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("GPS waypoints", style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSaveFile, enabled = !state.isEmpty) { Text("Save GPX file") }
                OutlinedButton(onClick = onExport, enabled = !state.isEmpty) { Text("Share GPX") }
                OutlinedButton(onClick = onClear, enabled = !state.isEmpty) { Text("Clear all") }
            }
            if (state.isEmpty) {
                Text(
                    "No waypoints yet. Log one with a TRACKER-role multi-function gesture or a " +
                        "\"Log GPS waypoint\" button.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.waypoints, key = { it.id }) { wp ->
                        WaypointRow(wp, onDelete)
                    }
                }
            }
        }
    }
}

@Composable
private fun WaypointRow(wp: WaypointEntity, onDelete: (Long) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Text("${wp.kind} · ${stamp(wp.capturedAt)}", style = MaterialTheme.typography.titleSmall)
            Text(
                "%.6f, %.6f%s".format(
                    Locale.US, wp.lat, wp.lon,
                    wp.accuracyM?.let { " (±%.0fm)".format(Locale.US, it) } ?: "",
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            wp.note?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.labelSmall)
            }
            OutlinedButton(onClick = { onDelete(wp.id) }) { Text("Delete") }
        }
    }
}

private fun stamp(epochMillis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(epochMillis))
