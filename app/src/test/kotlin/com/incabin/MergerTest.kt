package com.incabin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MergerTest {

    // -------------------------------------------------------------------------
    // Risk Scoring Tests (14 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_all_clear_returns_low() {
        val risk = computeRisk(
            driverUsingPhone = false,
            driverEyesClosed = false,
            dangerousPosture = false,
            childSlouching = false
        )
        assertEquals("low", risk)
    }

    @Test
    fun test_phone_only_returns_high() {
        val risk = computeRisk(
            driverUsingPhone = true,
            driverEyesClosed = false,
            dangerousPosture = false,
            childSlouching = false
        )
        assertEquals("high", risk)
    }

    @Test
    fun test_eyes_closed_only_returns_high() {
        val risk = computeRisk(
            driverUsingPhone = false,
            driverEyesClosed = true,
            dangerousPosture = false,
            childSlouching = false
        )
        assertEquals("high", risk)
    }

    @Test
    fun test_posture_only_returns_medium() {
        val risk = computeRisk(
            driverUsingPhone = false,
            driverEyesClosed = false,
            dangerousPosture = true,
            childSlouching = false
        )
        assertEquals("medium", risk)
    }

    @Test
    fun test_child_slouching_only_returns_medium() {
        val risk = computeRisk(
            driverUsingPhone = false,
            driverEyesClosed = false,
            dangerousPosture = false,
            childSlouching = true
        )
        assertEquals("medium", risk)
    }

    @Test
    fun test_phone_and_eyes_returns_high() {
        val risk = computeRisk(
            driverUsingPhone = true,
            driverEyesClosed = true,
            dangerousPosture = false,
            childSlouching = false
        )
        assertEquals("high", risk)
    }

    @Test
    fun test_posture_and_slouching_returns_high() {
        // score = posture(2) + slouch(1) = 3
        val risk = computeRisk(
            driverUsingPhone = false,
            driverEyesClosed = false,
            dangerousPosture = true,
            childSlouching = true
        )
        assertEquals("high", risk)
    }

    @Test
    fun test_all_true_returns_high() {
        val risk = computeRisk(
            driverUsingPhone = true,
            driverEyesClosed = true,
            dangerousPosture = true,
            childSlouching = true
        )
        assertEquals("high", risk)
    }

    @Test
    fun test_yawning_only_returns_medium() {
        val risk = computeRisk(
            driverUsingPhone = false,
            driverEyesClosed = false,
            dangerousPosture = false,
            childSlouching = false,
            driverYawning = true
        )
        assertEquals("medium", risk)
    }

    @Test
    fun test_distracted_only_returns_medium() {
        val risk = computeRisk(
            driverUsingPhone = false,
            driverEyesClosed = false,
            dangerousPosture = false,
            childSlouching = false,
            driverDistracted = true
        )
        assertEquals("medium", risk)
    }

    @Test
    fun test_eating_only_returns_medium() {
        val risk = computeRisk(
            driverUsingPhone = false,
            driverEyesClosed = false,
            dangerousPosture = false,
            childSlouching = false,
            driverEatingDrinking = true
        )
        assertEquals("medium", risk)
    }

    @Test
    fun test_yawning_plus_posture_returns_high() {
        // score = yawning(2) + posture(2) = 4
        val risk = computeRisk(
            driverUsingPhone = false,
            driverEyesClosed = false,
            dangerousPosture = true,
            childSlouching = false,
            driverYawning = true
        )
        assertEquals("high", risk)
    }

    @Test
    fun test_distracted_plus_eating_returns_high() {
        // score = distracted(2) + eating(1) = 3
        val risk = computeRisk(
            driverUsingPhone = false,
            driverEyesClosed = false,
            dangerousPosture = false,
            childSlouching = false,
            driverDistracted = true,
            driverEatingDrinking = true
        )
        assertEquals("high", risk)
    }

    @Test
    fun test_all_new_fields_returns_high() {
        // score = yawning(2) + distracted(2) + eating(1) = 5
        val risk = computeRisk(
            driverUsingPhone = false,
            driverEyesClosed = false,
            dangerousPosture = false,
            childSlouching = false,
            driverYawning = true,
            driverDistracted = true,
            driverEatingDrinking = true
        )
        assertEquals("high", risk)
    }

    // -------------------------------------------------------------------------
    // Merge Results Tests (5 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_merges_all_fields() {
        val face = FaceResult(
            driverEyesClosed = true,
            earValue = 0.15f,
            driverYawning = false,
            marValue = 0.3f,
            driverDistracted = false,
            headYaw = 5.0f,
            headPitch = 3.0f
        )
        val pose = PoseResult(
            passengerCount = 2,
            driverUsingPhone = true,
            dangerousPosture = false,
            childPresent = true,
            childSlouching = false,
            driverEatingDrinking = false
        )
        val result = mergeResults(face, pose)

        assertTrue(result.driverEyesClosed)
        assertEquals(2, result.passengerCount)
        assertTrue(result.driverUsingPhone)
        assertFalse(result.dangerousPosture)
        assertTrue(result.childPresent)
        assertFalse(result.childSlouching)
        assertFalse(result.driverYawning)
        assertFalse(result.driverDistracted)
        assertFalse(result.driverEatingDrinking)
        assertNotNull(result.timestamp)
        assertTrue(result.timestamp.isNotEmpty())
        // phone(3) + eyes(3) = 6 >= 3 -> "high"
        assertEquals("high", result.riskLevel)
    }

    @Test
    fun test_ear_value_passed_through() {
        val face = FaceResult(
            driverEyesClosed = false,
            earValue = 0.28f,
            driverYawning = false,
            marValue = 0.2f,
            driverDistracted = false,
            headYaw = 0.0f,
            headPitch = 0.0f
        )
        val pose = PoseResult(
            passengerCount = 1,
            driverUsingPhone = false,
            dangerousPosture = false,
            childPresent = false,
            childSlouching = false,
            driverEatingDrinking = false
        )
        val result = mergeResults(face, pose)
        assertEquals(0.28f, result.earValue)
    }

    @Test
    fun test_ear_value_none_when_no_face() {
        val face = FaceResult(
            driverEyesClosed = false,
            earValue = null,
            driverYawning = false,
            marValue = null,
            driverDistracted = false,
            headYaw = null,
            headPitch = null
        )
        val pose = PoseResult(
            passengerCount = 1,
            driverUsingPhone = false,
            dangerousPosture = false,
            childPresent = false,
            childSlouching = false,
            driverEatingDrinking = false
        )
        val result = mergeResults(face, pose)
        assertNull(result.earValue)
        assertNull(result.marValue)
        assertNull(result.headYaw)
        assertNull(result.headPitch)
    }

    @Test
    fun test_defaults_when_keys_missing() {
        // FaceResult() and PoseResult() use default values
        val face = FaceResult()
        val pose = PoseResult()
        val result = mergeResults(face, pose)

        assertFalse(result.driverEyesClosed)
        assertEquals(0, result.passengerCount)
        assertNull(result.earValue)
        assertFalse(result.driverYawning)
        assertFalse(result.driverDistracted)
        assertFalse(result.driverEatingDrinking)
    }

    @Test
    fun test_new_diagnostic_values_passed_through() {
        val face = FaceResult(
            driverEyesClosed = false,
            earValue = 0.25f,
            driverYawning = true,
            marValue = 0.65f,
            driverDistracted = true,
            headYaw = 35.0f,
            headPitch = -10.0f
        )
        val pose = PoseResult(
            passengerCount = 1,
            driverUsingPhone = false,
            dangerousPosture = false,
            childPresent = false,
            childSlouching = false,
            driverEatingDrinking = true
        )
        val result = mergeResults(face, pose)

        assertEquals(0.65f, result.marValue)
        assertEquals(35.0f, result.headYaw)
        assertEquals(-10.0f, result.headPitch)
        assertTrue(result.driverYawning)
        assertTrue(result.driverDistracted)
        assertTrue(result.driverEatingDrinking)
        // yawning(2) + distracted(2) + eating(1) = 5 >= 3 -> "high"
        assertEquals("high", result.riskLevel)
    }
}
