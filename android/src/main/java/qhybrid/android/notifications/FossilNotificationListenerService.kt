package qhybrid.android.notifications

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import qhybrid.android.db.WatchRepository
import java.util.concurrent.atomic.AtomicReference

/**
 * WP11 — the Android shell: a [NotificationListenerService] that turns posted phone notifications
 * into watch plays. All the policy/logic lives in pure, unit-tested helpers; this class only does
 * the Android-specific work:
 *  1. extract the raw fields off the [StatusBarNotification] (delegated to the pure
 *     [PostedNotificationExtractor]),
 *  2. keep the active watch's **rule package-set** cached off the main thread (refreshed on connect
 *     + observed via `observeRules` so rule edits take effect live), and
 *  3. drive the [NotificationDispatcher] (pure decide + play seam) on each post.
 *
 * **Play path.** A matched notification is a **play-only-by-package** put — the per-app vibe + hand
 * degrees are already on the watch in its `NOTIFICATION_FILTER` (init/provision + WP14 rule edits).
 * The dispatcher forwards just the package to [ServiceNotificationPlay], which pokes the WP3 service
 * (connect-then-play, 30 s stale-drop). No new wire bytes.
 *
 * **On-device-pending.** The live OS interception is hardware/device verified; the field extraction,
 * the decide policy, the rule-cache gating and the seam forwarding are all unit-tested off-device.
 */
class FossilNotificationListenerService : NotificationListenerService() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** The cached active-watch rule package-set; read on the hot path, refreshed off-thread. */
    private val ruledPackages = AtomicReference<Set<String>>(emptySet())

    private val dispatcher by lazy {
        NotificationDispatcher(
            rules = { ruledPackages.get() },
            play = ServiceNotificationPlay(applicationContext),
        )
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "listener connected — starting rule-cache observer")
        observeActiveWatchRules()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        // Cheap pre-gate on the main thread: only build/dispatch if this package has a rule. The
        // vast majority of notifications are from unconfigured apps; skip them with no further work.
        if (sbn.packageName !in ruledPackages.get()) return
        val posted = extract(sbn) ?: return
        runCatching { dispatcher.onPosted(posted) }
            .onFailure { Log.w(TAG, "dispatch failed for ${sbn.packageName}", it) }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Keep [ruledPackages] in sync with the active watch's rules (live): re-resolve the active watch
     * and observe its rule rows, mapping to the package-set. A rule edit (add/remove an app) updates
     * the cache without a service restart. Resolving the active watch each (re)collect keeps it
     * correct across watch switches (the screen restarts the listener's process rarely; this is a
     * best-effort live cache, the on-connect path also refreshes it via the same flow).
     */
    private fun observeActiveWatchRules() {
        scope.launch {
            runCatching {
                val repo = WatchRepository(applicationContext)
                val mac = repo.getActiveWatch()?.macAddress
                if (mac == null) {
                    ruledPackages.set(emptySet())
                    Log.i(TAG, "no active watch — rule cache empty")
                    return@launch
                }
                repo.observeRules(mac).collectLatest { rows ->
                    ruledPackages.set(rows.map { it.packageName }.toSet())
                    Log.i(TAG, "rule cache updated: ${rows.size} rule(s) for $mac")
                }
            }.onFailure { Log.w(TAG, "rule-cache observer failed", it) }
        }
    }

    private companion object {
        private const val TAG = "FossilQ-NotifListener"

        /** Unpack the [StatusBarNotification] to primitives + delegate to the pure extractor. */
        private fun extract(sbn: StatusBarNotification): NotificationDecider.PostedNotification? {
            val n: Notification = sbn.notification ?: return null
            val extras = n.extras
            return PostedNotificationExtractor.extract(
                id = sbn.id,
                packageName = sbn.packageName,
                title = extras?.getCharSequence("android.title"),
                bigText = extras?.getCharSequence("android.bigText"),
                text = extras?.getCharSequence("android.text"),
                whenTime = n.`when`,
                flags = n.flags,
                progressMax = extras?.getInt("android.progressMax", 0) ?: 0,
            )
        }
    }
}
