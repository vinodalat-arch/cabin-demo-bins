package com.incabin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for SeatMap, SeatState, and Seat data model.
 * Pure data classes — no Android dependencies.
 */
class SeatMapTest {

    @Test
    fun test_defaultSeatMap_allVacant() {
        val map = SeatMap()
        assertFalse(map.driver.occupied)
        assertEquals("Vacant", map.driver.state)
        assertFalse(map.frontPassenger.occupied)
        assertEquals("Vacant", map.frontPassenger.state)
        assertFalse(map.rearLeft.occupied)
        assertEquals("Vacant", map.rearLeft.state)
        assertFalse(map.rearRight.occupied)
        assertEquals("Vacant", map.rearRight.state)
    }

    @Test
    fun test_seatState_occupied() {
        val state = SeatState(occupied = true, state = "Upright")
        assertTrue(state.occupied)
        assertEquals("Upright", state.state)
    }

    @Test
    fun test_seatMap_equality() {
        val a = SeatMap(
            driver = SeatState(true, "Upright"),
            frontPassenger = SeatState(true, "Sleeping"),
            rearLeft = SeatState(false, "Vacant"),
            rearRight = SeatState(false, "Vacant")
        )
        val b = SeatMap(
            driver = SeatState(true, "Upright"),
            frontPassenger = SeatState(true, "Sleeping"),
            rearLeft = SeatState(false, "Vacant"),
            rearRight = SeatState(false, "Vacant")
        )
        assertEquals(a, b)
    }

    @Test
    fun test_seatEnum_values() {
        val values = Seat.values()
        assertEquals(4, values.size)
        assertEquals(Seat.DRIVER, values[0])
        assertEquals(Seat.FRONT_PASSENGER, values[1])
        assertEquals(Seat.REAR_LEFT, values[2])
        assertEquals(Seat.REAR_RIGHT, values[3])
    }
}
