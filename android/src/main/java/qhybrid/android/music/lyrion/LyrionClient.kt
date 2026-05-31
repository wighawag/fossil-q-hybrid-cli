package qhybrid.android.music.lyrion

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * L3 \u2014 narrow, injectable transport seam for the Lyrion (LMS) JSON-RPC endpoint. One method: POST a
 * pre-built body (from [LyrionCommands.request]) to `http://host:port/jsonrpc.js` and return the raw
 * response body, or `null` on any failure (unreachable / timeout / non-200 / IO error). It never
 * throws \u2014 a music gesture must degrade to a quiet no-op, never crash the BLE service.
 *
 * The production impl ([HttpLyrionClient]) uses [HttpURLConnection] (no new dependency). Tests inject
 * a fake so the dispatcher's command sequencing (L4) is verified without a live server.
 */
interface LyrionClient {
    /**
     * POST [body] to the LMS JSON-RPC endpoint at [host]:[port]. Returns the response body string, or
     * `null` on failure. Blocking \u2014 call from a worker thread.
     */
    fun post(host: String, port: Int, body: String): String?
}

/**
 * Production [LyrionClient] over [HttpURLConnection]. Short connect/read timeouts so a slow/absent
 * server can't wedge the dispatcher's worker thread. A blank host short-circuits to `null` (no server
 * configured). Never throws.
 */
class HttpLyrionClient(
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
) : LyrionClient {

    override fun post(host: String, port: Int, body: String): String? {
        if (host.isBlank()) return null
        var conn: HttpURLConnection? = null
        return try {
            val url = URL("http", host, port, ENDPOINT)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            conn.outputStream.use { os: OutputStream ->
                os.write(body.toByteArray(StandardCharsets.UTF_8))
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.d(TAG, "LMS POST $host:$port returned HTTP $code")
                return null
            }
            BufferedReader(InputStreamReader(conn.inputStream, StandardCharsets.UTF_8)).use { it.readText() }
        } catch (e: Exception) {
            Log.d(TAG, "LMS POST $host:$port failed: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    private companion object {
        const val TAG = "FossilQ-Lyrion"
        const val ENDPOINT = "/jsonrpc.js"
        const val DEFAULT_CONNECT_TIMEOUT_MS = 3_000
        const val DEFAULT_READ_TIMEOUT_MS = 5_000
    }
}

/** A no-op [LyrionClient] (always returns null) \u2014 a safe default before a server is configured. */
object NoopLyrionClient : LyrionClient {
    override fun post(host: String, port: Int, body: String): String? = null
}
