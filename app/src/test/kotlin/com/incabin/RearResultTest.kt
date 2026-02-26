package com.incabin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RearResultTest {

    // -------------------------------------------------------------------------
    // Risk computation (3 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_risk_person_is_danger() {
        assertEquals("danger", RearResult.computeRisk(personDetected = true, catDetected = false, dogDetected = false))
    }

    @Test
    fun test_risk_animal_is_caution() {
        assertEquals("caution", RearResult.computeRisk(personDetected = false, catDetected = true, dogDetected = false))
        assertEquals("caution", RearResult.computeRisk(personDetected = false, catDetected = false, dogDetected = true))
        assertEquals("caution", RearResult.computeRisk(personDetected = false, catDetected = true, dogDetected = true))
    }

    @Test
    fun test_risk_none_is_clear() {
        assertEquals("clear", RearResult.computeRisk(personDetected = false, catDetected = false, dogDetected = false))
    }

    // -------------------------------------------------------------------------
    // Risk priority: person overrides animal (1 test)
    // -------------------------------------------------------------------------

    @Test
    fun test_risk_person_overrides_animal() {
        assertEquals("danger", RearResult.computeRisk(personDetected = true, catDetected = true, dogDetected = true))
    }

    // -------------------------------------------------------------------------
    // Default result (2 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_default_all_clear() {
        val d = RearResult.default()
        assertFalse(d.personDetected)
        assertEquals(0, d.personCount)
        assertFalse(d.catDetected)
        assertFalse(d.dogDetected)
        assertEquals("clear", d.riskLevel)
    }

    @Test
    fun test_default_has_timestamp() {
        val d = RearResult.default()
        assertTrue(d.timestamp.isNotEmpty())
    }

    // -------------------------------------------------------------------------
    // JSON serialization (2 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_toJson_contains_all_fields() {
        val r = RearResult(
            timestamp = "2026-01-01T00:00:00Z",
            personDetected = true,
            personCount = 2,
            catDetected = false,
            dogDetected = true,
            riskLevel = "danger"
        )
        val json = r.toJson()
        assertTrue(json.contains("\"person_detected\":true"))
        assertTrue(json.contains("\"person_count\":2"))
        assertTrue(json.contains("\"cat_detected\":false"))
        assertTrue(json.contains("\"dog_detected\":true"))
        assertTrue(json.contains("\"risk_level\":\"danger\""))
        assertTrue(json.contains("\"timestamp\":\"2026-01-01T00:00:00Z\""))
    }

    @Test
    fun test_toJson_clear_result() {
        val r = RearResult.default()
        val json = r.toJson()
        assertTrue(json.contains("\"person_detected\":false"))
        assertTrue(json.contains("\"risk_level\":\"clear\""))
    }

    // -------------------------------------------------------------------------
    // Validation (4 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_validate_valid_data() {
        val data = mapOf<String, Any?>(
            "timestamp" to "2026-01-01T00:00:00Z",
            "person_detected" to true,
            "person_count" to 1,
            "cat_detected" to false,
            "dog_detected" to false,
            "risk_level" to "danger"
        )
        val errors = RearResult.validate(data)
        assertTrue("Expected no errors but got: $errors", errors.isEmpty())
    }

    @Test
    fun test_validate_missing_field() {
        val data = mapOf<String, Any?>(
            "timestamp" to "2026-01-01T00:00:00Z",
            "person_detected" to true
        )
        val errors = RearResult.validate(data)
        assertTrue(errors.any { it.contains("Missing required field") })
    }

    @Test
    fun test_validate_invalid_risk_level() {
        val data = mapOf<String, Any?>(
            "timestamp" to "2026-01-01T00:00:00Z",
            "person_detected" to true,
            "person_count" to 1,
            "cat_detected" to false,
            "dog_detected" to false,
            "risk_level" to "high"  // invalid — should be clear/caution/danger
        )
        val errors = RearResult.validate(data)
        assertTrue(errors.any { it.contains("risk_level") })
    }

    @Test
    fun test_validate_negative_person_count() {
        val data = mapOf<String, Any?>(
            "timestamp" to "2026-01-01T00:00:00Z",
            "person_detected" to false,
            "person_count" to -1,
            "cat_detected" to false,
            "dog_detected" to false,
            "risk_level" to "clear"
        )
        val errors = RearResult.validate(data)
        assertTrue(errors.any { it.contains("person_count") })
    }
}
