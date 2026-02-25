package com.incabin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for VehicleChannelManager pure companion function:
 * shouldSuppressForDrivingState() — parked/idling/moving × each level.
 */
class VehicleChannelManagerTest {

    // -------------------------------------------------------------------------
    // shouldSuppressForDrivingState — Parked
    // -------------------------------------------------------------------------

    @Test
    fun shouldSuppress_parked_L1_returnsTrue() {
        assertTrue(
            VehicleChannelManager.shouldSuppressForDrivingState(
                VehicleChannelManager.DRIVING_STATE_PARKED, EscalationLevel.L1_NUDGE
            )
        )
    }

    @Test
    fun shouldSuppress_parked_L2_returnsFalse() {
        assertFalse(
            VehicleChannelManager.shouldSuppressForDrivingState(
                VehicleChannelManager.DRIVING_STATE_PARKED, EscalationLevel.L2_WARNING
            )
        )
    }

    @Test
    fun shouldSuppress_parked_L5_returnsFalse() {
        assertFalse(
            VehicleChannelManager.shouldSuppressForDrivingState(
                VehicleChannelManager.DRIVING_STATE_PARKED, EscalationLevel.L5_EMERGENCY
            )
        )
    }

    // -------------------------------------------------------------------------
    // shouldSuppressForDrivingState — Idling
    // -------------------------------------------------------------------------

    @Test
    fun shouldSuppress_idling_L1_returnsFalse() {
        assertFalse(
            VehicleChannelManager.shouldSuppressForDrivingState(
                VehicleChannelManager.DRIVING_STATE_IDLING, EscalationLevel.L1_NUDGE
            )
        )
    }

    // -------------------------------------------------------------------------
    // shouldSuppressForDrivingState — Moving
    // -------------------------------------------------------------------------

    @Test
    fun shouldSuppress_moving_L1_returnsFalse() {
        assertFalse(
            VehicleChannelManager.shouldSuppressForDrivingState(
                VehicleChannelManager.DRIVING_STATE_MOVING, EscalationLevel.L1_NUDGE
            )
        )
    }

    @Test
    fun shouldSuppress_moving_L3_returnsFalse() {
        assertFalse(
            VehicleChannelManager.shouldSuppressForDrivingState(
                VehicleChannelManager.DRIVING_STATE_MOVING, EscalationLevel.L3_URGENT
            )
        )
    }

    @Test
    fun shouldSuppress_moving_L5_returnsFalse() {
        assertFalse(
            VehicleChannelManager.shouldSuppressForDrivingState(
                VehicleChannelManager.DRIVING_STATE_MOVING, EscalationLevel.L5_EMERGENCY
            )
        )
    }

    // -------------------------------------------------------------------------
    // shouldSuppressForDrivingState — Edge: unknown state
    // -------------------------------------------------------------------------

    @Test
    fun shouldSuppress_unknownState_returnsFalse() {
        // Unknown state (e.g., -1) should not suppress
        assertFalse(
            VehicleChannelManager.shouldSuppressForDrivingState(
                -1, EscalationLevel.L1_NUDGE
            )
        )
    }
}
