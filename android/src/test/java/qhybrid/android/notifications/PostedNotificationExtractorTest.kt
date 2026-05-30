package qhybrid.android.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WP11 — unit tests for the pure raw-fields → [NotificationDecider.PostedNotification] mapping
 * (mirrors the official app's NotificationStatus constructor: flag decoding + text trim).
 */
class PostedNotificationExtractorTest {

    private fun extract(
        id: Int = 1,
        pkg: String = "com.whatsapp",
        title: CharSequence? = "Alice",
        bigText: CharSequence? = null,
        text: CharSequence? = "hi",
        whenTime: Long = 1000L,
        flags: Int = 0,
        progressMax: Int = 0,
    ) = PostedNotificationExtractor.extract(id, pkg, title, bigText, text, whenTime, flags, progressMax)

    @Test
    fun mapsBasicFields() {
        val p = extract()
        assertEquals(1, p.id)
        assertEquals("com.whatsapp", p.packageName)
        assertEquals("Alice", p.title)
        assertEquals("hi", p.text)
        assertEquals(1000L, p.whenTime)
        assertFalse(p.isOngoing)
        assertFalse(p.isSummary)
        assertFalse(p.isDownloadEvent)
    }

    @Test
    fun prefersBigTextWhenPresent() {
        val p = extract(text = "short", bigText = "the full long body")
        assertEquals("the full long body", p.text)
    }

    @Test
    fun fallsBackToTextWhenBigTextBlank() {
        val p = extract(text = "short", bigText = "   ")
        assertEquals("short", p.text)
    }

    @Test
    fun nullTextFieldsBecomeEmpty() {
        val p = extract(title = null, bigText = null, text = null)
        assertEquals("", p.title)
        assertEquals("", p.text)
    }

    @Test
    fun trimsControlCharsAndWhitespace() {
        val p = extract(title = "  Alice\u0000 ", text = "\u0007hi there\n")
        assertEquals("Alice", p.title)
        assertEquals("hi there", p.text)
    }

    @Test
    fun decodesOngoingFlag() {
        val p = extract(flags = PostedNotificationExtractor.FLAG_ONGOING_EVENT)
        assertTrue(p.isOngoing)
        assertFalse(p.isSummary)
    }

    @Test
    fun decodesGroupSummaryFlag() {
        val p = extract(flags = PostedNotificationExtractor.FLAG_GROUP_SUMMARY)
        assertTrue(p.isSummary)
        assertFalse(p.isOngoing)
    }

    @Test
    fun decodesBothFlagsTogether() {
        val both = PostedNotificationExtractor.FLAG_ONGOING_EVENT or PostedNotificationExtractor.FLAG_GROUP_SUMMARY
        val p = extract(flags = both)
        assertTrue(p.isOngoing)
        assertTrue(p.isSummary)
    }

    @Test
    fun progressMaxMarksDownloadEvent() {
        assertTrue(extract(progressMax = 100).isDownloadEvent)
        assertFalse(extract(progressMax = 0).isDownloadEvent)
    }
}
