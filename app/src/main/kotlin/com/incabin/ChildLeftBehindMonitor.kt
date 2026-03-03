package com.incabin

/**
 * Child left-behind safety monitor.
 *
 * Triggers when the vehicle is in PARK and a child is detected for
 * [Config.CHILD_LEFT_BEHIND_DEBOUNCE_FRAMES] consecutive frames.
 * Fires alert once per park event; resets when vehicle moves.
 *
 * All decision logic lives in pure companion functions for testability.
 * No android.util.Log usage — instance methods are unit-testable without mocks.
 */
class ChildLeftBehindMonitor {

    companion object {
        /**
         * Pure function: should we fire a child-left-behind alert?
         * Requires vehicle parked, consecutive child frames meeting debounce threshold,
         * and not already alerted for this park event.
         */
        fun shouldAlert(
            isParked: Boolean,
            childConsecutiveFrames: Int,
            debounceThreshold: Int,
            alreadyAlerted: Boolean
        ): Boolean {
            if (!isParked) return false
            if (alreadyAlerted) return false
            return childConsecutiveFrames >= debounceThreshold
        }

        /**
         * Pure function: compute next consecutive child frame count.
         * Increments on child present, resets to 0 on absence.
         */
        fun nextConsecutiveCount(current: Int, childPresent: Boolean): Int {
            return if (childPresent) current + 1 else 0
        }
    }

    private var consecutiveChildFrames = 0
    private var alertedThisParkEvent = false
    private var wasParked = false

    /**
     * Called once per frame with current park state and child detection.
     * Returns true if a child-left-behind alert should fire (first time per park event).
     */
    fun update(isParked: Boolean, childPresent: Boolean): Boolean {
        if (!Config.ENABLE_CHILD_LEFT_BEHIND) return false

        // Reset alert flag when vehicle moves (transition from parked to moving)
        if (wasParked && !isParked) {
            alertedThisParkEvent = false
            consecutiveChildFrames = 0
        }
        wasParked = isParked

        consecutiveChildFrames = nextConsecutiveCount(consecutiveChildFrames, childPresent)

        val fire = shouldAlert(
            isParked, consecutiveChildFrames,
            Config.CHILD_LEFT_BEHIND_DEBOUNCE_FRAMES, alertedThisParkEvent
        )

        if (fire) {
            alertedThisParkEvent = true
        }

        return fire
    }

    /** Reset all state (e.g., on monitoring stop). */
    fun reset() {
        consecutiveChildFrames = 0
        alertedThisParkEvent = false
        wasParked = false
    }
}
