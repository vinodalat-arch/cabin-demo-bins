package com.incabin

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class QuietModeControllerTest {

    @Before
    fun setUp() {
        Config.ENABLE_QUIET_MODE = true
    }

    @After
    fun tearDown() {
        Config.ENABLE_QUIET_MODE = true
    }

    // -- isAnySleeping --

    @Test
    fun isAnySleeping_all_vacant() {
        assertFalse(QuietModeController.isAnySleeping(SeatMap()))
    }

    @Test
    fun isAnySleeping_front_passenger_sleeping() {
        val map = SeatMap(frontPassenger = SeatState(true, "Sleeping"))
        assertTrue(QuietModeController.isAnySleeping(map))
    }

    @Test
    fun isAnySleeping_rear_left_sleeping() {
        val map = SeatMap(rearLeft = SeatState(true, "Sleeping"))
        assertTrue(QuietModeController.isAnySleeping(map))
    }

    @Test
    fun isAnySleeping_driver_sleeping_not_counted() {
        val map = SeatMap(driver = SeatState(true, "Sleeping"))
        assertFalse(QuietModeController.isAnySleeping(map))
    }

    @Test
    fun isAnySleeping_occupied_but_upright() {
        val map = SeatMap(frontPassenger = SeatState(true, "Upright"))
        assertFalse(QuietModeController.isAnySleeping(map))
    }

    // -- formatMessage --

    @Test
    fun format_message_wake_english() {
        val msg = QuietModeController.formatMessage(entering = false, isJapanese = false)
        assertEquals("Everyone's awake", msg)
    }

    @Test
    fun format_message_wake_japanese() {
        val msg = QuietModeController.formatMessage(entering = false, isJapanese = true)
        assertEquals("みなさん起きました", msg)
    }

    // -- state transitions --

    @Test
    fun transition_sleeping_to_awake_emits_event() {
        val ctrl = QuietModeController()
        val sleeping = SeatMap(frontPassenger = SeatState(true, "Sleeping"))
        val awake = SeatMap(frontPassenger = SeatState(true, "Upright"))

        // First: sleeping
        ctrl.update(sleeping, null, false)
        assertTrue(ctrl.active)

        // Then: awake → emits event
        val event = ctrl.update(awake, null, false)
        assertNotNull(event)
        assertFalse(ctrl.active)
    }

    @Test
    fun no_event_on_activation() {
        val ctrl = QuietModeController()
        val sleeping = SeatMap(frontPassenger = SeatState(true, "Sleeping"))
        val event = ctrl.update(sleeping, null, false)
        assertNull(event)
    }

    @Test
    fun disabled_deactivates() {
        Config.ENABLE_QUIET_MODE = false
        val ctrl = QuietModeController()
        ctrl.activateExternal(null)
        assertTrue(ctrl.active)
        ctrl.update(SeatMap(frontPassenger = SeatState(true, "Sleeping")), null, false)
        assertFalse(ctrl.active)
    }
}
