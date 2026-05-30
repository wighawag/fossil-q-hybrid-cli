package qhybrid.android.defaults

import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
 * WP-DEFAULTS (sub-part 5) — the **self-contained** export/import codec for the defaults profile
 * (WP-MULTIWATCH was skipped, so there is no shared per-watch codec to reuse). The serialization is
 * the same shape as a per-watch export MINUS `watchMac` — and [DefaultsProfileJson] is the SINGLE
 * source of truth for that shape (reused here).
 *
 * This object is the **pure, stream-level** part (no Android types) so export→import is an identity
 * round-trip that is unit-testable in-memory; the Android FileProvider share-sheet +
 * `ACTION_OPEN_DOCUMENT` picker wiring lives in the Compose screen and calls these.
 *
 * **Tolerant on import:** malformed / foreign bytes fall back to [DefaultsProfile.FACTORY] (via the
 * codec), never crash.
 */
object DefaultsProfileTransfer {

    /** UTF-8 bytes of the encoded profile (what export writes to a file). */
    fun toBytes(profile: DefaultsProfile): ByteArray =
        DefaultsProfileJson.encode(profile).toByteArray(StandardCharsets.UTF_8)

    /** Decode bytes back into a profile (tolerant: garbage/foreign → factory). */
    fun fromBytes(bytes: ByteArray?): DefaultsProfile {
        if (bytes == null) return DefaultsProfile.FACTORY
        return DefaultsProfileJson.decode(String(bytes, StandardCharsets.UTF_8))
    }

    /** Write [profile] to [out] as UTF-8 JSON. Caller owns/closes the stream. */
    fun writeTo(profile: DefaultsProfile, out: OutputStream) {
        out.write(toBytes(profile))
        out.flush()
    }

    /** Read a profile from [input] (tolerant: garbage/foreign → factory). Caller owns the stream. */
    fun readFrom(input: InputStream): DefaultsProfile =
        fromBytes(input.readBytes())

    /** Suggested export filename (timestamp-free so it's deterministic for the picker title). */
    const val EXPORT_FILENAME = "fossilq-defaults.json"

    /** MIME type for the share-sheet + document picker. */
    const val MIME_TYPE = "application/json"
}
