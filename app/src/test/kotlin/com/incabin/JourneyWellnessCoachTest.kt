package com.incabin

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class JourneyWellnessCoachTest {

    @Before
    fun setUp() {
        Config.ENABLE_WELLNESS_COACH = true
    }

    @After
    fun tearDown() {
        Config.ENABLE_WELLNESS_COACH = true
    }

    // -- shouldAnnounce --

    @Test
    fun shouldAnnounce_before_milestone1_returns_null() {
        assertNull(JourneyWellnessCoach.shouldAnnounce(44, 0))
    }

    @Test
    fun shouldAnnounce_at_milestone1_returns_45() {
        assertEquals(45, JourneyWellnessCoach.shouldAnnounce(45, 0))
    }

    @Test
    fun shouldAnnounce_at_milestone2_returns_90() {
        assertEquals(90, JourneyWellnessCoach.shouldAnnounce(90, 45))
    }

    @Test
    fun shouldAnnounce_at_milestone3_returns_120() {
        assertEquals(120, JourneyWellnessCoach.shouldAnnounce(120, 90))
    }

    @Test
    fun shouldAnnounce_repeat_after_milestone3() {
        // 150 = 120 + 30 (one repeat interval)
        assertEquals(150, JourneyWellnessCoach.shouldAnnounce(150, 120))
    }

    @Test
    fun shouldAnnounce_no_repeat_within_interval() {
        assertNull(JourneyWellnessCoach.shouldAnnounce(140, 120))
    }

    // -- computePosturePercent --

    @Test
    fun posture_percent_all_upright() {
        assertEquals(100, JourneyWellnessCoach.computePosturePercent(100, 100))
    }

    @Test
    fun posture_percent_none_upright() {
        assertEquals(0, JourneyWellnessCoach.computePosturePercent(0, 100))
    }

    @Test
    fun posture_percent_half_upright() {
        assertEquals(50, JourneyWellnessCoach.computePosturePercent(50, 100))
    }

    @Test
    fun posture_percent_zero_total() {
        assertEquals(0, JourneyWellnessCoach.computePosturePercent(0, 0))
    }

    // -- hvacOffsetForDuration --

    @Test
    fun hvac_offset_before_milestone1() {
        assertEquals(0.0f, JourneyWellnessCoach.hvacOffsetForDuration(30), 0.01f)
    }

    @Test
    fun hvac_offset_at_milestone1() {
        assertEquals(-1.0f, JourneyWellnessCoach.hvacOffsetForDuration(45), 0.01f)
    }

    @Test
    fun hvac_offset_at_milestone2() {
        assertEquals(-1.5f, JourneyWellnessCoach.hvacOffsetForDuration(90), 0.01f)
    }

    @Test
    fun hvac_offset_at_milestone3() {
        assertEquals(-2.0f, JourneyWellnessCoach.hvacOffsetForDuration(120), 0.01f)
    }

    // -- formatMessage --

    @Test
    fun format_message_english_milestone1() {
        val msg = JourneyWellnessCoach.formatMessage(45, 95, false)
        assertTrue(msg.contains("45 minutes"))
        assertTrue(msg.contains("Great focus"))
    }

    @Test
    fun format_message_english_milestone2() {
        val msg = JourneyWellnessCoach.formatMessage(90, 80, false)
        assertTrue(msg.contains("Consider a break"))
    }

    @Test
    fun format_message_japanese_milestone1() {
        val msg = JourneyWellnessCoach.formatMessage(45, 95, true)
        assertTrue(msg.contains("45分"))
    }

    // -- disabled --

    @Test
    fun disabled_returns_null() {
        Config.ENABLE_WELLNESS_COACH = false
        val coach = JourneyWellnessCoach()
        coach.start(0L)
        val result = OutputResult.default()
        assertNull(coach.update(result, 50 * 60_000L, false))
    }
}
