package qhybrid.android.log

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import qhybrid.protocol.log.LogRecord
import qhybrid.protocol.log.LogRingBuffer

/**
 * WP15 — Compose glue that turns the pure [LogRingBuffer] (which only exposes a
 * `Runnable` change-listener, no coroutines/StateFlow) into observable Compose state.
 *
 * Subscribes to the buffer's change listener while the composition is active and
 * re-snapshots the records (filtered by [minLevel]) on every append. Snapshots are
 * cheap immutable copies, and the buffer is bounded, so this stays light.
 */
@Composable
fun rememberLogRecords(
    buffer: LogRingBuffer = LogRingBuffer.shared(),
    minLevel: LogRecord.Level?,
): State<List<LogRecord>> {
    val state = remember { mutableStateOf(buffer.filter(minLevel)) }
    // Re-snapshot whenever the filter changes too.
    DisposableEffect(buffer, minLevel) {
        state.value = buffer.filter(minLevel)
        val listener = Runnable { state.value = buffer.filter(minLevel) }
        buffer.addChangeListener(listener)
        onDispose { buffer.removeChangeListener(listener) }
    }
    return state
}
