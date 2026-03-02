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
}
