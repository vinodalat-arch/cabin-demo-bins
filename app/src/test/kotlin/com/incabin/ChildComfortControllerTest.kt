package com.incabin

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ChildComfortControllerTest {

    @Before
    fun setUp() {
        Config.ENABLE_CHILD_COMFORT = true
        Config.INFERENCE_FPS = 1
    }

    @After
    fun tearDown() {
        Config.ENABLE_CHILD_COMFORT = true
        Config.INFERENCE_FPS = 1
    }

    // -- shouldWarmRear --

    @Test
    fun shouldWarmRear_child_present_no_seatmap() {
        assertTrue(ChildComfortController.shouldWarmRear(true, null))
    }

    @Test
    fun shouldWarmRear_no_child() {
        assertFalse(ChildComfortController.shouldWarmRear(false, null))
    }

    @Test
    fun shouldWarmRear_child_in_rear_seat() {
        val map = SeatMap(rearLeft = SeatState(true, "Upright"))
        assertTrue(ChildComfortController.shouldWarmRear(true, map))
    }

    @Test
    fun shouldWarmRear_child_present_all_vacant() {
        val map = SeatMap()
        assertFalse(ChildComfortController.shouldWarmRear(true, map))
    }

    // -- childDriveMinutes --

    @Test
    fun childDriveMinutes_1fps() {
        assertEquals(1, ChildComfortController.childDriveMinutes(60, 1))
    }

    @Test
    fun childDriveMinutes_2fps() {
        assertEquals(1, ChildComfortController.childDriveMinutes(120, 2))
    }

    @Test
    fun childDriveMinutes_zero_fps_safe() {
        assertEquals(1, ChildComfortController.childDriveMinutes(60, 0)) // coerced to 1
    }

    // -- shouldRemind --

    @Test
    fun shouldRemind_first_at_30() {
        assertTrue(ChildComfortController.shouldRemind(30, 0, 30))
    }

    @Test
    fun shouldRemind_before_interval() {
        assertFalse(ChildComfortController.shouldRemind(29, 0, 30))
    }

    @Test
    fun shouldRemind_second_at_60() {
        assertTrue(ChildComfortController.shouldRemind(60, 30, 30))
    }

    // -- formatReminder --

    @Test
    fun formatReminder_english() {
        val msg = ChildComfortController.formatReminder(30, false)
        assertTrue(msg.contains("30 minutes"))
    }

    @Test
    fun formatReminder_japanese() {
        val msg = ChildComfortController.formatReminder(30, true)
        assertTrue(msg.contains("30分"))
    }

    // -- disabled --

    @Test
    fun disabled_returns_null() {
        Config.ENABLE_CHILD_COMFORT = false
        val ctrl = ChildComfortController()
        val result = OutputResult.default().copy(childPresent = true)
        assertNull(ctrl.update(result, null, false))
    }
}
