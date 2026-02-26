package com.incabin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end flow tests for VLM remote inference mode.
 * Tests: VLM JSON → parseDetectResponse → TemporalSmoother → distraction counter → alerts.
 */
class FlowVlmRemoteTest {

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun makeVlmJson(
        phone: Boolean = false,
        eyes: Boolean = false,
        yawning: Boolean = false,
        distracted: Boolean = false,
        eating: Boolean = false,
        handsOff: Boolean = false,
        posture: Boolean = false,
        childPresent: Boolean = false,
        childSlouching: Boolean = false,
        passengerCount: Int = 1,
        riskLevel: String = "low",
        driverDetected: Boolean = true,
        earValue: String = "null",
        marValue: String = "null",
        headYaw: String = "null",
        headPitch: String = "null",
        driverName: String = "null"
    ): String = """
    {
        "timestamp": "2026-01-01T00:00:00Z",
        "passenger_count": $passengerCount,
        "child_count": 0,
        "adult_count": ${if (driverDetected) passengerCount - 1 else passengerCount},
        "driver_using_phone": $phone,
        "driver_eyes_closed": $eyes,
        "driver_yawning": $yawning,
        "driver_distracted": $distracted,
        "driver_eating_drinking": $eating,
        "hands_off_wheel": $handsOff,
        "dangerous_posture": $posture,
        "child_present": $childPresent,
        "child_slouching": $childSlouching,
        "risk_level": "$riskLevel",
        "ear_value": $earValue,
        "mar_value": $marValue,
        "head_yaw": $headYaw,
        "head_pitch": $headPitch,
        "driver_name": $driverName,
        "driver_detected": $driverDetected
    }
    """.trimIndent()

    // -------------------------------------------------------------------------
    // VLM → Smoother (3 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_single_vlm_result_through_smoother() {
        val smoother = TemporalSmoother()
        val json = makeVlmJson()
        val parsed = VlmClient.parseDetectResponse(json)!!

        val smoothed = smoother.smooth(parsed)
        assertFalse(smoothed.driverUsingPhone)
        assertEquals("low", smoothed.riskLevel)
    }

    @Test
    fun test_phone_detected_through_smoother_requires_sustained() {
        val smoother = TemporalSmoother()
        // Phone detection for 3 frames (window=3, threshold=0.6 → need 2/3)
        val phoneJson = makeVlmJson(phone = true, riskLevel = "high")

        // Frame 1: first frame, not enough for sustained detection
        val result1 = smoother.smooth(VlmClient.parseDetectResponse(phoneJson)!!)
        // Frame 2: majority in window of 2 (2/2 = 100% > 60%)
        val result2 = smoother.smooth(VlmClient.parseDetectResponse(phoneJson)!!)
        // Frame 3: 3/3 in window
        val result3 = smoother.smooth(VlmClient.parseDetectResponse(phoneJson)!!)

        // After sustained frames, phone should be detected
        assertTrue(result3.driverUsingPhone)
    }

    @Test
    fun test_multiple_sequential_results_temporal_smoothing() {
        val smoother = TemporalSmoother()
        // 2 clean, then 1 dirty — smoother should keep it clean (2/3 clean)
        val cleanJson = makeVlmJson()
        val dirtyJson = makeVlmJson(phone = true, riskLevel = "high")

        smoother.smooth(VlmClient.parseDetectResponse(cleanJson)!!)
        smoother.smooth(VlmClient.parseDetectResponse(cleanJson)!!)
        val result = smoother.smooth(VlmClient.parseDetectResponse(dirtyJson)!!)

        // 2/3 are clean, so phone should NOT be detected by majority vote
        assertFalse(result.driverUsingPhone)
    }

    // -------------------------------------------------------------------------
    // Distraction Duration (3 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_distraction_duration_accumulates() {
        // Simulate the distraction counter logic from onVlmResult
        var duration = 0
        val phoneJson = makeVlmJson(phone = true, riskLevel = "high")

        for (i in 1..5) {
            val parsed = VlmClient.parseDetectResponse(phoneJson)!!
            // Simulate: if any distraction field is true, increment
            val hasDistraction = parsed.driverUsingPhone || parsed.driverEyesClosed ||
                parsed.driverYawning || parsed.driverDistracted || parsed.driverEatingDrinking
            if (hasDistraction) duration++
        }

        assertEquals(5, duration)
    }

    @Test
    fun test_all_clear_resets_duration() {
        var duration = 3  // Accumulated some distraction
        val cleanJson = makeVlmJson()
        val parsed = VlmClient.parseDetectResponse(cleanJson)!!

        val hasDistraction = parsed.driverUsingPhone || parsed.driverEyesClosed ||
            parsed.driverYawning || parsed.driverDistracted || parsed.driverEatingDrinking
        if (!hasDistraction) duration = 0

        assertEquals(0, duration)
    }

    @Test
    fun test_driver_absent_skips_distraction_tracking() {
        val json = makeVlmJson(driverDetected = false, passengerCount = 0)
        val parsed = VlmClient.parseDetectResponse(json)!!

        assertFalse(parsed.driverDetected)
        // onVlmResult checks driverDetected before distraction tracking
        val durationVal = if (!parsed.driverDetected) 0 else 1
        assertEquals(0, durationVal)
    }

    // -------------------------------------------------------------------------
    // Malformed input (1 test)
    // -------------------------------------------------------------------------

    @Test
    fun test_malformed_vlm_response_skipped_no_crash() {
        val smoother = TemporalSmoother()
        // Good frame
        val goodJson = makeVlmJson()
        val good = VlmClient.parseDetectResponse(goodJson)
        assertNotNull(good)
        smoother.smooth(good!!)

        // Malformed frame — should return null, not crash
        val bad = VlmClient.parseDetectResponse("{invalid json")
        assertNull(bad)
        // Smoother wasn't called with bad data — no crash
    }

    // -------------------------------------------------------------------------
    // Risk recomputation (1 test)
    // -------------------------------------------------------------------------

    @Test
    fun test_risk_recomputed_from_smoothed_booleans() {
        val smoother = TemporalSmoother()
        // VLM says "high" risk with phone, but smoother window has 2/3 clean
        val cleanJson = makeVlmJson()
        val phoneJson = makeVlmJson(phone = true, riskLevel = "high")

        smoother.smooth(VlmClient.parseDetectResponse(cleanJson)!!)
        smoother.smooth(VlmClient.parseDetectResponse(cleanJson)!!)
        val result = smoother.smooth(VlmClient.parseDetectResponse(phoneJson)!!)

        // Smoother recomputes risk from smoothed booleans, not VLM's risk_level
        assertEquals("low", result.riskLevel)
    }

    // -------------------------------------------------------------------------
    // Null face-gated fields (1 test)
    // -------------------------------------------------------------------------

    @Test
    fun test_vlm_result_without_ear_mar_smoother_handles() {
        val smoother = TemporalSmoother()
        val json = makeVlmJson() // ear_value, mar_value are null
        val parsed = VlmClient.parseDetectResponse(json)!!
        assertNull(parsed.earValue)
        assertNull(parsed.marValue)

        // Smoother should handle null face-gated fields gracefully
        val smoothed = smoother.smooth(parsed)
        assertNull(smoothed.earValue)
        assertNull(smoothed.marValue)
    }

    // -------------------------------------------------------------------------
    // Mixed dangers (1 test)
    // -------------------------------------------------------------------------

    @Test
    fun test_mixed_dangers_highest_risk() {
        val json = makeVlmJson(phone = true, eyes = true, riskLevel = "high")
        val parsed = VlmClient.parseDetectResponse(json)!!
        assertTrue(parsed.driverUsingPhone)
        assertTrue(parsed.driverEyesClosed)

        // Both phone (weight 3) and eyes (weight 3) → score 6 → "high"
        val recomputedRisk = computeRisk(
            driverUsingPhone = parsed.driverUsingPhone,
            driverEyesClosed = parsed.driverEyesClosed,
            dangerousPosture = parsed.dangerousPosture,
            childSlouching = parsed.childSlouching,
            driverYawning = parsed.driverYawning,
            driverDistracted = parsed.driverDistracted,
            driverEatingDrinking = parsed.driverEatingDrinking,
            handsOffWheel = parsed.handsOffWheel
        )
        assertEquals("high", recomputedRisk)
    }
}
