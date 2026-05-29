package qhybrid.android

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.util.Log
import qhybrid.linux.BleTransport
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.function.BiConsumer
import java.util.function.Consumer

/**
 * WP0.5 first-pass [BleTransport] over Android's native [BluetoothGatt].
 *
 * The protocol layer ([qhybrid.linux.FossilQAdapter]) drives this through the
 * SYNCHRONOUS/blocking BleTransport contract: connect() blocks until ready,
 * readCharacteristic() returns bytes directly, etc. Android's GATT API is the
 * opposite — fully async and callback-driven, and only ONE GATT operation may
 * be in flight at a time. This class bridges the two by:
 *   - serializing every GATT op behind a single lock, and
 *   - blocking the caller on a per-op CountDownLatch until the matching
 *     BluetoothGattCallback fires (or a timeout elapses).
 *
 * This is intentionally a minimal first pass to prove :protocol links and runs
 * inside an APK on real hardware (WP0.5). WP2 hardens it (robust bonding,
 * write-type selection, reconnect/backoff, MTU edge cases, threading polish).
 */
@SuppressLint("MissingPermission") // Permissions are requested in MainActivity before connect().
class AndroidBleTransport(private val context: Context) : BleTransport {

    companion object {
        private const val TAG = "AndroidBleTransport"
        private const val OP_TIMEOUT_MS = 10_000L
        private const val CONNECT_TIMEOUT_MS = 20_000L
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val DEFAULT_MTU = 23

        // The six Fossil characteristics that the protocol layer expects to be
        // receiving notifications/indications on. The protocol (FossilQAdapter)
        // assumes the transport enabled these during connect() — the CLI's
        // BluezTransport does exactly this. Without them, the auth handshake's
        // response on 3dda0005 never arrives and init stalls until timeout.
        private val FOSSIL_NOTIFY_UUIDS: List<UUID> = listOf(
            UUID.fromString("3dda0002-957f-7d4a-34a6-74696673696d"),
            UUID.fromString("3dda0003-957f-7d4a-34a6-74696673696d"),
            UUID.fromString("3dda0004-957f-7d4a-34a6-74696673696d"),
            UUID.fromString("3dda0005-957f-7d4a-34a6-74696673696d"),
            UUID.fromString("3dda0006-957f-7d4a-34a6-74696673696d"),
            UUID.fromString("3dda0007-957f-7d4a-34a6-74696673696d"),
        )
    }

    private var gatt: BluetoothGatt? = null
    @Volatile private var connectedMac: String? = null
    @Volatile private var mtu: Int = DEFAULT_MTU

    // One GATT op at a time. Each blocking call grabs this lock for its duration.
    private val opLock = Any()

    // Latches/results for the currently outstanding operation.
    private var servicesLatch: CountDownLatch? = null
    private var readLatch: CountDownLatch? = null
    private val readResult = AtomicReference<ByteArray?>()
    private var writeLatch: CountDownLatch? = null
    @Volatile private var writeSuccess = false
    private var mtuLatch: CountDownLatch? = null

    private var notificationCallback: BiConsumer<UUID, ByteArray>? = null
    private var connectionCallback: Consumer<Boolean>? = null
    private var mtuCallback: Consumer<Int>? = null

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "onConnectionStateChange status=$status newState=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectedMac = g.device.address
                // Discover services before we report "connected" to the protocol layer.
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connectedMac = null
                connectionCallback?.accept(false)
                // Release anything still waiting so callers don't hang forever.
                servicesLatch?.countDown()
                readLatch?.countDown()
                writeLatch?.countDown()
                mtuLatch?.countDown()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            Log.d(TAG, "onServicesDiscovered status=$status")
            servicesLatch?.countDown()
        }

        // API 33+ signature
        override fun onCharacteristicRead(
            g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray, status: Int
        ) {
            readResult.set(if (status == BluetoothGatt.GATT_SUCCESS) value else null)
            readLatch?.countDown()
        }

        // Pre-33 signature
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(
            g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                readResult.set(if (status == BluetoothGatt.GATT_SUCCESS) ch.value else null)
                readLatch?.countDown()
            }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int
        ) {
            writeSuccess = status == BluetoothGatt.GATT_SUCCESS
            writeLatch?.countDown()
        }

        // API 33+ signature
        override fun onCharacteristicChanged(
            g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray
        ) {
            Log.d(TAG, "NOTIFY ${ch.uuid} <- ${value.toHex()}")
            notificationCallback?.accept(ch.uuid, value)
        }

        // Pre-33 signature
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                val v = ch.value ?: return
                notificationCallback?.accept(ch.uuid, v)
            }
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int
        ) {
            // CCCD write completion — release the enableNotifications() waiter.
            writeSuccess = status == BluetoothGatt.GATT_SUCCESS
            writeLatch?.countDown()
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                mtu = newMtu
                mtuCallback?.accept(newMtu)
            }
            mtuLatch?.countDown()
        }
    }

    override fun connect(macAddress: String): Boolean = synchronized(opLock) {
        val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = mgr?.adapter ?: run { Log.e(TAG, "No BluetoothAdapter"); return false }
        if (!adapter.isEnabled) { Log.e(TAG, "Bluetooth is off"); return false }

        val device = try {
            adapter.getRemoteDevice(macAddress)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Bad MAC: $macAddress", e); return false
        }

        servicesLatch = CountDownLatch(1)
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice_TRANSPORT_LE)

        val ready = servicesLatch?.await(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS) ?: false
        servicesLatch = null
        if (!ready || connectedMac == null) {
            Log.e(TAG, "Connect/discover timed out")
            disconnect()
            return false
        }

        // Enable notifications BEFORE reporting connected, so the protocol layer's
        // auth handshake (and all later indications) are actually delivered.
        // synchronized(opLock) is reentrant, so calling enableNotifications()
        // here — while still holding the lock — is safe.
        for (uuid in FOSSIL_NOTIFY_UUIDS) {
            try {
                enableNotifications(uuid)
            } catch (e: Exception) {
                Log.w(TAG, "enableNotifications failed for $uuid", e)
            }
        }
        Log.i(TAG, "Enabled notifications on ${FOSSIL_NOTIFY_UUIDS.size} characteristics")

        connectionCallback?.accept(true)
        return true
    }

    override fun disconnect() {
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (e: Exception) {
            Log.w(TAG, "disconnect error", e)
        } finally {
            gatt = null
            connectedMac = null
        }
    }

    override fun isConnected(): Boolean = connectedMac != null

    override fun getConnectedMac(): String? = connectedMac

    private fun findCharacteristic(uuid: UUID): BluetoothGattCharacteristic? {
        val g = gatt ?: return null
        for (service in g.services) {
            service.getCharacteristic(uuid)?.let { return it }
        }
        return null
    }

    override fun writeCharacteristic(uuid: UUID, data: ByteArray) {
        synchronized(opLock) {
            val g = gatt ?: return
            val ch = findCharacteristic(uuid) ?: run {
                Log.e(TAG, "write: characteristic not found $uuid"); return
            }
            Log.d(TAG, "WRITE $uuid -> ${data.toHex()}")
            writeLatch = CountDownLatch(1)
            writeSuccess = false
            val writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(ch, data, writeType)
            } else {
                @Suppress("DEPRECATION")
                run {
                    ch.value = data
                    ch.writeType = writeType
                    g.writeCharacteristic(ch)
                }
            }
            writeLatch?.await(OP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            writeLatch = null
        }
    }

    override fun readCharacteristic(uuid: UUID): ByteArray? = synchronized(opLock) {
        val g = gatt ?: return null
        val ch = findCharacteristic(uuid) ?: run {
            Log.e(TAG, "read: characteristic not found $uuid"); return null
        }
        readLatch = CountDownLatch(1)
        readResult.set(null)
        g.readCharacteristic(ch)
        readLatch?.await(OP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        readLatch = null
        return readResult.getAndSet(null)
    }

    override fun enableNotifications(uuid: UUID) {
        synchronized(opLock) {
            val g = gatt ?: return
            val ch = findCharacteristic(uuid) ?: run {
                Log.e(TAG, "enableNotifications: characteristic not found $uuid"); return
            }
            g.setCharacteristicNotification(ch, true)
            val cccd = ch.getDescriptor(CCCD_UUID) ?: run {
                Log.w(TAG, "no CCCD on $uuid"); return
            }
            writeLatch = CountDownLatch(1)
            writeSuccess = false
            // CRITICAL: Fossil uses INDICATE on 3dda0003 / 3dda0005 (write+indicate)
            // and NOTIFY on the rest. The CCCD value differs — writing the notify
            // value to an indicate-only characteristic means the watch never sends
            // on it, so the file header (3dda0003) and auth response (3dda0005)
            // never arrive. Pick the right value from the characteristic properties.
            val isIndicate =
                (ch.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
            val enable = if (isIndicate) {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            }
            Log.d(TAG, "enableNotifications $uuid (${if (isIndicate) "INDICATE" else "NOTIFY"})")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, enable)
            } else {
                @Suppress("DEPRECATION")
                run {
                    cccd.value = enable
                    g.writeDescriptor(cccd)
                }
            }
            writeLatch?.await(OP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            writeLatch = null
        }
    }

    override fun requestMtu(mtu: Int) {
        synchronized(opLock) {
            val g = gatt ?: return
            mtuLatch = CountDownLatch(1)
            g.requestMtu(mtu)
            mtuLatch?.await(OP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            mtuLatch = null
        }
    }

    override fun getMtu(): Int = mtu

    /**
     * First-pass bonding. Full bonding state-machine (wait for BOND_BONDED,
     * handle the auth button press, broadcast receivers) is WP2.
     */
    override fun pair(): Boolean {
        val g = gatt ?: return false
        return try {
            if (g.device.bondState == android.bluetooth.BluetoothDevice.BOND_BONDED) true
            else g.device.createBond()
        } catch (e: Exception) {
            Log.w(TAG, "createBond failed", e); false
        }
    }

    override fun setNotificationCallback(callback: BiConsumer<UUID, ByteArray>) {
        this.notificationCallback = callback
    }

    override fun setConnectionCallback(callback: Consumer<Boolean>) {
        this.connectionCallback = callback
    }

    override fun setMtuCallback(callback: Consumer<Int>) {
        this.mtuCallback = callback
    }
}

// BluetoothDevice.TRANSPORT_LE = 2; named here to keep the import list minimal.
private const val BluetoothDevice_TRANSPORT_LE = 2

private fun ByteArray.toHex(): String =
    joinToString(" ") { "%02x".format(it) }
