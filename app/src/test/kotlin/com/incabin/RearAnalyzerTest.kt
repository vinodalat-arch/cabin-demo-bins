package com.incabin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for RearAnalyzer.buildResult() — the pure function that converts
 * a PoseResult into a RearResult for rear camera detections.
 */
class RearAnalyzerTest {

    private fun makePerson(isDriver: Boolean = false) = OverlayPerson(
        x1 = 0f, y1 = 0f, x2 = 100f, y2 = 200f,
        confidence = 0.85f, isDriver = isDriver, badPosture = false
    )

    // -------------------------------------------------------------------------
    // Person detection from pose model (4 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_no_persons_detected() {
        val pose = PoseResult(persons = emptyList())
        val result = RearAnalyzer.buildResult(pose)
        assertFalse(result.personDetected)
        assertEquals(0, result.personCount)
        assertEquals("clear", result.riskLevel)
    }

    @Test
    fun test_one_person_detected() {
        val pose = PoseResult(persons = listOf(makePerson()))
        val result = RearAnalyzer.buildResult(pose)
        assertTrue(result.personDetected)
        assertEquals(1, result.personCount)
        assertEquals("danger", result.riskLevel)
    }

    @Test
    fun test_multiple_persons_detected() {
        val pose = PoseResult(persons = listOf(makePerson(), makePerson(), makePerson()))
        val result = RearAnalyzer.buildResult(pose)
        assertTrue(result.personDetected)
        assertEquals(3, result.personCount)
        assertEquals("danger", result.riskLevel)
    }

    @Test
    fun test_driver_person_counted_in_rear() {
        // In rear camera, "driver" flag is irrelevant — all persons are counted
        val pose = PoseResult(persons = listOf(makePerson(isDriver = true), makePerson()))
        val result = RearAnalyzer.buildResult(pose)
        assertEquals(2, result.personCount)
    }

    // -------------------------------------------------------------------------
    // Cat/dog currently unsupported (2 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_cat_always_false() {
        val pose = PoseResult(persons = listOf(makePerson()))
        val result = RearAnalyzer.buildResult(pose)
        assertFalse("Cat detection requires C++ changes", result.catDetected)
    }

    @Test
    fun test_dog_always_false() {
        val pose = PoseResult(persons = listOf(makePerson()))
        val result = RearAnalyzer.buildResult(pose)
        assertFalse("Dog detection requires C++ changes", result.dogDetected)
    }

    // -------------------------------------------------------------------------
    // Timestamp (1 test)
    // -------------------------------------------------------------------------

    @Test
    fun test_result_has_timestamp() {
        val pose = PoseResult()
        val result = RearAnalyzer.buildResult(pose)
        assertTrue(result.timestamp.isNotEmpty())
    }
}
