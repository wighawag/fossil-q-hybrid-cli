package qhybrid.android.music.lyrion

import java.nio.charset.StandardCharsets

/**
 * L7 \u2014 the **pure**, unit-testable codec for LMS UDP server discovery (port 3483). No sockets here;
 * just build the request datagram and parse a response datagram. The actual UDP send/receive lives in
 * the [LyrionDiscovery] seam so this can be tested with plain byte arrays.
 *
 * **Protocol (the LMS \"TLV\" discovery).** A client broadcasts a datagram beginning with `'e'`
 * (0x65) followed by a sequence of NUL-terminated 4-char request tags (e.g. `NAME`, `JSON`, `IPAD`,
 * `UUID`). A server replies with a datagram beginning with `'E'` (0x45) followed by TLV entries:
 * a 4-char tag, a 1-byte length, then that many bytes of value. We mainly want `NAME` (server name)
 * and `JSON` (the HTTP/JSON-RPC port, default 9000).
 */
object LyrionDiscoveryCodec {

    const val DISCOVERY_PORT = 3483

    private const val REQUEST_PREFIX = 'e'.code.toByte() // 0x65
    private const val RESPONSE_PREFIX = 'E'.code.toByte() // 0x45

    /** The tags we ask for (and parse): server name + JSON port. */
    val REQUEST_TAGS = listOf("NAME", "JSON", "IPAD", "UUID")

    /**
     * A server found via UDP discovery: its name, the host/IP to connect to (filled by the socket
     * layer from the responder address — the codec leaves it blank), and the JSON-RPC port.
     */
    data class DiscoveredServer(val name: String, val jsonPort: Int, val host: String = "")

    /** Build the broadcast request datagram: `'e'` + each tag + a NUL terminator. */
    fun buildRequest(tags: List<String> = REQUEST_TAGS): ByteArray {
        val out = ArrayList<Byte>()
        out.add(REQUEST_PREFIX)
        for (tag in tags) {
            for (c in tag) out.add(c.code.toByte())
            out.add(0)
        }
        return out.toByteArray()
    }

    /**
     * Parse a response datagram (of [length] valid bytes) into a [DiscoveredServer], or null if it is
     * not a valid `'E'` response or carries no usable data. The JSON port defaults to 9000 when the
     * `JSON` TLV is absent or unparsable; the name defaults to empty. Never throws.
     */
    fun parseResponse(data: ByteArray?, length: Int = data?.size ?: 0): DiscoveredServer? {
        if (data == null || length < 1 || data[0] != RESPONSE_PREFIX) return null
        var name = ""
        var jsonPort = SettingsVocabularyDefaults.LYRION_PORT_DEFAULT
        var i = 1
        while (i + 5 <= length) {
            val tag = String(data, i, 4, StandardCharsets.US_ASCII)
            val len = data[i + 4].toInt() and 0xFF
            val valueStart = i + 5
            if (valueStart + len > length) break
            val value = String(data, valueStart, len, StandardCharsets.US_ASCII)
            when (tag) {
                "NAME" -> name = value
                "JSON" -> value.trim().toIntOrNull()?.let { jsonPort = it }
            }
            i = valueStart + len
        }
        return DiscoveredServer(name = name, jsonPort = jsonPort)
    }

    /**
     * Default port constant duplicated here so this codec stays dependency-free of the Android-only
     * SettingsVocabulary (which it does not need); kept in sync with
     * [qhybrid.android.settings.SettingsVocabulary.LYRION_PORT_DEFAULT].
     */
    private object SettingsVocabularyDefaults {
        const val LYRION_PORT_DEFAULT = 9000
    }
}
