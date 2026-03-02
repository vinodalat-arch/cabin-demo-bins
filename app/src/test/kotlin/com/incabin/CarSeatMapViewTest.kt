package com.incabin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for CarSeatMapView pure companion functions.
 * No Android dependencies — fully testable without Robolectric.
 */
class CarSeatMapViewTest {

    // -------------------------------------------------------------------------
    // seatColor() tests
    // -------------------------------------------------------------------------

    @Test
    fun seatColor_upright_returnsSafe() {
        assertEquals("safe", CarSeatMapView.seatColor("Upright"))
    }

    @Test
    fun seatColor_sleeping_returnsDanger() {
        assertEquals("danger", CarSeatMapView.seatColor("Sleeping"))
    }

    @Test
    fun seatColor_phone_returnsDanger() {
        assertEquals("danger", CarSeatMapView.seatColor("Phone"))
    }

    @Test
    fun seatColor_distracted_returnsCaution() {
        assertEquals("caution", CarSeatMapView.seatColor("Distracted"))
    }

    @Test
    fun seatColor_eating_returnsCaution() {
        assertEquals("caution", CarSeatMapView.seatColor("Eating"))
    }

    @Test
    fun seatColor_yawning_returnsCaution() {
        assertEquals("caution", CarSeatMapView.seatColor("Yawning"))
    }

    @Test
    fun seatColor_vacant_returnsVacant() {
        assertEquals("vacant", CarSeatMapView.seatColor("Vacant"))
    }

    // -------------------------------------------------------------------------
    // stateIcon() tests
    // -------------------------------------------------------------------------

    @Test
    fun stateIcon_allStates() {
        assertEquals("OK", CarSeatMapView.stateIcon("Upright"))
        assertEquals("Zzz", CarSeatMapView.stateIcon("Sleeping"))
        assertEquals("TEL", CarSeatMapView.stateIcon("Phone"))
        assertEquals("!!", CarSeatMapView.stateIcon("Distracted"))
        assertEquals("EAT", CarSeatMapView.stateIcon("Eating"))
        assertEquals("~", CarSeatMapView.stateIcon("Yawning"))
        assertEquals("--", CarSeatMapView.stateIcon("Vacant"))
        assertEquals("?", CarSeatMapView.stateIcon("Unknown"))
    }

    // -------------------------------------------------------------------------
    // isDanger() tests
    // -------------------------------------------------------------------------

    @Test
    fun isDanger_trueForAllDangerStates() {
        assertTrue(CarSeatMapView.isDanger("Sleeping"))
        assertTrue(CarSeatMapView.isDanger("Phone"))
        assertTrue(CarSeatMapView.isDanger("Distracted"))
        assertTrue(CarSeatMapView.isDanger("Eating"))
        assertTrue(CarSeatMapView.isDanger("Yawning"))
    }

    @Test
    fun isDanger_falseForUprightAndVacant() {
        assertFalse(CarSeatMapView.isDanger("Upright"))
        assertFalse(CarSeatMapView.isDanger("Vacant"))
    }

    // -------------------------------------------------------------------------
    // benchZoneCount() tests
    // -------------------------------------------------------------------------

    @Test
    fun benchZoneCount_noRearCenter_returns2() {
        val map = SeatMap(
            rearLeft = SeatState(true, "Upright"),
            rearRight = SeatState(true, "Upright")
        )
        assertEquals(2, CarSeatMapView.benchZoneCount(map))
    }

    @Test
    fun benchZoneCount_withRearCenter_returns3() {
        val map = SeatMap(
            rearLeft = SeatState(true, "Upright"),
            rearCenter = SeatState(true, "Upright"),
            rearRight = SeatState(true, "Upright")
        )
        assertEquals(3, CarSeatMapView.benchZoneCount(map))
    }

    @Test
    fun benchZoneCount_rearCenterVacant_returns2() {
        val map = SeatMap(
            rearLeft = SeatState(true, "Upright"),
            rearCenter = SeatState(false, "Vacant"),
            rearRight = SeatState(true, "Upright")
        )
        assertEquals(2, CarSeatMapView.benchZoneCount(map))
    }

    @Test
    fun benchZoneCount_allVacant_returns2() {
        val map = SeatMap()
        assertEquals(2, CarSeatMapView.benchZoneCount(map))
    }
}
