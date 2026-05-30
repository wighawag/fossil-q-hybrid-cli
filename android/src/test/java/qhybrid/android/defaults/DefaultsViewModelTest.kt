package qhybrid.android.defaults

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import qhybrid.android.buttons.ButtonActions
import qhybrid.android.buttons.ButtonDialModes
import qhybrid.android.buttons.ButtonModes
import qhybrid.android.buttons.ButtonSlots
import qhybrid.android.settings.ApplyDefaultsSync

/**
 * WP-DEFAULTS (sub-part 4) — the defaults editor ViewModel: edits round-trip through a fake store,
 * reset restores the factory buttons, and the cardinality contract ([ButtonMappingRules]) is
 * enforced so an invalid mapping can't be persisted. Robolectric only for `org.json` (actionsJson).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DefaultsViewModelTest {

    private class FakeApply(private val wired: Boolean = true) : ApplyDefaultsSync {
        var count = 0
        override fun applyDefaultsToActiveWatch(): Boolean { count++; return wired }
    }

    private fun vm(
        store: DefaultsProfileStore = InMemoryDefaultsProfileStore(),
        apply: ApplyDefaultsSync = FakeApply(),
    ) = DefaultsViewModel(store = store, applyDefaults = apply)

    @Test
    fun initialState_isStoreProfile() {
        val store = InMemoryDefaultsProfileStore()
        assertEquals(DefaultsProfile.FACTORY, vm(store).uiState.value.profile)
    }

    @Test
    fun setButtonSlot_singleAction_persistsThroughStore() {
        val store = InMemoryDefaultsProfileStore()
        val model = vm(store)
        model.setButtonSlot(ButtonSlots.TOP, ButtonModes.SINGLE_ACTION, listOf(ButtonActions.DATE))
        // round-trips through the store AND the published state
        assertEquals(listOf(ButtonActions.DATE), store.get().buttons.first { it.buttonId == ButtonSlots.TOP }.actions)
        assertEquals(listOf(ButtonActions.DATE), model.uiState.value.buttonFor(ButtonSlots.TOP)!!.actions)
    }

    @Test
    fun setButtonSlot_singleAction_collapsesMultipleIdsToOne() {
        val store = InMemoryDefaultsProfileStore()
        val model = vm(store)
        // A SINGLE_ACTION slot given several ids must persist AT MOST ONE (cardinality contract).
        model.setButtonSlot(
            ButtonSlots.TOP, ButtonModes.SINGLE_ACTION,
            listOf(ButtonActions.STOPWATCH, ButtonActions.DATE, ButtonActions.RING_PHONE),
        )
        assertEquals(
            listOf(ButtonActions.STOPWATCH),
            model.uiState.value.buttonFor(ButtonSlots.TOP)!!.actions,
        )
    }

    @Test
    fun setButtonSlot_customToggle_keepsCanonicalCycle() {
        val store = InMemoryDefaultsProfileStore()
        val model = vm(store)
        // Tapped non-canonically; the stored cycle is canonicalized (TIMEZONE_2, ALARM, DATE).
        model.setButtonSlot(
            ButtonSlots.MIDDLE, ButtonModes.CUSTOM_TOGGLE,
            listOf(ButtonDialModes.DATE, ButtonDialModes.TIMEZONE_2, ButtonDialModes.ALARM),
        )
        assertEquals(
            listOf(ButtonDialModes.TIMEZONE_2, ButtonDialModes.ALARM, ButtonDialModes.DATE),
            model.uiState.value.buttonFor(ButtonSlots.MIDDLE)!!.actions,
        )
    }

    @Test
    fun clearButtonSlot_removesIt() {
        val model = vm()
        model.clearButtonSlot(ButtonSlots.BOTTOM)
        assertTrue(model.uiState.value.buttons.none { it.buttonId == ButtonSlots.BOTTOM })
    }

    @Test
    fun clearAllButtons_thenResetRestoresFactory() {
        val store = InMemoryDefaultsProfileStore()
        val model = vm(store)
        model.clearAllButtons()
        assertTrue(model.uiState.value.buttons.isEmpty())
        assertTrue(store.get().buttons.isEmpty())

        model.resetToFactory()
        assertEquals(DefaultsProfile.FACTORY, model.uiState.value.profile)
        assertEquals(DefaultsProfile.FACTORY.buttons, store.get().buttons)
    }

    @Test
    fun alarms_roundTripThroughStore() {
        val store = InMemoryDefaultsProfileStore()
        val model = vm(store)
        model.upsertAlarm(DefaultAlarm(2, 9, 0, true, 0x3E, true, "Standup"))
        assertEquals(1, model.uiState.value.alarms.size)
        assertEquals(1, store.get().alarms.size)
        model.removeAlarm(2)
        assertTrue(model.uiState.value.alarms.isEmpty())
    }

    @Test
    fun rules_roundTripThroughStore() {
        val store = InMemoryDefaultsProfileStore()
        val model = vm(store)
        model.upsertRule(DefaultRule("com.whatsapp", 2, 90, 180))
        assertEquals(1, model.uiState.value.rules.size)
        // upsert by package replaces, doesn't duplicate
        model.upsertRule(DefaultRule("com.whatsapp", 3, 0, 0))
        assertEquals(1, model.uiState.value.rules.size)
        assertEquals(3, model.uiState.value.rules.first().vibePattern)
    }

    @Test
    fun applyToActiveWatch_forwardsToSeam() {
        val apply = FakeApply(wired = true)
        val model = vm(apply = apply)
        assertTrue(model.applyToActiveWatch())
        assertEquals(1, apply.count)
    }

    @Test
    fun applyToActiveWatch_noopSeamReturnsFalse() {
        val apply = FakeApply(wired = false)
        val model = vm(apply = apply)
        assertFalse(model.applyToActiveWatch())
        assertEquals(1, apply.count)
    }
}
