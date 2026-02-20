package com.incabin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TemporalSmootherTest {

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
        ear: Float? = 0.25f,
        mar: Float? = 0.2f,
        headYaw: Float? = 0.0f,
        headPitch: Float? = 0.0f
    ): OutputResult = OutputResult(
        timestamp = "2026-01-01T00:00:00+00:00",
        passengerCount = passengers,
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
        distractionDurationS = 0
    )

    // -------------------------------------------------------------------------
    // Basic Smoothing (4 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_single_frame_below_sustained_threshold() {
        // 1 frame of eyes_closed with low EAR: majority says true, but sustained counter < 3 → false
        val s = TemporalSmoother(windowSize = 5, threshold = 0.6f)
        val result = s.smooth(makeResult(eyes = true, ear = 0.10f))
        assertFalse(result.driverEyesClosed) // blink filtered out
    }

    @Test
    fun test_eyes_closed_sustained_threshold() {
        // 3 consecutive frames of eyes_closed with low EAR → sustained counter reaches 3 → true
        val s = TemporalSmoother(windowSize = 5, threshold = 0.6f)
        s.smooth(makeResult(eyes = true, ear = 0.10f))
        s.smooth(makeResult(eyes = true, ear = 0.10f))
        val result = s.smooth(makeResult(eyes = true, ear = 0.10f))
        assertTrue(result.driverEyesClosed)
    }

    @Test
    fun test_warm_property() {
        val s = TemporalSmoother(windowSize = 3, threshold = 0.6f)
        assertFalse(s.warm)

        s.smooth(makeResult())
        assertFalse(s.warm)

        s.smooth(makeResult())
        assertFalse(s.warm)

        s.smooth(makeResult())
        assertTrue(s.warm)
    }

    @Test
    fun test_majority_voting() {
        // 2/5 = 0.4 < 0.6 -> phone = false
        val s = TemporalSmoother(windowSize = 5, threshold = 0.6f)
        repeat(3) { s.smooth(makeResult(phone = false)) }
        var result: OutputResult? = null
        repeat(2) { result = s.smooth(makeResult(phone = true)) }
        assertFalse(result!!.driverUsingPhone)
    }

    @Test
    fun test_threshold_met() {
        // 3/5 = 0.6 >= 0.6 -> phone = true
        val s = TemporalSmoother(windowSize = 5, threshold = 0.6f)
        repeat(2) { s.smooth(makeResult(phone = false)) }
        var result: OutputResult? = null
        repeat(3) { result = s.smooth(makeResult(phone = true)) }
        assertTrue(result!!.driverUsingPhone)
    }

    // -------------------------------------------------------------------------
    // No-Face Handling (7 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_no_face_frames_skipped_for_eyes() {
        // face_frames=3, true_count=3, 3/3=1.0 >= 0.6 -> eyes_closed = true
        val s = TemporalSmoother(windowSize = 5, threshold = 0.6f)
        s.smooth(makeResult(eyes = true, ear = 0.10f))
        s.smooth(makeResult(eyes = false, ear = null))   // no face
        s.smooth(makeResult(eyes = true, ear = 0.08f))
        s.smooth(makeResult(eyes = false, ear = null))   // no face
        val result = s.smooth(makeResult(eyes = true, ear = 0.05f))
        assertTrue(result.driverEyesClosed)
    }

    @Test
    fun test_no_face_still_counts_for_phone() {
        // 2/5 = 0.4 < 0.6 -> phone = false (phone is NOT face-gated)
        val s = TemporalSmoother(windowSize = 5, threshold = 0.6f)
        s.smooth(makeResult(phone = true, ear = null))
        s.smooth(makeResult(phone = true, ear = 0.25f))
        s.smooth(makeResult(phone = false, ear = null))
        s.smooth(makeResult(phone = false, ear = 0.25f))
        val result = s.smooth(makeResult(phone = false, ear = null))
        assertFalse(result.driverUsingPhone)
    }

    @Test
    fun test_all_no_face_eyes_returns_false() {
        // 0 face frames -> default false
        val s = TemporalSmoother(windowSize = 3, threshold = 0.6f)
        var result: OutputResult? = null
        repeat(3) { result = s.smooth(makeResult(eyes = true, ear = null)) }
        assertFalse(result!!.driverEyesClosed)
    }

    @Test
    fun test_no_face_frames_skipped_for_yawning() {
        // face_frames=3, true_count=3, 3/3=1.0 >= 0.6 -> yawning = true
        val s = TemporalSmoother(windowSize = 5, threshold = 0.6f)
        s.smooth(makeResult(yawn = true, mar = 0.6f))
        s.smooth(makeResult(yawn = false, mar = null))
        s.smooth(makeResult(yawn = true, mar = 0.7f))
        s.smooth(makeResult(yawn = false, mar = null))
        val result = s.smooth(makeResult(yawn = true, mar = 0.8f))
        assertTrue(result.driverYawning)
    }

    @Test
    fun test_all_no_face_yawning_returns_false() {
        val s = TemporalSmoother(windowSize = 3, threshold = 0.6f)
        var result: OutputResult? = null
        repeat(3) { result = s.smooth(makeResult(yawn = true, mar = null)) }
        assertFalse(result!!.driverYawning)
    }

    @Test
    fun test_no_face_frames_skipped_for_distracted() {
        // face_frames=3, true_count=3, 3/3=1.0 >= 0.6 -> distracted = true
        val s = TemporalSmoother(windowSize = 5, threshold = 0.6f)
        s.smooth(makeResult(distracted = true, headYaw = 35.0f))
        s.smooth(makeResult(distracted = false, headYaw = null))
        s.smooth(makeResult(distracted = true, headYaw = 40.0f))
        s.smooth(makeResult(distracted = false, headYaw = null))
        val result = s.smooth(makeResult(distracted = true, headYaw = 38.0f))
        assertTrue(result.driverDistracted)
    }

    @Test
    fun test_all_no_face_distracted_returns_false() {
        val s = TemporalSmoother(windowSize = 3, threshold = 0.6f)
        var result: OutputResult? = null
        repeat(3) { result = s.smooth(makeResult(distracted = true, headYaw = null)) }
        assertFalse(result!!.driverDistracted)
    }

    // -------------------------------------------------------------------------
    // Eating/Drinking Majority Voting (2 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_eating_drinking_majority_voting() {
        // Sustained: need 3 consecutive frames where majority votes true
        // Fill window with eating=true so majority is always true
        val s = TemporalSmoother(windowSize = 3, threshold = 0.6f)
        s.smooth(makeResult(eating = true))
        s.smooth(makeResult(eating = true))
        val result = s.smooth(makeResult(eating = true))
        assertTrue(result.driverEatingDrinking)
    }

    @Test
    fun test_eating_drinking_below_threshold() {
        // 2/5 = 0.4 < 0.6 -> eating = false
        val s = TemporalSmoother(windowSize = 5, threshold = 0.6f)
        repeat(3) { s.smooth(makeResult(eating = false)) }
        var result: OutputResult? = null
        repeat(2) { result = s.smooth(makeResult(eating = true)) }
        assertFalse(result!!.driverEatingDrinking)
    }

    // -------------------------------------------------------------------------
    // Passenger Count (1 test)
    // -------------------------------------------------------------------------

    @Test
    fun test_mode_of_counts() {
        // mode: 2 appears 3 times, 1 appears 2 times -> passenger_count = 2
        val s = TemporalSmoother(windowSize = 5, threshold = 0.6f)
        s.smooth(makeResult(passengers = 1))
        s.smooth(makeResult(passengers = 2))
        s.smooth(makeResult(passengers = 2))
        s.smooth(makeResult(passengers = 1))
        val result = s.smooth(makeResult(passengers = 2))
        assertEquals(2, result.passengerCount)
    }

    // -------------------------------------------------------------------------
    // Fast-Clear (3 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_fast_clear_eyes_on_high_ear() {
        // 2 consecutive high-EAR frames -> fast-clear overrides buffer
        val s = TemporalSmoother(windowSize = 5, threshold = 0.6f)
        repeat(5) { s.smooth(makeResult(eyes = true, ear = 0.10f)) }
        s.smooth(makeResult(eyes = false, ear = 0.25f))
        val result = s.smooth(makeResult(eyes = false, ear = 0.28f))
        assertFalse(result.driverEyesClosed)
    }

    @Test
    fun test_no_fast_clear_with_single_high_ear() {
        // Only 1 high-EAR frame, no fast-clear; buffer: 5/6 face-frames have eyes=true
        val s = TemporalSmoother(windowSize = 5, threshold = 0.6f)
        repeat(5) { s.smooth(makeResult(eyes = true, ear = 0.10f)) }
        val result = s.smooth(makeResult(eyes = false, ear = 0.28f))
        assertTrue(result.driverEyesClosed)
    }

    @Test
    fun test_fast_clear_streak_resets_on_low_ear() {
        // Streak was reset by low-EAR frame, only 1 high-EAR frame after reset
        val s = TemporalSmoother(windowSize = 5, threshold = 0.6f)
        repeat(5) { s.smooth(makeResult(eyes = true, ear = 0.10f)) }
        s.smooth(makeResult(eyes = false, ear = 0.28f))   // streak=1
        s.smooth(makeResult(eyes = true, ear = 0.10f))    // streak reset to 0
        val result = s.smooth(makeResult(eyes = false, ear = 0.28f))  // streak=1
        assertTrue(result.driverEyesClosed)
    }

    // -------------------------------------------------------------------------
    // Risk Recomputed (3 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_risk_recomputed_from_smoothed() {
        // phone=true in all 3 frames -> smoothed phone=true -> risk="high"
        val s = TemporalSmoother(windowSize = 3, threshold = 0.6f)
        var result: OutputResult? = null
        repeat(3) { result = s.smooth(makeResult(phone = true)) }
        assertEquals("high", result!!.riskLevel)
    }

    @Test
    fun test_risk_low_when_smoothed_clears() {
        // 1/3 = 0.33 < 0.6 -> phone=false -> risk="low"
        val s = TemporalSmoother(windowSize = 3, threshold = 0.6f)
        s.smooth(makeResult(phone = true))
        s.smooth(makeResult(phone = false))
        val result = s.smooth(makeResult(phone = false))
        assertEquals("low", result.riskLevel)
    }

    @Test
    fun test_risk_includes_new_fields() {
        // yawning=true (score=2) -> medium
        val s = TemporalSmoother(windowSize = 3, threshold = 0.6f)
        var result: OutputResult? = null
        repeat(3) { result = s.smooth(makeResult(yawn = true, mar = 0.6f)) }
        assertEquals("medium", result!!.riskLevel)
    }
}
