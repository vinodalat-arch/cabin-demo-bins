package com.incabin

/**
 * Suppresses INFO-tier TTS announcements when any non-driver passenger is sleeping.
 * All decision logic in pure companion functions — no Android dependencies.
 */
class QuietModeController {

    var active: Boolean = false
        private set
    private var wasActive: Boolean = false

    /**
     * Called once per frame. Returns a CabinEvent on state transitions, else null.
     * Sets [AudioAlerter.quietMode] as side effect.
     */
    fun update(seatMap: SeatMap?, audioAlerter: AudioAlerter?, isJapanese: Boolean): CabinEvent? {
        if (!Config.ENABLE_QUIET_MODE) {
            if (active) deactivate(audioAlerter)
            return null
        }

        val sleeping = seatMap != null && isAnySleeping(seatMap)

        wasActive = active
        active = sleeping

        audioAlerter?.quietMode = active

        // Transition: was active → now inactive (everyone woke up)
        if (wasActive && !active) {
            return CabinEvent(CabinEvent.INFO, formatMessage(entering = false, isJapanese = isJapanese))
        }
        // No announcement on activation (don't wake the sleeper)
        return null
    }

    /** Force-activated by NapMode or ChildComfort. */
    fun activateExternal(audioAlerter: AudioAlerter?) {
        active = true
        audioAlerter?.quietMode = true
    }

    private fun deactivate(audioAlerter: AudioAlerter?) {
        active = false
        audioAlerter?.quietMode = false
    }

    fun reset(audioAlerter: AudioAlerter?) {
        active = false
        wasActive = false
        audioAlerter?.quietMode = false
    }

    companion object {
        fun isAnySleeping(seatMap: SeatMap): Boolean {
            return seatMap.frontPassenger.let { it.occupied && it.state == "Sleeping" } ||
                seatMap.rearLeft.let { it.occupied && it.state == "Sleeping" } ||
                seatMap.rearCenter.let { it.occupied && it.state == "Sleeping" } ||
                seatMap.rearRight.let { it.occupied && it.state == "Sleeping" }
        }

        fun formatMessage(entering: Boolean, isJapanese: Boolean): String {
            return if (isJapanese) {
                if (entering) "" else "みなさん起きました"
            } else {
                if (entering) "" else "Everyone's awake"
            }
        }
    }
}
