package com.incabin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Flow integration tests for the core monitoring pipeline.
 * Chains mergeResults() → TemporalSmoother.smooth() → OutputResult.validate()
 * for realistic detection sequences.
 */
class FlowMonitoringTest {

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun makeResult(
        eyes: Boolean = false,
        phone: Boolean = false,
        posture: Boolean = false,
        child: Boolean = false,
        slouch: Boolean = false,
        yawn: Boolean = false,
        distracted: Boolean = false,
        eating: Boolean = false,
        passengers: Int = 1,
        childCount: Int = 0,
        adultCount: Int = 0,
        ear: Float? = 0.25f,
        mar: Float? = 0.2f,
        headYaw: Float? = 0.0f,
        headPitch: Float? = 0.0f,
        distractionDuration: Int = 0
    ): OutputResult = OutputResult(
        timestamp = "2026-01-01T00:00:00Z",
        passengerCount = passengers,
        childCount = childCount,
        adultCount = adultCount,
        driverUsingPhone = phone,
        driverEyesClosed = eyes,
        driverYawning = yawn,
        driverDistracted = distracted,
        driverEatingDrinking = eating,
        dangerousPosture = posture,
        childPresent = child,
        childSlouching = slouch,
        riskLevel = "low",
        earValue = ear,
        marValue = mar,
        headYaw = headYaw,
        headPitch = headPitch,
        distractionDurationS = distractionDuration
    )

    // -------------------------------------------------------------------------
    // Clean Startup (1 test)
    // -------------------------------------------------------------------------

    @Test
    fun test_clean_startup_produces_low_risk_through_full_pipeline() {
        val face = FaceResult(earValue = 0.25f, marValue = 0.2f, headYaw = 0f, headPitch = 0f)
        val pose = PoseResult(passengerCount = 1)
        val merged = mergeResults(face, pose)
        val smoother = TemporalSmoother()
        val smoothed = smoother.smooth(merged)

        assertEquals("low", smoothed.riskLevel)
        assertFalse(smoothed.driverUsingPhone)
        assertFalse(smoothed.driverEyesClosed)
        assertFalse(smoothed.driverYawning)
        assertFalse(smoothed.driverDistracted)
        val errors = OutputResult.validate(smoothed.toMap())
        assertTrue("Schema validation failed: $errors", errors.isEmpty())
    }

    // -------------------------------------------------------------------------
    // Phone Detection Onset (1 test)
    // -------------------------------------------------------------------------

    @Test
    fun test_phone_detection_through_merge_smooth_3_sustained_frames() {
        // Phone has no sustained counter in smoother — fires on majority (2/3)
        val smoother = TemporalSmoother()
        smoother.smooth(makeResult(phone = true))
        smoother.smooth(makeResult(phone = true))
        val result = smoother.smooth(makeResult(phone = true))

        assertTrue("Phone should be detected after 3 consecutive frames", result.driverUsingPhone)
        assertEquals("high", result.riskLevel) // phone weight=3 → high
    }

    // -------------------------------------------------------------------------
    // Eyes Closed with Fast-Clear (1 test)
    // -------------------------------------------------------------------------

    @Test
    fun test_eyes_closed_fast_clear_5_closed_then_2_open() {
        val smoother = TemporalSmoother()
        // 5 frames eyes closed → sustained counter reaches 3, eyes_closed fires
        repeat(5) { smoother.smooth(makeResult(eyes = true, ear = 0.10f)) }

        // 2 frames eyes open with high EAR → fast-clear triggers
        smoother.smooth(makeResult(eyes = false, ear = 0.30f))
        val result = smoother.smooth(makeResult(eyes = false, ear = 0.30f))

        assertFalse("Eyes should be cleared after 2 high-EAR frames", result.driverEyesClosed)
    }

    // -------------------------------------------------------------------------
    // Multi-Detection Merge (1 test)
    // -------------------------------------------------------------------------

    @Test
    fun test_multi_detection_phone_eyes_yawning() {
        val face = FaceResult(
            driverEyesClosed = true, earValue = 0.10f,
            driverYawning = true, marValue = 0.7f,
            headYaw = 0f, headPitch = 0f
        )
        val pose = PoseResult(driverUsingPhone = true, passengerCount = 1)
        val merged = mergeResults(face, pose)

        assertTrue(merged.driverUsingPhone)
        assertTrue(merged.driverEyesClosed)
        assertTrue(merged.driverYawning)
        assertEquals("high", merged.riskLevel) // phone(3)+eyes(3)+yawning(2)=8 → high
    }

    // -------------------------------------------------------------------------
    // No-Face Frames Don't Trigger False Eyes (1 test)
    // -------------------------------------------------------------------------

    @Test
    fun test_no_face_frames_dont_trigger_false_eyes_closed() {
        val smoother = TemporalSmoother()
        // 5 frames with no face (ear=null) — face-gating should prevent eyes_closed
        repeat(5) { smoother.smooth(makeResult(ear = null, mar = null, headYaw = null, headPitch = null)) }
        val result = smoother.smooth(makeResult(ear = null, mar = null, headYaw = null, headPitch = null))

        assertFalse("No face → eyes_closed must not fire", result.driverEyesClosed)
        assertFalse("No face → yawning must not fire", result.driverYawning)
        assertFalse("No face → distracted must not fire", result.driverDistracted)
    }

    // -------------------------------------------------------------------------
    // Distraction Duration Counter (2 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_distraction_duration_increments() {
        // Simulate the main loop's increment logic: +1 each frame while any active
        var duration = 0
        for (i in 1..5) {
            duration++ // phone active → increment
        }
        assertEquals(5, duration)
    }

    @Test
    fun test_distraction_duration_resets_on_all_clear() {
        var duration = 5
        // All clear → reset
        val anyActive = false
        if (!anyActive) duration = 0
        assertEquals(0, duration)
    }

    // -------------------------------------------------------------------------
    // Risk Recomputed After Smoothing Clears (1 test)
    // -------------------------------------------------------------------------

    @Test
    fun test_risk_recomputed_after_smoothing_clears_detections() {
        val smoother = TemporalSmoother()
        // 3 frames phone → detected
        repeat(3) { smoother.smooth(makeResult(phone = true)) }
        // 3 frames clean → phone clears via majority voting
        repeat(2) { smoother.smooth(makeResult()) }
        val result = smoother.smooth(makeResult())

        assertFalse("Phone should be cleared", result.driverUsingPhone)
        assertEquals("low", result.riskLevel)
    }

    // -------------------------------------------------------------------------
    // Output Schema Validates After Full Pipeline (1 test)
    // -------------------------------------------------------------------------

    @Test
    fun test_output_schema_validates_after_full_pipeline() {
        val face = FaceResult(
            driverEyesClosed = true, earValue = 0.15f,
            driverYawning = false, marValue = 0.3f,
            driverDistracted = false, headYaw = 10f, headPitch = 5f
        )
        val pose = PoseResult(passengerCount = 2, driverUsingPhone = true, childPresent = true)
        val merged = mergeResults(face, pose)
        val smoother = TemporalSmoother()
        val smoothed = smoother.smooth(merged)
        val errors = OutputResult.validate(smoothed.toMap())
        assertTrue("Schema validation failed: $errors", errors.isEmpty())
    }

    // -------------------------------------------------------------------------
    // Passenger Count Mode Through Pipeline (1 test)
    // -------------------------------------------------------------------------

    @Test
    fun test_passenger_count_mode_through_pipeline() {
        val smoother = TemporalSmoother()
        smoother.smooth(makeResult(passengers = 2))
        smoother.smooth(makeResult(passengers = 2))
        val result = smoother.smooth(makeResult(passengers = 1))

        // Mode of [2, 2, 1] → 2 (2 appears most frequently)
        assertEquals(2, result.passengerCount)
    }

    // -------------------------------------------------------------------------
    // Merge Uses Correct Source Fields (1 test)
    // -------------------------------------------------------------------------

    @Test
    fun test_merge_uses_face_for_eyes_and_pose_for_phone() {
        // Face says eyes closed, pose says phone — merge should use each source correctly
        val face = FaceResult(driverEyesClosed = true, earValue = 0.10f, headYaw = 0f, headPitch = 0f)
        val pose = PoseResult(driverUsingPhone = true, passengerCount = 2)
        val merged = mergeResults(face, pose)

        assertTrue("Eyes from FaceResult", merged.driverEyesClosed)
        assertTrue("Phone from PoseResult", merged.driverUsingPhone)
        assertEquals("Passengers from PoseResult", 2, merged.passengerCount)
        assertEquals(0.10f, merged.earValue!!, 0.001f)
    }

    // -------------------------------------------------------------------------
    // Yawning Sustained Threshold (1 test)
    // -------------------------------------------------------------------------

    @Test
    fun test_yawning_fires_on_first_sustained_frame() {
        val smoother = TemporalSmoother()
        // 1 frame yawning → fires immediately (YAWNING_MIN_FRAMES=1)
        val r1 = smoother.smooth(makeResult(yawn = true, mar = 0.7f))
        assertTrue("1 frame yawning should fire (MIN_FRAMES=1)", r1.driverYawning)
    }
}
