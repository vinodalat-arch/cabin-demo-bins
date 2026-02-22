package com.incabin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for OutputResult serialization: toJson(), fromJson() round-trip,
 * toMap() field mapping, and default() factory.
 */
class OutputResultSerializationTest {

    private fun makeResult(
        phone: Boolean = false,
        eyes: Boolean = false,
        yawn: Boolean = false,
        distracted: Boolean = false,
        eating: Boolean = false,
        posture: Boolean = false,
        childPresent: Boolean = false,
        slouch: Boolean = false,
        riskLevel: String = "low",
        ear: Float? = 0.25f,
        mar: Float? = 0.2f,
        headYaw: Float? = 5.0f,
        headPitch: Float? = -3.0f,
        distractionS: Int = 0,
        driverName: String? = null,
        driverDetected: Boolean = true
    ): OutputResult = OutputResult(
        timestamp = "2026-01-15T10:30:00Z",
        passengerCount = 2,
        driverUsingPhone = phone,
        driverEyesClosed = eyes,
        driverYawning = yawn,
        driverDistracted = distracted,
        driverEatingDrinking = eating,
        dangerousPosture = posture,
        childPresent = childPresent,
        childSlouching = slouch,
        riskLevel = riskLevel,
        earValue = ear,
        marValue = mar,
        headYaw = headYaw,
        headPitch = headPitch,
        distractionDurationS = distractionS,
        driverName = driverName,
        driverDetected = driverDetected
    )

    // -------------------------------------------------------------------------
    // toJson() basic tests (5 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_toJson_contains_all_required_fields() {
        val json = makeResult().toJson()
        assertTrue(json.contains("\"timestamp\""))
        assertTrue(json.contains("\"passenger_count\""))
        assertTrue(json.contains("\"driver_using_phone\""))
        assertTrue(json.contains("\"driver_eyes_closed\""))
        assertTrue(json.contains("\"driver_yawning\""))
        assertTrue(json.contains("\"driver_distracted\""))
        assertTrue(json.contains("\"driver_eating_drinking\""))
        assertTrue(json.contains("\"dangerous_posture\""))
        assertTrue(json.contains("\"child_present\""))
        assertTrue(json.contains("\"child_slouching\""))
        assertTrue(json.contains("\"risk_level\""))
        assertTrue(json.contains("\"distraction_duration_s\""))
        assertTrue(json.contains("\"driver_detected\""))
        assertTrue(json.contains("\"ear_value\""))
        assertTrue(json.contains("\"mar_value\""))
        assertTrue(json.contains("\"head_yaw\""))
        assertTrue(json.contains("\"head_pitch\""))
        assertTrue(json.contains("\"driver_name\""))
    }

    @Test
    fun test_toJson_boolean_values() {
        val json = makeResult(phone = true, eyes = false).toJson()
        assertTrue(json.contains("\"driver_using_phone\":true"))
        assertTrue(json.contains("\"driver_eyes_closed\":false"))
    }

    @Test
    fun test_toJson_integer_values() {
        val result = makeResult(distractionS = 42)
        val json = result.toJson()
        assertTrue(json.contains("\"passenger_count\":2"))
        assertTrue(json.contains("\"distraction_duration_s\":42"))
    }

    @Test
    fun test_toJson_null_driver_name() {
        val json = makeResult(driverName = null).toJson()
        assertTrue(json.contains("\"driver_name\":null"))
    }

    @Test
    fun test_toJson_with_driver_name() {
        val json = makeResult(driverName = "Alice").toJson()
        assertTrue(json.contains("\"driver_name\":\"Alice\""))
    }

    // -------------------------------------------------------------------------
    // toJson() special characters in driver_name (4 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_toJson_escapes_quotes_in_name() {
        val json = makeResult(driverName = "O\"Brien").toJson()
        assertTrue(json.contains("O\\\"Brien"))
    }

    @Test
    fun test_toJson_escapes_backslash_in_name() {
        val json = makeResult(driverName = "path\\name").toJson()
        assertTrue(json.contains("path\\\\name"))
    }

    @Test
    fun test_toJson_escapes_newline_in_name() {
        val json = makeResult(driverName = "line1\nline2").toJson()
        assertTrue(json.contains("line1\\nline2"))
    }

    @Test
    fun test_toJson_escapes_tab_in_name() {
        val json = makeResult(driverName = "col1\tcol2").toJson()
        assertTrue(json.contains("col1\\tcol2"))
    }

    // -------------------------------------------------------------------------
    // toJson() null float fields (2 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_toJson_null_ear_value() {
        val json = makeResult(ear = null).toJson()
        assertTrue(json.contains("\"ear_value\":null"))
    }

    @Test
    fun test_toJson_all_nullable_fields_null() {
        val json = makeResult(ear = null, mar = null, headYaw = null, headPitch = null, driverName = null).toJson()
        assertTrue(json.contains("\"ear_value\":null"))
        assertTrue(json.contains("\"mar_value\":null"))
        assertTrue(json.contains("\"head_yaw\":null"))
        assertTrue(json.contains("\"head_pitch\":null"))
        assertTrue(json.contains("\"driver_name\":null"))
    }

    // -------------------------------------------------------------------------
    // toJson() → fromJson() round-trip (5 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_roundtrip_basic_result() {
        val original = makeResult()
        val json = original.toJson()
        val parsed = OutputResult.fromJson(json)
        assertEquals(original.timestamp, parsed.timestamp)
        assertEquals(original.passengerCount, parsed.passengerCount)
        assertEquals(original.driverUsingPhone, parsed.driverUsingPhone)
        assertEquals(original.driverEyesClosed, parsed.driverEyesClosed)
        assertEquals(original.riskLevel, parsed.riskLevel)
        assertEquals(original.driverDetected, parsed.driverDetected)
    }

    @Test
    fun test_roundtrip_all_detections_active() {
        val original = makeResult(
            phone = true, eyes = true, yawn = true, distracted = true,
            eating = true, posture = true, childPresent = true, slouch = true,
            riskLevel = "high", distractionS = 15
        )
        val parsed = OutputResult.fromJson(original.toJson())
        assertTrue(parsed.driverUsingPhone)
        assertTrue(parsed.driverEyesClosed)
        assertTrue(parsed.driverYawning)
        assertTrue(parsed.driverDistracted)
        assertTrue(parsed.driverEatingDrinking)
        assertTrue(parsed.dangerousPosture)
        assertTrue(parsed.childPresent)
        assertTrue(parsed.childSlouching)
        assertEquals("high", parsed.riskLevel)
        assertEquals(15, parsed.distractionDurationS)
    }

    @Test
    fun test_roundtrip_with_driver_name() {
        val original = makeResult(driverName = "Bob Smith")
        val parsed = OutputResult.fromJson(original.toJson())
        assertEquals("Bob Smith", parsed.driverName)
    }

    @Test
    fun test_roundtrip_with_special_chars_in_name() {
        val original = makeResult(driverName = "O\"Brien\\Jr\nIII")
        val parsed = OutputResult.fromJson(original.toJson())
        assertEquals("O\"Brien\\Jr\nIII", parsed.driverName)
    }

    @Test
    fun test_roundtrip_driver_detected_false() {
        val original = makeResult(driverDetected = false)
        val parsed = OutputResult.fromJson(original.toJson())
        assertFalse(parsed.driverDetected)
    }

    // -------------------------------------------------------------------------
    // toMap() tests (5 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_toMap_has_18_fields() {
        val map = makeResult().toMap()
        assertEquals(18, map.size)
    }

    @Test
    fun test_toMap_field_names_match_schema() {
        val map = makeResult().toMap()
        val expectedKeys = setOf(
            "timestamp", "passenger_count",
            "driver_using_phone", "driver_eyes_closed", "driver_yawning",
            "driver_distracted", "driver_eating_drinking", "dangerous_posture",
            "child_present", "child_slouching", "risk_level",
            "ear_value", "mar_value", "head_yaw", "head_pitch",
            "distraction_duration_s", "driver_name", "driver_detected"
        )
        assertEquals(expectedKeys, map.keys)
    }

    @Test
    fun test_toMap_values_match_fields() {
        val result = makeResult(phone = true, driverName = "Alice", distractionS = 7)
        val map = result.toMap()
        assertEquals("2026-01-15T10:30:00Z", map["timestamp"])
        assertEquals(2, map["passenger_count"])
        assertEquals(true, map["driver_using_phone"])
        assertEquals("Alice", map["driver_name"])
        assertEquals(7, map["distraction_duration_s"])
    }

    @Test
    fun test_toMap_null_values() {
        val map = makeResult(ear = null, mar = null, headYaw = null, headPitch = null, driverName = null).toMap()
        assertNull(map["ear_value"])
        assertNull(map["mar_value"])
        assertNull(map["head_yaw"])
        assertNull(map["head_pitch"])
        assertNull(map["driver_name"])
    }

    @Test
    fun test_toMap_validates_with_schema() {
        val result = makeResult(phone = true, riskLevel = "high", driverName = "Test")
        val errors = OutputResult.validate(result.toMap())
        assertTrue("toMap output should pass schema validation: $errors", errors.isEmpty())
    }

    // -------------------------------------------------------------------------
    // default() factory tests (4 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_default_has_low_risk() {
        val d = OutputResult.default()
        assertEquals("low", d.riskLevel)
    }

    @Test
    fun test_default_has_zero_passengers() {
        val d = OutputResult.default()
        assertEquals(0, d.passengerCount)
    }

    @Test
    fun test_default_all_booleans_false() {
        val d = OutputResult.default()
        assertFalse(d.driverUsingPhone)
        assertFalse(d.driverEyesClosed)
        assertFalse(d.driverYawning)
        assertFalse(d.driverDistracted)
        assertFalse(d.driverEatingDrinking)
        assertFalse(d.dangerousPosture)
        assertFalse(d.childPresent)
        assertFalse(d.childSlouching)
    }

    @Test
    fun test_default_nullable_fields_are_null() {
        val d = OutputResult.default()
        assertNull(d.earValue)
        assertNull(d.marValue)
        assertNull(d.headYaw)
        assertNull(d.headPitch)
        assertNull(d.driverName)
    }

    @Test
    fun test_default_driver_detected_true() {
        val d = OutputResult.default()
        assertTrue(d.driverDetected)
    }

    @Test
    fun test_default_has_valid_timestamp() {
        val d = OutputResult.default()
        assertNotNull(d.timestamp)
        assertTrue("Timestamp should contain T separator", d.timestamp.contains("T"))
    }

    @Test
    fun test_default_passes_schema_validation() {
        val d = OutputResult.default()
        val errors = OutputResult.validate(d.toMap())
        assertTrue("Default result should validate: $errors", errors.isEmpty())
    }
}
