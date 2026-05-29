package qhybrid.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import qhybrid.android.sync.SyncScheduler

/**
 * WP3 — reboot survival.
 *
 * On BOOT_COMPLETED (and the equivalent quick-boot intents), if we have an associated
 * watch we (a) re-arm CDM presence observation so the system will wake us when the watch
 * reappears, and (b) start the foreground service so it is alive and will reconnect on
 * the next appearance. Reconnect stays event-driven — we do NOT scan here.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "FossilQ-Boot"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != "com.htc.intent.action.QUICKBOOT_POWERON"
        ) {
            return
        }
        val mac = CompanionManager.getAssociatedMac(context)
        if (mac == null) {
            Log.i(TAG, "boot: no associated watch — nothing to do")
            return
        }
        Log.i(TAG, "boot: re-arming presence + starting service for $mac")
        // Re-arm event-driven presence (API 31+); fallback handles 26–30.
        CompanionManager.startObserving(context, mac)
        ReconnectFallback.arm(context, mac)
        // WP14: (re-)arm the periodic safety-sync job (idempotent; KEEP policy).
        SyncScheduler.schedule(context)
        // Bring the service back up (it will reconnect when the watch appears).
        WatchConnectionService.connectNow(context, mac)
    }
}
