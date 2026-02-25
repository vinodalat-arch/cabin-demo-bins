package com.incabin

import com.incabin.channels.*
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for per-channel isPropertyAvailable() pure companion functions.
 * Tests property ID presence/absence in a set (simulating CarPropertyManager.getPropertyList).
 */
class ChannelPropertyTest {

    private val emptyIds = emptySet<Int>()

    // -------------------------------------------------------------------------
    // CabinLightChannel
    // -------------------------------------------------------------------------

    @Test
    fun cabinLight_cabinSwitch_present() {
        assertTrue(CabinLightChannel.isPropertyAvailable(
            CabinLightChannel.CABIN_LIGHTS_SWITCH,
            setOf(CabinLightChannel.CABIN_LIGHTS_SWITCH)
        ))
    }

    @Test
    fun cabinLight_footwellSwitch_present() {
        assertTrue(CabinLightChannel.isPropertyAvailable(
            CabinLightChannel.SEAT_FOOTWELL_LIGHTS_SWITCH,
            setOf(CabinLightChannel.SEAT_FOOTWELL_LIGHTS_SWITCH)
        ))
    }

    @Test
    fun cabinLight_notPresent() {
        assertFalse(CabinLightChannel.isPropertyAvailable(
            CabinLightChannel.CABIN_LIGHTS_SWITCH, emptyIds
        ))
    }

    // -------------------------------------------------------------------------
    // SeatHapticChannel
    // -------------------------------------------------------------------------

    @Test
    fun seatHaptic_present() {
        assertTrue(SeatHapticChannel.isPropertyAvailable(
            SeatHapticChannel.SEAT_LUMBAR_FORE_AFT_MOVE,
            setOf(SeatHapticChannel.SEAT_LUMBAR_FORE_AFT_MOVE)
        ))
    }

    @Test
    fun seatHaptic_notPresent() {
        assertFalse(SeatHapticChannel.isPropertyAvailable(
            SeatHapticChannel.SEAT_LUMBAR_FORE_AFT_MOVE, emptyIds
        ))
    }

    // -------------------------------------------------------------------------
    // SeatThermalChannel
    // -------------------------------------------------------------------------

    @Test
    fun seatThermal_present() {
        assertTrue(SeatThermalChannel.isPropertyAvailable(
            SeatThermalChannel.HVAC_SEAT_TEMPERATURE,
            setOf(SeatThermalChannel.HVAC_SEAT_TEMPERATURE)
        ))
    }

    @Test
    fun seatThermal_notPresent() {
        assertFalse(SeatThermalChannel.isPropertyAvailable(
            SeatThermalChannel.HVAC_SEAT_TEMPERATURE, emptyIds
        ))
    }

    // -------------------------------------------------------------------------
    // SteeringHeatChannel
    // -------------------------------------------------------------------------

    @Test
    fun steeringHeat_present() {
        assertTrue(SteeringHeatChannel.isPropertyAvailable(
            SteeringHeatChannel.HVAC_STEERING_WHEEL_HEAT,
            setOf(SteeringHeatChannel.HVAC_STEERING_WHEEL_HEAT)
        ))
    }

    @Test
    fun steeringHeat_notPresent() {
        assertFalse(SteeringHeatChannel.isPropertyAvailable(
            SteeringHeatChannel.HVAC_STEERING_WHEEL_HEAT, emptyIds
        ))
    }

    // -------------------------------------------------------------------------
    // WindowChannel
    // -------------------------------------------------------------------------

    @Test
    fun window_present() {
        assertTrue(WindowChannel.isPropertyAvailable(
            WindowChannel.WINDOW_MOVE,
            setOf(WindowChannel.WINDOW_MOVE)
        ))
    }

    @Test
    fun window_notPresent() {
        assertFalse(WindowChannel.isPropertyAvailable(
            WindowChannel.WINDOW_MOVE, emptyIds
        ))
    }

    // -------------------------------------------------------------------------
    // AdasChannel
    // -------------------------------------------------------------------------

    @Test
    fun adas_distractionState_present() {
        assertTrue(AdasChannel.isPropertyAvailable(
            AdasChannel.DRIVER_DISTRACTION_STATE,
            setOf(AdasChannel.DRIVER_DISTRACTION_STATE)
        ))
    }

    @Test
    fun adas_drowsinessState_present() {
        assertTrue(AdasChannel.isPropertyAvailable(
            AdasChannel.DRIVER_DROWSINESS_ATTENTION_STATE,
            setOf(AdasChannel.DRIVER_DROWSINESS_ATTENTION_STATE)
        ))
    }

    @Test
    fun adas_notPresent() {
        assertFalse(AdasChannel.isPropertyAvailable(
            AdasChannel.DRIVER_DISTRACTION_STATE, emptyIds
        ))
    }
}
