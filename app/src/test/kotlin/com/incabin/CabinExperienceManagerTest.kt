package com.incabin

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CabinExperienceManagerTest {

    @Before
    fun setUp() {
        Config.ENABLE_WELLNESS_COACH = true
        Config.ENABLE_QUIET_MODE = true
        Config.ENABLE_FATIGUE_COMFORT = true
        Config.ENABLE_NAP_MODE = true
        Config.ENABLE_CHILD_COMFORT = true
        Config.ENABLE_ECO_CABIN = true
        Config.ENABLE_ARRIVAL_PREP = true
        Config.LANGUAGE = "en"
        Config.VEHICLE_SPEED_KMH = -1f
    }

    @After
    fun tearDown() {
        Config.ENABLE_WELLNESS_COACH = true
        Config.ENABLE_QUIET_MODE = true
        Config.ENABLE_FATIGUE_COMFORT = true
        Config.ENABLE_NAP_MODE = true
        Config.ENABLE_CHILD_COMFORT = true
        Config.ENABLE_ECO_CABIN = true
        Config.ENABLE_ARRIVAL_PREP = true
        Config.LANGUAGE = "en"
        Config.VEHICLE_SPEED_KMH = -1f
    }

    private fun makeManager() = CabinExperienceManager(null)

    // -- CabinEvent --

    @Test
    fun cabinEvent_priority_constants() {
        assertEquals(0, CabinEvent.INFO)
        assertEquals(1, CabinEvent.IMPORTANT)
    }

    @Test
    fun cabinEvent_defaults() {
        val event = CabinEvent(CabinEvent.INFO, "test")
        assertEquals(0f, event.hvacOffset, 0.001f)
    }

    // -- evaluate returns events --

    @Test
    fun evaluate_safe_result_no_events() {
        val mgr = makeManager()
        mgr.start()
        val result = OutputResult.default()
        val events = mgr.evaluate(result, SeatMap(), false, 150, 22.0f, null, null)
        // No comfort events on first safe frame with no time elapsed
        assertTrue(events.isEmpty())
    }

    @Test
    fun evaluate_fatigue_emits_event() {
        val mgr = makeManager()
        mgr.start()
        val drowsy = OutputResult.default().copy(driverEyesClosed = true, riskLevel = "high")
        val events = mgr.evaluate(drowsy, SeatMap(), false, 150, 22.0f, null, null)
        assertTrue(events.any { it.message.contains("alert") || it.message.contains("集中") })
    }

    // -- cross-feature coordination --

    @Test
    fun nap_mode_blocks_eco() {
        val mgr = makeManager()
        mgr.start()
        // Passenger sleeping — nap mode active, eco should NOT fire even with empty other seats
        val sleeping = SeatMap(frontPassenger = SeatState(true, "Sleeping"))
        val result = OutputResult.default()
        // Run enough frames for eco debounce (10 frames)
        for (i in 1..15) {
            mgr.evaluate(result, sleeping, true, 150, 22.0f, null, null)
        }
        assertFalse(mgr.ecoCabin.active)
    }

    @Test
    fun quiet_mode_activated_by_nap() {
        val mgr = makeManager()
        mgr.start()
        val sleeping = SeatMap(frontPassenger = SeatState(true, "Sleeping"))
        mgr.evaluate(OutputResult.default(), sleeping, false, 150, 22.0f, null, null)
        assertTrue(mgr.quietMode.active)
    }

    // -- TripStats --

    @Test
    fun buildTripStats_returns_valid() {
        val mgr = makeManager()
        mgr.start()
        // Run a few frames
        for (i in 1..10) {
            mgr.evaluate(OutputResult.default(), SeatMap(), false, 150, 22.0f, null, null)
        }
        val stats = mgr.buildTripStats(60_000L, false)
        assertEquals(60_000L, stats.durationMs)
        assertEquals(10, stats.totalFrames)
        assertTrue(stats.avgScore >= 0)
    }

    // -- resetState --

    @Test
    fun resetState_clears_all() {
        val mgr = makeManager()
        mgr.start()
        val drowsy = OutputResult.default().copy(driverEyesClosed = true, riskLevel = "high")
        mgr.evaluate(drowsy, SeatMap(), false, 150, 22.0f, null, null)
        assertTrue(mgr.fatigueComfort.active)

        mgr.resetState()
        assertFalse(mgr.fatigueComfort.active)
        assertFalse(mgr.quietMode.active)
        assertFalse(mgr.ecoCabin.active)
    }
}
