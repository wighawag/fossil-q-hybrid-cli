package qhybrid.android.sync

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import qhybrid.android.WatchState

/**
 * WP-SYNCFIX — a small connection-state banner for the per-feature config screens (Buttons /
 * Alarms / Notifications / Settings).
 *
 * **Design (option A):** the config screens stay fully editable offline — you are editing the
 * watch's *saved settings* (Room is the source of truth), not the live watch. This banner just
 * makes the link state honest so the user understands WHEN their changes reach the watch:
 *
 * - **Connected** → "Watch connected" (changes sync on Save).
 * - **Connecting / Initializing / Authorizing** → a spinner + the live status message.
 * - **Disconnected / Idle** → "Watch disconnected — changes are saved and will be sent when it
 *   connects" (Save still works: it triggers a connect-then-sync, see [ServiceSaveToWatch]).
 *
 * It observes the WP3 [WatchState] process-wide holder directly (read-only, like the Dashboard),
 * so no ViewModel plumbing is needed. Visual rendering is on-device-pending; the link-state
 * classification is the WP3-owned signal.
 */
@Composable
fun ConnectionBanner(modifier: Modifier = Modifier) {
    val status by WatchState.status.collectAsStateWithLifecycle()
    val info = bannerInfoFor(status.link, status.message)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(info.container)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (info.busy) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        }
        Text(info.text, style = MaterialTheme.typography.labelMedium, color = info.onContainer)
    }
}

private data class BannerInfo(
    val text: String,
    val busy: Boolean,
    val container: Color,
    val onContainer: Color,
)

/**
 * Pure classification of the link state into the banner's text + busy flag. Kept separate (and
 * colour-free) so the message mapping is trivially reasoned about; colours are resolved in the
 * composable from the theme.
 */
internal fun bannerText(link: WatchState.LinkState, liveMessage: String?): Pair<String, Boolean> =
    when (link) {
        WatchState.LinkState.INITIALIZED -> "Watch connected" to false
        WatchState.LinkState.CONNECTING -> (liveMessage ?: "Connecting to your watch…") to true
        WatchState.LinkState.INITIALIZING -> (liveMessage ?: "Initializing…") to true
        WatchState.LinkState.AUTH_REQUIRED ->
            (liveMessage ?: "Hold the watch's TOP button to authorize.") to true
        WatchState.LinkState.DISCONNECTED, WatchState.LinkState.IDLE ->
            "Watch disconnected — changes are saved and will be sent when it connects." to false
    }

@Composable
private fun bannerInfoFor(link: WatchState.LinkState, liveMessage: String?): BannerInfo {
    val (text, busy) = bannerText(link, liveMessage)
    val scheme = MaterialTheme.colorScheme
    val connected = link == WatchState.LinkState.INITIALIZED
    return if (connected) {
        BannerInfo(text, busy, scheme.secondaryContainer, scheme.onSecondaryContainer)
    } else {
        // Disconnected / busy use the neutral surface variant (not an error — offline is normal).
        BannerInfo(text, busy, scheme.surfaceVariant, scheme.onSurfaceVariant)
    }
}
