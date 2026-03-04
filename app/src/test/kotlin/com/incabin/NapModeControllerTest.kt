package com.incabin

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class NapModeControllerTest {

    @Before
    fun setUp() {
        Config.ENABLE_NAP_MODE = true
    }

    @After
    fun tearDown() {
        Config.ENABLE_NAP_MODE = true
    }

    // -- findSleepingPassengers --

    @Test
    fun findSleepingPassengers_empty_cabin() {
        assertTrue(NapModeController.findSleepingPassengers(SeatMap()).isEmpty())
    }

    @Test
    fun findSleepingPassengers_front_sleeping() {
        val map = SeatMap(frontPassenger = SeatState(true, "Sleeping"))
        val result = NapModeController.findSleepingPassengers(map)
        assertEquals(setOf(Seat.FRONT_PASSENGER), result)
    }

    @Test
    fun findSleepingPassengers_multiple_sleeping() {
        val map = SeatMap(
            frontPassenger = SeatState(true, "Sleeping"),
            rearLeft = SeatState(true, "Sleeping")
        )
        val result = NapModeController.findSleepingPassengers(map)
        assertEquals(setOf(Seat.FRONT_PASSENGER, Seat.REAR_LEFT), result)
    }

    @Test
    fun findSleepingPassengers_driver_excluded() {
        val map = SeatMap(driver = SeatState(true, "Sleeping"))
        assertTrue(NapModeController.findSleepingPassengers(map).isEmpty())
    }

    // -- newSleepers / newWakers --

    @Test
    fun newSleepers_detects_new() {
        val prev = setOf(Seat.FRONT_PASSENGER)
        val curr = setOf(Seat.FRONT_PASSENGER, Seat.REAR_LEFT)
        assertEquals(setOf(Seat.REAR_LEFT), NapModeController.newSleepers(prev, curr))
    }

    @Test
    fun newWakers_detects_woke() {
        val prev = setOf(Seat.FRONT_PASSENGER, Seat.REAR_LEFT)
        val curr = setOf(Seat.FRONT_PASSENGER)
        assertEquals(setOf(Seat.REAR_LEFT), NapModeController.newWakers(prev, curr))
    }

    // -- seatDisplayName --

    @Test
    fun seatDisplayName_english() {
        assertEquals("Front passenger", NapModeController.seatDisplayName(Seat.FRONT_PASSENGER, false))
        assertEquals("Rear left", NapModeController.seatDisplayName(Seat.REAR_LEFT, false))
    }

    @Test
    fun seatDisplayName_japanese() {
        assertEquals("助手席", NapModeController.seatDisplayName(Seat.FRONT_PASSENGER, true))
    }

    // -- formatWakeMessage --

    @Test
    fun formatWakeMessage_english() {
        val msg = NapModeController.formatWakeMessage(Seat.FRONT_PASSENGER, false)
        assertTrue(msg.contains("front passenger"))
    }

    @Test
    fun formatWakeMessage_japanese() {
        val msg = NapModeController.formatWakeMessage(Seat.FRONT_PASSENGER, true)
        assertTrue(msg.contains("助手席"))
    }

    // -- controller lifecycle --

    @Test
    fun wake_event_on_state_change() {
        val ctrl = NapModeController()
        val sleeping = SeatMap(frontPassenger = SeatState(true, "Sleeping"))
        val awake = SeatMap(frontPassenger = SeatState(true, "Upright"))

        ctrl.update(sleeping, false)
        assertTrue(ctrl.hasAnySleeping())

        val events = ctrl.update(awake, false)
        assertEquals(1, events.size)
        assertTrue(events[0].message.contains("front passenger") || events[0].message.contains("助手席"))
    }

    @Test
    fun disabled_returns_empty() {
        Config.ENABLE_NAP_MODE = false
        val ctrl = NapModeController()
        val sleeping = SeatMap(frontPassenger = SeatState(true, "Sleeping"))
        assertTrue(ctrl.update(sleeping, false).isEmpty())
    }
}
