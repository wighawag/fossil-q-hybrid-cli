package qhybrid.android.music.lyrion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.charset.StandardCharsets

/**
 * L7 \u2014 unit tests for the PURE UDP discovery codec ([LyrionDiscoveryCodec]). No sockets; just byte
 * arrays. Pure JVM (no Android deps).
 */
class LyrionDiscoveryCodecTest {

    @Test
    fun buildRequest_startsWithEAndNulTerminatedTags() {
        val req = LyrionDiscoveryCodec.buildRequest(listOf("NAME", "JSON"))
        assertEquals('e'.code.toByte(), req[0])
        // 'e' + "NAME" + 0 + "JSON" + 0
        val expected = byteArrayOf(
            'e'.code.toByte(),
            'N'.code.toByte(), 'A'.code.toByte(), 'M'.code.toByte(), 'E'.code.toByte(), 0,
            'J'.code.toByte(), 'S'.code.toByte(), 'O'.code.toByte(), 'N'.code.toByte(), 0,
        )
        assertEquals(expected.toList(), req.toList())
    }

    /** Build a valid 'E' response with the given TLV entries. */
    private fun response(vararg tlv: Pair<String, String>): ByteArray {
        val out = ArrayList<Byte>()
        out.add('E'.code.toByte())
        for ((tag, value) in tlv) {
            for (c in tag) out.add(c.code.toByte())
            val bytes = value.toByteArray(StandardCharsets.US_ASCII)
            out.add(bytes.size.toByte())
            for (b in bytes) out.add(b)
        }
        return out.toByteArray()
    }

    @Test
    fun parseResponse_extractsNameAndJsonPort() {
        val data = response("NAME" to "Living Room", "JSON" to "9000")
        val server = LyrionDiscoveryCodec.parseResponse(data, data.size)!!
        assertEquals("Living Room", server.name)
        assertEquals(9000, server.jsonPort)
    }

    @Test
    fun parseResponse_jsonPortDefaultsWhenAbsentOrBad() {
        val noJson = response("NAME" to "Box")
        assertEquals(9000, LyrionDiscoveryCodec.parseResponse(noJson, noJson.size)!!.jsonPort)
        val badJson = response("NAME" to "Box", "JSON" to "xyz")
        assertEquals(9000, LyrionDiscoveryCodec.parseResponse(badJson, badJson.size)!!.jsonPort)
        // Custom port honoured.
        val custom = response("JSON" to "9005")
        assertEquals(9005, LyrionDiscoveryCodec.parseResponse(custom, custom.size)!!.jsonPort)
    }

    @Test
    fun parseResponse_rejectsNonResponseAndGarbage() {
        assertNull(LyrionDiscoveryCodec.parseResponse(null))
        assertNull(LyrionDiscoveryCodec.parseResponse(ByteArray(0), 0))
        // A request ('e') is not a response ('E').
        assertNull(LyrionDiscoveryCodec.parseResponse(byteArrayOf('e'.code.toByte()), 1))
    }

    @Test
    fun parseResponse_truncatedValueStopsCleanly() {
        // Claims a 10-byte value but only 3 bytes follow — must not throw, returns what it parsed.
        val data = byteArrayOf(
            'E'.code.toByte(),
            'N'.code.toByte(), 'A'.code.toByte(), 'M'.code.toByte(), 'E'.code.toByte(),
            10, // length 10 but only 3 bytes of value present
            'a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte(),
        )
        val server = LyrionDiscoveryCodec.parseResponse(data, data.size)!!
        // Name not set (entry truncated), port defaults.
        assertEquals("", server.name)
        assertEquals(9000, server.jsonPort)
    }
}
