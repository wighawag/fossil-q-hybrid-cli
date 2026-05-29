package qhybrid.android.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import qhybrid.protocol.log.LogRecord
import qhybrid.protocol.log.LogRingBuffer

/**
 * WP15 — verifies the SLF4J tee bridge maps app + `:protocol` log calls into a
 * [LogRingBuffer] at the correct level, with arguments formatted, on the JVM
 * (Robolectric provides android.util.Log so the slf4j-android delegate resolves).
 *
 * We drive the bridge **directly** (instantiating [BufferTeeServiceProvider] and using its
 * logger factory) rather than via the global SLF4J binding, because AGP's unit-test
 * classpath does not necessarily select our `META-INF/services` provider (slf4j-android's
 * own registration may win). On-device, the packaged APK includes our service file and our
 * app classes shadow the jar's, so [BufferTeeServiceProvider] is the live binding — and
 * logcat routing is preserved because each logger delegates to slf4j-android. This test
 * locks the tee/level/format logic that the on-device binding then uses.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class Slf4jBridgeTest {

    private lateinit var buffer: LogRingBuffer
    private lateinit var provider: BufferTeeServiceProvider

    @Before
    fun setUp() {
        buffer = LogRingBuffer.shared()
        buffer.clear()
        provider = BufferTeeServiceProvider().apply { initialize() }
    }

    @Test
    fun appAndProtocolLogs_appearInBufferAtRightLevel() {
        val factory = provider.loggerFactory
        // Each logger must be our tee wrapper (delegating to slf4j-android for logcat).
        val appLog = factory.getLogger("FossilQ-App")
        assertTrue("tee provider should return LogBufferLogger", appLog is LogBufferLogger)
        val protoLog = factory.getLogger("qhybrid.protocol.FossilController")

        appLog.info("Connected! Battery: 88%")
        protoLog.debug("write 3dda0002 NO_RESPONSE")
        protoLog.error("connect failed")

        val all = buffer.snapshot()
        assertEquals(3, all.size)

        val info = all.first { it.level() == LogRecord.Level.INFO }
        assertEquals("FossilQ-App", info.tag())
        assertEquals("Connected! Battery: 88%", info.message())

        val debug = all.first { it.level() == LogRecord.Level.DEBUG }
        assertEquals("qhybrid.protocol.FossilController", debug.tag())
        assertEquals("write 3dda0002 NO_RESPONSE", debug.message())

        // Level filter returns the expected subset: INFO + ERROR, DEBUG dropped.
        val infoAndAbove = buffer.filter(LogRecord.Level.INFO)
        assertEquals(2, infoAndAbove.size)
        assertTrue(infoAndAbove.none { it.level() == LogRecord.Level.DEBUG })
    }

    @Test
    fun parameterizedMessage_isFormatted() {
        val log = provider.loggerFactory.getLogger("FossilQ-App")
        log.info("Compiled {} calendar events into alarms {}-{}", 6, 16, 21)
        val rec = buffer.snapshot().last()
        assertEquals("Compiled 6 calendar events into alarms 16-21", rec.message())
    }

    @Test
    fun errorWithThrowable_capturesStackInMessage() {
        val log = provider.loggerFactory.getLogger("FossilQ-App")
        log.error("boom", IllegalStateException("nope"))
        val rec = buffer.snapshot().last()
        assertEquals(LogRecord.Level.ERROR, rec.level())
        assertTrue(rec.message().startsWith("boom"))
        assertTrue(rec.message().contains("IllegalStateException"))
    }
}
