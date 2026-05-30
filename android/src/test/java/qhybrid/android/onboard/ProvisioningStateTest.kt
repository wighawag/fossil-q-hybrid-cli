package qhybrid.android.onboard

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WP-ONBOARD — unit tests for the in-memory [ProvisioningState] holder (the "adding a new watch"
 * signal the Dashboard modal observes). Mirrors the SyncState/ActivityState holder-test style:
 * publish drives the phase; the clock is injected; errorMessage only survives on FAILED.
 */
class ProvisioningStateTest {

    @After
    fun tearDown() = ProvisioningState.reset()

    @Test
    fun startsIdle() {
        ProvisioningState.reset()
        val s = ProvisioningState.status.value
        assertEquals(ProvisioningState.Phase.IDLE, s.phase)
        assertFalse(s.isProvisioning)
        assertNull(s.mac)
    }

    @Test
    fun provisioningPhaseIsInFlightAndCarriesMac() {
        ProvisioningState.publish(ProvisioningState.Phase.PROVISIONING, mac = "AA:BB", nowMillis = 100)
        val s = ProvisioningState.status.value
        assertEquals(ProvisioningState.Phase.PROVISIONING, s.phase)
        assertTrue(s.isProvisioning)
        assertEquals("AA:BB", s.mac)
        assertEquals(100L, s.lastUpdatedMillis)
    }

    @Test
    fun addedKeepsMacAndClearsError() {
        ProvisioningState.publish(ProvisioningState.Phase.PROVISIONING, mac = "AA:BB", nowMillis = 1)
        ProvisioningState.publish(ProvisioningState.Phase.ADDED, nowMillis = 2)
        val s = ProvisioningState.status.value
        assertEquals(ProvisioningState.Phase.ADDED, s.phase)
        assertFalse(s.isProvisioning)
        assertEquals("AA:BB", s.mac) // mac carried forward when not re-supplied
        assertNull(s.errorMessage)
    }

    @Test
    fun failedCarriesErrorMessage() {
        ProvisioningState.publish(
            ProvisioningState.Phase.FAILED, mac = "AA:BB", errorMessage = "lost link", nowMillis = 5,
        )
        val s = ProvisioningState.status.value
        assertEquals(ProvisioningState.Phase.FAILED, s.phase)
        assertEquals("lost link", s.errorMessage)
    }

    @Test
    fun errorMessageClearedOnNonFailedPhase() {
        ProvisioningState.publish(
            ProvisioningState.Phase.FAILED, errorMessage = "boom", nowMillis = 1,
        )
        ProvisioningState.publish(ProvisioningState.Phase.PROVISIONING, mac = "CC", nowMillis = 2)
        assertNull(ProvisioningState.status.value.errorMessage)
    }
}
