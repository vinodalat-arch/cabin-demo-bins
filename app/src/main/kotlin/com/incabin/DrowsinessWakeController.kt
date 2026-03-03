package com.incabin

/**
 * Thin controller for drowsiness wake-up via seat massage.
 * Fires on rising edge (non-drowsy → drowsy transition).
 * Mirrors ClimateController pattern — no VHAL code, delegates to SeatMassageChannel.
 *
 * No android.util.Log usage — instance methods are unit-testable without mocks.
 */
class DrowsinessWakeController {

    companion object {
        /**
         * Pure function: should we trigger a drowsiness wake-up?
         * True on rising edge (was not drowsy, now is drowsy).
         */
        fun shouldTrigger(wasDrowsy: Boolean, isDrowsy: Boolean): Boolean {
            return !wasDrowsy && isDrowsy
        }
    }

    private var wasDrowsy = false

    /**
     * Called once per frame with current drowsiness indicators.
     * Returns true on rising edge (non-drowsy → drowsy transition).
     */
    fun update(eyesClosed: Boolean, yawning: Boolean): Boolean {
        if (!Config.ENABLE_SEAT_MASSAGE) return false

        val isDrowsy = eyesClosed || yawning
        val trigger = shouldTrigger(wasDrowsy, isDrowsy)
        wasDrowsy = isDrowsy

        return trigger
    }

    /** Reset state (e.g., on monitoring stop). */
    fun reset() {
        wasDrowsy = false
    }
}
