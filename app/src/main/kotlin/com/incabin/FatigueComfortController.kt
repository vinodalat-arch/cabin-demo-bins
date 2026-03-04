package com.incabin

/**
 * On driver drowsiness, cools cabin and brightens display to increase alertness.
 * Restores original settings after 30 consecutive clear frames.
 * All decision logic in pure companion functions — no Android dependencies.
 */
class FatigueComfortController {

    var active: Boolean = false
        private set
    private var originalBrightness: Int = -1
    private var allClearFrames: Int = 0

    /**
     * Called once per frame. Returns CabinEvent on activation/deactivation, else null.
     * [applyBrightness] callback allows caller to apply brightness without Android import here.
     */
    fun update(
        eyesClosed: Boolean,
        yawning: Boolean,
        currentBrightness: Int,
        isJapanese: Boolean,
        applyBrightness: ((Int) -> Unit)? = null
    ): CabinEvent? {
        if (!Config.ENABLE_FATIGUE_COMFORT) return null

        if (shouldActivate(eyesClosed, yawning, active)) {
            active = true
            allClearFrames = 0
            originalBrightness = currentBrightness
            val newBrightness = alertBrightness(currentBrightness, Config.FATIGUE_BRIGHTNESS_BOOST)
            applyBrightness?.invoke(newBrightness)
            return CabinEvent(
                CabinEvent.IMPORTANT,
                formatActivateMessage(isJapanese),
                Config.FATIGUE_HVAC_OFFSET_C
            )
        }

        if (active) {
            if (!eyesClosed && !yawning) {
                allClearFrames++
            } else {
                allClearFrames = 0
            }

            if (shouldDeactivate(allClearFrames, Config.FATIGUE_DEACTIVATE_FRAMES)) {
                active = false
                if (originalBrightness >= 0) {
                    applyBrightness?.invoke(originalBrightness)
                }
                val event = CabinEvent(
                    CabinEvent.INFO,
                    formatDeactivateMessage(isJapanese),
                    0f // restore HVAC
                )
                originalBrightness = -1
                allClearFrames = 0
                return event
            }
        }

        return null
    }

    fun reset(applyBrightness: ((Int) -> Unit)? = null) {
        if (active && originalBrightness >= 0) {
            applyBrightness?.invoke(originalBrightness)
        }
        active = false
        originalBrightness = -1
        allClearFrames = 0
    }

    companion object {
        fun shouldActivate(eyesClosed: Boolean, yawning: Boolean, currentlyActive: Boolean): Boolean {
            if (currentlyActive) return false
            return eyesClosed || yawning
        }

        fun shouldDeactivate(allClearFrames: Int, threshold: Int): Boolean {
            return allClearFrames >= threshold
        }

        fun alertBrightness(current: Int, boost: Int): Int {
            return (current + boost).coerceAtMost(255)
        }

        fun formatActivateMessage(isJapanese: Boolean): String {
            return if (isJapanese) "集中してください。車内を冷却します。"
            else "Stay alert. Cabin cooling down."
        }

        fun formatDeactivateMessage(isJapanese: Boolean): String {
            return if (isJapanese) "元気になったようですね。"
            else "Good, you seem refreshed."
        }
    }
}
