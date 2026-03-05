package com.incabin

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class EcoCabinControllerTest {

    @Before
    fun setUp() {
        Config.ENABLE_ECO_CABIN = true
    }

    @After
    fun tearDown() {
        Config.ENABLE_ECO_CABIN = true
    }

    // -- isFullyEmpty --

    @Test
    fun isFullyEmpty_all_vacant() {
        assertTrue(EcoCabinController.isFullyEmpty(SeatMap()))
    }

    @Test
    fun isFullyEmpty_driver_present() {
        assertFalse(EcoCabinController.isFullyEmpty(SeatMap(driver = SeatState(true, "Upright"))))
    }

    @Test
    fun isFullyEmpty_rear_occupied() {
        assertFalse(EcoCabinController.isFullyEmpty(SeatMap(rearLeft = SeatState(true, "Upright"))))
    }

    // -- shouldActivate --

    @Test
    fun shouldActivate_empty_parked_debounced() {
        assertTrue(EcoCabinController.shouldActivate(true, true, 10, 10))
    }

    @Test
    fun shouldActivate_not_parked() {
        assertFalse(EcoCabinController.shouldActivate(true, false, 10, 10))
    }

    @Test
    fun shouldActivate_not_enough_frames() {
        assertFalse(EcoCabinController.shouldActivate(true, true, 9, 10))
    }

    @Test
    fun shouldActivate_not_empty() {
        assertFalse(EcoCabinController.shouldActivate(false, true, 10, 10))
    }

    // -- shouldDeactivate --

    @Test
    fun shouldDeactivate_occupant_returns() {
        assertTrue(EcoCabinController.shouldDeactivate(false))
    }

    @Test
    fun shouldDeactivate_still_empty() {
        assertFalse(EcoCabinController.shouldDeactivate(true))
    }

    // -- formatMessage --

    @Test
    fun formatMessage_activate_english() {
        val msg = EcoCabinController.formatMessage(activating = true, isJapanese = false)
        assertEquals("", msg) // Silent — no TTS for eco transitions
    }

    @Test
    fun formatMessage_deactivate_english() {
        val msg = EcoCabinController.formatMessage(activating = false, isJapanese = false)
        assertEquals("", msg) // Silent — no TTS for eco transitions
    }

    @Test
    fun formatMessage_activate_japanese() {
        val msg = EcoCabinController.formatMessage(activating = true, isJapanese = true)
        assertEquals("", msg) // Silent — no TTS for eco transitions
    }

    @Test
    fun formatMessage_deactivate_japanese() {
        val msg = EcoCabinController.formatMessage(activating = false, isJapanese = true)
        assertEquals("", msg) // Silent — no TTS for eco transitions
    }

    // -- controller lifecycle --

    @Test
    fun activates_after_debounce() {
        val ctrl = EcoCabinController()
        val empty = SeatMap()
        var activationEvent: CabinEvent? = null
        for (i in 1..12) {
            val event = ctrl.update(empty, true, 150, 22.0f, false)
            if (event != null) activationEvent = event
        }
        assertNotNull(activationEvent)
        assertTrue(ctrl.active)
    }

    @Test
    fun deactivates_on_occupant() {
        val ctrl = EcoCabinController()
        val empty = SeatMap()
        val occupied = SeatMap(driver = SeatState(true, "Upright"))

        // Activate
        for (i in 1..12) ctrl.update(empty, true, 150, 22.0f, false)
        assertTrue(ctrl.active)

        // Deactivate
        val event = ctrl.update(occupied, true, 150, 22.0f, false)
        assertNotNull(event)
        assertFalse(ctrl.active)
        assertEquals("", event!!.message) // Silent eco transitions
    }

    @Test
    fun disabled_returns_null() {
        Config.ENABLE_ECO_CABIN = false
        val ctrl = EcoCabinController()
        assertNull(ctrl.update(SeatMap(), true, 150, 22.0f, false))
    }
}
