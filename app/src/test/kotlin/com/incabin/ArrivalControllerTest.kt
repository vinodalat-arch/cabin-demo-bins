package com.incabin

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ArrivalControllerTest {

    @Before
    fun setUp() {
        Config.ENABLE_ARRIVAL_PREP = true
    }

    @After
    fun tearDown() {
        Config.ENABLE_ARRIVAL_PREP = true
    }

    // -- detectArrival --

    @Test
    fun detectArrival_was_moving_now_parked() {
        val history = listOf(60f, 50f, 40f, 20f, 0f)
        assertTrue(ArrivalController.detectArrival(history, true))
    }

    @Test
    fun detectArrival_never_moved() {
        val history = listOf(0f, 0f, 0f, 0f, 0f)
        assertFalse(ArrivalController.detectArrival(history, true))
    }

    @Test
    fun detectArrival_still_moving() {
        val history = listOf(60f, 50f, 40f)
        assertFalse(ArrivalController.detectArrival(history, false))
    }

    @Test
    fun detectArrival_speed_below_threshold() {
        val history = listOf(20f, 15f, 10f, 0f)
        assertFalse(ArrivalController.detectArrival(history, true))
    }

    // -- formatArrived --

    @Test
    fun formatArrived_english() {
        val msg = ArrivalController.formatArrived(47, false)
        assertTrue(msg.contains("47 minutes"))
    }

    @Test
    fun formatArrived_japanese() {
        val msg = ArrivalController.formatArrived(47, true)
        assertTrue(msg.contains("47分"))
    }

    // -- formatArrivingSoon --

    @Test
    fun formatArrivingSoon_english() {
        assertEquals("Arriving soon.", ArrivalController.formatArrivingSoon(false))
    }

    @Test
    fun formatArrivingSoon_japanese() {
        assertTrue(ArrivalController.formatArrivingSoon(true).contains("到着"))
    }

    // -- controller lifecycle --

    @Test
    fun announces_once() {
        val ctrl = ArrivalController()
        ctrl.start(0L)

        // Simulate driving then parking
        for (i in 1..5) ctrl.update(60f, false, i * 1000L, false)
        val event = ctrl.update(0f, true, 50 * 60_000L, false)
        assertNotNull(event)

        // Second park should not re-announce
        val event2 = ctrl.update(0f, true, 51 * 60_000L, false)
        assertNull(event2)
    }

    @Test
    fun disabled_returns_null() {
        Config.ENABLE_ARRIVAL_PREP = false
        val ctrl = ArrivalController()
        ctrl.start(0L)
        for (i in 1..5) ctrl.update(60f, false, i * 1000L, false)
        assertNull(ctrl.update(0f, true, 50 * 60_000L, false))
    }
}
