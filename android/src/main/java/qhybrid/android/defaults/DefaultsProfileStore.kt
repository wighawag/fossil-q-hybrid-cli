package qhybrid.android.defaults

import android.content.Context

/**
 * WP-DEFAULTS (sub-part 1) — injectable seam over the ONE app-level defaults profile so the
 * ViewModels / provisioning logic are unit-testable with an in-memory fake (no Android
 * `SharedPreferences`). Mirrors the WP16g [qhybrid.android.settings.SettingsPrefs] storage style.
 *
 * The store is pre-populated with the [DefaultsProfile.FACTORY] defaults when unset. The profile is
 * persisted as the [DefaultsProfileJson] blob (the single source of truth for the shape).
 */
interface DefaultsProfileStore {
    /** The current defaults profile (factory defaults when unset / corrupt). Never throws. */
    fun get(): DefaultsProfile

    /** Persist [profile] as the app-level defaults. */
    fun set(profile: DefaultsProfile)

    /** Restore the [DefaultsProfile.FACTORY] defaults (clears any user edits). */
    fun resetToFactory()
}

/**
 * Production [DefaultsProfileStore] — a tiny SharedPreferences blob holding the
 * [DefaultsProfileJson] string. Holds the application context so it never leaks an Activity
 * (mirrors [qhybrid.android.settings.SharedPreferencesSettingsPrefs]).
 *
 * When the key is unset, [get] returns [DefaultsProfile.FACTORY] (the decoder maps blank → factory),
 * so a brand-new install already has the factory button defaults without an explicit write.
 */
class SharedPreferencesDefaultsProfileStore(context: Context) : DefaultsProfileStore {
    private val appContext = context.applicationContext

    private val prefs
        get() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun get(): DefaultsProfile =
        DefaultsProfileJson.decode(prefs.getString(KEY_PROFILE, null))

    override fun set(profile: DefaultsProfile) {
        prefs.edit().putString(KEY_PROFILE, DefaultsProfileJson.encode(profile)).apply()
    }

    override fun resetToFactory() {
        // Persist the factory blob explicitly so an export reflects it, and so a later
        // section-clear is distinguishable from "never edited".
        set(DefaultsProfile.FACTORY)
    }

    private companion object {
        const val PREFS = "fossilq_defaults_profile"
        const val KEY_PROFILE = "defaults_profile_json"
    }
}

/**
 * An in-memory [DefaultsProfileStore] (factory-seeded) — handy as a ViewModel constructor default
 * and a test fake. Starts at [DefaultsProfile.FACTORY].
 */
class InMemoryDefaultsProfileStore(
    initial: DefaultsProfile = DefaultsProfile.FACTORY,
) : DefaultsProfileStore {
    private var profile: DefaultsProfile = initial

    override fun get(): DefaultsProfile = profile
    override fun set(profile: DefaultsProfile) { this.profile = profile }
    override fun resetToFactory() { profile = DefaultsProfile.FACTORY }
}
