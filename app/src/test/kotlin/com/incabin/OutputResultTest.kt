package com.incabin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputResultTest {

    /**
     * Creates a valid result map with optional overrides.
     * Keys use snake_case JSON field names matching the schema.
     */
    private fun validResult(overrides: Map<String, Any?> = emptyMap()): MutableMap<String, Any?> {
        val base = mutableMapOf<String, Any?>(
            "timestamp" to "2026-01-01T00:00:00+00:00",
            "passenger_count" to 1,
            "driver_using_phone" to false,
            "driver_eyes_closed" to false,
            "driver_yawning" to false,
            "driver_distracted" to false,
            "driver_eating_drinking" to false,
            "dangerous_posture" to false,
            "child_present" to false,
            "child_slouching" to false,
            "risk_level" to "low",
            "ear_value" to 0.25f,
            "mar_value" to 0.2f,
            "head_yaw" to 5.0f,
            "head_pitch" to 3.0f,
            "distraction_duration_s" to 0
        )
        base.putAll(overrides)
        return base
    }

    // -------------------------------------------------------------------------
    // Valid Payloads (11 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_valid_all_clear() {
        val data = validResult()
        val errors = OutputResult.validate(data)
        assertEquals(0, errors.size)
    }

    @Test
    fun test_valid_high_risk() {
        val data = validResult(mapOf(
            "driver_using_phone" to true,
            "driver_eyes_closed" to true,
            "risk_level" to "high"
        ))
        val errors = OutputResult.validate(data)
        assertEquals(0, errors.size)
    }

    @Test
    fun test_valid_ear_null() {
        val data = validResult(mapOf("ear_value" to null))
        val errors = OutputResult.validate(data)
        assertEquals(0, errors.size)
    }

    @Test
    fun test_valid_ear_zero() {
        val data = validResult(mapOf("ear_value" to 0.0f))
        val errors = OutputResult.validate(data)
        assertEquals(0, errors.size)
    }

    @Test
    fun test_valid_medium_risk() {
        val data = validResult(mapOf("risk_level" to "medium"))
        val errors = OutputResult.validate(data)
        assertEquals(0, errors.size)
    }

    @Test
    fun test_valid_mar_null() {
        val data = validResult(mapOf("mar_value" to null))
        val errors = OutputResult.validate(data)
        assertEquals(0, errors.size)
    }

    @Test
    fun test_valid_head_yaw_null() {
        val data = validResult(mapOf("head_yaw" to null))
        val errors = OutputResult.validate(data)
        assertEquals(0, errors.size)
    }

    @Test
    fun test_valid_head_pitch_null() {
        val data = validResult(mapOf("head_pitch" to null))
        val errors = OutputResult.validate(data)
        assertEquals(0, errors.size)
    }

    @Test
    fun test_valid_with_new_booleans_true() {
        val data = validResult(mapOf(
            "driver_yawning" to true,
            "driver_distracted" to true,
            "driver_eating_drinking" to true,
            "risk_level" to "high"
        ))
        val errors = OutputResult.validate(data)
        assertEquals(0, errors.size)
    }

    @Test
    fun test_valid_distraction_duration() {
        val data = validResult(mapOf("distraction_duration_s" to 15))
        val errors = OutputResult.validate(data)
        assertEquals(0, errors.size)
    }

    @Test
    fun test_valid_without_optional_diagnostics() {
        val data = validResult()
        data.remove("ear_value")
        data.remove("mar_value")
        data.remove("head_yaw")
        data.remove("head_pitch")
        val errors = OutputResult.validate(data)
        assertEquals(0, errors.size)
    }

    // -------------------------------------------------------------------------
    // Invalid Payloads (14 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_missing_timestamp() {
        val data = validResult()
        data.remove("timestamp")
        val errors = OutputResult.validate(data)
        assertTrue("Expected 1+ errors for missing timestamp", errors.isNotEmpty())
    }

    @Test
    fun test_missing_risk_level() {
        val data = validResult()
        data.remove("risk_level")
        val errors = OutputResult.validate(data)
        assertTrue("Expected 1+ errors for missing risk_level", errors.isNotEmpty())
    }

    @Test
    fun test_invalid_risk_value() {
        val data = validResult(mapOf("risk_level" to "critical"))
        val errors = OutputResult.validate(data)
        assertTrue("Expected 1+ errors for invalid risk_level 'critical'", errors.isNotEmpty())
    }

    @Test
    fun test_negative_passenger_count() {
        val data = validResult(mapOf("passenger_count" to -1))
        val errors = OutputResult.validate(data)
        assertTrue("Expected 1+ errors for negative passenger_count", errors.isNotEmpty())
    }

    @Test
    fun test_passenger_count_string() {
        val data = validResult(mapOf("passenger_count" to "two"))
        val errors = OutputResult.validate(data)
        assertTrue("Expected 1+ errors for string passenger_count", errors.isNotEmpty())
    }

    @Test
    fun test_boolean_field_as_string() {
        val data = validResult(mapOf("driver_using_phone" to "true"))
        val errors = OutputResult.validate(data)
        assertTrue("Expected 1+ errors for string boolean field", errors.isNotEmpty())
    }

    @Test
    fun test_extra_field_rejected() {
        val data = validResult(mapOf("unknown_field" to "foo"))
        val errors = OutputResult.validate(data)
        assertTrue("Expected 1+ errors for unknown field", errors.isNotEmpty())
    }

    @Test
    fun test_ear_value_as_string() {
        val data = validResult(mapOf("ear_value" to "0.25"))
        val errors = OutputResult.validate(data)
        assertTrue("Expected 1+ errors for string ear_value", errors.isNotEmpty())
    }

    @Test
    fun test_missing_driver_yawning() {
        val data = validResult()
        data.remove("driver_yawning")
        val errors = OutputResult.validate(data)
        assertTrue("Expected 1+ errors for missing driver_yawning", errors.isNotEmpty())
    }

    @Test
    fun test_missing_distraction_duration() {
        val data = validResult()
        data.remove("distraction_duration_s")
        val errors = OutputResult.validate(data)
        assertTrue("Expected 1+ errors for missing distraction_duration_s", errors.isNotEmpty())
    }

    @Test
    fun test_negative_distraction_duration() {
        val data = validResult(mapOf("distraction_duration_s" to -1))
        val errors = OutputResult.validate(data)
        assertTrue("Expected 1+ errors for negative distraction_duration_s", errors.isNotEmpty())
    }

    @Test
    fun test_distraction_duration_as_float() {
        val data = validResult(mapOf("distraction_duration_s" to 5.5))
        val errors = OutputResult.validate(data)
        assertTrue("Expected 1+ errors for float distraction_duration_s", errors.isNotEmpty())
    }

    @Test
    fun test_yawning_as_string() {
        val data = validResult(mapOf("driver_yawning" to "true"))
        val errors = OutputResult.validate(data)
        assertTrue("Expected 1+ errors for string driver_yawning", errors.isNotEmpty())
    }

    @Test
    fun test_mar_value_as_string() {
        val data = validResult(mapOf("mar_value" to "0.5"))
        val errors = OutputResult.validate(data)
        assertTrue("Expected 1+ errors for string mar_value", errors.isNotEmpty())
    }
}
