package qhybrid.android

import android.app.Application

/**
 * WP15 — pins the SLF4J binding to our tee provider BEFORE any logger is created.
 *
 * The packaged APK contains TWO `org.slf4j.spi.SLF4JServiceProvider` service entries
 * (ours — [qhybrid.android.log.BufferTeeServiceProvider] — and slf4j-android's, which we
 * delegate to). SLF4J 2.x would otherwise pick one non-deterministically and warn about
 * "multiple bindings". Setting `slf4j.provider` in a static initializer (which runs when
 * the Application class is loaded, before `LoggerFactory.getLogger` is ever called) forces
 * OUR provider, so every logger tees into the in-app [qhybrid.protocol.log.LogRingBuffer]
 * while still forwarding to slf4j-android → logcat.
 */
class FossilQApplication : Application() {
    companion object {
        init {
            // Must be set before the first SLF4J LoggerFactory access.
            System.setProperty("slf4j.provider", "qhybrid.android.log.BufferTeeServiceProvider")
        }
    }
}
