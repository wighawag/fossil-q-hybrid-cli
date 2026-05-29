package qhybrid.android.log

import org.slf4j.Logger
import org.slf4j.Marker
import org.slf4j.helpers.MessageFormatter
import qhybrid.protocol.log.LogRecord
import qhybrid.protocol.log.LogRingBuffer

/**
 * WP15 — a tee'ing SLF4J [Logger]: every call is forwarded UNCHANGED to a [delegate]
 * (slf4j-android → logcat) and, when the delegate has that level enabled, also captured
 * into the in-app [LogRingBuffer].
 *
 * This is the bridge that makes the app's + `:protocol`'s SLF4J logs appear in the in-app
 * console **without replacing logcat routing** (we delegate to slf4j-android for the real
 * Android log, then additionally append to the ring buffer).
 *
 * The ring-buffer capture is INTENTIONALLY NOT gated by the delegate's per-level enablement:
 * the in-app console is a debug surface that should always show DEBUG (raw hex / GATT / DB)
 * even when logcat is configured to suppress it. The delegate still independently decides
 * whether the line reaches logcat. Message arguments are substituted with SLF4J's own
 * [MessageFormatter] so the captured text matches what logcat would show.
 */
internal class LogBufferLogger(
    private val delegate: Logger,
    private val buffer: LogRingBuffer,
    private val tag: String,
) : Logger by delegate {

    private fun capture(level: LogRecord.Level, msg: String?, t: Throwable? = null) {
        val text = if (t != null && msg != null) "$msg\n${stack(t)}"
        else if (t != null) stack(t)
        else msg ?: ""
        buffer.add(LogRecord(System.currentTimeMillis(), level, tag, text))
    }

    private fun stack(t: Throwable): String {
        val sb = StringBuilder()
        sb.append(t.javaClass.name)
        t.message?.let { sb.append(": ").append(it) }
        for (el in t.stackTrace) {
            sb.append("\n\tat ").append(el)
        }
        return sb.toString()
    }

    private fun fmt(format: String?, vararg args: Any?): String =
        MessageFormatter.arrayFormat(format, args).message

    // ---- TRACE ---------------------------------------------------------------
    override fun trace(msg: String?) {
        delegate.trace(msg); capture(LogRecord.Level.TRACE, msg)
    }
    override fun trace(format: String?, arg: Any?) {
        delegate.trace(format, arg); capture(LogRecord.Level.TRACE, fmt(format, arg))
    }
    override fun trace(format: String?, arg1: Any?, arg2: Any?) {
        delegate.trace(format, arg1, arg2); capture(LogRecord.Level.TRACE, fmt(format, arg1, arg2))
    }
    override fun trace(format: String?, vararg arguments: Any?) {
        delegate.trace(format, *arguments); capture(LogRecord.Level.TRACE, fmt(format, *arguments))
    }
    override fun trace(msg: String?, t: Throwable?) {
        delegate.trace(msg, t); capture(LogRecord.Level.TRACE, msg, t)
    }

    // ---- DEBUG ---------------------------------------------------------------
    override fun debug(msg: String?) {
        delegate.debug(msg); capture(LogRecord.Level.DEBUG, msg)
    }
    override fun debug(format: String?, arg: Any?) {
        delegate.debug(format, arg); capture(LogRecord.Level.DEBUG, fmt(format, arg))
    }
    override fun debug(format: String?, arg1: Any?, arg2: Any?) {
        delegate.debug(format, arg1, arg2); capture(LogRecord.Level.DEBUG, fmt(format, arg1, arg2))
    }
    override fun debug(format: String?, vararg arguments: Any?) {
        delegate.debug(format, *arguments); capture(LogRecord.Level.DEBUG, fmt(format, *arguments))
    }
    override fun debug(msg: String?, t: Throwable?) {
        delegate.debug(msg, t); capture(LogRecord.Level.DEBUG, msg, t)
    }

    // ---- INFO ----------------------------------------------------------------
    override fun info(msg: String?) {
        delegate.info(msg); capture(LogRecord.Level.INFO, msg)
    }
    override fun info(format: String?, arg: Any?) {
        delegate.info(format, arg); capture(LogRecord.Level.INFO, fmt(format, arg))
    }
    override fun info(format: String?, arg1: Any?, arg2: Any?) {
        delegate.info(format, arg1, arg2); capture(LogRecord.Level.INFO, fmt(format, arg1, arg2))
    }
    override fun info(format: String?, vararg arguments: Any?) {
        delegate.info(format, *arguments); capture(LogRecord.Level.INFO, fmt(format, *arguments))
    }
    override fun info(msg: String?, t: Throwable?) {
        delegate.info(msg, t); capture(LogRecord.Level.INFO, msg, t)
    }

    // ---- WARN ----------------------------------------------------------------
    override fun warn(msg: String?) {
        delegate.warn(msg); capture(LogRecord.Level.WARN, msg)
    }
    override fun warn(format: String?, arg: Any?) {
        delegate.warn(format, arg); capture(LogRecord.Level.WARN, fmt(format, arg))
    }
    override fun warn(format: String?, vararg arguments: Any?) {
        delegate.warn(format, *arguments); capture(LogRecord.Level.WARN, fmt(format, *arguments))
    }
    override fun warn(format: String?, arg1: Any?, arg2: Any?) {
        delegate.warn(format, arg1, arg2); capture(LogRecord.Level.WARN, fmt(format, arg1, arg2))
    }
    override fun warn(msg: String?, t: Throwable?) {
        delegate.warn(msg, t); capture(LogRecord.Level.WARN, msg, t)
    }

    // ---- ERROR ---------------------------------------------------------------
    override fun error(msg: String?) {
        delegate.error(msg); capture(LogRecord.Level.ERROR, msg)
    }
    override fun error(format: String?, arg: Any?) {
        delegate.error(format, arg); capture(LogRecord.Level.ERROR, fmt(format, arg))
    }
    override fun error(format: String?, arg1: Any?, arg2: Any?) {
        delegate.error(format, arg1, arg2); capture(LogRecord.Level.ERROR, fmt(format, arg1, arg2))
    }
    override fun error(format: String?, vararg arguments: Any?) {
        delegate.error(format, *arguments); capture(LogRecord.Level.ERROR, fmt(format, *arguments))
    }
    override fun error(msg: String?, t: Throwable?) {
        delegate.error(msg, t); capture(LogRecord.Level.ERROR, msg, t)
    }

    // Note: Marker-tagged overloads are inherited unchanged from `delegate` via Kotlin
    // delegation (`Logger by delegate`); the protocol/app use the marker-less forms, which
    // are the ones overridden above for buffer capture.
}
