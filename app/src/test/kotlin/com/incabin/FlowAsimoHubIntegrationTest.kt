package com.incabin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end integration tests for the ASIMO Companion Hub:
 * pose+face → merge → smooth → AsimoHub resolution.
 * Verifies that real detection sequences produce correct ASIMO poses,
 * glow colors, labels, and hub modes.
 */
class FlowAsimoHubIntegrationTest {

    // -------------------------------------------------------------------------
    // Full pipeline: merge → smooth → AsimoHub (6 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_phone_detection_pipeline() {
        val smoother = TemporalSmoother()
        val face = FaceResult(earValue = 0.25f, marValue = 0.2f, headYaw = 0f, headPitch = 0f)
        val pose = PoseResult(passengerCount = 1, driverUsingPhone = true)

        // Need 3 frames of phone to pass smoother (sustained threshold)
        lateinit var smoothed: OutputResult
        repeat(3) {
            val merged = mergeResults(face, pose)
            smoothed = smoother.smooth(merged)
        }

        // After 3 sustained frames, phone should be smoothed through
        // Note: phone has NO sustained threshold in smoother (it's a standard field, just majority)
        // But we need buffer full (3 frames) with 2/3 majority
        assertTrue("Phone should pass through smoother", smoothed.driverUsingPhone)

        // AsimoHub should resolve to phone detection
        val detectionKey = AsimoHub.resolveDetectionKey(smoothed)
        assertEquals("driverUsingPhone", detectionKey)

        // Glow should be danger (phone weight=3 → high risk)
        assertEquals("high", smoothed.riskLevel)
        assertEquals(AsimoHub.GlowCategory.DANGER, AsimoHub.resolveGlowCategory(smoothed.riskLevel))
        assertTrue(AsimoHub.shouldPulse(smoothed.riskLevel))

        // Label should say PHONE DETECTED
        assertEquals("PHONE DETECTED", AsimoHub.getDetectionLabel(detectionKey, "en"))
        assertTrue(AsimoHub.isDangerField(detectionKey))
    }

    @Test
    fun test_yawning_detection_pipeline() {
        val smoother = TemporalSmoother()
        val face = FaceResult(
            earValue = 0.25f, marValue = 0.6f,  // MAR > 0.5 → yawning
            driverYawning = true, headYaw = 0f, headPitch = 0f
        )
        val pose = PoseResult(passengerCount = 1)

        // Yawning fires on first sustained frame (YAWNING_MIN_FRAMES = 1), feed 3 for full window
        lateinit var smoothed: OutputResult
        repeat(3) {
            smoothed = smoother.smooth(mergeResults(face, pose))
        }

        assertTrue("Yawning should pass through smoother", smoothed.driverYawning)

        val detectionKey = AsimoHub.resolveDetectionKey(smoothed)
        assertEquals("driverYawning", detectionKey)

        // Risk: yawning weight=2 → medium
        assertEquals("medium", smoothed.riskLevel)
        assertEquals(AsimoHub.GlowCategory.CAUTION, AsimoHub.resolveGlowCategory(smoothed.riskLevel))
        assertFalse(AsimoHub.shouldPulse(smoothed.riskLevel))
    }

    @Test
    fun test_all_clear_pipeline() {
        val smoother = TemporalSmoother()
        val face = FaceResult(earValue = 0.25f, marValue = 0.2f, headYaw = 0f, headPitch = 0f)
        val pose = PoseResult(passengerCount = 1)

        lateinit var smoothed: OutputResult
        repeat(3) {
            smoothed = smoother.smooth(mergeResults(face, pose))
        }

        assertFalse(smoothed.driverUsingPhone)
        assertFalse(smoothed.driverEyesClosed)
        assertEquals("low", smoothed.riskLevel)

        val detectionKey = AsimoHub.resolveDetectionKey(smoothed)
        assertEquals("", detectionKey)
        assertNull(AsimoHub.getDetectionLabel(detectionKey, "en"))
        assertEquals(AsimoHub.GlowCategory.SAFE, AsimoHub.resolveGlowCategory(smoothed.riskLevel))
    }

    @Test
    fun test_driver_absent_pipeline() {
        val smoother = TemporalSmoother()
        val face = FaceResult.NO_FACE
        val pose = PoseResult(passengerCount = 2, driverDetected = false)

        lateinit var smoothed: OutputResult
        repeat(3) {
            smoothed = smoother.smooth(mergeResults(face, pose))
        }

        assertFalse(smoothed.driverDetected)
        assertEquals("low", smoothed.riskLevel)
        val detectionKey = AsimoHub.resolveDetectionKey(smoothed)
        assertEquals("", detectionKey)
    }

    @Test
    fun test_multiple_detections_priority() {
        val smoother = TemporalSmoother()
        val face = FaceResult(
            driverEyesClosed = true, earValue = 0.15f,
            driverDistracted = true, headYaw = 45f, headPitch = 0f, marValue = 0.2f
        )
        val pose = PoseResult(passengerCount = 1, dangerousPosture = true)

        // Need enough frames for sustained thresholds
        lateinit var smoothed: OutputResult
        repeat(5) {
            smoothed = smoother.smooth(mergeResults(face, pose))
        }

        // Eyes closed should be highest priority among active detections
        val key = AsimoHub.resolveDetectionKey(smoothed)
        assertEquals("driverEyesClosed", key)
        assertTrue(AsimoHub.isDangerField(key))
    }

    @Test
    fun test_child_slouching_pipeline() {
        val smoother = TemporalSmoother()
        val face = FaceResult(earValue = 0.25f, marValue = 0.2f, headYaw = 0f, headPitch = 0f)
        val pose = PoseResult(
            passengerCount = 2, childPresent = true, childSlouching = true
        )

        // Child slouching needs 5 sustained frames (CHILD_SLOUCH_MIN_FRAMES = 5)
        lateinit var smoothed: OutputResult
        repeat(6) {
            smoothed = smoother.smooth(mergeResults(face, pose))
        }

        assertTrue("Child slouching should pass after 5+ frames", smoothed.childSlouching)
        val key = AsimoHub.resolveDetectionKey(smoothed)
        assertEquals("childSlouching", key)
        assertFalse(AsimoHub.isDangerField(key))
        assertEquals("CHILD SLOUCHING", AsimoHub.getDetectionLabel(key, "en"))
    }

    // -------------------------------------------------------------------------
    // Hub mode with different configs (3 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_hub_full_mode_routes_to_bubble() {
        assertEquals(AsimoHub.HubMode.FULL, AsimoHub.resolveHubMode(false))
        assertEquals("bubble", AsimoHub.resolveStatusTarget(false))
    }

    @Test
    fun test_hub_compact_mode_routes_to_overlay() {
        assertEquals(AsimoHub.HubMode.COMPACT, AsimoHub.resolveHubMode(true))
        assertEquals("overlay", AsimoHub.resolveStatusTarget(true))
    }

    @Test
    fun test_score_penalty_integrates_with_detection() {
        val face = FaceResult(
            driverEyesClosed = true, earValue = 0.15f,
            marValue = 0.2f, headYaw = 0f, headPitch = 0f
        )
        val pose = PoseResult(passengerCount = 1, driverUsingPhone = true)
        val merged = mergeResults(face, pose)

        // Phone + Eyes = 2.0 + 2.0 = 4.0 penalty
        assertEquals(4.0f, AsimoHub.computeScorePenalty(merged), 0.001f)
        assertTrue(AsimoHub.isDistracted(merged))
    }

    // -------------------------------------------------------------------------
    // Japanese labels integration (2 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_japanese_label_for_phone() {
        val result = OutputResult(
            timestamp = "2026-01-01T00:00:00Z", passengerCount = 1,
            driverUsingPhone = true, driverEyesClosed = false, driverYawning = false,
            driverDistracted = false, driverEatingDrinking = false, dangerousPosture = false,
            childPresent = false, childSlouching = false, riskLevel = "high",
            earValue = 0.25f, marValue = 0.2f, headYaw = 0f, headPitch = 0f,
            distractionDurationS = 0
        )
        val key = AsimoHub.resolveDetectionKey(result)
        assertEquals("スマホ検出", AsimoHub.getDetectionLabel(key, "ja"))
    }

    @Test
    fun test_japanese_label_for_eyes_closed() {
        val result = OutputResult(
            timestamp = "2026-01-01T00:00:00Z", passengerCount = 1,
            driverUsingPhone = false, driverEyesClosed = true, driverYawning = false,
            driverDistracted = false, driverEatingDrinking = false, dangerousPosture = false,
            childPresent = false, childSlouching = false, riskLevel = "high",
            earValue = 0.15f, marValue = 0.2f, headYaw = 0f, headPitch = 0f,
            distractionDurationS = 0
        )
        val key = AsimoHub.resolveDetectionKey(result)
        assertEquals("目を閉じている", AsimoHub.getDetectionLabel(key, "ja"))
    }
}
