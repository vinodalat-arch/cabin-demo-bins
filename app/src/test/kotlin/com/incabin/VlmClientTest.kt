package com.incabin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for VlmClient.parseDetectResponse() and buildHealthUrl() companions.
 * All pure functions — no Android dependencies.
 */
class VlmClientTest {

    // -------------------------------------------------------------------------
    // parseDetectResponse — valid inputs (5 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_valid_full_json() {
        val json = """
        {
            "timestamp": "2025-01-01T00:00:00Z",
            "passenger_count": 2,
            "child_count": 1,
            "adult_count": 1,
            "driver_using_phone": true,
            "driver_eyes_closed": false,
            "driver_yawning": false,
            "driver_distracted": false,
            "driver_eating_drinking": false,
            "hands_off_wheel": false,
            "dangerous_posture": false,
            "child_present": true,
            "child_slouching": false,
            "risk_level": "high",
            "ear_value": 0.25,
            "mar_value": 0.3,
            "head_yaw": 5.0,
            "head_pitch": -2.0,
            "driver_name": "Alice",
            "driver_detected": true
        }
        """.trimIndent()

        val result = VlmClient.parseDetectResponse(json)
        assertNotNull(result)
        result!!
        assertEquals("2025-01-01T00:00:00Z", result.timestamp)
        assertEquals(2, result.passengerCount)
        assertEquals(1, result.childCount)
        assertEquals(1, result.adultCount)
        assertTrue(result.driverUsingPhone)
        assertFalse(result.driverEyesClosed)
        assertFalse(result.driverYawning)
        assertFalse(result.driverDistracted)
        assertFalse(result.driverEatingDrinking)
        assertFalse(result.handsOffWheel)
        assertFalse(result.dangerousPosture)
        assertTrue(result.childPresent)
        assertFalse(result.childSlouching)
        assertEquals("high", result.riskLevel)
        assertEquals(0.25f, result.earValue!!, 0.01f)
        assertEquals(0.3f, result.marValue!!, 0.01f)
        assertEquals(5.0f, result.headYaw!!, 0.01f)
        assertEquals(-2.0f, result.headPitch!!, 0.01f)
        assertEquals("Alice", result.driverName)
        assertTrue(result.driverDetected)
        // distraction_duration_s is always 0 (on-device computed)
        assertEquals(0, result.distractionDurationS)
    }

    @Test
    fun test_missing_optional_fields_null_defaults() {
        val json = """
        {
            "timestamp": "2025-01-01T00:00:00Z",
            "passenger_count": 1,
            "driver_using_phone": false,
            "driver_eyes_closed": false,
            "driver_yawning": false,
            "driver_distracted": false,
            "driver_eating_drinking": false,
            "dangerous_posture": false,
            "child_present": false,
            "child_slouching": false,
            "risk_level": "low",
            "ear_value": null,
            "mar_value": null,
            "head_yaw": null,
            "head_pitch": null,
            "driver_name": null,
            "driver_detected": true
        }
        """.trimIndent()

        val result = VlmClient.parseDetectResponse(json)
        assertNotNull(result)
        result!!
        assertNull(result.earValue)
        assertNull(result.marValue)
        assertNull(result.headYaw)
        assertNull(result.headPitch)
        assertNull(result.driverName)
        assertEquals(0, result.childCount)
        assertEquals(0, result.adultCount)
    }

    @Test
    fun test_driver_name_present() {
        val json = """
        {
            "timestamp": "2025-01-01T00:00:00Z",
            "passenger_count": 1,
            "driver_using_phone": false,
            "driver_eyes_closed": false,
            "driver_yawning": false,
            "driver_distracted": false,
            "driver_eating_drinking": false,
            "dangerous_posture": false,
            "child_present": false,
            "child_slouching": false,
            "risk_level": "low",
            "driver_name": "Bob",
            "driver_detected": true
        }
        """.trimIndent()

        val result = VlmClient.parseDetectResponse(json)
        assertNotNull(result)
        assertEquals("Bob", result!!.driverName)
    }

    @Test
    fun test_driver_detected_false() {
        val json = """
        {
            "timestamp": "2025-01-01T00:00:00Z",
            "passenger_count": 0,
            "driver_using_phone": false,
            "driver_eyes_closed": false,
            "driver_yawning": false,
            "driver_distracted": false,
            "driver_eating_drinking": false,
            "dangerous_posture": false,
            "child_present": false,
            "child_slouching": false,
            "risk_level": "low",
            "driver_detected": false
        }
        """.trimIndent()

        val result = VlmClient.parseDetectResponse(json)
        assertNotNull(result)
        assertFalse(result!!.driverDetected)
    }

    @Test
    fun test_distraction_duration_in_response_ignored() {
        // Even if the server sends distraction_duration_s, it should be 0 in parsed result
        val json = """
        {
            "timestamp": "2025-01-01T00:00:00Z",
            "passenger_count": 1,
            "driver_using_phone": true,
            "driver_eyes_closed": false,
            "driver_yawning": false,
            "driver_distracted": false,
            "driver_eating_drinking": false,
            "dangerous_posture": false,
            "child_present": false,
            "child_slouching": false,
            "risk_level": "high",
            "distraction_duration_s": 42,
            "driver_detected": true
        }
        """.trimIndent()

        val result = VlmClient.parseDetectResponse(json)
        assertNotNull(result)
        assertEquals(0, result!!.distractionDurationS)
    }

    // -------------------------------------------------------------------------
    // parseDetectResponse — invalid inputs (5 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_missing_required_field_returns_null() {
        // Missing "driver_detected"
        val json = """
        {
            "timestamp": "2025-01-01T00:00:00Z",
            "passenger_count": 1,
            "driver_using_phone": false,
            "driver_eyes_closed": false,
            "driver_yawning": false,
            "driver_distracted": false,
            "driver_eating_drinking": false,
            "dangerous_posture": false,
            "child_present": false,
            "child_slouching": false,
            "risk_level": "low"
        }
        """.trimIndent()

        assertNull(VlmClient.parseDetectResponse(json))
    }

    @Test
    fun test_empty_json_returns_null() {
        assertNull(VlmClient.parseDetectResponse(""))
    }

    @Test
    fun test_invalid_json_returns_null() {
        assertNull(VlmClient.parseDetectResponse("not json at all"))
    }

    @Test
    fun test_boolean_field_as_string_returns_null() {
        val json = """
        {
            "timestamp": "2025-01-01T00:00:00Z",
            "passenger_count": 1,
            "driver_using_phone": "true",
            "driver_eyes_closed": false,
            "driver_yawning": false,
            "driver_distracted": false,
            "driver_eating_drinking": false,
            "dangerous_posture": false,
            "child_present": false,
            "child_slouching": false,
            "risk_level": "low",
            "driver_detected": true
        }
        """.trimIndent()

        assertNull(VlmClient.parseDetectResponse(json))
    }

    @Test
    fun test_negative_passenger_count_returns_null() {
        val json = """
        {
            "timestamp": "2025-01-01T00:00:00Z",
            "passenger_count": -1,
            "driver_using_phone": false,
            "driver_eyes_closed": false,
            "driver_yawning": false,
            "driver_distracted": false,
            "driver_eating_drinking": false,
            "dangerous_posture": false,
            "child_present": false,
            "child_slouching": false,
            "risk_level": "low",
            "driver_detected": true
        }
        """.trimIndent()

        assertNull(VlmClient.parseDetectResponse(json))
    }

    @Test
    fun test_invalid_risk_level_returns_null() {
        val json = """
        {
            "timestamp": "2025-01-01T00:00:00Z",
            "passenger_count": 1,
            "driver_using_phone": false,
            "driver_eyes_closed": false,
            "driver_yawning": false,
            "driver_distracted": false,
            "driver_eating_drinking": false,
            "dangerous_posture": false,
            "child_present": false,
            "child_slouching": false,
            "risk_level": "extreme",
            "driver_detected": true
        }
        """.trimIndent()

        assertNull(VlmClient.parseDetectResponse(json))
    }

    // -------------------------------------------------------------------------
    // parseSeatMap (3 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_parseSeatMap_valid() {
        val json = """
        {
            "seat_map": {
                "driver": {"occupied": true, "state": "Upright"},
                "front_passenger": {"occupied": true, "state": "Sleeping"},
                "rear_left": {"occupied": false, "state": "Vacant"},
                "rear_center": {"occupied": true, "state": "Upright"},
                "rear_right": {"occupied": true, "state": "Phone"}
            }
        }
        """.trimIndent()
        val obj = com.google.gson.Gson().fromJson(json, com.google.gson.JsonObject::class.java)
        val seatMap = VlmClient.parseSeatMap(obj)
        assertNotNull(seatMap)
        seatMap!!
        assertTrue(seatMap.driver.occupied)
        assertEquals("Upright", seatMap.driver.state)
        assertTrue(seatMap.frontPassenger.occupied)
        assertEquals("Sleeping", seatMap.frontPassenger.state)
        assertFalse(seatMap.rearLeft.occupied)
        assertEquals("Vacant", seatMap.rearLeft.state)
        assertTrue(seatMap.rearCenter.occupied)
        assertEquals("Upright", seatMap.rearCenter.state)
        assertTrue(seatMap.rearRight.occupied)
        assertEquals("Phone", seatMap.rearRight.state)
    }

    @Test
    fun test_parseSeatMap_missing() {
        val json = """{"timestamp": "2025-01-01T00:00:00Z"}"""
        val obj = com.google.gson.Gson().fromJson(json, com.google.gson.JsonObject::class.java)
        val seatMap = VlmClient.parseSeatMap(obj)
        assertNull(seatMap)
    }

    @Test
    fun test_parseSeatMap_partial() {
        // Only driver and front_passenger, missing rear seats → defaults to Vacant
        val json = """
        {
            "seat_map": {
                "driver": {"occupied": true, "state": "Distracted"},
                "front_passenger": {"occupied": false, "state": "Vacant"}
            }
        }
        """.trimIndent()
        val obj = com.google.gson.Gson().fromJson(json, com.google.gson.JsonObject::class.java)
        val seatMap = VlmClient.parseSeatMap(obj)
        assertNotNull(seatMap)
        seatMap!!
        assertTrue(seatMap.driver.occupied)
        assertEquals("Distracted", seatMap.driver.state)
        assertFalse(seatMap.rearLeft.occupied)
        assertEquals("Vacant", seatMap.rearLeft.state)
        assertFalse(seatMap.rearCenter.occupied)
        assertEquals("Vacant", seatMap.rearCenter.state)
        assertFalse(seatMap.rearRight.occupied)
        assertEquals("Vacant", seatMap.rearRight.state)
    }

    // -------------------------------------------------------------------------
    // buildHealthUrl / buildDetectUrl (2 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_buildHealthUrl_constructs_correct_url() {
        assertEquals(
            "http://192.168.1.100:8000/api/health",
            VlmClient.buildHealthUrl("http://192.168.1.100:8000")
        )
    }

    @Test
    fun test_buildHealthUrl_handles_trailing_slash() {
        assertEquals(
            "http://192.168.1.100:8000/api/health",
            VlmClient.buildHealthUrl("http://192.168.1.100:8000/")
        )
    }
}
