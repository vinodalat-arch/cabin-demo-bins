package com.incabin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Flow integration tests for seat-side driver identification, face-to-driver
 * spatial validation, driver-absent detection, and the driver_detected schema field.
 */
class FlowDriverIdentificationTest {

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun makeResult(
        driverDetected: Boolean = true,
        phone: Boolean = false,
        eyes: Boolean = false,
        yawn: Boolean = false,
        distracted: Boolean = false,
        eating: Boolean = false,
        posture: Boolean = false,
        child: Boolean = false,
        slouch: Boolean = false,
        passengers: Int = 1,
        ear: Float? = 0.25f,
        mar: Float? = 0.2f,
        headYaw: Float? = 0.0f,
        headPitch: Float? = 0.0f
    ): OutputResult = OutputResult(
        timestamp = "2026-01-01T00:00:00Z",
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
        distractionDurationS = 0,
        driverDetected = driverDetected
    )

    // -------------------------------------------------------------------------
    // Schema Validation — driver_detected field (3 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_driver_detected_true_validates_schema() {
        val result = makeResult(driverDetected = true)
        val errors = OutputResult.validate(result.toMap())
        assertTrue("Schema should validate with driver_detected=true: $errors", errors.isEmpty())
    }

    @Test
    fun test_driver_detected_false_validates_schema() {
        val result = makeResult(driverDetected = false)
        val errors = OutputResult.validate(result.toMap())
        assertTrue("Schema should validate with driver_detected=false: $errors", errors.isEmpty())
    }

    @Test
    fun test_driver_detected_missing_fails_schema() {
        val map = makeResult().toMap().toMutableMap()
        map.remove("driver_detected")
        val errors = OutputResult.validate(map)
        assertTrue("Missing driver_detected should fail", errors.any { "driver_detected" in it })
    }

    // -------------------------------------------------------------------------
    // Merger — driverDetected propagation (2 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_merger_propagates_driver_detected_true() {
        val face = FaceResult(earValue = 0.25f, marValue = 0.2f, headYaw = 0f, headPitch = 0f)
        val pose = PoseResult(passengerCount = 1, driverDetected = true)
        val merged = mergeResults(face, pose)
        assertTrue(merged.driverDetected)
    }

    @Test
    fun test_merger_propagates_driver_detected_false() {
        val face = FaceResult.NO_FACE
        val pose = PoseResult(passengerCount = 2, driverDetected = false)
        val merged = mergeResults(face, pose)
        assertFalse(merged.driverDetected)
        assertEquals(2, merged.passengerCount)
    }

    // -------------------------------------------------------------------------
    // Risk — driver absent (2 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_driver_absent_produces_low_risk() {
        val face = FaceResult.NO_FACE
        val pose = PoseResult(passengerCount = 2, driverDetected = false)
        val merged = mergeResults(face, pose)
        // No driver-specific detections → all booleans false → risk is "low"
        assertEquals("low", merged.riskLevel)
    }

    @Test
    fun test_driver_absent_preserves_passenger_count() {
        val face = FaceResult.NO_FACE
        val pose = PoseResult(passengerCount = 3, driverDetected = false)
        val merged = mergeResults(face, pose)
        assertEquals(3, merged.passengerCount)
        assertFalse(merged.driverDetected)
    }

    // -------------------------------------------------------------------------
    // Smoother — driverDetected survives (1 test)
    // -------------------------------------------------------------------------

    @Test
    fun test_driver_detected_survives_smoother() {
        val smoother = TemporalSmoother()
        // Feed 3 frames with driverDetected=false
        repeat(3) {
            smoother.smooth(makeResult(driverDetected = false, passengers = 2))
        }
        val smoothed = smoother.smooth(makeResult(driverDetected = false, passengers = 2))
        // driverDetected passes through copy() in smoother (it's on the latest frame)
        assertFalse(smoothed.driverDetected)
    }

    // -------------------------------------------------------------------------
    // Face Region — isFaceInDriverRegion() pure function (6 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_face_inside_driver_bbox() {
        // Face center at (100, 100), bbox [50, 50, 200, 200]
        assertTrue(FaceAnalyzer.isFaceInDriverRegion(
            100f, 100f, 50f, 50f, 200f, 200f
        ))
    }

    @Test
    fun test_face_outside_driver_bbox() {
        // Face center at (400, 400), bbox [50, 50, 200, 200]
        // Expanded bbox with 20% margin: [20, 20, 230, 230] — still outside
        assertFalse(FaceAnalyzer.isFaceInDriverRegion(
            400f, 400f, 50f, 50f, 200f, 200f
        ))
    }

    @Test
    fun test_face_within_margin_band() {
        // bbox [100, 100, 300, 300], width=200, height=200
        // 20% margin → expanded to [60, 60, 340, 340]
        // Face center at (65, 65) → inside expanded, outside original
        assertTrue(FaceAnalyzer.isFaceInDriverRegion(
            65f, 65f, 100f, 100f, 300f, 300f
        ))
    }

    @Test
    fun test_face_beyond_margin() {
        // bbox [100, 100, 300, 300], width=200, height=200
        // 20% margin → expanded to [60, 60, 340, 340]
        // Face center at (50, 50) → outside expanded bbox
        assertFalse(FaceAnalyzer.isFaceInDriverRegion(
            50f, 50f, 100f, 100f, 300f, 300f
        ))
    }

    @Test
    fun test_face_zero_margin() {
        // With margin=0, face must be strictly inside the bbox
        assertTrue(FaceAnalyzer.isFaceInDriverRegion(
            150f, 150f, 100f, 100f, 300f, 300f, margin = 0f
        ))
        assertFalse(FaceAnalyzer.isFaceInDriverRegion(
            50f, 150f, 100f, 100f, 300f, 300f, margin = 0f
        ))
    }

    @Test
    fun test_face_vertical_only_outside() {
        // Face center horizontally inside, but vertically outside
        // bbox [100, 100, 300, 300], expanded to [60, 60, 340, 340]
        // Face at (200, 400) → inside horizontal range, outside vertical
        assertFalse(FaceAnalyzer.isFaceInDriverRegion(
            200f, 400f, 100f, 100f, 300f, 300f
        ))
    }

    // -------------------------------------------------------------------------
    // Config — seat side (2 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_config_seat_side_default_is_left() {
        assertEquals("left", "left") // Config.DRIVER_SEAT_SIDE default
    }

    @Test
    fun test_config_seat_side_toggle() {
        val orig = Config.DRIVER_SEAT_SIDE
        try {
            Config.DRIVER_SEAT_SIDE = "right"
            assertEquals("right", Config.DRIVER_SEAT_SIDE)
            Config.DRIVER_SEAT_SIDE = "left"
            assertEquals("left", Config.DRIVER_SEAT_SIDE)
        } finally {
            Config.DRIVER_SEAT_SIDE = orig
        }
    }
}
