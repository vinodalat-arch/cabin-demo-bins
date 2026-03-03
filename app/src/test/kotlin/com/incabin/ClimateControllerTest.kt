package com.incabin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for ClimateController pure companion functions.
 * No Android dependencies — fully unit-testable.
 */
class ClimateControllerTest {

    // -------------------------------------------------------------------------
    // computeTargetTemp
    // -------------------------------------------------------------------------

    @Test
    fun computeTargetTemp_singleOccupant_noAdjustment() {
        assertEquals(22.0f, ClimateController.computeTargetTemp(22.0f, 1), 0.001f)
    }

    @Test
    fun computeTargetTemp_twoOccupants() {
        assertEquals(21.5f, ClimateController.computeTargetTemp(22.0f, 2), 0.001f)
    }

    @Test
    fun computeTargetTemp_threeOccupants() {
        assertEquals(21.0f, ClimateController.computeTargetTemp(22.0f, 3), 0.001f)
    }

    @Test
    fun computeTargetTemp_fourOccupants() {
        assertEquals(20.5f, ClimateController.computeTargetTemp(22.0f, 4), 0.001f)
    }

    @Test
    fun computeTargetTemp_fivePlusOccupants_capped() {
        // 5 occupants: 4 additional * 0.5 = 2.0 (hits max), 22 - 2 = 20
        assertEquals(20.0f, ClimateController.computeTargetTemp(22.0f, 5), 0.001f)
        // 8 occupants: still capped at 2.0 max adjustment
        assertEquals(20.0f, ClimateController.computeTargetTemp(22.0f, 8), 0.001f)
    }

    @Test
    fun computeTargetTemp_zeroOccupants_baseTemp() {
        // 0 occupants: no additional, returns base
        assertEquals(22.0f, ClimateController.computeTargetTemp(22.0f, 0), 0.001f)
    }

    @Test
    fun computeTargetTemp_customBase() {
        // base=24, 3 occupants: 2 additional * 0.5 = 1.0, 24 - 1 = 23
        assertEquals(23.0f, ClimateController.computeTargetTemp(24.0f, 3), 0.001f)
    }

    @Test
    fun computeTargetTemp_clampedToMin() {
        // Very low base with many occupants — should not go below MIN_TEMP (16°C)
        assertEquals(16.0f, ClimateController.computeTargetTemp(16.5f, 5), 0.001f)
    }

    @Test
    fun computeTargetTemp_clampedToMax() {
        // Very high base — should not exceed MAX_TEMP (28°C)
        assertEquals(28.0f, ClimateController.computeTargetTemp(29.0f, 1), 0.001f)
    }

    // -------------------------------------------------------------------------
    // shouldAdjust
    // -------------------------------------------------------------------------

    @Test
    fun shouldAdjust_stableCountReachesThreshold() {
        // 5 stable frames, count differs from previous → should adjust
        assertTrue(ClimateController.shouldAdjust(3, 2, 5, 5))
    }

    @Test
    fun shouldAdjust_countChanges_resetsStability() {
        // Only 3 stable frames (below threshold of 5) → should not adjust
        assertFalse(ClimateController.shouldAdjust(3, 2, 3, 5))
    }

    @Test
    fun shouldAdjust_sameCountAsCurrent_noAdjust() {
        // Count equals previous — no change needed
        assertFalse(ClimateController.shouldAdjust(2, 2, 5, 5))
    }

    // -------------------------------------------------------------------------
    // rampStep
    // -------------------------------------------------------------------------

    @Test
    fun rampStep_movesTowardTarget() {
        // current=22, target=20, step=0.5 → 21.5
        assertEquals(21.5f, ClimateController.rampStep(22.0f, 20.0f, 0.5f), 0.001f)
    }

    @Test
    fun rampStep_movesUpTowardTarget() {
        // current=20, target=22, step=0.5 → 20.5
        assertEquals(20.5f, ClimateController.rampStep(20.0f, 22.0f, 0.5f), 0.001f)
    }

    @Test
    fun rampStep_doesNotOvershoot() {
        // current=21.3, target=21.0, step=0.5 → 21.0 (not 20.8)
        assertEquals(21.0f, ClimateController.rampStep(21.3f, 21.0f, 0.5f), 0.001f)
    }

    @Test
    fun rampStep_alreadyAtTarget() {
        assertEquals(20.0f, ClimateController.rampStep(20.0f, 20.0f, 0.5f), 0.001f)
    }

    // -------------------------------------------------------------------------
    // formatAlertMessage
    // -------------------------------------------------------------------------

    @Test
    fun formatAlertMessage_english() {
        val adj = ClimateAdjustment(21.0f, 3)
        val msg = ClimateController.formatAlertMessage(adj, isJapanese = false)
        assertEquals("Temperature adjusted to 21.0 degrees, 3 occupants", msg)
    }

    @Test
    fun formatAlertMessage_japanese() {
        val adj = ClimateAdjustment(21.0f, 3)
        val msg = ClimateController.formatAlertMessage(adj, isJapanese = true)
        assertEquals("空調21.0度に調整、乗員3名", msg)
    }

    @Test
    fun formatAlertMessage_singleOccupant_english() {
        val adj = ClimateAdjustment(22.0f, 1)
        val msg = ClimateController.formatAlertMessage(adj, isJapanese = false)
        assertEquals("Temperature adjusted to 22.0 degrees, 1 occupants", msg)
    }

    // -------------------------------------------------------------------------
    // computeZoneTemps (per-zone HVAC)
    // -------------------------------------------------------------------------

    @Test
    fun computeZoneTemps_driverOnly_leftDrive() {
        val seatMap = SeatMap(
            driver = SeatState(true, "Upright"),
            frontPassenger = SeatState(false),
            rearLeft = SeatState(false),
            rearCenter = SeatState(false),
            rearRight = SeatState(false)
        )
        val zones = ClimateController.computeZoneTemps(21.0f, seatMap, "left")
        // Driver zone: target temp, all others: eco (target + 3 = 24)
        assertEquals(21.0f, zones[Config.HVAC_AREA_ROW1_LEFT]!!, 0.001f)
        assertEquals(24.0f, zones[Config.HVAC_AREA_ROW1_RIGHT]!!, 0.001f)
        assertEquals(24.0f, zones[Config.HVAC_AREA_ROW2_LEFT]!!, 0.001f)
        assertEquals(24.0f, zones[Config.HVAC_AREA_ROW2_RIGHT]!!, 0.001f)
    }

    @Test
    fun computeZoneTemps_allOccupied() {
        val seatMap = SeatMap(
            driver = SeatState(true),
            frontPassenger = SeatState(true),
            rearLeft = SeatState(true),
            rearCenter = SeatState(false),
            rearRight = SeatState(true)
        )
        val zones = ClimateController.computeZoneTemps(20.0f, seatMap, "left")
        // All zones: target temp
        for ((_, temp) in zones) {
            assertEquals(20.0f, temp, 0.001f)
        }
    }

    @Test
    fun computeZoneTemps_empty_allEco() {
        val seatMap = SeatMap()
        val zones = ClimateController.computeZoneTemps(22.0f, seatMap, "left")
        for ((_, temp) in zones) {
            assertEquals(25.0f, temp, 0.001f) // 22 + 3 eco offset
        }
    }

    @Test
    fun computeZoneTemps_rearCenter_mapsToRearZones() {
        val seatMap = SeatMap(
            driver = SeatState(false),
            frontPassenger = SeatState(false),
            rearLeft = SeatState(false),
            rearCenter = SeatState(true, "Upright"),
            rearRight = SeatState(false)
        )
        val zones = ClimateController.computeZoneTemps(21.0f, seatMap, "left")
        // Front zones: eco, rear zones: target (rear center maps to both)
        assertEquals(24.0f, zones[Config.HVAC_AREA_ROW1_LEFT]!!, 0.001f)
        assertEquals(24.0f, zones[Config.HVAC_AREA_ROW1_RIGHT]!!, 0.001f)
        assertEquals(21.0f, zones[Config.HVAC_AREA_ROW2_LEFT]!!, 0.001f)
        assertEquals(21.0f, zones[Config.HVAC_AREA_ROW2_RIGHT]!!, 0.001f)
    }

    @Test
    fun computeZoneTemps_ecoClampedAtMax() {
        // target=27, eco offset=3 → 30 but capped at 28 (CLIMATE_MAX_TEMP_C)
        val seatMap = SeatMap(
            driver = SeatState(true),
            frontPassenger = SeatState(false),
            rearLeft = SeatState(false),
            rearCenter = SeatState(false),
            rearRight = SeatState(false)
        )
        val zones = ClimateController.computeZoneTemps(27.0f, seatMap, "left")
        assertEquals(27.0f, zones[Config.HVAC_AREA_ROW1_LEFT]!!, 0.001f)
        assertEquals(28.0f, zones[Config.HVAC_AREA_ROW1_RIGHT]!!, 0.001f) // capped
    }

    @Test
    fun computeZoneTemps_rightDrive_driverMapsToRight() {
        val seatMap = SeatMap(
            driver = SeatState(true, "Upright"),
            frontPassenger = SeatState(false),
            rearLeft = SeatState(false),
            rearCenter = SeatState(false),
            rearRight = SeatState(false)
        )
        val zones = ClimateController.computeZoneTemps(21.0f, seatMap, "right")
        // Right-hand drive: driver area is ROW1_RIGHT
        assertEquals(21.0f, zones[Config.HVAC_AREA_ROW1_RIGHT]!!, 0.001f)
        assertEquals(24.0f, zones[Config.HVAC_AREA_ROW1_LEFT]!!, 0.001f)
    }
}
