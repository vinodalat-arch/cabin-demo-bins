package com.incabin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for score color thresholds, distraction field checks,
 * and score penalty computation.
 */
class ScoreAndDistractionTest {

    private fun makeResult(
        phone: Boolean = false,
        eyes: Boolean = false,
        yawn: Boolean = false,
        distracted: Boolean = false,
        eating: Boolean = false,
        posture: Boolean = false,
        slouch: Boolean = false
    ): OutputResult = OutputResult(
        timestamp = "2026-01-01T00:00:00Z",
        passengerCount = 1,
        driverUsingPhone = phone,
        driverEyesClosed = eyes,
        driverYawning = yawn,
        driverDistracted = distracted,
        driverEatingDrinking = eating,
        dangerousPosture = posture,
        childPresent = false,
        childSlouching = slouch,
        riskLevel = "low",
        earValue = 0.25f,
        marValue = 0.2f,
        headYaw = 0f,
        headPitch = 0f,
        distractionDurationS = 0
    )

    // -------------------------------------------------------------------------
    // scoreColorCategory (5 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_score_100_is_safe() {
        assertEquals(AsimoHub.ScoreColor.SAFE, AsimoHub.scoreColorCategory(100f))
    }

    @Test
    fun test_score_75_is_safe() {
        assertEquals(AsimoHub.ScoreColor.SAFE, AsimoHub.scoreColorCategory(75f))
    }

    @Test
    fun test_score_74_is_caution() {
        assertEquals(AsimoHub.ScoreColor.CAUTION, AsimoHub.scoreColorCategory(74.9f))
    }

    @Test
    fun test_score_40_is_caution() {
        assertEquals(AsimoHub.ScoreColor.CAUTION, AsimoHub.scoreColorCategory(40f))
    }

    @Test
    fun test_score_39_is_danger() {
        assertEquals(AsimoHub.ScoreColor.DANGER, AsimoHub.scoreColorCategory(39.9f))
    }

    @Test
    fun test_score_0_is_danger() {
        assertEquals(AsimoHub.ScoreColor.DANGER, AsimoHub.scoreColorCategory(0f))
    }

    // -------------------------------------------------------------------------
    // isDistracted — distraction field check (8 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_no_distraction_when_all_clear() {
        assertFalse(AsimoHub.isDistracted(makeResult()))
    }

    @Test
    fun test_phone_is_distraction() {
        assertTrue(AsimoHub.isDistracted(makeResult(phone = true)))
    }

    @Test
    fun test_eyes_closed_is_distraction() {
        assertTrue(AsimoHub.isDistracted(makeResult(eyes = true)))
    }

    @Test
    fun test_yawning_is_distraction() {
        assertTrue(AsimoHub.isDistracted(makeResult(yawn = true)))
    }

    @Test
    fun test_distracted_is_distraction() {
        assertTrue(AsimoHub.isDistracted(makeResult(distracted = true)))
    }

    @Test
    fun test_eating_is_distraction() {
        assertTrue(AsimoHub.isDistracted(makeResult(eating = true)))
    }

    @Test
    fun test_posture_is_NOT_distraction() {
        assertFalse(AsimoHub.isDistracted(makeResult(posture = true)))
    }

    @Test
    fun test_child_slouch_is_NOT_distraction() {
        assertFalse(AsimoHub.isDistracted(makeResult(slouch = true)))
    }

    // -------------------------------------------------------------------------
    // computeScorePenalty (8 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_penalty_all_clear_is_zero() {
        assertEquals(0f, AsimoHub.computeScorePenalty(makeResult()), 0.001f)
    }

    @Test
    fun test_penalty_phone_is_2() {
        assertEquals(2.0f, AsimoHub.computeScorePenalty(makeResult(phone = true)), 0.001f)
    }

    @Test
    fun test_penalty_eyes_is_2() {
        assertEquals(2.0f, AsimoHub.computeScorePenalty(makeResult(eyes = true)), 0.001f)
    }

    @Test
    fun test_penalty_distracted_is_1_5() {
        assertEquals(1.5f, AsimoHub.computeScorePenalty(makeResult(distracted = true)), 0.001f)
    }

    @Test
    fun test_penalty_yawning_is_1() {
        assertEquals(1.0f, AsimoHub.computeScorePenalty(makeResult(yawn = true)), 0.001f)
    }

    @Test
    fun test_penalty_eating_is_1() {
        assertEquals(1.0f, AsimoHub.computeScorePenalty(makeResult(eating = true)), 0.001f)
    }

    @Test
    fun test_penalty_posture_is_1() {
        assertEquals(1.0f, AsimoHub.computeScorePenalty(makeResult(posture = true)), 0.001f)
    }

    @Test
    fun test_penalty_child_slouch_is_0_5() {
        assertEquals(0.5f, AsimoHub.computeScorePenalty(makeResult(slouch = true)), 0.001f)
    }

    @Test
    fun test_penalty_all_active_sums_correctly() {
        val result = makeResult(
            phone = true, eyes = true, distracted = true, yawn = true,
            eating = true, posture = true, slouch = true
        )
        // 2.0 + 2.0 + 1.5 + 1.0 + 1.0 + 1.0 + 0.5 = 9.0
        assertEquals(9.0f, AsimoHub.computeScorePenalty(result), 0.001f)
    }

    @Test
    fun test_penalty_phone_plus_eyes_is_4() {
        val result = makeResult(phone = true, eyes = true)
        assertEquals(4.0f, AsimoHub.computeScorePenalty(result), 0.001f)
    }
}
