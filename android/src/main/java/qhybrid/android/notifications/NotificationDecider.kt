package qhybrid.android.notifications

/**
 * WP11 — the **pure** notification → watch-action decision (no Android Service, no BLE, no Room).
 *
 * A posted notification's fields + the active watch's configured per-app rule package-set + the
 * immediately-previous posted notification are mapped to a [NotificationDecision]: either
 * [NotificationDecision.None] (with a reason) or [NotificationDecision.Play] carrying just the
 * package name.
 *
 * **Why only the package name.** The per-app vibration pattern + precise hand degrees (WP4 rule)
 * are NOT carried in the runtime play — the `NOTIFICATION_FILTER` table is **already on the watch**
 * (written at init/provisioning and re-pushed when the user edits an app's rule via the WP14 sync).
 * At runtime the watch matches the play file's package CRC against that on-watch filter and applies
 * the configured vibe + hands itself. So WP11 only needs to decide *"play package X"* — the watch
 * owns the pattern/degrees. (This mirrors the official app: the listener forwards the package; the
 * vibe/hands live in the already-uploaded filter.)
 *
 * **Policy (ported from the official Fossil app's native pre-filter — see
 * `WatchNotificationManager.didReceivedNotification` + `NotificationStatus`/`NotificationFactory`):**
 *  - **Rule gate** — only apps with a configured rule buzz. No rule for the package → [None] (we do
 *    NOT send a useless play the watch would ignore for an unmatched CRC). This also drops our own
 *    foreground-service notification and every unconfigured system/app notification.
 *  - **Ongoing → skip** (`FLAG_ONGOING_EVENT`): media/nav/foreground-service notifications.
 *  - **Group-summary → skip** (`FLAG_GROUP_SUMMARY`): only the child item buzzes, never the summary
 *    (avoids a double-buzz).
 *  - **Download/progress event → skip** (`extras.progressMax != 0`): download/upload/progress bars.
 *  - **Consecutive-duplicate suppression** — drop a notification identical to the immediately
 *    previous one on `(id, packageName, title, text, whenTime)` (the official app's exact dedupe;
 *    collapses the constant in-place content re-posts apps spam). NOT a time window; NOT a
 *    per-package rate-limit.
 *
 * Priority is intentionally NOT filtered: a user who created a per-app rule explicitly opted that
 * app in, so we buzz regardless of the notification's priority.
 *
 * Kept pure so the whole policy is JVM/Robolectric unit-testable; the Android shell
 * ([FossilNotificationListenerService]) extracts the fields, holds the prev-notification + the
 * cached rule package-set, and forwards the decision to the play seam.
 */
object NotificationDecider {

    /**
     * A posted notification reduced to exactly the fields the decision needs (a faithful subset of
     * the official app's `NotificationStatus`). Android-free so it is pure-JVM testable; the shell
     * builds it from a `StatusBarNotification`.
     *
     * @param id        `StatusBarNotification.getId()`
     * @param packageName posting app's package
     * @param title     `extras["android.title"]` (trimmed) or "" — part of the dedupe key
     * @param text      `extras["android.bigText"]`/`["android.text"]` (trimmed) or "" — dedupe key
     * @param whenTime  `Notification.when` — dedupe key
     * @param isOngoing `flags & FLAG_ONGOING_EVENT (0x2)`
     * @param isSummary `flags & FLAG_GROUP_SUMMARY (0x200)`
     * @param isDownloadEvent `extras.getInt("android.progressMax", 0) != 0`
     */
    data class PostedNotification(
        val id: Int,
        val packageName: String,
        val title: String = "",
        val text: String = "",
        val whenTime: Long = 0L,
        val isOngoing: Boolean = false,
        val isSummary: Boolean = false,
        val isDownloadEvent: Boolean = false,
    )

    /**
     * The decision. [Play] carries ONLY the package name (the watch owns the vibe/hands via the
     * already-uploaded filter); [None] carries a [reason] for logging/tests.
     */
    sealed interface NotificationDecision {
        /** No watch action; [reason] explains why (no-rule / ongoing / summary / download / dup). */
        data class None(val reason: String) : NotificationDecision

        /** Play (buzz + move hands per the on-watch filter) for [packageName]. */
        data class Play(val packageName: String) : NotificationDecision
    }

    /**
     * Decide what (if anything) a posted notification should do on the watch.
     *
     * @param posted     the posted notification's relevant fields.
     * @param ruledPackages the active watch's configured rule package-set (empty = no rules / no
     *                   active watch → never plays). Membership is the rule gate.
     * @param previous   the immediately-previous posted notification (null = none yet) for the
     *                   consecutive-duplicate suppression.
     * @return [NotificationDecision.Play] only when the package has a rule and the notification
     *         passes every skip filter; otherwise [NotificationDecision.None] with a reason.
     */
    fun decide(
        posted: PostedNotification,
        ruledPackages: Set<String>,
        previous: PostedNotification?,
    ): NotificationDecision {
        // Rule gate first — the vast majority of notifications are from apps with no rule, so this
        // short-circuits before any other work and never sends a useless play to the watch.
        if (posted.packageName !in ruledPackages) {
            return NotificationDecision.None("no-rule")
        }
        if (posted.isOngoing) {
            return NotificationDecision.None("ongoing")
        }
        if (posted.isSummary) {
            return NotificationDecision.None("group-summary")
        }
        if (posted.isDownloadEvent) {
            return NotificationDecision.None("download-event")
        }
        if (previous != null && isConsecutiveDuplicate(posted, previous)) {
            return NotificationDecision.None("duplicate")
        }
        return NotificationDecision.Play(posted.packageName)
    }

    /**
     * The official app's exact dedupe: two posts are "the same" when their id, package, title, text
     * and `when` timestamp all match. Apps re-post the same notification in place (progress, typing
     * indicators, edited text) — those collapse to a single buzz.
     */
    private fun isConsecutiveDuplicate(a: PostedNotification, b: PostedNotification): Boolean =
        a.id == b.id &&
            a.packageName == b.packageName &&
            a.title == b.title &&
            a.text == b.text &&
            a.whenTime == b.whenTime
}
