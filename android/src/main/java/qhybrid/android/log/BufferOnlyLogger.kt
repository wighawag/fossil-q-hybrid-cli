package qhybrid.android.log

import org.slf4j.Marker
import org.slf4j.event.Level
import org.slf4j.helpers.LegacyAbstractLogger
import org.slf4j.helpers.MessageFormatter
import qhybrid.protocol.log.LogRecord
import qhybrid.protocol.log.LogRingBuffer

/**
 * WP15 — fallback SLF4J logger used only if slf4j-android could not be loaded. Captures
 * into the in-app [LogRingBuffer] (no logcat). All severities enabled so nothing is lost
 * from the in-app console. Uses [LegacyAbstractLogger] so SLF4J's own message formatting
 * funnels every overload into a single [handleNormalizedLoggingCall].
 */
internal class BufferOnlyLogger(
    private val buffer: LogRingBuffer,
    private val tag: String,
) : LegacyAbstractLogger() {

    init {
        this.name = tag
    }

    override fun getFullyQualifiedCallerName(): String? = null

    override fun isTraceEnabled(): Boolean = true
    override fun isDebugEnabled(): Boolean = true
    override fun isInfoEnabled(): Boolean = true
    override fun isWarnEnabled(): Boolean = true
    override fun isErrorEnabled(): Boolean = true

    override fun handleNormalizedLoggingCall(
        level: Level?,
        marker: Marker?,
        messagePattern: String?,
        arguments: Array<out Any?>?,
        throwable: Throwable?,
    ) {
        val msg = MessageFormatter.basicArrayFormat(messagePattern, arguments?.toList()?.toTypedArray())
        val text = if (throwable != null) "$msg\n${throwable.stackTraceToString()}" else (msg ?: "")
        buffer.add(LogRecord(System.currentTimeMillis(), map(level), tag, text))
    }

    private fun map(level: Level?): LogRecord.Level = when (level) {
        Level.TRACE -> LogRecord.Level.TRACE
        Level.DEBUG -> LogRecord.Level.DEBUG
        Level.INFO -> LogRecord.Level.INFO
        Level.WARN -> LogRecord.Level.WARN
        Level.ERROR -> LogRecord.Level.ERROR
        null -> LogRecord.Level.INFO
    }
}
