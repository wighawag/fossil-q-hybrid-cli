package qhybrid.android.onboard

import qhybrid.android.settings.AppSettings

/**
 * WP-ONBOARD — pure policy for seeding the **app-level** (global, not per-watch) prefs — inactivity
 * nudge and second timezone — from a brand-new watch's read-back ([ConfigToSeed.SeededSettings]) at
 * provision time.
 *
 * **The rule (per the WP brief): read WINS, but only when the watch actually HAS the value set.**
 * Nudge and second-timezone are global user-level prefs (one phone, one user — see [AppSettings]),
 * yet the read-back is per-watch. So:
 *   - Watch reports the nudge **enabled** (with a minutes value) → seed enabled + minutes.
 *   - Watch reports a second-timezone **set** (not the 1024 "disabled" sentinel) → seed that offset.
 *   - Watch reports nudge **off** / second-timezone **unset/absent** → DO NOTHING. We must NOT clobber
 *     an existing global pref with a freshly-added watch's "off" (the brief's explicit caution).
 *
 * This is intentionally conservative and idempotent: a no-op decision leaves the prefs untouched, so
 * re-provisioning never erodes a user's chosen nudge/timezone.
 */
object PrefSeedDecision {

    /** What to write to [qhybrid.android.settings.SettingsPrefs], or null per field for "leave it". */
    data class Decision(
        /** Non-null → call `setNudge(enabled=true, minutes=...)`. Null → leave the nudge pref. */
        val nudge: NudgeWrite? = null,
        /** Non-null → call `setSecondTimezoneOffset(minutes)`. Null → leave the timezone pref. */
        val secondTimezoneOffsetMinutes: Int? = null,
    ) {
        val writesAnything: Boolean get() = nudge != null || secondTimezoneOffsetMinutes != null
    }

    data class NudgeWrite(val enabled: Boolean, val minutes: Int)

    /**
     * Decide what to seed from [seeded]. [current] is the existing app prefs (unused for the
     * conservative "only-write-when-watch-has-it" policy, but accepted so a future policy could be
     * smarter — e.g. only seed when the user hasn't customized it — without changing the call site).
     */
    fun decide(
        seeded: ConfigToSeed.SeededSettings,
        @Suppress("UNUSED_PARAMETER") current: AppSettings = AppSettings(),
    ): Decision {
        // Nudge: only seed when the watch has it ENABLED with a concrete minutes value.
        val nudge = if (seeded.nudgeEnabled && seeded.nudgeMinutes != null) {
            NudgeWrite(enabled = true, minutes = seeded.nudgeMinutes)
        } else {
            null
        }

        // Second timezone: only seed when the watch reports a concrete offset (null == unset/absent).
        val tz = seeded.secondTimezoneOffsetMinutes

        return Decision(nudge = nudge, secondTimezoneOffsetMinutes = tz)
    }
}
