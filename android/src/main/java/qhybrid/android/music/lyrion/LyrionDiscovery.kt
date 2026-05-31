package qhybrid.android.music.lyrion

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import qhybrid.android.music.lyrion.LyrionCommands.LyrionFavorite
import qhybrid.android.music.lyrion.LyrionCommands.LyrionPlayer
import qhybrid.android.music.lyrion.LyrionDiscoveryCodec.DiscoveredServer

/**
 * Discovery/config seam for the Settings Lyrion pickers: list the players + favourites on a known
 * server (over HTTP via [LyrionClient]), and discover servers on the LAN (L7, UDP broadcast on port
 * 3483). Injectable so the ViewModel is testable with a fake; all methods are blocking and must be
 * called off the main thread. Never throw \u2192 empty list on failure.
 */
interface LyrionDiscovery {
    /** Players known to the server at [host]:[port] (HTTP `players` query). */
    fun players(host: String, port: Int): List<LyrionPlayer>

    /** Favourites on the server at [host]:[port] (HTTP `favorites items` query). */
    fun favorites(host: String, port: Int): List<LyrionFavorite>

    /** L7 \u2014 servers found on the LAN via UDP broadcast (best-effort, within [timeoutMs]). */
    fun discoverServers(timeoutMs: Int = DEFAULT_DISCOVERY_TIMEOUT_MS): List<DiscoveredServer>

    companion object {
        const val DEFAULT_DISCOVERY_TIMEOUT_MS = 1_500
    }
}

/**
 * Production [LyrionDiscovery]: players/favourites via the injected [client] + [LyrionCommands]
 * parsers; server discovery via a broadcast [DatagramSocket] using [LyrionDiscoveryCodec]. Never
 * throws.
 */
class SystemLyrionDiscovery(
    private val client: LyrionClient = HttpLyrionClient(),
    // Optional: held for a multicast/broadcast lock during UDP discovery (some devices need it).
    private val context: Context? = null,
) : LyrionDiscovery {

    override fun players(host: String, port: Int): List<LyrionPlayer> {
        if (host.isBlank()) return emptyList()
        val resp = client.post(host, port, LyrionCommands.request(LyrionCommands.GLOBAL_PLAYER, LyrionCommands.playersQuery()))
        return LyrionCommands.parsePlayers(resp)
    }

    override fun favorites(host: String, port: Int): List<LyrionFavorite> {
        if (host.isBlank()) return emptyList()
        val resp = client.post(host, port, LyrionCommands.request(LyrionCommands.GLOBAL_PLAYER, LyrionCommands.favoritesQuery()))
        return LyrionCommands.parseFavorites(resp)
    }

    override fun discoverServers(timeoutMs: Int): List<DiscoveredServer> {
        val found = LinkedHashMap<String, DiscoveredServer>() // de-dup by name+port
        var socket: DatagramSocket? = null
        // Acquire a multicast lock so broadcast replies are delivered on Wi-Fi (best-effort).
        val wifi = context?.applicationContext?.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = runCatching { wifi?.createMulticastLock("fossilq-lyrion-discovery") }.getOrNull()
        runCatching { lock?.acquire() }
        return try {
            socket = DatagramSocket().apply {
                broadcast = true
                soTimeout = timeoutMs
            }
            val req = LyrionDiscoveryCodec.buildRequest()
            val dest = InetAddress.getByName("255.255.255.255")
            socket.send(DatagramPacket(req, req.size, dest, LyrionDiscoveryCodec.DISCOVERY_PORT))

            val deadline = System.currentTimeMillis() + timeoutMs
            val buf = ByteArray(512)
            while (System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(packet)
                } catch (_: java.net.SocketTimeoutException) {
                    break
                }
                val server = LyrionDiscoveryCodec.parseResponse(packet.data, packet.length) ?: continue
                // Pair the parsed name/port with the responder's IP (the actual host to connect to).
                val host = packet.address?.hostAddress ?: continue
                found["$host:${server.jsonPort}"] = server.copy(
                    name = server.name.ifBlank { host },
                    host = host,
                )
            }
            found.values.toList()
        } catch (e: Exception) {
            Log.d(TAG, "UDP discovery failed: ${e.message}")
            emptyList()
        } finally {
            socket?.close()
            runCatching { if (lock?.isHeld == true) lock.release() }
        }
    }

    private companion object {
        const val TAG = "FossilQ-Lyrion"
    }
}

/** A no-op [LyrionDiscovery] (empty everything) \u2014 a safe default for previews/tests. */
object NoopLyrionDiscovery : LyrionDiscovery {
    override fun players(host: String, port: Int) = emptyList<LyrionPlayer>()
    override fun favorites(host: String, port: Int) = emptyList<LyrionFavorite>()
    override fun discoverServers(timeoutMs: Int) = emptyList<DiscoveredServer>()
}
