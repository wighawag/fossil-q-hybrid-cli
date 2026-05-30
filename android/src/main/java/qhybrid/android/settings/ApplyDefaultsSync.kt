package qhybrid.android.settings

import android.content.Context
import android.util.Log
import qhybrid.android.defaults.DefaultsProfileStore
import qhybrid.android.defaults.DefaultsToSeed
import qhybrid.android.defaults.SharedPreferencesDefaultsProfileStore
import qhybrid.android.db.WatchRepository
import qhybrid.android.sync.ServiceSaveToWatch
import qhybrid.android.sync.SyncSection

/**
 * WP-DEFAULTS (sub-part 3) — narrow, injectable seam for the manual **"Apply defaults to this
 * watch"** action on the Settings / Defaults screen (mirrors [FullSync] / [VibrationSync] /
 * [WatchAdminSync]) so [SettingsViewModel] stays unit-testable with a fake (no Android service, no
 * Room, no BLE).
 *
 * The action takes the app-level defaults profile, re-keys it to the ACTIVE watch's mac
 * ([DefaultsToSeed]), **full-REPLACEs** that watch's per-watch buttons + notification rules with the
 * defaults (an overwrite, NOT a merge — the watch ends up with EXACTLY the profile's unreadable
 * sections, same as provisioning), persists those re-keyed rows, then triggers a **targeted sync
 * push** of just those sections ([SyncSection.BUTTONS] + [SyncSection.NOTIFICATION_FILTER]) via the
 * existing [ServiceSaveToWatch] path. No new wire bytes.
 *
 * The default alarms are empty, so this is mostly buttons + filter; the alarms section is left
 * untouched on the watch (the apply does NOT blank a user's alarms — only the buttons/rules the
 * defaults own).
 */
interface ApplyDefaultsSync {
    /**
     * Apply the defaults profile to the already-added ACTIVE watch on demand: persist the re-keyed
     * buttons + rules (full-replace) + trigger a targeted sync push. No-op (returns `false`) when
     * there is no active watch. Returns whether the pipeline is wired/dispatched.
     */
    fun applyDefaultsToActiveWatch(): Boolean

    companion object {
        /** WP-DEFAULTS: the apply-defaults push is wired (persist + targeted ServiceSaveToWatch). */
        const val APPLY_DEFAULTS_WIRED = true

        /** The sections the apply overwrites on the watch: buttons + the notification filter. */
        val SECTIONS: Set<SyncSection> = setOf(SyncSection.BUTTONS, SyncSection.NOTIFICATION_FILTER)
    }
}

/**
 * Production [ApplyDefaultsSync] — resolves the active watch, persists the re-keyed defaults
 * (buttons + rules full-replace; alarms left untouched), then pokes [ServiceSaveToWatch] for a
 * targeted push of buttons + the notification filter. The DB write is dispatched off the caller's
 * thread via [launchPersist]. Holds the application context so it never leaks an Activity.
 *
 * Adds NO new BLE/protocol behavior — the push reuses the golden [ServiceSaveToWatch] targeted-sync
 * path; only the persisted DB rows change.
 */
class ServiceApplyDefaults(
    context: Context,
    private val store: DefaultsProfileStore = SharedPreferencesDefaultsProfileStore(context),
    /** Resolves the active watch's mac (or null). Production = WP4 repo. Injected for tests. */
    private val activeMac: suspend () -> String?,
    /** Persists the re-keyed defaults onto [mac] (full-replace buttons + rules). */
    private val persist: suspend (mac: String, seed: DefaultsToSeed.Seed) -> Unit,
    /** Launches the suspend persist off the caller's thread; production = an IO coroutine. */
    private val launchPersist: (suspend () -> Unit) -> Unit,
) : ApplyDefaultsSync {
    private val appContext = context.applicationContext

    override fun applyDefaultsToActiveWatch(): Boolean {
        // Publish SYNCING SYNCHRONOUSLY so the blocking "Saving…" modal appears the instant the user
        // confirms (mirrors the other push actions), even before the persist/link work runs.
        qhybrid.android.sync.SyncState.publish(
            qhybrid.android.sync.SyncState.SyncPhase.SYNCING,
            nowMillis = System.currentTimeMillis(),
        )
        val profile = store.get()
        launchPersist {
            val mac = activeMac()
            if (mac == null) {
                Log.w(TAG, "applyDefaults: no active watch — nothing to apply")
                return@launchPersist
            }
            val seed = DefaultsToSeed.seed(profile, mac)
            Log.i(
                TAG,
                "applyDefaults($mac): buttons=${seed.buttons.size} rules=${seed.rules.size} " +
                    "(full-overwrite of buttons + filter)",
            )
            runCatching { persist(mac, seed) }
                .onFailure { Log.w(TAG, "applyDefaults($mac): persist failed", it) }
            // Push the overwritten sections to the watch (buttons + notification filter).
            ServiceSaveToWatch.trigger(appContext, ApplyDefaultsSync.SECTIONS)
        }
        return ApplyDefaultsSync.APPLY_DEFAULTS_WIRED
    }

    companion object {
        private const val TAG = "FossilQ-ApplyDefaults"

        /**
         * Production factory: wires the WP4 repo for the active-mac lookup + the full-replace
         * persist (buttons + rules; alarms left untouched), and an IO coroutine launcher.
         */
        fun create(context: Context, launch: (suspend () -> Unit) -> Unit): ServiceApplyDefaults {
            val appContext = context.applicationContext
            return ServiceApplyDefaults(
                context = appContext,
                activeMac = { WatchRepository(appContext).getActiveWatch()?.macAddress },
                persist = { mac, seed ->
                    WatchRepository(appContext).replaceDefaultsSections(
                        mac,
                        alarms = seed.alarms,
                        rules = seed.rules,
                        buttons = seed.buttons,
                        // Apply overwrites buttons + filter; leave the user's per-watch alarms alone.
                        replaceAlarms = false,
                    )
                },
                launchPersist = launch,
            )
        }
    }
}

/**
 * A no-op [ApplyDefaultsSync] used as the [SettingsViewModel] constructor default so callers / tests
 * that don't exercise the apply path never touch the service or Room. Returns `false`.
 */
object NoopApplyDefaults : ApplyDefaultsSync {
    override fun applyDefaultsToActiveWatch(): Boolean = false
}
