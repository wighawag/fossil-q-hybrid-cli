package qhybrid.android.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import qhybrid.android.notifications.NotificationDecider.NotificationDecision
import qhybrid.android.notifications.NotificationDecider.PostedNotification

/**
 * WP11 — unit tests for the dispatch glue: it reads the cached rule package-set, runs the pure
 * decider, forwards a Play to the seam, and tracks the previous notification for dedupe. Verified
 * with a fake play seam (no service, no BLE).
 */
class NotificationDispatcherTest {

    private val WHATSAPP = "com.whatsapp"

    private class FakePlay : NotificationPlay {
        val played = mutableListOf<String>()
        override fun play(packageName: String): Boolean {
            played += packageName
            return true
        }
    }

    private fun post(id: Int = 1, pkg: String = WHATSAPP, title: String = "Alice", text: String = "hi", whenTime: Long = 1000L) =
        PostedNotification(id = id, packageName = pkg, title = title, text = text, whenTime = whenTime)

    @Test
    fun matchedNotification_playsAndForwardsPackage() {
        val play = FakePlay()
        val d = NotificationDispatcher(rules = { setOf(WHATSAPP) }, play = play)
        val decision = d.onPosted(post())
        assertEquals(NotificationDecision.Play(WHATSAPP), decision)
        assertEquals(listOf(WHATSAPP), play.played)
    }

    @Test
    fun unruledNotification_doesNotPlay() {
        val play = FakePlay()
        val d = NotificationDispatcher(rules = { setOf(WHATSAPP) }, play = play)
        val decision = d.onPosted(post(pkg = "com.slack"))
        assertTrue(decision is NotificationDecision.None)
        assertTrue(play.played.isEmpty())
    }

    @Test
    fun emptyRuleCache_neverPlays() {
        val play = FakePlay()
        val d = NotificationDispatcher(rules = { emptySet() }, play = play)
        d.onPosted(post())
        assertTrue(play.played.isEmpty())
    }

    @Test
    fun consecutiveDuplicate_playsOnceThenSuppressed() {
        val play = FakePlay()
        val d = NotificationDispatcher(rules = { setOf(WHATSAPP) }, play = play)
        d.onPosted(post())            // plays
        d.onPosted(post())            // identical -> suppressed
        assertEquals(listOf(WHATSAPP), play.played)
    }

    @Test
    fun changedText_playsAgain() {
        val play = FakePlay()
        val d = NotificationDispatcher(rules = { setOf(WHATSAPP) }, play = play)
        d.onPosted(post(text = "hi"))
        d.onPosted(post(text = "you there?"))
        assertEquals(listOf(WHATSAPP, WHATSAPP), play.played)
    }

    @Test
    fun previousTracksEveryPost_evenUnmatched() {
        // An unmatched post in between must still become "previous" so the dedupe key is the true
        // last post (a re-post of the FIRST whatsapp is NOT a duplicate of the slack post between).
        val play = FakePlay()
        val d = NotificationDispatcher(rules = { setOf(WHATSAPP) }, play = play)
        d.onPosted(post(text = "hi"))                 // plays (1)
        d.onPosted(post(pkg = "com.slack"))           // unmatched, becomes previous
        d.onPosted(post(text = "hi"))                 // not a dup of slack -> plays (2)
        assertEquals(listOf(WHATSAPP, WHATSAPP), play.played)
    }

    @Test
    fun liveRuleCacheUpdate_isReflected() {
        // The rules lambda is read on each post, so a rule added later starts matching.
        val play = FakePlay()
        var current = emptySet<String>()
        val d = NotificationDispatcher(rules = { current }, play = play)
        d.onPosted(post())                  // no rules yet -> none
        assertTrue(play.played.isEmpty())
        current = setOf(WHATSAPP)
        d.onPosted(post(text = "new"))      // now matched
        assertEquals(listOf(WHATSAPP), play.played)
    }
}
