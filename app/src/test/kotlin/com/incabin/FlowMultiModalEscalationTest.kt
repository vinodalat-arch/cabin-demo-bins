package com.incabin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Flow integration tests for multi-modal escalation: end-to-end scenarios
 * testing EscalationLevel.resolveLevel + AlertOrchestrator.activeDangers +
 * channelsForLevel + per-detection caps.
 */
class FlowMultiModalEscalationTest {

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun result(
        phone: Boolean = false,
        eyes: Boolean = false,
        handsOff: Boolean = false,
        yawning: Boolean = false,
        distracted: Boolean = false,
        eating: Boolean = false,
        posture: Boolean = false,
        slouching: Boolean = false,
        duration: Int = 0
    ) = OutputResult(
        timestamp = "2025-01-01T00:00:00Z",
        passengerCount = 1,
        driverUsingPhone = phone,
        driverEyesClosed = eyes,
        driverYawning = yawning,
        driverDistracted = distracted,
        driverEatingDrinking = eating,
        handsOffWheel = handsOff,
        dangerousPosture = posture,
        childPresent = false,
        childSlouching = slouching,
        riskLevel = "low",
        earValue = null,
        marValue = null,
        headYaw = null,
        headPitch = null,
        distractionDurationS = duration
    )

    /** Simulate the orchestrator's evaluate logic: activeDangers → resolveLevel → channelsForLevel */
    private fun evaluateLevel(r: OutputResult): EscalationLevel? {
        val dangers = AlertOrchestrator.activeDangers(r)
        return EscalationLevel.resolveLevel(r.distractionDurationS, dangers)
    }

    private fun evaluateChannels(r: OutputResult): Set<VehicleChannelId>? {
        val level = evaluateLevel(r) ?: return null
        return EscalationLevel.channelsForLevel(level)
    }

    // -------------------------------------------------------------------------
    // Phone Onset → L1 → L2 → L3 → L4 → L5 Progression
    // -------------------------------------------------------------------------

    @Test
    fun phoneProgression_L1_at_onset() {
        assertEquals(EscalationLevel.L1_NUDGE, evaluateLevel(result(phone = true, duration = 0)))
    }

    @Test
    fun phoneProgression_L2_at_5s() {
        assertEquals(EscalationLevel.L2_WARNING, evaluateLevel(result(phone = true, duration = 5)))
    }

    @Test
    fun phoneProgression_L3_at_10s() {
        assertEquals(EscalationLevel.L3_URGENT, evaluateLevel(result(phone = true, duration = 10)))
    }

    @Test
    fun phoneProgression_L4_at_20s() {
        assertEquals(EscalationLevel.L4_INTERVENTION, evaluateLevel(result(phone = true, duration = 20)))
    }

    @Test
    fun phoneProgression_L5_at_30s() {
        assertEquals(EscalationLevel.L5_EMERGENCY, evaluateLevel(result(phone = true, duration = 30)))
    }

    // -------------------------------------------------------------------------
    // Yawning Caps at L4
    // -------------------------------------------------------------------------

    @Test
    fun yawningCap_L3_at_10s() {
        assertEquals(EscalationLevel.L3_URGENT, evaluateLevel(result(yawning = true, duration = 10)))
    }

    @Test
    fun yawningCap_L4_at_20s() {
        assertEquals(EscalationLevel.L4_INTERVENTION, evaluateLevel(result(yawning = true, duration = 20)))
    }

    @Test
    fun yawningCap_stays_L4_at_30s() {
        assertEquals(EscalationLevel.L4_INTERVENTION, evaluateLevel(result(yawning = true, duration = 30)))
    }

    @Test
    fun yawningCap_stays_L4_at_60s() {
        assertEquals(EscalationLevel.L4_INTERVENTION, evaluateLevel(result(yawning = true, duration = 60)))
    }

    // -------------------------------------------------------------------------
    // Advisory Detections Cap at L3
    // -------------------------------------------------------------------------

    @Test
    fun eatingCap_L3_at_10s() {
        assertEquals(EscalationLevel.L3_URGENT, evaluateLevel(result(eating = true, duration = 10)))
    }

    @Test
    fun eatingCap_stays_L3_at_60s() {
        assertEquals(EscalationLevel.L3_URGENT, evaluateLevel(result(eating = true, duration = 60)))
    }

    // -------------------------------------------------------------------------
    // All-Clear Restore
    // -------------------------------------------------------------------------

    @Test
    fun allClear_returnsNull() {
        assertNull(evaluateLevel(result()))
    }

    @Test
    fun allClear_noChannels() {
        assertNull(evaluateChannels(result()))
    }

    // -------------------------------------------------------------------------
    // Mixed Dangers — Highest Cap Wins
    // -------------------------------------------------------------------------

    @Test
    fun mixedDangers_eatingAndPhone_phonCapWins() {
        // Eating alone caps at L3, but phone raises cap to L5
        val level = evaluateLevel(result(eating = true, phone = true, duration = 60))
        assertEquals(EscalationLevel.L5_EMERGENCY, level)
    }

    // -------------------------------------------------------------------------
    // Vehicle Channel Dispatch Logic
    // -------------------------------------------------------------------------

    @Test
    fun L1_noVehicleChannels() {
        val channels = evaluateChannels(result(phone = true, duration = 0))!!
        assertFalse(AlertOrchestrator.shouldDispatchVehicle(channels))
    }

    @Test
    fun L3_hasVehicleChannels() {
        val channels = evaluateChannels(result(phone = true, duration = 10))!!
        assertTrue(AlertOrchestrator.shouldDispatchVehicle(channels))
        assertTrue(channels.contains(VehicleChannelId.CABIN_LIGHTS))
        assertTrue(channels.contains(VehicleChannelId.SEAT_HAPTIC))
    }

    @Test
    fun L5_hasWindowAndAdas() {
        val channels = evaluateChannels(result(phone = true, duration = 30))!!
        assertTrue(channels.contains(VehicleChannelId.WINDOW))
        assertTrue(channels.contains(VehicleChannelId.ADAS_STATE))
    }

    // -------------------------------------------------------------------------
    // Speed Gate (Driving State)
    // -------------------------------------------------------------------------

    @Test
    fun parked_L1_suppressed() {
        assertTrue(
            VehicleChannelManager.shouldSuppressForDrivingState(
                VehicleChannelManager.DRIVING_STATE_PARKED, EscalationLevel.L1_NUDGE
            )
        )
    }

    @Test
    fun parked_L3_notSuppressed() {
        assertFalse(
            VehicleChannelManager.shouldSuppressForDrivingState(
                VehicleChannelManager.DRIVING_STATE_PARKED, EscalationLevel.L3_URGENT
            )
        )
    }

    // -------------------------------------------------------------------------
    // Channel Property Availability (Pure Functions)
    // -------------------------------------------------------------------------

    @Test
    fun cabinLightChannel_propertyAvailable() {
        val ids = setOf(com.incabin.channels.CabinLightChannel.CABIN_LIGHTS_SWITCH, 0x1234)
        assertTrue(com.incabin.channels.CabinLightChannel.isPropertyAvailable(
            com.incabin.channels.CabinLightChannel.CABIN_LIGHTS_SWITCH, ids
        ))
    }

    @Test
    fun cabinLightChannel_propertyNotAvailable() {
        val ids = setOf(0x1234, 0x5678)
        assertFalse(com.incabin.channels.CabinLightChannel.isPropertyAvailable(
            com.incabin.channels.CabinLightChannel.CABIN_LIGHTS_SWITCH, ids
        ))
    }

    @Test
    fun adasChannel_propertyAvailable() {
        val ids = setOf(com.incabin.channels.AdasChannel.DRIVER_DISTRACTION_STATE)
        assertTrue(com.incabin.channels.AdasChannel.isPropertyAvailable(
            com.incabin.channels.AdasChannel.DRIVER_DISTRACTION_STATE, ids
        ))
    }
}
