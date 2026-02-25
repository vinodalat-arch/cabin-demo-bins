package com.incabin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for AlertOrchestrator pure companion functions:
 * activeDangers() extraction and shouldDispatchVehicle() intersection logic.
 */
class AlertOrchestratorTest {

    // -------------------------------------------------------------------------
    // Helper
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

    // -------------------------------------------------------------------------
    // activeDangers
    // -------------------------------------------------------------------------

    @Test
    fun activeDangers_noDangers_emptySet() {
        val dangers = AlertOrchestrator.activeDangers(result())
        assertTrue(dangers.isEmpty())
    }

    @Test
    fun activeDangers_singleDanger_phone() {
        val dangers = AlertOrchestrator.activeDangers(result(phone = true))
        assertEquals(setOf("driver_using_phone"), dangers)
    }

    @Test
    fun activeDangers_multipleDangers() {
        val dangers = AlertOrchestrator.activeDangers(
            result(phone = true, eyes = true, yawning = true)
        )
        assertEquals(
            setOf("driver_using_phone", "driver_eyes_closed", "driver_yawning"),
            dangers
        )
    }

    @Test
    fun activeDangers_allDangers() {
        val dangers = AlertOrchestrator.activeDangers(
            result(
                phone = true, eyes = true, handsOff = true,
                yawning = true, distracted = true, eating = true,
                posture = true, slouching = true
            )
        )
        assertEquals(8, dangers.size)
    }

    @Test
    fun activeDangers_handsOff_included() {
        val dangers = AlertOrchestrator.activeDangers(result(handsOff = true))
        assertTrue(dangers.contains("hands_off_wheel"))
    }

    @Test
    fun activeDangers_childSlouching_included() {
        val dangers = AlertOrchestrator.activeDangers(result(slouching = true))
        assertTrue(dangers.contains("child_slouching"))
    }

    @Test
    fun activeDangers_posture_included() {
        val dangers = AlertOrchestrator.activeDangers(result(posture = true))
        assertTrue(dangers.contains("dangerous_posture"))
    }

    @Test
    fun activeDangers_eating_included() {
        val dangers = AlertOrchestrator.activeDangers(result(eating = true))
        assertTrue(dangers.contains("driver_eating_drinking"))
    }

    // -------------------------------------------------------------------------
    // shouldDispatchVehicle
    // -------------------------------------------------------------------------

    @Test
    fun shouldDispatchVehicle_softwareOnly_returnsFalse() {
        val channels = setOf(
            VehicleChannelId.CHIME,
            VehicleChannelId.DASHBOARD,
            VehicleChannelId.TTS,
            VehicleChannelId.NOTIFICATION
        )
        assertFalse(AlertOrchestrator.shouldDispatchVehicle(channels))
    }

    @Test
    fun shouldDispatchVehicle_withCabinLights_returnsTrue() {
        val channels = setOf(VehicleChannelId.CHIME, VehicleChannelId.CABIN_LIGHTS)
        assertTrue(AlertOrchestrator.shouldDispatchVehicle(channels))
    }

    @Test
    fun shouldDispatchVehicle_withAdasState_returnsTrue() {
        val channels = setOf(VehicleChannelId.ADAS_STATE)
        assertTrue(AlertOrchestrator.shouldDispatchVehicle(channels))
    }

    @Test
    fun shouldDispatchVehicle_withWindow_returnsTrue() {
        val channels = setOf(VehicleChannelId.WINDOW)
        assertTrue(AlertOrchestrator.shouldDispatchVehicle(channels))
    }

    @Test
    fun shouldDispatchVehicle_emptySet_returnsFalse() {
        assertFalse(AlertOrchestrator.shouldDispatchVehicle(emptySet()))
    }

    @Test
    fun shouldDispatchVehicle_allVehicleChannels_returnsTrue() {
        val channels = setOf(
            VehicleChannelId.CABIN_LIGHTS,
            VehicleChannelId.SEAT_HAPTIC,
            VehicleChannelId.SEAT_THERMAL,
            VehicleChannelId.STEERING_HEAT,
            VehicleChannelId.WINDOW,
            VehicleChannelId.ADAS_STATE
        )
        assertTrue(AlertOrchestrator.shouldDispatchVehicle(channels))
    }

    @Test
    fun shouldDispatchVehicle_beepOnly_returnsFalse() {
        // BEEP is a software channel (AudioAlerter handles it)
        assertFalse(AlertOrchestrator.shouldDispatchVehicle(setOf(VehicleChannelId.BEEP)))
    }
}
