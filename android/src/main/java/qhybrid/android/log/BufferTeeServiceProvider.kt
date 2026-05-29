package qhybrid.android.log

import org.slf4j.ILoggerFactory
import org.slf4j.IMarkerFactory
import org.slf4j.Logger
import org.slf4j.spi.MDCAdapter
import org.slf4j.spi.SLF4JServiceProvider
import qhybrid.protocol.log.LogRingBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * WP15 — the single SLF4J binding for `:android`. SLF4J only loads ONE
 * [SLF4JServiceProvider]; this one wraps the slf4j-android provider so that:
 *
 *  1. logcat routing is **preserved** — every logger delegates to slf4j-android, AND
 *  2. each log call is also tee'd into [LogRingBuffer.shared] for the in-app console.
 *
 * Registered via `META-INF/services/org.slf4j.spi.SLF4JServiceProvider`, which **shadows**
 * the one inside the slf4j-android jar (our app classes win on the classpath). We do not
 * remove slf4j-android — we instantiate its provider internally and forward to it, so the
 * ring buffer "sits alongside" logcat rather than replacing it (per the WP15 brief).
 */
class BufferTeeServiceProvider : SLF4JServiceProvider {

    // The real slf4j-android provider — instantiated reflectively so this class still
    // compiles/loads even if the dependency name ever changes (graceful no-logcat fallback).
    private val androidProvider: SLF4JServiceProvider? = runCatching {
        Class.forName("uk.uuid.slf4j.android.ServiceProvider")
            .getDeclaredConstructor()
            .newInstance() as SLF4JServiceProvider
    }.getOrNull()

    private lateinit var teeFactory: ILoggerFactory

    override fun getLoggerFactory(): ILoggerFactory = teeFactory
    override fun getMarkerFactory(): IMarkerFactory =
        androidProvider?.markerFactory ?: org.slf4j.helpers.BasicMarkerFactory()

    override fun getMDCAdapter(): MDCAdapter =
        androidProvider?.mdcAdapter ?: org.slf4j.helpers.NOPMDCAdapter()

    override fun getRequestedApiVersion(): String = "2.0.99"

    override fun initialize() {
        androidProvider?.initialize()
        val buffer = LogRingBuffer.shared()
        val delegateFactory = androidProvider?.loggerFactory
        val cache = ConcurrentHashMap<String, Logger>()
        teeFactory = ILoggerFactory { name ->
            cache.getOrPut(name) {
                val tag = name ?: ""
                val delegate = delegateFactory?.getLogger(name)
                if (delegate != null) {
                    LogBufferLogger(delegate, buffer, tag)
                } else {
                    // No logcat backend available: still capture into the buffer so the
                    // in-app console works (degrades gracefully, never crashes logging).
                    BufferOnlyLogger(buffer, tag)
                }
            }
        }
    }
}
