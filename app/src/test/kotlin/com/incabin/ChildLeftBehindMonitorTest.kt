package com.incabin

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for ChildLeftBehindMonitor pure companion functions and instance behavior.
 * No Android dependencies — fully unit-testable.
 */
class ChildLeftBehindMonitorTest {

    @Before
    fun setUp() {
        Config.ENABLE_CHILD_LEFT_BEHIND = true
    }

    @After
    fun tearDown() {
        Config.ENABLE_CHILD_LEFT_BEHIND = true
    }

    // -------------------------------------------------------------------------
    // shouldAlert (pure companion)
    // -------------------------------------------------------------------------

    @Test
    fun shouldAlert_parkedWithChild_overThreshold_notAlerted_returnsTrue() {
        assertTrue(ChildLeftBehindMonitor.shouldAlert(
            isParked = true, childConsecutiveFrames = 3,
            debounceThreshold = 3, alreadyAlerted = false
        ))
    }

    @Test
    fun shouldAlert_parkedWithChild_belowThreshold_returnsFalse() {
        assertFalse(ChildLeftBehindMonitor.shouldAlert(
            isParked = true, childConsecutiveFrames = 2,
            debounceThreshold = 3, alreadyAlerted = false
        ))
    }

    @Test
    fun shouldAlert_notParked_returnsFalse() {
        assertFalse(ChildLeftBehindMonitor.shouldAlert(
            isParked = false, childConsecutiveFrames = 5,
            debounceThreshold = 3, alreadyAlerted = false
        ))
    }

    @Test
    fun shouldAlert_alreadyAlerted_returnsFalse() {
        assertFalse(ChildLeftBehindMonitor.shouldAlert(
            isParked = true, childConsecutiveFrames = 5,
            debounceThreshold = 3, alreadyAlerted = true
        ))
    }

    @Test
    fun shouldAlert_exactlyAtThreshold_returnsTrue() {
        assertTrue(ChildLeftBehindMonitor.shouldAlert(
            isParked = true, childConsecutiveFrames = 3,
            debounceThreshold = 3, alreadyAlerted = false
        ))
    }

    @Test
    fun shouldAlert_aboveThreshold_notAlerted_returnsTrue() {
        assertTrue(ChildLeftBehindMonitor.shouldAlert(
            isParked = true, childConsecutiveFrames = 10,
            debounceThreshold = 3, alreadyAlerted = false
        ))
    }

    // -------------------------------------------------------------------------
    // nextConsecutiveCount (pure companion)
    // -------------------------------------------------------------------------

    @Test
    fun nextConsecutiveCount_childPresent_increments() {
        assertEquals(1, ChildLeftBehindMonitor.nextConsecutiveCount(0, true))
        assertEquals(4, ChildLeftBehindMonitor.nextConsecutiveCount(3, true))
    }

    @Test
    fun nextConsecutiveCount_childAbsent_resetsToZero() {
        assertEquals(0, ChildLeftBehindMonitor.nextConsecutiveCount(5, false))
    }

    // -------------------------------------------------------------------------
    // Instance behavior (update)
    // -------------------------------------------------------------------------

    @Test
    fun update_firesOnce_thenSuppresses() {
        val monitor = ChildLeftBehindMonitor()
        // Accumulate child frames while parked
        assertFalse(monitor.update(true, true)) // frame 1
        assertFalse(monitor.update(true, true)) // frame 2
        assertTrue(monitor.update(true, true))  // frame 3 — fires!
        assertFalse(monitor.update(true, true)) // frame 4 — already alerted
    }

    @Test
    fun update_resetsOnMove() {
        val monitor = ChildLeftBehindMonitor()
        // Fire alert
        monitor.update(true, true)
        monitor.update(true, true)
        assertTrue(monitor.update(true, true))
        // Vehicle moves
        monitor.update(false, false)
        // Park again with child — should fire again
        assertFalse(monitor.update(true, true)) // frame 1
        assertFalse(monitor.update(true, true)) // frame 2
        assertTrue(monitor.update(true, true))  // frame 3 — fires again!
    }

    @Test
    fun update_featureDisabled_neverFires() {
        Config.ENABLE_CHILD_LEFT_BEHIND = false
        val monitor = ChildLeftBehindMonitor()
        monitor.update(true, true)
        monitor.update(true, true)
        assertFalse(monitor.update(true, true)) // would fire, but feature disabled
    }

    @Test
    fun update_childAbsentBreaksStreak() {
        val monitor = ChildLeftBehindMonitor()
        monitor.update(true, true)  // 1
        monitor.update(true, true)  // 2
        monitor.update(true, false) // child gone — resets
        assertFalse(monitor.update(true, true)) // 1 again
        assertFalse(monitor.update(true, true)) // 2
        assertTrue(monitor.update(true, true))  // 3 — fires
    }

    @Test
    fun reset_clearsAllState() {
        val monitor = ChildLeftBehindMonitor()
        monitor.update(true, true)
        monitor.update(true, true)
        assertTrue(monitor.update(true, true)) // fires
        monitor.reset()
        // After reset, needs to accumulate again
        assertFalse(monitor.update(true, true))
        assertFalse(monitor.update(true, true))
        assertTrue(monitor.update(true, true)) // fires again
    }
}
