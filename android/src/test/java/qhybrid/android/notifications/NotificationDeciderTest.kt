package qhybrid.android.notifications

import org.junit.Assert.assertEquals
import org.junit.Test
import qhybrid.android.notifications.NotificationDecider.NotificationDecision
import qhybrid.android.notifications.NotificationDecider.PostedNotification

/**
 * WP11 — unit tests for the pure notification → watch-action decision. Covers the rule gate, every
 * skip filter (ongoing / group-summary / download-event), the official-app consecutive-duplicate
 * suppression, and the priority opt-in (a ruled app always buzzes regardless of priority).
 */
class NotificationDeciderTest {

    private val WHATSAPP = "com.whatsapp"
    private val GMAIL = "com.google.android.gm"
    private val rules = setOf(WHATSAPP, GMAIL)

    private fun decide(
        posted: PostedNotification,
        ruled: Set<String> = rules,
        previous: PostedNotification? = null,
    ) = NotificationDecider.decide(posted, ruled, previous)

    @Test
    fun ruledPackage_plays() {
        val d = decide(PostedNotification(id = 1, packageName = WHATSAPP, title = "Alice", text = "hi"))
        assertEquals(NotificationDecision.Play(WHATSAPP), d)
    }

    @Test
    fun unruledPackage_none() {
        val d = decide(PostedNotification(id = 1, packageName = "com.slack"))
        assertEquals(NotificationDecision.None("no-rule"), d)
    }

    @Test
    fun emptyRules_none() {
        val d = decide(PostedNotification(id = 1, packageName = WHATSAPP), ruled = emptySet())
        assertEquals(NotificationDecision.None("no-rule"), d)
    }

    @Test
    fun ongoing_skipped() {
        val d = decide(PostedNotification(id = 1, packageName = WHATSAPP, isOngoing = true))
        assertEquals(NotificationDecision.None("ongoing"), d)
    }

    @Test
    fun groupSummary_skipped() {
        val d = decide(PostedNotification(id = 1, packageName = WHATSAPP, isSummary = true))
        assertEquals(NotificationDecision.None("group-summary"), d)
    }

    @Test
    fun downloadEvent_skipped() {
        val d = decide(PostedNotification(id = 1, packageName = WHATSAPP, isDownloadEvent = true))
        assertEquals(NotificationDecision.None("download-event"), d)
    }

    @Test
    fun ruleGate_takesPrecedenceOverSkips() {
        // An unruled ongoing/summary/download notification still reports "no-rule" (the gate is
        // checked first — it short-circuits before any per-notification work).
        val d = decide(
            PostedNotification(id = 1, packageName = "com.slack", isOngoing = true, isSummary = true),
        )
        assertEquals(NotificationDecision.None("no-rule"), d)
    }

    @Test
    fun consecutiveDuplicate_suppressed() {
        val first = PostedNotification(id = 7, packageName = WHATSAPP, title = "Alice", text = "hi", whenTime = 1000L)
        val again = first.copy()
        assertEquals(NotificationDecision.None("duplicate"), decide(again, previous = first))
    }

    @Test
    fun differentText_notDuplicate() {
        val first = PostedNotification(id = 7, packageName = WHATSAPP, title = "Alice", text = "hi", whenTime = 1000L)
        val second = first.copy(text = "you there?")
        assertEquals(NotificationDecision.Play(WHATSAPP), decide(second, previous = first))
    }

    @Test
    fun differentWhenTime_notDuplicate() {
        val first = PostedNotification(id = 7, packageName = WHATSAPP, title = "Alice", text = "hi", whenTime = 1000L)
        val second = first.copy(whenTime = 2000L)
        assertEquals(NotificationDecision.Play(WHATSAPP), decide(second, previous = first))
    }

    @Test
    fun differentId_notDuplicate() {
        val first = PostedNotification(id = 7, packageName = WHATSAPP, title = "Alice", text = "hi", whenTime = 1000L)
        val second = first.copy(id = 8)
        assertEquals(NotificationDecision.Play(WHATSAPP), decide(second, previous = first))
    }

    @Test
    fun samePackageDifferentSender_notDuplicate() {
        // Two different people messaging on WhatsApp differ by title → both buzz.
        val first = PostedNotification(id = 7, packageName = WHATSAPP, title = "Alice", text = "hi", whenTime = 1000L)
        val second = first.copy(title = "Bob")
        assertEquals(NotificationDecision.Play(WHATSAPP), decide(second, previous = first))
    }

    @Test
    fun duplicateOfADifferentPackage_notSuppressed() {
        // The previous notification was from a different app — not a duplicate of this one.
        val prev = PostedNotification(id = 7, packageName = GMAIL, title = "X", text = "y", whenTime = 1000L)
        val now = PostedNotification(id = 7, packageName = WHATSAPP, title = "X", text = "y", whenTime = 1000L)
        assertEquals(NotificationDecision.Play(WHATSAPP), decide(now, previous = prev))
    }

    @Test
    fun nullPrevious_notDuplicate() {
        val d = decide(PostedNotification(id = 1, packageName = WHATSAPP, title = "Alice", text = "hi"), previous = null)
        assertEquals(NotificationDecision.Play(WHATSAPP), d)
    }

    @Test
    fun ruledApp_buzzesRegardlessOfPriority() {
        // Priority is intentionally not modelled/filtered — a configured rule is an explicit opt-in.
        // (No priority field on PostedNotification; this documents the decision.)
        val d = decide(PostedNotification(id = 1, packageName = GMAIL, title = "low-prio", text = "x"))
        assertEquals(NotificationDecision.Play(GMAIL), d)
    }
}
