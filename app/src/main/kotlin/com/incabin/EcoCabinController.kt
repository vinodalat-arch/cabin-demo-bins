package com.incabin

/**
 * When cabin is fully empty and parked, sets HVAC to eco temp and dims display.
 * Restores on occupant return.
 * All decision logic in pure companion functions — no Android dependencies.
 */
class EcoCabinController {

    var active: Boolean = false
        private set
    private var emptyFrames: Int = 0
    private var previousBrightness: Int = -1
    private var previousHvac: Float = -1f

    /**
     * Called once per frame. Returns CabinEvent on activation/deactivation, else null.
     */
    fun update(
        seatMap: SeatMap?,
        isParked: Boolean,
        currentBrightness: Int,
        currentHvac: Float,
        isJapanese: Boolean,
        applyBrightness: ((Int) -> Unit)? = null,
        applyHvac: ((Float) -> Unit)? = null
    ): CabinEvent? {
        if (!Config.ENABLE_ECO_CABIN) return null

        val empty = seatMap != null && isFullyEmpty(seatMap)

        // Deactivate if someone returns
        if (active && shouldDeactivate(empty)) {
            active = false
            emptyFrames = 0
            if (previousBrightness >= 0) applyBrightness?.invoke(previousBrightness)
            if (previousHvac >= 0) applyHvac?.invoke(previousHvac)
            previousBrightness = -1
            previousHvac = -1f
            return CabinEvent(CabinEvent.INFO, formatMessage(activating = false, isJapanese = isJapanese))
        }

        if (!active) {
            if (empty && isParked) {
                emptyFrames++
            } else {
                emptyFrames = 0
            }

            if (shouldActivate(empty, isParked, emptyFrames, Config.ECO_DEBOUNCE_FRAMES)) {
                active = true
                previousBrightness = currentBrightness
                previousHvac = currentHvac
                applyBrightness?.invoke(Config.ECO_BRIGHTNESS)
                applyHvac?.invoke(Config.CLIMATE_MAX_TEMP_C)
                return CabinEvent(CabinEvent.INFO, formatMessage(activating = true, isJapanese = isJapanese))
            }
        }

        return null
    }

    fun reset(applyBrightness: ((Int) -> Unit)? = null, applyHvac: ((Float) -> Unit)? = null) {
        if (active) {
            if (previousBrightness >= 0) applyBrightness?.invoke(previousBrightness)
            if (previousHvac >= 0) applyHvac?.invoke(previousHvac)
        }
        active = false
        emptyFrames = 0
        previousBrightness = -1
        previousHvac = -1f
    }

    companion object {
        fun isFullyEmpty(seatMap: SeatMap): Boolean {
            return !seatMap.driver.occupied &&
                !seatMap.frontPassenger.occupied &&
                !seatMap.rearLeft.occupied &&
                !seatMap.rearCenter.occupied &&
                !seatMap.rearRight.occupied
        }

        fun shouldActivate(empty: Boolean, parked: Boolean, stableFrames: Int, threshold: Int): Boolean {
            return empty && parked && stableFrames >= threshold
        }

        fun shouldDeactivate(empty: Boolean): Boolean {
            return !empty
        }

        fun formatMessage(activating: Boolean, isJapanese: Boolean): String {
            return if (isJapanese) {
                if (activating) "車内を節電モードにしました。またお会いしましょう！"
                else "おかえりなさい！"
            } else {
                if (activating) "Cabin secured. See you next time!"
                else "Welcome back!"
            }
        }
    }
}
