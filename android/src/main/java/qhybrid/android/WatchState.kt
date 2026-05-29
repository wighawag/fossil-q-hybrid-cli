package qhybrid.android

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * WP3 — process-wide connection-state holder.
 *
 * The [WatchConnectionService] is the SINGLE writer; the UI (MainActivity) and
 * future feature WPs (WP11/12/14) are read-only observers via [status].
 *
 * Kept as a plain object (not tied to the service lifecycle) so the latest known
 * state survives the service being rebound/recreated and is instantly available
 * to a freshly-launched Activity.
 */
object WatchState {

    enum class LinkState {
        /** No association / nothing requested yet. */
        IDLE,

        /** GATT connecting (pre-discovery). */
        CONNECTING,

        /** Connected; running the Fossil init/auth handshake. */
        INITIALIZING,

        /** Watch actively asked for authorization (it is vibrating — press TOP). */
        AUTH_REQUIRED,

        /** Fully initialized — link live, device info populated. */
        INITIALIZED,

        /** Link down (intentional disconnect or out-of-range drop). */
        DISCONNECTED,
    }

    data class WatchStatus(
        val link: LinkState = LinkState.IDLE,
        val mac: String? = null,
        val battery: Int? = null,
        val firmware: String? = null,
        val model: String? = null,
        /** Human-readable last event, for the notification + UI status line. */
        val message: String? = null,
    )

    private val _status = MutableStateFlow(WatchStatus())
    val status: StateFlow<WatchStatus> = _status.asStateFlow()

    /** Service-only: replace the whole status. */
    internal fun set(status: WatchStatus) {
        _status.value = status
    }

    /** Service-only: patch fields, keeping the rest. */
    internal fun update(
        link: LinkState? = null,
        mac: String? = null,
        battery: Int? = null,
        firmware: String? = null,
        model: String? = null,
        message: String? = null,
        clearDeviceInfo: Boolean = false,
    ) {
        val cur = _status.value
        _status.value = cur.copy(
            link = link ?: cur.link,
            mac = mac ?: cur.mac,
            battery = if (clearDeviceInfo) null else (battery ?: cur.battery),
            firmware = if (clearDeviceInfo) null else (firmware ?: cur.firmware),
            model = if (clearDeviceInfo) null else (model ?: cur.model),
            message = message ?: cur.message,
        )
    }
}
