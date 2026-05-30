package qhybrid.android.notifications

import qhybrid.android.notifications.NotificationDecider.PostedNotification

/**
 * WP11 — the **pure** mapping from a posted notification's raw Android fields to a
 * [PostedNotification] (Android-free so it is JVM-testable; the [FossilNotificationListenerService]
 * does the trivial `StatusBarNotification`/`Notification`/`Bundle` unpacking and hands the
 * primitives here).
 *
 * It mirrors the official Fossil app's `NotificationStatus` constructor exactly (recovered from
 * `FossilOfficialApp-deobf`): title/text are trimmed of control chars + surrounding whitespace, and
 * the skip-flags are derived from the standard `Notification` flag bits + the progress extra:
 *  - ongoing      = `flags & FLAG_ONGOING_EVENT (0x2)`
 *  - group-summary = `flags & FLAG_GROUP_SUMMARY (0x200)`
 *  - download-event = `progressMax != 0`
 */
object PostedNotificationExtractor {

    /** `Notification.FLAG_ONGOING_EVENT`. */
    const val FLAG_ONGOING_EVENT = 0x00000002

    /** `Notification.FLAG_GROUP_SUMMARY`. */
    const val FLAG_GROUP_SUMMARY = 0x00000200

    /**
     * Build a [PostedNotification] from the raw fields read off a `StatusBarNotification` /
     * `Notification`.
     *
     * @param id          `StatusBarNotification.getId()`
     * @param packageName `StatusBarNotification.getPackageName()`
     * @param title       `extras.getCharSequence("android.title")` (or null)
     * @param bigText     `extras.getCharSequence("android.bigText")` (or null) — preferred text
     * @param text        `extras.getCharSequence("android.text")` (or null) — fallback text
     * @param whenTime    `Notification.when`
     * @param flags       `Notification.flags`
     * @param progressMax `extras.getInt("android.progressMax", 0)`
     */
    fun extract(
        id: Int,
        packageName: String,
        title: CharSequence?,
        bigText: CharSequence?,
        text: CharSequence?,
        whenTime: Long,
        flags: Int,
        progressMax: Int,
    ): PostedNotification {
        // Prefer bigText when non-blank (matches the official app), else the collapsed text.
        val message = bigText?.toString()?.takeIf { it.isNotBlank() } ?: text?.toString() ?: ""
        return PostedNotification(
            id = id,
            packageName = packageName,
            title = clean(title?.toString()),
            text = clean(message),
            whenTime = whenTime,
            isOngoing = flags and FLAG_ONGOING_EVENT == FLAG_ONGOING_EVENT,
            isSummary = flags and FLAG_GROUP_SUMMARY == FLAG_GROUP_SUMMARY,
            isDownloadEvent = progressMax != 0,
        )
    }

    /**
     * Strip Unicode control characters (`\p{C}`) and surrounding whitespace, like the official app's
     * `NotificationStatus.cleanString`. Null → "" so the dedupe key is stable.
     */
    private fun clean(s: String?): String =
        (s ?: "").replace(CONTROL_CHARS, "").trim()

    private val CONTROL_CHARS = Regex("\\p{C}")
}
