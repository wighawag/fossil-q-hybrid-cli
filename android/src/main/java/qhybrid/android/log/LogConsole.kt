package qhybrid.android.log

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import qhybrid.protocol.log.LogRecord
import qhybrid.protocol.log.LogRingBuffer
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * WP15 — terminal-style scrollable log console with a level-filter toggle and
 * copy-to-clipboard + export-to-file actions. Reads the in-app [LogRingBuffer]
 * (fed by the SLF4J tee bridge), so both app and `:protocol` logs appear here.
 *
 * On-device verification pending: the rendering / scroll / share-sheet behaviour can
 * only be fully confirmed on a device; the data path (buffer → filter → export blob)
 * is unit-tested headlessly in `:protocol`.
 */
@Composable
fun LogConsole(
    modifier: Modifier = Modifier,
    buffer: LogRingBuffer = LogRingBuffer.shared(),
) {
    val context = LocalContext.current
    // null == show everything; otherwise "at or above" this level.
    var minLevel by remember { mutableStateOf<LogRecord.Level?>(null) }
    val records by rememberLogRecords(buffer, minLevel)
    val listState = rememberLazyListState()

    // Auto-scroll to newest as records arrive.
    LaunchedEffect(records.size) {
        if (records.isNotEmpty()) listState.scrollToItem(records.size - 1)
    }

    Column(modifier = modifier.fillMaxSize().padding(8.dp)) {
        // ---- level filter chips ---------------------------------------------
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FilterChip(
                selected = minLevel == null,
                onClick = { minLevel = null },
                label = { Text("ALL") },
            )
            for (lvl in listOf(
                LogRecord.Level.DEBUG,
                LogRecord.Level.INFO,
                LogRecord.Level.WARN,
                LogRecord.Level.ERROR,
            )) {
                FilterChip(
                    selected = minLevel == lvl,
                    onClick = { minLevel = lvl },
                    label = { Text(lvl.name) },
                )
            }
        }

        // ---- copy / export --------------------------------------------------
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = { copyToClipboard(context, buffer.export(minLevel)) }) {
                Text("Copy")
            }
            OutlinedButton(onClick = { exportToFile(context, buffer.export(minLevel)) }) {
                Text("Export")
            }
            Button(onClick = { buffer.clear() }) { Text("Clear") }
        }

        Text(
            "${records.size} lines" + (minLevel?.let { " (≥ ${it.name})" } ?: " (all)"),
            style = MaterialTheme.typography.labelSmall,
        )

        // ---- the scrollable console ----------------------------------------
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF101418))
                .padding(6.dp),
        ) {
            items(records) { rec ->
                Text(
                    text = LogRingBuffer.formatLine(rec),
                    color = colorFor(rec.level()),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

private fun colorFor(level: LogRecord.Level): Color = when (level) {
    LogRecord.Level.TRACE -> Color(0xFF6B7280)
    LogRecord.Level.DEBUG -> Color(0xFF93C5FD)
    LogRecord.Level.INFO -> Color(0xFFD1FAE5)
    LogRecord.Level.WARN -> Color(0xFFFCD34D)
    LogRecord.Level.ERROR -> Color(0xFFFCA5A5)
}

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Fossil Q logs", text))
    Toast.makeText(context, "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
}

private fun exportToFile(context: Context, text: String) {
    try {
        val dir = File(context.cacheDir, "logs").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "fossilq-log-$stamp.txt")
        file.writeText(text)
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file,
        )
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(share, "Export logs").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    } catch (e: Exception) {
        Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
    }
}
