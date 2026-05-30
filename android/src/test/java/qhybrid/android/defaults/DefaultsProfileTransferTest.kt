package qhybrid.android.defaults

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import qhybrid.android.buttons.ButtonActions
import qhybrid.android.buttons.ButtonModes
import qhybrid.android.buttons.ButtonSlots
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * WP-DEFAULTS (sub-part 5) — export→import is an in-memory IDENTITY round-trip; malformed / foreign
 * bytes are tolerated (→ factory). The Android FileProvider share-sheet + ACTION_OPEN_DOCUMENT
 * picker wiring is on-device; the pure stream codec is the provable seam.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DefaultsProfileTransferTest {

    @Test
    fun bytesRoundTrip_isIdentity_forFactory() {
        val bytes = DefaultsProfileTransfer.toBytes(DefaultsProfile.FACTORY)
        assertEquals(DefaultsProfile.FACTORY, DefaultsProfileTransfer.fromBytes(bytes))
    }

    @Test
    fun bytesRoundTrip_isIdentity_forRichProfile() {
        val profile = DefaultsProfile(
            alarms = listOf(DefaultAlarm(0, 7, 30, true, 0x3E, true, "Wake")),
            rules = listOf(DefaultRule("com.whatsapp", 2, 90, 180)),
            buttons = listOf(
                DefaultButton(ButtonSlots.TOP, ButtonModes.SINGLE_ACTION, listOf(ButtonActions.STOPWATCH)),
            ),
        )
        val bytes = DefaultsProfileTransfer.toBytes(profile)
        assertEquals(profile, DefaultsProfileTransfer.fromBytes(bytes))
    }

    @Test
    fun streamRoundTrip_isIdentity() {
        val profile = DefaultsProfile.FACTORY.copy(
            buttons = listOf(DefaultButton(ButtonSlots.BOTTOM, ButtonModes.SINGLE_ACTION, listOf(ButtonActions.DATE))),
        )
        val out = ByteArrayOutputStream()
        DefaultsProfileTransfer.writeTo(profile, out)
        val back = DefaultsProfileTransfer.readFrom(ByteArrayInputStream(out.toByteArray()))
        assertEquals(profile, back)
    }

    @Test
    fun malformedBytes_fallBackToFactory() {
        assertEquals(DefaultsProfile.FACTORY, DefaultsProfileTransfer.fromBytes("{not json".toByteArray()))
        assertEquals(DefaultsProfile.FACTORY, DefaultsProfileTransfer.fromBytes("garbage".toByteArray()))
        assertEquals(DefaultsProfile.FACTORY, DefaultsProfileTransfer.fromBytes(null))
    }

    @Test
    fun foreignJson_fallsBackToFactory() {
        assertEquals(
            DefaultsProfile.FACTORY,
            DefaultsProfileTransfer.fromBytes("""{"some":"other","app":1}""".toByteArray()),
        )
    }
}
