package com.incabin

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class FatigueComfortControllerTest {

    @Before
    fun setUp() {
        Config.ENABLE_FATIGUE_COMFORT = true
    }

    @After
    fun tearDown() {
        Config.ENABLE_FATIGUE_COMFORT = true
    }

    // -- shouldActivate --

    @Test
    fun shouldActivate_eyes_closed() {
        assertTrue(FatigueComfortController.shouldActivate(eyesClosed = true, yawning = false, currentlyActive = false))
    }

    @Test
    fun shouldActivate_yawning() {
        assertTrue(FatigueComfortController.shouldActivate(eyesClosed = false, yawning = true, currentlyActive = false))
    }

    @Test
    fun shouldActivate_already_active_returns_false() {
        assertFalse(FatigueComfortController.shouldActivate(eyesClosed = true, yawning = false, currentlyActive = true))
    }

    @Test
    fun shouldActivate_no_drowsiness() {
        assertFalse(FatigueComfortController.shouldActivate(eyesClosed = false, yawning = false, currentlyActive = false))
    }

    // -- shouldDeactivate --

    @Test
    fun shouldDeactivate_at_threshold() {
        assertTrue(FatigueComfortController.shouldDeactivate(30, 30))
    }

    @Test
    fun shouldDeactivate_below_threshold() {
        assertFalse(FatigueComfortController.shouldDeactivate(29, 30))
    }

    // -- alertBrightness --

    @Test
    fun alertBrightness_normal_boost() {
        assertEquals(200, FatigueComfortController.alertBrightness(150, 50))
    }

    @Test
    fun alertBrightness_clamp_at_255() {
        assertEquals(255, FatigueComfortController.alertBrightness(220, 50))
    }

    // -- formatMessage --

    @Test
    fun format_activate_english() {
        val msg = FatigueComfortController.formatActivateMessage(false)
        assertTrue(msg.contains("Stay alert"))
    }

    @Test
    fun format_activate_japanese() {
        val msg = FatigueComfortController.formatActivateMessage(true)
        assertTrue(msg.contains("集中"))
    }

    @Test
    fun format_deactivate_english() {
        val msg = FatigueComfortController.formatDeactivateMessage(false)
        assertTrue(msg.contains("refreshed"))
    }

    @Test
    fun format_deactivate_japanese() {
        val msg = FatigueComfortController.formatDeactivateMessage(true)
        assertTrue(msg.contains("元気"))
    }

    // -- controller lifecycle --

    @Test
    fun activation_sets_active_and_emits_event() {
        val ctrl = FatigueComfortController()
        val event = ctrl.update(eyesClosed = true, yawning = false, currentBrightness = 150, isJapanese = false)
        assertNotNull(event)
        assertTrue(ctrl.active)
        assertEquals(CabinEvent.IMPORTANT, event!!.priority)
    }

    @Test
    fun deactivation_after_clear_frames() {
        val ctrl = FatigueComfortController()
        // Activate
        ctrl.update(eyesClosed = true, yawning = false, currentBrightness = 150, isJapanese = false)
        assertTrue(ctrl.active)
        // 30 clear frames
        var event: CabinEvent? = null
        for (i in 1..30) {
            event = ctrl.update(eyesClosed = false, yawning = false, currentBrightness = 200, isJapanese = false)
        }
        assertNotNull(event)
        assertFalse(ctrl.active)
    }

    @Test
    fun disabled_returns_null() {
        Config.ENABLE_FATIGUE_COMFORT = false
        val ctrl = FatigueComfortController()
        assertNull(ctrl.update(eyesClosed = true, yawning = true, currentBrightness = 150, isJapanese = false))
    }
}
