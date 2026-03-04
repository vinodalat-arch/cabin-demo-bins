package com.incabin

import org.junit.Assert.*
import org.junit.Test

class TripStatsTest {

    private fun makeStats(
        durationMs: Long = 60 * 60_000L,
        totalFrames: Int = 3600,
        uprightFrames: Int = 3600,
        detectionCounts: Map<String, Int> = emptyMap(),
        avgScore: Int = 100,
        bestStreakMs: Long = 60 * 60_000L,
        comfortEvents: Int = 0,
        wellnessMilestones: Int = 0,
        nightDrive: Boolean = false,
        maxPassengers: Int = 1
    ) = TripStats(
        durationMs, totalFrames, uprightFrames, detectionCounts,
        avgScore, bestStreakMs, comfortEvents, wellnessMilestones,
        nightDrive, maxPassengers
    )

    // -- computePosturePercent --

    @Test
    fun posture_percent_all() {
        assertEquals(100, TripStats.computePosturePercent(100, 100))
    }

    @Test
    fun posture_percent_half() {
        assertEquals(50, TripStats.computePosturePercent(50, 100))
    }

    @Test
    fun posture_percent_zero_total() {
        assertEquals(0, TripStats.computePosturePercent(0, 0))
    }

    // -- Badge: ZEN_DRIVER --

    @Test
    fun badge_zen_driver_30min_clean_streak() {
        val stats = makeStats(durationMs = 35 * 60_000L, bestStreakMs = 35 * 60_000L)
        val badges = TripStats.awardBadges(stats)
        assertTrue(badges.contains(Badge.ZEN_DRIVER))
    }

    @Test
    fun badge_zen_driver_short_streak_no_badge() {
        val stats = makeStats(durationMs = 35 * 60_000L, bestStreakMs = 20 * 60_000L)
        assertFalse(TripStats.awardBadges(stats).contains(Badge.ZEN_DRIVER))
    }

    // -- Badge: NIGHT_OWL --

    @Test
    fun badge_night_owl() {
        val stats = makeStats(nightDrive = true)
        assertTrue(TripStats.awardBadges(stats).contains(Badge.NIGHT_OWL))
    }

    @Test
    fun badge_night_owl_daytime() {
        val stats = makeStats(nightDrive = false)
        assertFalse(TripStats.awardBadges(stats).contains(Badge.NIGHT_OWL))
    }

    // -- Badge: ROAD_TRIP_PRO --

    @Test
    fun badge_road_trip_pro() {
        val stats = makeStats(durationMs = 130 * 60_000L, maxPassengers = 4)
        assertTrue(TripStats.awardBadges(stats).contains(Badge.ROAD_TRIP_PRO))
    }

    @Test
    fun badge_road_trip_pro_not_enough_passengers() {
        val stats = makeStats(durationMs = 130 * 60_000L, maxPassengers = 3)
        assertFalse(TripStats.awardBadges(stats).contains(Badge.ROAD_TRIP_PRO))
    }

    // -- Badge: PERFECT_POSTURE --

    @Test
    fun badge_perfect_posture() {
        val stats = makeStats(uprightFrames = 91, totalFrames = 100)
        assertTrue(TripStats.awardBadges(stats).contains(Badge.PERFECT_POSTURE))
    }

    @Test
    fun badge_perfect_posture_exactly_90_no_badge() {
        val stats = makeStats(uprightFrames = 90, totalFrames = 100)
        assertFalse(TripStats.awardBadges(stats).contains(Badge.PERFECT_POSTURE))
    }

    // -- Badge: COMFORT_CAPTAIN --

    @Test
    fun badge_comfort_captain() {
        val stats = makeStats(comfortEvents = 5)
        assertTrue(TripStats.awardBadges(stats).contains(Badge.COMFORT_CAPTAIN))
    }

    // -- Badge: MARATHON --

    @Test
    fun badge_marathon() {
        val stats = makeStats(durationMs = 180 * 60_000L)
        assertTrue(TripStats.awardBadges(stats).contains(Badge.MARATHON))
    }

    // -- Badge: REFRESHED --

    @Test
    fun badge_refreshed() {
        val stats = makeStats(wellnessMilestones = 2)
        assertTrue(TripStats.awardBadges(stats).contains(Badge.REFRESHED))
    }

    // -- Badge: FAMILY_DRIVE --

    @Test
    fun badge_family_drive() {
        val stats = makeStats(detectionCounts = mapOf("child_present" to 30))
        assertTrue(TripStats.awardBadges(stats).contains(Badge.FAMILY_DRIVE))
    }

    @Test
    fun badge_family_drive_not_enough() {
        val stats = makeStats(detectionCounts = mapOf("child_present" to 29))
        assertFalse(TripStats.awardBadges(stats).contains(Badge.FAMILY_DRIVE))
    }

    // -- formatSummary --

    @Test
    fun format_summary_english() {
        val stats = makeStats(comfortEvents = 3)
        val summary = TripStats.formatSummary(stats, listOf(Badge.ZEN_DRIVER), false)
        assertTrue(summary.contains("Trip duration"))
        assertTrue(summary.contains("Zen Driver"))
    }

    @Test
    fun format_summary_japanese() {
        val stats = makeStats()
        val summary = TripStats.formatSummary(stats, listOf(Badge.NIGHT_OWL), true)
        assertTrue(summary.contains("走行時間"))
        assertTrue(summary.contains("夜行性"))
    }

    @Test
    fun format_summary_no_badges() {
        val stats = makeStats()
        val summary = TripStats.formatSummary(stats, emptyList(), false)
        assertFalse(summary.contains("Badges"))
    }
}
