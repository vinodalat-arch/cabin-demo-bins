package com.incabin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for AmbientLightController pure companion functions.
 * No Android dependencies — fully unit-testable.
 */
class AmbientLightControllerTest {

    // -------------------------------------------------------------------------
    // safetyColor
    // -------------------------------------------------------------------------

    @Test
    fun safetyColor_high_returnsRed() {
        assertEquals(0xFFE74C3C.toInt(), AmbientLightController.safetyColor("high"))
    }

    @Test
    fun safetyColor_medium_returnsAmber() {
        assertEquals(0xFFF39C12.toInt(), AmbientLightController.safetyColor("medium"))
    }

    @Test
    fun safetyColor_low_returnsGreen() {
        assertEquals(0xFF2ECC71.toInt(), AmbientLightController.safetyColor("low"))
    }

    @Test
    fun safetyColor_unknown_returnsGreen() {
        assertEquals(0xFF2ECC71.toInt(), AmbientLightController.safetyColor("unknown"))
    }

    // -------------------------------------------------------------------------
    // parseColorHex
    // -------------------------------------------------------------------------

    @Test
    fun parseColorHex_sixDigit() {
        val result = AmbientLightController.parseColorHex("#FF0000")
        assertEquals(0xFFFF0000.toInt(), result)
    }

    @Test
    fun parseColorHex_eightDigit() {
        val result = AmbientLightController.parseColorHex("#80FF0000")
        assertEquals(0x80FF0000.toInt(), result)
    }

    @Test
    fun parseColorHex_emptyString_returnsZero() {
        assertEquals(0, AmbientLightController.parseColorHex(""))
    }

    @Test
    fun parseColorHex_noHashPrefix_returnsZero() {
        assertEquals(0, AmbientLightController.parseColorHex("FF0000"))
    }

    @Test
    fun parseColorHex_invalidHex_returnsZero() {
        assertEquals(0, AmbientLightController.parseColorHex("#ZZZZZZ"))
    }

    @Test
    fun parseColorHex_wrongLength_returnsZero() {
        assertEquals(0, AmbientLightController.parseColorHex("#FFF"))
    }

    @Test
    fun parseColorHex_accentColor() {
        val result = AmbientLightController.parseColorHex("#5B8DEF")
        assertEquals(0xFF5B8DEF.toInt(), result)
    }

    // -------------------------------------------------------------------------
    // occupiedAreaIds
    // -------------------------------------------------------------------------

    @Test
    fun occupiedAreaIds_driverOnly_leftDrive() {
        val seatMap = SeatMap(
            driver = SeatState(true, "Upright"),
            frontPassenger = SeatState(false),
            rearLeft = SeatState(false),
            rearCenter = SeatState(false),
            rearRight = SeatState(false)
        )
        val areas = AmbientLightController.occupiedAreaIds(seatMap, "left")
        assertEquals(setOf(AmbientLightController.AREA_DRIVER_LEFT), areas)
    }

    @Test
    fun occupiedAreaIds_driverOnly_rightDrive() {
        val seatMap = SeatMap(
            driver = SeatState(true, "Upright"),
            frontPassenger = SeatState(false),
            rearLeft = SeatState(false),
            rearCenter = SeatState(false),
            rearRight = SeatState(false)
        )
        val areas = AmbientLightController.occupiedAreaIds(seatMap, "right")
        assertEquals(setOf(AmbientLightController.AREA_FRONT_PAX_RIGHT), areas)
    }

    @Test
    fun occupiedAreaIds_allOccupied() {
        val seatMap = SeatMap(
            driver = SeatState(true),
            frontPassenger = SeatState(true),
            rearLeft = SeatState(true),
            rearCenter = SeatState(false),
            rearRight = SeatState(true)
        )
        val areas = AmbientLightController.occupiedAreaIds(seatMap, "left")
        assertEquals(
            setOf(
                AmbientLightController.AREA_DRIVER_LEFT,
                AmbientLightController.AREA_FRONT_PAX_RIGHT,
                AmbientLightController.AREA_REAR_LEFT,
                AmbientLightController.AREA_REAR_RIGHT
            ),
            areas
        )
    }

    @Test
    fun occupiedAreaIds_rearCenter_mapsToBothRearZones() {
        val seatMap = SeatMap(
            driver = SeatState(false),
            frontPassenger = SeatState(false),
            rearLeft = SeatState(false),
            rearCenter = SeatState(true, "Upright"),
            rearRight = SeatState(false)
        )
        val areas = AmbientLightController.occupiedAreaIds(seatMap, "left")
        assertEquals(
            setOf(AmbientLightController.AREA_REAR_LEFT, AmbientLightController.AREA_REAR_RIGHT),
            areas
        )
    }

    @Test
    fun occupiedAreaIds_empty_returnsEmptySet() {
        val seatMap = SeatMap()
        val areas = AmbientLightController.occupiedAreaIds(seatMap, "left")
        assertTrue(areas.isEmpty())
    }

    @Test
    fun occupiedAreaIds_frontPax_leftDrive() {
        val seatMap = SeatMap(
            driver = SeatState(false),
            frontPassenger = SeatState(true, "Upright"),
            rearLeft = SeatState(false),
            rearCenter = SeatState(false),
            rearRight = SeatState(false)
        )
        val areas = AmbientLightController.occupiedAreaIds(seatMap, "left")
        assertEquals(setOf(AmbientLightController.AREA_FRONT_PAX_RIGHT), areas)
    }
}
