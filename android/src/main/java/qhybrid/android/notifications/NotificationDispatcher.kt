package qhybrid.android.notifications

import qhybrid.android.notifications.NotificationDecider.NotificationDecision
import qhybrid.android.notifications.NotificationDecider.PostedNotification
import java.util.concurrent.atomic.AtomicReference

/**
 * WP11 — the testable glue between the listener and the watch: it holds the **cached rule
 * package-set** of the active watch + the **immediately-previous posted notification**, runs the
 * pure [NotificationDecider], and forwards a [NotificationDecision.Play] to the injected play seam.
 *
 * Android-free (no Service, no Room, no BLE): the rule package-set is supplied by an injected
 * [rules] snapshot (the [FossilNotificationListenerService] refreshes it off the main thread on
 * connect + via an `observeRules` flow) and the play is an injected [play] (production =
 * [ServiceNotificationPlay]). This makes the whole dispatch path unit-testable with fakes.
 *
 * **Caching.** [rules] returns the current cached set on each call (a cheap in-memory read), so the
 * decider never hits Room on the hot path of hundreds of daily notifications. The service owns the
 * cache lifecycle ([updateRules]); the dispatcher only reads it.
 *
 * **Previous-notification state.** Held here (not in the pure decider) so the decider stays pure.
 * Updated to the latest posted notification on EVERY post (matched or not), exactly like the
 * official app, so consecutive-duplicate suppression keys off the true last post.
 */
class NotificationDispatcher(
    /** Current cached rule package-set of the active watch (empty = none / no active watch). */
    private val rules: () -> Set<String>,
    /** Play seam — production pokes the WP3 service; tests inject a fake. */
    private val play: NotificationPlay,
) {
    private val previous = AtomicReference<PostedNotification?>(null)

    /**
     * Decide + (maybe) play for a freshly posted notification. Returns the [NotificationDecision]
     * (for logging/tests). Always records [posted] as the new "previous" so the next post's
     * duplicate check is against the true last post.
     */
    fun onPosted(posted: PostedNotification): NotificationDecision {
        val decision = NotificationDecider.decide(posted, rules(), previous.getAndSet(posted))
        if (decision is NotificationDecision.Play) {
            play.play(decision.packageName)
        }
        return decision
    }
}
