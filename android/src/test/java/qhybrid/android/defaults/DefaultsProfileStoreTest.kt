package qhybrid.android.defaults

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import qhybrid.android.buttons.ButtonActions
import qhybrid.android.buttons.ButtonModes
import qhybrid.android.buttons.ButtonSlots

/**
 * WP-DEFAULTS (sub-part 1) — the SharedPreferences-backed store. Robolectric for real
 * SharedPreferences.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DefaultsProfileStoreTest {

    private fun store() =
        SharedPreferencesDefaultsProfileStore(ApplicationProvider.getApplicationContext())

    @Test
    fun unsetStore_returnsFactory() {
        assertEquals(DefaultsProfile.FACTORY, store().get())
    }

    @Test
    fun setThenGet_roundTrips() {
        val s = store()
        val edited = DefaultsProfile.FACTORY.copy(
            alarms = listOf(DefaultAlarm(0, 8, 0, true, 0x3E, true, "Wake")),
            buttons = listOf(
                DefaultButton(ButtonSlots.BOTTOM, ButtonModes.SINGLE_ACTION, listOf(ButtonActions.DATE)),
            ),
        )
        s.set(edited)
        assertEquals(edited, store().get()) // re-read via a fresh instance (same prefs file)
    }

    @Test
    fun resetToFactory_restoresFactoryButtons() {
        val s = store()
        s.set(DefaultsProfile(alarms = emptyList(), rules = emptyList(), buttons = emptyList()))
        assertTrue(s.get().buttons.isEmpty())
        s.resetToFactory()
        assertEquals(DefaultsProfile.FACTORY, s.get())
    }

    @Test
    fun clearedButtons_persistAsEmpty_notFactory() {
        val s = store()
        s.set(DefaultsProfile.FACTORY.copy(buttons = emptyList()))
        assertTrue("a deliberately cleared buttons section must survive a reload", store().get().buttons.isEmpty())
    }

    @Test
    fun inMemoryStore_seededWithFactory() {
        val s = InMemoryDefaultsProfileStore()
        assertEquals(DefaultsProfile.FACTORY, s.get())
        s.set(DefaultsProfile.FACTORY.copy(buttons = emptyList()))
        assertTrue(s.get().buttons.isEmpty())
        s.resetToFactory()
        assertEquals(DefaultsProfile.FACTORY, s.get())
    }
}
