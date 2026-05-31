package qhybrid.android

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import qhybrid.protocol.BleTransport
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.function.BiConsumer
import java.util.function.Consumer

/**
 * WP2 hardened [BleTransport] over Android's native [BluetoothGatt].
 *
 * The protocol layer ([qhybrid.protocol.FossilQAdapter]) drives this through the
 * SYNCHRONOUS/blocking BleTransport contract: connect() blocks until ready,
 * readCharacteristic() returns bytes directly, etc. Android's GATT API is the
 * opposite — fully async and callback-driven, and only ONE GATT operation may
 * be in flight at a time. This class bridges the two by:
 *   - serializing every GATT op behind a single lock (opLock), and
 *   - blocking the caller on a per-op CountDownLatch until the matching
 *     BluetoothGattCallback fires (or a timeout elapses).
 *
 * Hardening over the WP0.5 first pass:
 *   - Per-characteristic write-type selection (FINDINGS #2): the Fossil watch
 *     rejects write-with-response on its write+notify characteristics; only the
 *     INDICATE chars (3dda0003 / 3dda0005) take write-with-response.
 *   - Proactive MTU request (target 512) in connect(), wired to mtuCallback
 *     (FINDINGS #5 — the adapter never issues an ATT-layer MTU request itself).
 *   - Connect/discover retry + backoff with clean timeouts (increment 2).
 *   - Bond state-machine in pair() (increment 3).
 *   - Stale-callback guarding: each op records the expected characteristic UUID
 *     and an op generation, so a late callback from a previous op can never
 *     complete the next op's latch.
 *
 * Threading: a dedicated background [HandlerThread] ("ble-gatt") is passed to
 * [BluetoothDevice.connectGatt] so that ALL [BluetoothGattCallback] events —
 * including [onCharacteristicChanged], which forwards every incoming watch
 * notification into the SYNCHRONOUS protocol layer ([qhybrid.protocol.FossilQAdapter])
 * — are delivered OFF the main thread. (Without an explicit Handler, Android delivers
 * GATT callbacks on the app's MAIN thread; during the connect/init/auth/file-sync
 * notification burst the protocol's synchronous processing would then saturate the
 * main thread and ANR the UI — "Skipped N frames". See the connect() comment.) The
 * caller's blocking (connect()/read()/write() awaiting a CountDownLatch) happens on
 * the service's "ble-worker" executor, never the main thread.
 */
@SuppressLint("MissingPermission") // Permissions are requested in MainActivity before connect().
class AndroidBleTransport(private val context: Context) : BleTransport {

    companion object {
        private const val TAG = "AndroidBleTransport"
        private const val OP_TIMEOUT_MS = 10_000L
        private const val CONNECT_STATE_TIMEOUT_MS = 15_000L
        private const val DISCOVER_TIMEOUT_MS = 10_000L
        // Flow control for WRITE_TYPE_NO_RESPONSE (file-transfer data chunks). The pacing wait is
        // the per-chunk "ready for next" window (onCharacteristicWrite); a few retries cover a
        // momentarily-busy stack so a chunk is never silently dropped (which truncated the file).
        private const val NO_RESPONSE_PACING_MS = 250L
        private const val NO_RESPONSE_MAX_RETRIES = 20
        // WP-FILEPUT-RELIABLE: bounded wait for a write-WITH-response (control char 3dda0003) ATT
        // ack. The file-PUT protocol's real completion signal is the watch's INDICATION (e.g. the
        // 0x83 PUT accept, the 0x84 VERIFY success), NOT the ATT write ack — and that indication
        // arrives on the GATT callback thread, where the protocol must SYNCHRONOUSLY issue the next
        // write (data chunks on 3dda0004, or VERIFY on 3dda0003). [opLock] is held for the whole of
        // this wait, so the watch's response indication cannot be PROCESSED until it elapses.
        //
        // The ATT ack normally arrives in well under this window; we only wait at all to satisfy
        // Android's one-outstanding-GATT-op rule (so the next op isn't rejected as busy). Keep it
        // SHORT: a long wait (e.g. 1.5s) was held after EVERY control write, adding ~1.5s of latency
        // per PUT step (open + verify) before the watch's already-sent 0x83/0x84 was handled — a
        // visible multi-second buzz delay. If onCharacteristicWrite fires, the latch releases even
        // sooner.
        private const val CONTROL_WRITE_ACK_MS = 400L
        private const val DISCOVER_MAX_ATTEMPTS = 3
        private const val BOND_TIMEOUT_MS = 30_000L
        private const val TARGET_MTU = 512
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val DEFAULT_MTU = 23
        private const val TRANSPORT_LE = 2 // BluetoothDevice.TRANSPORT_LE

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

    // Dedicated background thread for GATT callback delivery (see the class doc). Created
    // lazily on connect() and quit in disconnect() — the WP3 service builds a fresh
    // AndroidBleTransport per connect, so this is naturally scoped to one link.
    @Volatile private var gattThread: HandlerThread? = null
    @Volatile private var gattHandler: Handler? = null

    // One GATT op at a time. Each blocking call grabs this lock for its duration.
    private val opLock = Any()

    // ---- Outstanding-operation state (only touched under opLock or by callbacks) ----
    // Connection-state latch (signalled by onConnectionStateChange).
    @Volatile private var connectLatch: CountDownLatch? = null
    @Volatile private var lastConnState: Int = BluetoothProfile.STATE_DISCONNECTED

    // Service-discovery latch.
    @Volatile private var servicesLatch: CountDownLatch? = null
    @Volatile private var servicesStatus: Int = BluetoothGatt.GATT_FAILURE

    // Read latch + result. expectedReadUuid guards against stale callbacks.
    @Volatile private var readLatch: CountDownLatch? = null
    @Volatile private var expectedReadUuid: UUID? = null
    private val readResult = AtomicReference<ByteArray?>()

    // Write latch (characteristic OR descriptor). expectedWriteUuid guards stale callbacks.
    @Volatile private var writeLatch: CountDownLatch? = null
    @Volatile private var expectedWriteUuid: UUID? = null
    @Volatile private var writeSuccess = false

    // MTU latch.
    @Volatile private var mtuLatch: CountDownLatch? = null

    private var notificationCallback: BiConsumer<UUID, ByteArray>? = null
    private var connectionCallback: Consumer<Boolean>? = null
    private var mtuCallback: Consumer<Int>? = null

    // HYBRID-AUTOCONNECT: true while this transport was registered with autoConnect=true (a
    // controller-managed background reconnect that does NOT block 15s and may establish at ANY
    // later time when the watch reappears). For such a connect, onConnectionStateChange(CONNECTED)
    // is what drives the post-connect sequence (discover → MTU → notifications → accept(true)),
    // since there is no blocking connect() call awaiting connectLatch to do it inline.
    @Volatile private var autoConnectMode: Boolean = false
    // The mac we registered an auto-connect for (used to label the deferred post-connect work).
    @Volatile private var autoConnectMac: String? = null
    // Guards the post-connect sequence so it runs exactly once per established link (the blocking
    // path runs it inline; the auto path runs it from the CONNECTED callback). Reset on each
    // connect()/disconnect().
    private val postConnectDone = java.util.concurrent.atomic.AtomicBoolean(false)

    // HYBRID-AUTOCONNECT: a single-thread executor on which the DEFERRED post-connect sequence runs
    // for the auto path. It MUST NOT run on the gatt HandlerThread: runPostConnectSequence() blocks
    // on latches that are released by GATT callbacks (onServicesDiscovered/onDescriptorWrite/
    // onMtuChanged) which are themselves delivered on the gatt HandlerThread — running it there
    // would deadlock. (The blocking connect() path avoids this by blocking on the ble-worker.) Lazy
    // + scoped to one link; shut down in disconnect().
    @Volatile private var autoConnectWorker: java.util.concurrent.ExecutorService? = null

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "onConnectionStateChange status=$status newState=$newState")
            lastConnState = newState
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectedMac = g.device.address
                connectLatch?.countDown()
                // HYBRID-AUTOCONNECT: an autoConnect=true link can come up at ANY time (deferred),
                // with no blocking connect() awaiting connectLatch to run the post-connect sequence.
                // Drive discover → MTU → notifications → accept(true) HERE (on the gatt handler
                // thread). The blocking path leaves autoConnectMode=false and runs it inline, so we
                // skip it here to keep that path's behavior identical.
                if (autoConnectMode) {
                    // Run the (blocking) post-connect sequence OFF the gatt handler thread — it
                    // awaits latches released BY this thread's callbacks, so running it here would
                    // deadlock. Hand it to the dedicated auto-connect worker.
                    val mac = autoConnectMac ?: g.device.address
                    val ex = ensureAutoConnectWorker()
                    runCatching { ex.execute { runPostConnectSequence(mac) } }
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val wasConnected = connectedMac != null
                connectedMac = null
                // Release anything still waiting so callers don't hang forever.
                connectLatch?.countDown()
                servicesLatch?.countDown()
                readLatch?.countDown()
                writeLatch?.countDown()
                mtuLatch?.countDown()
                // Only notify the protocol on an UNEXPECTED drop (we were connected).
                // An intentional disconnect() reports false itself.
                if (wasConnected) {
                    connectionCallback?.accept(false)
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            Log.d(TAG, "onServicesDiscovered status=$status")
            servicesStatus = status
            servicesLatch?.countDown()
        }

        // API 33+ signature
        override fun onCharacteristicRead(
            g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray, status: Int
        ) {
            if (ch.uuid != expectedReadUuid) return // stale callback
            readResult.set(if (status == BluetoothGatt.GATT_SUCCESS) value else null)
            readLatch?.countDown()
        }

        // Pre-33 signature
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(
            g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                if (ch.uuid != expectedReadUuid) return
                @Suppress("DEPRECATION")
                readResult.set(if (status == BluetoothGatt.GATT_SUCCESS) ch.value else null)
                readLatch?.countDown()
            }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int
        ) {
            if (ch.uuid != expectedWriteUuid) return // stale callback
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
                Log.d(TAG, "NOTIFY ${ch.uuid} <- ${v.toHex()}")
                notificationCallback?.accept(ch.uuid, v)
            }
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int
        ) {
            // CCCD write completion — release the enableNotifications() waiter.
            // The CCCD always lives under the characteristic we are enabling.
            if (d.characteristic?.uuid != expectedWriteUuid) return // stale callback
            writeSuccess = status == BluetoothGatt.GATT_SUCCESS
            writeLatch?.countDown()
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            Log.d(TAG, "onMtuChanged mtu=$newMtu status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                mtu = newMtu
                mtuCallback?.accept(newMtu)
            }
            mtuLatch?.countDown()
        }
    }

    override fun connect(macAddress: String): Boolean = connect(macAddress, false)

    /**
     * HYBRID-AUTOCONNECT — connect with an explicit platform BLE auto-connect preference.
     *
     * - [autoConnect] = false (the [connect] default): the FAST, BLOCKING path used for
     *   user-initiated / first connects. {@code connectGatt(autoConnect=false)} is a directly
     *   initiated connect; we await STATE_CONNECTED for [CONNECT_STATE_TIMEOUT_MS], then run the
     *   post-connect sequence (discover → MTU → notifications → accept(true)) INLINE and return
     *   true/false. An unreachable watch fails honestly and quickly — behavior is unchanged.
     *
     * - [autoConnect] = true: the controller-managed BACKGROUND path used for keep-alive reconnect
     *   after a drop. {@code connectGatt(autoConnect=true)} does NOT time out — it returns
     *   immediately and the OS BLE controller maintains the pending connect until the (directed-
     *   advertising) watch reappears, then fires STATE_CONNECTED. We therefore register the GATT and
     *   return WITHOUT blocking; the post-connect sequence + accept(true) are driven from
     *   [onConnectionStateChange] when the link actually establishes (possibly much later). The
     *   boolean return here only reports whether the connect was successfully REGISTERED.
     */
    override fun connect(macAddress: String, autoConnect: Boolean): Boolean = synchronized(opLock) {
        val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter: BluetoothAdapter = mgr?.adapter ?: run { Log.e(TAG, "No BluetoothAdapter"); return false }
        if (!adapter.isEnabled) { Log.e(TAG, "Bluetooth is off"); return false }

        val device: BluetoothDevice = try {
            adapter.getRemoteDevice(macAddress)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Bad MAC: $macAddress", e); return false
        }

        // Per-link state: the post-connect sequence must run exactly once for this connect.
        postConnectDone.set(false)
        autoConnectMode = autoConnect
        autoConnectMac = macAddress

        // Deliver every GATT callback on a dedicated background thread (NOT the main
        // thread) so that onCharacteristicChanged → the synchronous protocol layer can't
        // saturate the UI thread during the notification burst (ANR fix). The 6-arg
        // overload that accepts a Handler is API 26+ (== our minSdk).
        val handler = ensureGattHandler()

        if (autoConnect) {
            // BACKGROUND path: register a non-timing-out auto-connect and return immediately. The
            // link-up + post-connect sequence are handled in onConnectionStateChange(CONNECTED).
            // No connectLatch is armed (we are NOT blocking on it here).
            gatt = device.connectGatt(
                context, true, gattCallback, TRANSPORT_LE,
                BluetoothDevice.PHY_LE_1M_MASK, handler,
            )
            val registered = gatt != null
            Log.i(TAG, "auto-connect registered=$registered for $macAddress (link-up deferred)")
            return registered
        }

        // BLOCKING path (autoConnect=false): wait for STATE_CONNECTED, then run the post-connect
        // sequence inline. autoConnectMode=false means onConnectionStateChange(CONNECTED) does NOT
        // run the sequence — we own it here.
        connectLatch = CountDownLatch(1)
        gatt = device.connectGatt(
            context, false, gattCallback, TRANSPORT_LE,
            BluetoothDevice.PHY_LE_1M_MASK, handler,
        )
        val connected = connectLatch?.await(CONNECT_STATE_TIMEOUT_MS, TimeUnit.MILLISECONDS) ?: false
        connectLatch = null
        if (!connected || connectedMac == null) {
            Log.e(TAG, "GATT connect timed out for $macAddress")
            disconnect()
            return false
        }
        return runPostConnectSequence(macAddress)
    }

    /**
     * The post-link-up GATT sequence: discover services (with retry/refresh) → request MTU →
     * enable notifications → report connected. Runs UNDER [opLock] (reentrant from the blocking
     * connect() path; grabbed fresh from the auto-connect callback). Idempotent per link via
     * [postConnectDone]. Returns true if the Fossil characteristics were resolved and the link is
     * usable; false (after disconnect()) otherwise.
     *
     * For the auto-connect path this is invoked from onConnectionStateChange(CONNECTED), so a
     * deferred link establishes exactly as a blocking connect would have — service-discovery and
     * notification-enable still complete BEFORE accept(true) is delivered to the protocol layer.
     */
    private fun runPostConnectSequence(macAddress: String): Boolean = synchronized(opLock) {
        if (!postConnectDone.compareAndSet(false, true)) {
            // Already ran for this link (e.g. a duplicate CONNECTED callback) — no-op.
            return true
        }
        if (connectedMac == null) {
            Log.w(TAG, "post-connect: link already gone for $macAddress")
            return false
        }

        // Step 2: discover services, with retry + backoff. Android's stack is
        // usually reliable on the first try (FINDINGS #15), but transient failures
        // (status 133, stale cache after a previous bond) do occur. We retry up to
        // DISCOVER_MAX_ATTEMPTS, refreshing the GATT cache as a fallback (option b)
        // before the last attempt to clear a stale service list.
        var resolved = false
        var attempt = 0
        while (attempt < DISCOVER_MAX_ATTEMPTS && connectedMac != null) {
            attempt++
            if (discoverServices() && findCharacteristic(FOSSIL_NOTIFY_UUIDS[0]) != null) {
                resolved = true
                break
            }
            Log.w(TAG, "Service discovery attempt $attempt/$DISCOVER_MAX_ATTEMPTS did not resolve Fossil chars")
            if (attempt < DISCOVER_MAX_ATTEMPTS && connectedMac != null) {
                // Fallback (option b): clear the stale GATT cache before retrying.
                refreshGattCache()
                try { Thread.sleep(250L * attempt) } catch (ignored: InterruptedException) {}
            }
        }
        if (!resolved) {
            Log.e(TAG, "Fossil service/characteristics not found after $attempt discovery attempt(s)")
            disconnect()
            return false
        }

        // Step 3: request a larger MTU (FINDINGS #5 — the adapter never does this
        // at the ATT layer; it relies on the transport). Best-effort: on failure
        // we keep DEFAULT_MTU and the file-transfer layer chunks accordingly.
        try { requestMtu(TARGET_MTU) } catch (e: Exception) { Log.w(TAG, "requestMtu failed", e) }

        // Step 4: enable notifications BEFORE reporting connected, so the protocol
        // layer's auth handshake (and all later indications) are delivered.
        // synchronized(opLock) is reentrant, so calling these here is safe.
        for (uuid in FOSSIL_NOTIFY_UUIDS) {
            try {
                enableNotifications(uuid)
            } catch (e: Exception) {
                Log.w(TAG, "enableNotifications failed for $uuid", e)
            }
        }
        Log.i(TAG, "Connected to $macAddress (mtu=$mtu), notifications enabled on ${FOSSIL_NOTIFY_UUIDS.size} chars")

        // Report connected LAST (after discovery + notifications). For the auto path this fires
        // whenever the deferred link establishes — service-discovery and notification-enable have
        // completed BEFORE accept(true), exactly as the blocking path's inline return does.
        connectionCallback?.accept(true)
        return true
    }

    /** Issue discoverServices() and block on the result. Must hold opLock. */
    private fun discoverServices(): Boolean {
        val g = gatt ?: return false
        servicesLatch = CountDownLatch(1)
        servicesStatus = BluetoothGatt.GATT_FAILURE
        if (!g.discoverServices()) {
            servicesLatch = null
            Log.e(TAG, "discoverServices() returned false")
            return false
        }
        val done = servicesLatch?.await(DISCOVER_TIMEOUT_MS, TimeUnit.MILLISECONDS) ?: false
        servicesLatch = null
        return done && servicesStatus == BluetoothGatt.GATT_SUCCESS && connectedMac != null
    }

    /**
     * Clear the GATT service cache via the hidden BluetoothGatt.refresh() (option b
     * fallback). This is a non-public API; we call it reflectively and tolerate its
     * absence. Used only after a failed service discovery to drop a stale cache.
     */
    private fun refreshGattCache() {
        val g = gatt ?: return
        try {
            val refresh = g.javaClass.getMethod("refresh")
            val ok = refresh.invoke(g) as? Boolean ?: false
            Log.d(TAG, "BluetoothGatt.refresh() -> $ok")
        } catch (e: Exception) {
            Log.d(TAG, "BluetoothGatt.refresh() unavailable: ${e.message}")
        }
    }

    override fun disconnect() {
        try {
            val g = gatt
            if (g != null) {
                // Best-effort: stop local notification routing before tearing down.
                // (We do NOT block on CCCD writes here — the link may already be gone.)
                if (connectedMac != null) {
                    for (uuid in FOSSIL_NOTIFY_UUIDS) {
                        findCharacteristic(uuid)?.let {
                            try { g.setCharacteristicNotification(it, false) } catch (_: Exception) {}
                        }
                    }
                }
                g.disconnect()
                g.close()
            }
        } catch (e: Exception) {
            Log.w(TAG, "disconnect error", e)
        } finally {
            val wasConnected = connectedMac != null
            gatt = null
            connectedMac = null
            // Release any in-flight waiters.
            connectLatch?.countDown()
            servicesLatch?.countDown()
            readLatch?.countDown()
            writeLatch?.countDown()
            mtuLatch?.countDown()
            if (wasConnected) connectionCallback?.accept(false)
            // HYBRID-AUTOCONNECT: a non-blocking auto-connect pending GATT is cancelled by
            // disconnect()+close() above — so an intentional disconnect()/forget never lets the
            // controller silently re-establish the link. Tear down the deferred-work worker too.
            // shutdown() (not shutdownNow) so an in-flight post-connect task drains — disconnect()
            // can be called FROM that task (discovery failure), where interrupting itself is moot.
            autoConnectMode = false
            autoConnectMac = null
            runCatching { autoConnectWorker?.shutdown() }
            autoConnectWorker = null
            // Tear down the GATT callback thread (a fresh transport is built per connect).
            // quitSafely() lets already-queued callbacks drain first.
            gattThread?.quitSafely()
            gattThread = null
            gattHandler = null
        }
    }

    /** Lazily create (or reuse) the background HandlerThread that delivers GATT callbacks. */
    private fun ensureGattHandler(): Handler {
        gattHandler?.let { return it }
        val thread = HandlerThread("ble-gatt").also { it.start() }
        val handler = Handler(thread.looper)
        gattThread = thread
        gattHandler = handler
        return handler
    }

    /**
     * Lazily create (or reuse) the single-thread executor that runs the DEFERRED post-connect
     * sequence for the auto-connect path (off the gatt handler thread — see [autoConnectWorker]).
     */
    private fun ensureAutoConnectWorker(): java.util.concurrent.ExecutorService {
        autoConnectWorker?.let { return it }
        val ex = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "ble-autoconnect")
        }
        autoConnectWorker = ex
        return ex
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

    /**
     * Choose the ATT write type from the characteristic's properties (FINDINGS #2).
     * The Fossil watch REJECTS write-with-response on its write+notify chars
     * (3dda0002/0004/0006/0007); only the INDICATE chars (3dda0003/0005) accept
     * write-with-response. Mirrors BluezTransport.getWriteType():
     *   - write-without-response flag  → WRITE_TYPE_NO_RESPONSE
     *   - indicate flag                → WRITE_TYPE_DEFAULT (write-with-response)
     *   - otherwise (write+notify)     → WRITE_TYPE_NO_RESPONSE (safe Fossil default)
     */
    private fun writeTypeFor(ch: BluetoothGattCharacteristic): Int {
        val props = ch.properties
        val hasNoResponse =
            (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
        val hasIndicate =
            (props and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
        return when {
            hasNoResponse -> BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            hasIndicate -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            else -> BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
    }

    override fun writeCharacteristic(uuid: UUID, data: ByteArray) {
        synchronized(opLock) {
            val g = gatt ?: return
            val ch = findCharacteristic(uuid) ?: run {
                Log.e(TAG, "write: characteristic not found $uuid"); return
            }
            val writeType = writeTypeFor(ch)
            val noResponse = writeType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            val typeName = if (writeType == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) "request" else "command"
            Log.d(TAG, "WRITE $uuid ($typeName) -> ${data.toHex()}")
            writeLatch = CountDownLatch(1)
            expectedWriteUuid = uuid
            writeSuccess = false

            // Submit. For WRITE_TYPE_NO_RESPONSE (the 3dda0004 file-transfer data chunks) Android's
            // stack accepts only ONE unacknowledged write at a time: a back-to-back submit returns
            // a busy/failure code and the chunk is silently DROPPED — which truncated the button
            // file and made the watch time out the PUT (response 9). So on a busy no-response
            // submit we briefly wait for the in-flight write's onCharacteristicWrite (the stack's
            // "ready for next" signal) and RETRY, instead of dropping the chunk.
            var ok = submitWrite(g, ch, data, writeType)
            if (!ok && noResponse) {
                var attempt = 0
                while (!ok && attempt < NO_RESPONSE_MAX_RETRIES) {
                    attempt++
                    // The previous write's callback releases this latch when the stack is ready.
                    writeLatch?.await(NO_RESPONSE_PACING_MS, TimeUnit.MILLISECONDS)
                    writeLatch = CountDownLatch(1)
                    expectedWriteUuid = uuid
                    ok = submitWrite(g, ch, data, writeType)
                }
                if (!ok) Log.w(TAG, "writeCharacteristic($uuid) dropped after $attempt retries")
            }
            if (!ok) {
                Log.w(TAG, "writeCharacteristic($uuid) submission failed")
                writeLatch = null
                expectedWriteUuid = null
                return
            }
            // Wait for completion. For no-response writes onCharacteristicWrite fires as the stack's
            // pacing/"ready" signal (flow control), so we await it with a SHORT timeout. For
            // write-with-response (the indicate control chars) we await the ATT ack but only for a
            // BOUNDED window (CONTROL_WRITE_ACK_MS) — NOT the full 10s op timeout. Holding opLock for
            // 10s on a control write starved the accept-driven data write (which runs on the GATT
            // callback thread and needs opLock), so the watch ABORTed the PUT before data arrived.
            val waitMs = if (noResponse) NO_RESPONSE_PACING_MS else CONTROL_WRITE_ACK_MS
            writeLatch?.await(waitMs, TimeUnit.MILLISECONDS)
            writeLatch = null
            expectedWriteUuid = null
        }
    }

    /** Submit one characteristic write; returns true if the stack accepted it (GATT_SUCCESS). */
    private fun submitWrite(
        g: BluetoothGatt,
        ch: BluetoothGattCharacteristic,
        data: ByteArray,
        writeType: Int,
    ): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // API 33+ returns a BluetoothStatusCodes value, not a BluetoothGatt status.
            g.writeCharacteristic(ch, data, writeType) == android.bluetooth.BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                ch.value = data
                ch.writeType = writeType
                g.writeCharacteristic(ch)
            }
        }
    }

    override fun readCharacteristic(uuid: UUID): ByteArray? = synchronized(opLock) {
        val g = gatt ?: return null
        val ch = findCharacteristic(uuid) ?: run {
            Log.e(TAG, "read: characteristic not found $uuid"); return null
        }
        readLatch = CountDownLatch(1)
        expectedReadUuid = uuid
        readResult.set(null)
        if (!g.readCharacteristic(ch)) {
            Log.w(TAG, "readCharacteristic($uuid) submission failed")
            readLatch = null
            expectedReadUuid = null
            return null
        }
        readLatch?.await(OP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        readLatch = null
        expectedReadUuid = null
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
            expectedWriteUuid = uuid
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
            val ok: Boolean
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val rc = g.writeDescriptor(cccd, enable)
                ok = rc == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    cccd.value = enable
                    ok = g.writeDescriptor(cccd)
                }
            }
            if (!ok) {
                Log.w(TAG, "writeDescriptor(CCCD $uuid) submission failed")
                writeLatch = null
                expectedWriteUuid = null
                return
            }
            writeLatch?.await(OP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            writeLatch = null
            expectedWriteUuid = null
        }
    }

    override fun requestMtu(mtu: Int) {
        synchronized(opLock) {
            val g = gatt ?: return
            mtuLatch = CountDownLatch(1)
            if (!g.requestMtu(mtu)) {
                Log.w(TAG, "requestMtu($mtu) submission failed")
                mtuLatch = null
                return
            }
            mtuLatch?.await(OP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            mtuLatch = null
        }
    }

    override fun getMtu(): Int = mtu

    /**
     * Create a BLE bond and block until it reaches a terminal state.
     *
     * Driven by the protocol (FossilQAdapter) at the right time: post-auth (after
     * the user presses the TOP button) and on the already-authorized fast-path.
     * The bond ties the watch's auth state to the BLE link key — when the bond is
     * later removed (Android "Forget"), the watch clears its auth on next connect
     * (FINDINGS #7, #17).
     *
     * createBond() is asynchronous; we register a transient BOND_STATE_CHANGED
     * receiver and wait (≤BOND_TIMEOUT_MS) for BOND_BONDED (success) or BOND_NONE
     * (failure). "Just Works" pairing needs no PIN/passkey, so no agent is needed
     * on Android — the system handles confirmation.
     */
    override fun pair(): Boolean {
        val g = gatt ?: return false
        val device = g.device
        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            Log.d(TAG, "Already bonded to ${device.address}")
            return true
        }

        // We POLL device.bondState as the primary completion signal rather than
        // relying on the ACTION_BOND_STATE_CHANGED broadcast: in practice an
        // application-context receiver can miss the terminal BONDED transition
        // (it fired but our receiver wasn't delivered), which previously cost the
        // full BOND_TIMEOUT_MS of dead wait AFTER the bond had already succeeded.
        // Polling the authoritative system state is simple and reliable.
        val ok = try {
            if (!device.createBond()) {
                Log.w(TAG, "createBond() returned false")
                return false
            }
            Log.d(TAG, "createBond() initiated — polling bond state…")
            val deadline = System.currentTimeMillis() + BOND_TIMEOUT_MS
            var result = false
            var sawBonding = false
            while (System.currentTimeMillis() < deadline) {
                when (device.bondState) {
                    BluetoothDevice.BOND_BONDED -> { result = true; break }
                    BluetoothDevice.BOND_BONDING -> sawBonding = true
                    BluetoothDevice.BOND_NONE -> {
                        // Returning to NONE *after* we observed BONDING means the
                        // attempt failed/was cancelled — stop early. An initial NONE
                        // (before createBond transitions to BONDING) is ignored.
                        if (sawBonding) { result = false; break }
                    }
                }
                try { Thread.sleep(200L) } catch (ignored: InterruptedException) {}
            }
            result || device.bondState == BluetoothDevice.BOND_BONDED
        } catch (e: Exception) {
            Log.w(TAG, "createBond failed", e); false
        }
        Log.i(TAG, if (ok) "Bonded to ${device.address}" else "Bonding did not complete for ${device.address}")
        return ok
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

private fun ByteArray.toHex(): String =
    joinToString(" ") { "%02x".format(it) }
