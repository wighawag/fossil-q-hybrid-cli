package qhybrid.android.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import qhybrid.protocol.BleTransport
import qhybrid.protocol.FossilController
import java.util.UUID
import java.util.function.BiConsumer
import java.util.function.Consumer

/**
 * HYBRID-AUTOCONNECT — pure unit tests for WatchConnectionService's connect-path → autoConnect-flag
 * policy (no Android Service / live BLE needed). They model the SAME decision the service makes —
 * which connect paths use the FAST bounded (autoConnect=false) path vs the BACKGROUND keep-alive
 * (autoConnect=true) path — and verify the flag is actually plumbed through [FossilController] to the
 * transport via the recording [FakeBleTransport].
 *
 * Mapping under test (see WatchConnectionService):
 *   - USER-initiated / first connects (Add watch / Connect now / boot reconnect / CDM
 *     onDeviceAppeared) and the connect-then-sync/buzz/play for explicit saves  → autoConnect=false
 *   - BACKGROUND keep-alive after an unexpected drop, AND the fallback after a user/save connect
 *     that timed out because the watch was asleep                               → autoConnect=true
 */
class AutoConnectPolicyTest {

    /** The connect entry points in the service, classified user-initiated vs background. */
    private enum class ConnectPath {
        ADD_WATCH,            // provisioning / first connect
        CONNECT_NOW,          // user "Connect now"
        BOOT_RECONNECT,       // BootReceiver
        DEVICE_APPEARED,      // CDM presence onDeviceAppeared
        SAVE_THEN_SYNC,       // explicit save (alarm/calendar/buzz) connect-then-do
        DROP_KEEPALIVE,       // background reconnect after an unexpected drop
        TIMEOUT_KEEPALIVE,    // fallback keep-alive after a user/save connect timed out
    }

    /** Mirrors the service's choice of the platform BLE autoConnect flag for each path. */
    private fun autoConnectFor(path: ConnectPath): Boolean = when (path) {
        ConnectPath.ADD_WATCH,
        ConnectPath.CONNECT_NOW,
        ConnectPath.BOOT_RECONNECT,
        ConnectPath.DEVICE_APPEARED,
        ConnectPath.SAVE_THEN_SYNC -> false
        ConnectPath.DROP_KEEPALIVE,
        ConnectPath.TIMEOUT_KEEPALIVE -> true
    }

    /**
     * A minimal recording [BleTransport] (self-contained — no dependency on protocol's test fixture)
     * that captures the autoConnect flag the service would pass for a given path, proving the flag is
     * plumbed through [FossilController.connect].
     */
    private class RecordingTransport : BleTransport {
        var lastAutoConnect: Boolean? = null
        var connectCount = 0
        override fun connect(macAddress: String): Boolean = connect(macAddress, false)
        override fun connect(macAddress: String, autoConnect: Boolean): Boolean {
            lastAutoConnect = autoConnect
            connectCount++
            return true
        }
        override fun disconnect() {}
        override fun isConnected(): Boolean = false
        override fun getConnectedMac(): String? = null
        override fun writeCharacteristic(uuid: UUID, data: ByteArray) {}
        override fun pair(): Boolean = true
        override fun readCharacteristic(uuid: UUID): ByteArray = ByteArray(0)
        override fun enableNotifications(uuid: UUID) {}
        override fun requestMtu(mtu: Int) {}
        override fun getMtu(): Int = 23
        override fun setNotificationCallback(callback: BiConsumer<UUID, ByteArray>) {}
        override fun setConnectionCallback(callback: Consumer<Boolean>) {}
        override fun setMtuCallback(callback: Consumer<Int>) {}
    }

    private fun connectVia(path: ConnectPath): RecordingTransport {
        val fake = RecordingTransport()
        val controller = FossilController(fake)
        controller.connect("AA:BB:CC:DD:EE:FF", autoConnectFor(path))
        return fake
    }

    @Test
    fun userAndFirstConnects_useFastBoundedPath() {
        for (path in listOf(
            ConnectPath.ADD_WATCH,
            ConnectPath.CONNECT_NOW,
            ConnectPath.BOOT_RECONNECT,
            ConnectPath.DEVICE_APPEARED,
        )) {
            val fake = connectVia(path)
            assertFalse("$path must use autoConnect=false (fast bounded)", fake.lastAutoConnect!!)
        }
    }

    @Test
    fun explicitSaveConnectThenSync_staysFastBounded() {
        val fake = connectVia(ConnectPath.SAVE_THEN_SYNC)
        assertFalse(
            "connect-then-sync for a save keeps autoConnect=false (immediate honest feedback)",
            fake.lastAutoConnect!!,
        )
    }

    @Test
    fun backgroundDropReconnect_usesAutoConnectTrue() {
        val fake = connectVia(ConnectPath.DROP_KEEPALIVE)
        assertTrue(
            "background keep-alive after a drop must use autoConnect=true (controller-managed)",
            fake.lastAutoConnect!!,
        )
    }

    @Test
    fun saveTimeoutFallback_armsAutoConnectTrue() {
        val fake = connectVia(ConnectPath.TIMEOUT_KEEPALIVE)
        assertTrue(
            "a save/user connect that timed out arms an autoConnect=true keep-alive",
            fake.lastAutoConnect!!,
        )
    }

    @Test
    fun exactlyOneConnectPerArm() {
        val fake = connectVia(ConnectPath.DROP_KEEPALIVE)
        assertEquals("arming the keep-alive registers exactly one connect", 1, fake.connectCount)
    }
}
