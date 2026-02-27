package com.incabin

import org.junit.Assert.assertEquals
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

    // -------------------------------------------------------------------------
    // isReverseGear — gear value classification
    // -------------------------------------------------------------------------

    @Test
    fun isReverseGear_reverse_returnsTrue() {
        assertTrue(VehicleChannelManager.isReverseGear(0x0020))
    }

    @Test
    fun isReverseGear_park_returnsFalse() {
        assertFalse(VehicleChannelManager.isReverseGear(0x0001))
    }

    @Test
    fun isReverseGear_drive_returnsFalse() {
        assertFalse(VehicleChannelManager.isReverseGear(0x0008))
    }

    @Test
    fun isReverseGear_neutral_returnsFalse() {
        assertFalse(VehicleChannelManager.isReverseGear(0x0004))
    }

    @Test
    fun isReverseGear_zero_returnsFalse() {
        assertFalse(VehicleChannelManager.isReverseGear(0))
    }

    // -------------------------------------------------------------------------
    // speedTier — speed classification
    // -------------------------------------------------------------------------

    @Test
    fun speedTier_stationary() {
        assertEquals(SpeedTier.STATIONARY, VehicleChannelManager.speedTier(0f))
    }

    @Test
    fun speedTier_slow() {
        assertEquals(SpeedTier.SLOW, VehicleChannelManager.speedTier(25f))
    }

    @Test
    fun speedTier_moderate() {
        assertEquals(SpeedTier.MODERATE, VehicleChannelManager.speedTier(60f))
    }

    @Test
    fun speedTier_fast() {
        assertEquals(SpeedTier.FAST, VehicleChannelManager.speedTier(100f))
    }

    @Test
    fun speedTier_unavailable() {
        assertEquals(SpeedTier.UNAVAILABLE, VehicleChannelManager.speedTier(-1f))
    }
}
