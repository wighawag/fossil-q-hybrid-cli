package qhybrid.android.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import qhybrid.android.buttons.ButtonActions
import qhybrid.android.buttons.ButtonActionsJson
import qhybrid.android.buttons.ButtonModes
import qhybrid.android.buttons.ButtonSlots
import qhybrid.android.db.ButtonMappingEntity
import qhybrid.android.tracker.ButtonActionRouter.Path2Action

/**
 * WP-TRACKER — unit tests for the Path-2 (0x08) button-aware single-press path: the pure
 * [ButtonPressParser] (exact emitted JSON → pressed button) + the pure [ButtonActionRouter]
 * (pressed button + stored WP4 mappings → which app-side action to run). Robolectric only because
 * parsing/decode uses Android's bundled `org.json`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ButtonPressRouterTest {

    // The EXACT shape FossilQAdapter.handleMicroAppEvent emits for declarationId 3073 (RING_PHONE).
    private fun buttonEvent(button: String) =
        """{"type":"button","button":"$button","app":"RING_PHONE","variant":"STANDARD",""" +
            """"declarationId":3073,"eventId":48,"sequence":5,"gesture":"SINGLE",""" +
            """"timestamp":"2026-05-31T12:00:00Z"}"""

    // ---- ButtonPressParser ---------------------------------------------------

    @Test
    fun parsesEachButtonToItsSlotId() {
        assertEquals(ButtonSlots.TOP, ButtonPressParser.parse(buttonEvent("TOP"))?.buttonId)
        assertEquals(ButtonSlots.MIDDLE, ButtonPressParser.parse(buttonEvent("MIDDLE"))?.buttonId)
        assertEquals(ButtonSlots.BOTTOM, ButtonPressParser.parse(buttonEvent("BOTTOM"))?.buttonId)
    }

    @Test
    fun ignoresNonRingPhoneOrNonButtonOrMalformed() {
        // A music (0x05) event is NOT a Path-2 button press.
        assertNull(ButtonPressParser.parse("""{"type":"music","action":"NEXT"}"""))
        // A micro-app button for a DIFFERENT app carries no usable Path-2 action here.
        assertNull(
            ButtonPressParser.parse(
                """{"type":"button","button":"TOP","app":"STOPWATCH","gesture":"SINGLE"}"""
            )
        )
        // Unknown button name / blank / malformed.
        assertNull(ButtonPressParser.parse(buttonEvent("WRIST")))
        assertNull(ButtonPressParser.parse(null))
        assertNull(ButtonPressParser.parse("   "))
        assertNull(ButtonPressParser.parse("{not json"))
    }

    // ---- ButtonActionRouter --------------------------------------------------

    private fun mapping(buttonId: Int, action: String) = ButtonMappingEntity(
        watchMac = "AA:00:00:00:00:01",
        buttonId = buttonId,
        modeType = ButtonModes.SINGLE_ACTION,
        actionsJson = ButtonActionsJson.encode(listOf(action)),
    )

    @Test
    fun resolvesLogWaypointButtonToLogWaypointAction() {
        val mappings = listOf(mapping(ButtonSlots.TOP, ButtonActions.LOG_WAYPOINT))
        val action = ButtonActionRouter.resolve(ButtonSlots.TOP, mappings)
        assertEquals(Path2Action.LogWaypoint(), action)
    }

    @Test
    fun resolvesRingPhoneButtonToRingPhoneAction() {
        val mappings = listOf(mapping(ButtonSlots.MIDDLE, ButtonActions.RING_PHONE))
        assertEquals(Path2Action.RingPhone(), ButtonActionRouter.resolve(ButtonSlots.MIDDLE, mappings))
    }

    @Test
    fun resolvesSwitchModeButtonToSwitchAction() {
        val mappings = listOf(mapping(ButtonSlots.BOTTOM, ButtonActions.SWITCH_MULTI_FUNCTION_MODE))
        assertEquals(
            Path2Action.SwitchMultiFunctionMode(),
            ButtonActionRouter.resolve(ButtonSlots.BOTTOM, mappings),
        )
    }

    @Test
    fun coexistingButtonsAreIndividuallyDistinguished() {
        // Three buttons all sharing the 01 01 0C 00 payload, each a DIFFERENT app-side action —
        // distinguished only by the pressed button's stored mapping (the wire bytes are identical).
        val mappings = listOf(
            mapping(ButtonSlots.TOP, ButtonActions.LOG_WAYPOINT),
            mapping(ButtonSlots.MIDDLE, ButtonActions.RING_PHONE),
            mapping(ButtonSlots.BOTTOM, ButtonActions.SWITCH_MULTI_FUNCTION_MODE),
        )
        assertEquals(Path2Action.LogWaypoint(), ButtonActionRouter.resolve(ButtonSlots.TOP, mappings))
        assertEquals(Path2Action.RingPhone(), ButtonActionRouter.resolve(ButtonSlots.MIDDLE, mappings))
        assertEquals(
            Path2Action.SwitchMultiFunctionMode(),
            ButtonActionRouter.resolve(ButtonSlots.BOTTOM, mappings),
        )
    }

    @Test
    fun foldsLegacyForwardToPhoneOntoRingPhone() {
        // A legacy DB row storing FORWARD_TO_PHONE decodes (normalized) to RING_PHONE.
        val mappings = listOf(mapping(ButtonSlots.TOP, ButtonActions.FORWARD_TO_PHONE))
        assertEquals(Path2Action.RingPhone(), ButtonActionRouter.resolve(ButtonSlots.TOP, mappings))
    }

    @Test
    fun returnsNullForNoMappingOrNonPath2Action() {
        // No row for the pressed button.
        assertNull(ButtonActionRouter.resolve(ButtonSlots.TOP, emptyList()))
        // A button mapped to a non-Path-2 action (e.g. STOPWATCH) is not handled here.
        val mappings = listOf(mapping(ButtonSlots.TOP, ButtonActions.STOPWATCH))
        assertNull(ButtonActionRouter.resolve(ButtonSlots.TOP, mappings))
        // A pressed button with no mapping among present mappings.
        val other = listOf(mapping(ButtonSlots.MIDDLE, ButtonActions.LOG_WAYPOINT))
        assertNull(ButtonActionRouter.resolve(ButtonSlots.TOP, other))
    }

    @Test
    fun resolveActionMapsIdsDirectly() {
        assertEquals(Path2Action.LogWaypoint(), ButtonActionRouter.resolveAction(ButtonActions.LOG_WAYPOINT))
        assertEquals(Path2Action.RingPhone(), ButtonActionRouter.resolveAction(ButtonActions.RING_PHONE))
        assertEquals(
            Path2Action.SwitchMultiFunctionMode(),
            ButtonActionRouter.resolveAction(ButtonActions.SWITCH_MULTI_FUNCTION_MODE),
        )
        assertNull(ButtonActionRouter.resolveAction(ButtonActions.STOPWATCH))
    }
}
