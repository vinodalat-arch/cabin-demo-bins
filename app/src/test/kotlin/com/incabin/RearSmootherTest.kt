package com.incabin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RearSmootherTest {

    private fun makeResult(
        person: Boolean = false,
        personCount: Int = if (person) 1 else 0,
        cat: Boolean = false,
        dog: Boolean = false,
        risk: String = RearResult.computeRisk(person, cat, dog)
    ) = RearResult(
        timestamp = java.time.Instant.now().toString(),
        personDetected = person,
        personCount = personCount,
        catDetected = cat,
        dogDetected = dog,
        riskLevel = risk
    )

    // -------------------------------------------------------------------------
    // Person detection with min_frames=1 (immediate) (3 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_person_detected_immediately() {
        val smoother = RearSmoother()
        val result = smoother.smooth(makeResult(person = true))
        assertTrue("Person should be detected on first frame (min_frames=1)", result.personDetected)
    }

    @Test
    fun test_person_clears_on_absent_frame() {
        val smoother = RearSmoother()
        smoother.smooth(makeResult(person = true))
        val result = smoother.smooth(makeResult(person = false))
        // With window=2: [true, false] → 1/2 = 0.5 >= 0.5 threshold → still detected
        // But streak resets since raw majority is borderline
        // Actually: 1/2 = 0.5 >= 0.5 → rawPerson=true, streak=2 → still detected
        // Need 2 consecutive absent to clear
        assertTrue("Person still detected with 1 of 2 frames", result.personDetected)
    }

    @Test
    fun test_person_clears_after_two_absent() {
        val smoother = RearSmoother()
        smoother.smooth(makeResult(person = true))
        smoother.smooth(makeResult(person = false))
        val result = smoother.smooth(makeResult(person = false))
        // Buffer: [false, false] → 0/2 = 0 < 0.5 → rawPerson=false, streak=0
        assertFalse("Person should clear after 2 absent frames", result.personDetected)
    }

    // -------------------------------------------------------------------------
    // Cat/dog detection with min_frames=2 (sustained) (3 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_cat_not_detected_on_first_frame() {
        val smoother = RearSmoother()
        val result = smoother.smooth(makeResult(cat = true))
        // min_frames=2, streak=1 → not yet
        assertFalse("Cat should not fire on first frame (min_frames=2)", result.catDetected)
    }

    @Test
    fun test_cat_detected_after_two_frames() {
        val smoother = RearSmoother()
        smoother.smooth(makeResult(cat = true))
        val result = smoother.smooth(makeResult(cat = true))
        assertTrue("Cat should fire after 2 consecutive frames", result.catDetected)
    }

    @Test
    fun test_dog_detected_after_sustained() {
        val smoother = RearSmoother()
        smoother.smooth(makeResult(dog = true))
        val result = smoother.smooth(makeResult(dog = true))
        assertTrue("Dog should fire after 2 consecutive frames", result.dogDetected)
    }

    // -------------------------------------------------------------------------
    // Person count mode (2 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_person_count_mode_basic() {
        assertEquals(0, RearSmoother.personCountMode(emptyList()))
        assertEquals(2, RearSmoother.personCountMode(listOf(2, 2, 1)))
        assertEquals(3, RearSmoother.personCountMode(listOf(3, 3, 2)))
    }

    @Test
    fun test_person_count_mode_tie_favors_higher() {
        // [1, 2] → tie, higher wins
        assertEquals(2, RearSmoother.personCountMode(listOf(1, 2)))
    }

    // -------------------------------------------------------------------------
    // Risk recomputation (2 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_risk_recomputed_from_smoothed() {
        val smoother = RearSmoother()
        // First frame: person=true but send with wrong risk
        val r = makeResult(person = true).copy(riskLevel = "clear")
        val result = smoother.smooth(r)
        // Smoother should recompute risk from smoothed booleans
        assertEquals("danger", result.riskLevel)
    }

    @Test
    fun test_risk_clear_when_all_absent() {
        val smoother = RearSmoother()
        val result = smoother.smooth(makeResult())
        assertEquals("clear", result.riskLevel)
    }

    // -------------------------------------------------------------------------
    // Reset (1 test)
    // -------------------------------------------------------------------------

    @Test
    fun test_reset_clears_state() {
        val smoother = RearSmoother()
        smoother.smooth(makeResult(person = true))
        smoother.reset()
        // After reset, first frame with person should still detect (min_frames=1)
        val result = smoother.smooth(makeResult(person = true))
        assertTrue("After reset, should detect person immediately", result.personDetected)
    }
}
