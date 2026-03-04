package com.incabin

import android.util.Log

/**
 * Coordination layer for all smart cabin comfort features.
 * Called from AlertOrchestrator.evaluate() as Step 6, wrapped in try-catch.
 *
 * Cross-feature coordination rules:
 * - Quiet Mode is shared: Nap Mode and Child Comfort both activate it
 * - Fatigue Response overrides HVAC temporarily (stores/restores base)
 * - Eco Shutdown only when ALL seats vacant (Nap Mode blocks it)
 * - Arrival Preparation fires before Eco
 */
class CabinExperienceManager(private val audioAlerter: AudioAlerter?) {

    val wellnessCoach = JourneyWellnessCoach()
    val quietMode = QuietModeController()
    val fatigueComfort = FatigueComfortController()
    val napMode = NapModeController()
    val childComfort = ChildComfortController()
    val ecoCabin = EcoCabinController()
    val arrival = ArrivalController()

    // Stats accumulation
    private var comfortEventCount: Int = 0
    private var scoreSum: Long = 0
    private var scoreCount: Int = 0
    private var bestStreakMs: Long = 0
    private var currentStreakStartMs: Long = 0
    private var maxPassengers: Int = 0
    private var childPresentFrames: Int = 0
    private var detectionCounts = mutableMapOf<String, Int>()

    companion object {
        private const val TAG = "CabinExperience"
    }

    fun start(nowMs: Long = System.currentTimeMillis()) {
        val now = nowMs
        wellnessCoach.start(now)
        arrival.start(now)
        currentStreakStartMs = now
        comfortEventCount = 0
        scoreSum = 0
        scoreCount = 0
        bestStreakMs = 0
        maxPassengers = 0
        childPresentFrames = 0
        detectionCounts.clear()
    }

    /**
     * Main entry point — called once per frame.
     * Returns list of CabinEvents for TTS announcements.
     */
    fun evaluate(
        result: OutputResult,
        seatMap: SeatMap?,
        isParked: Boolean,
        currentBrightness: Int,
        currentHvac: Float,
        applyBrightness: ((Int) -> Unit)?,
        applyHvac: ((Float) -> Unit)?,
        nowMs: Long = System.currentTimeMillis()
    ): List<CabinEvent> {
        val events = mutableListOf<CabinEvent>()
        val isJapanese = Config.LANGUAGE == "ja"
        val now = nowMs

        // Accumulate stats
        accumulateStats(result, now)

        // Feature 1: Journey Wellness Coach
        try {
            wellnessCoach.update(result, now, isJapanese)?.let { event ->
                events.add(event)
                comfortEventCount++
                // Apply HVAC cooling offset for long drives
                if (event.hvacOffset != 0f) {
                    applyHvac?.invoke(currentHvac + event.hvacOffset)
                }
            }
        } catch (e: Exception) { Log.w(TAG, "WellnessCoach failed", e) }

        // Feature 4: Nap Mode (before Quiet Mode — it can activate quiet mode)
        try {
            val prevSleeping = napMode.getSleepingSeats()
            val napEvents = napMode.update(seatMap, isJapanese)
            events.addAll(napEvents)
            if (napEvents.isNotEmpty()) comfortEventCount += napEvents.size
            // Warm zone for new sleepers, restore for wakers
            val currSleeping = napMode.getSleepingSeats()
            if (currSleeping != prevSleeping) {
                if (currSleeping.isNotEmpty()) {
                    applyHvac?.invoke(currentHvac + Config.NAP_HVAC_WARM_OFFSET_C)
                } else {
                    applyHvac?.invoke(currentHvac)  // restore
                }
            }
        } catch (e: Exception) { Log.w(TAG, "NapMode failed", e) }

        // Feature 6: Child Comfort
        try {
            val hadChild = childComfort.hasChildInRear()
            childComfort.update(result, seatMap, isJapanese)?.let {
                events.add(it)
                comfortEventCount++
            }
            // Warm rear zone when child first detected
            if (!hadChild && childComfort.hasChildInRear()) {
                applyHvac?.invoke(currentHvac + Config.CHILD_COMFORT_HVAC_OFFSET_C)
            } else if (hadChild && !childComfort.hasChildInRear()) {
                applyHvac?.invoke(currentHvac)  // restore
            }
        } catch (e: Exception) { Log.w(TAG, "ChildComfort failed", e) }

        // Feature 2: Quiet Mode (after nap/child — they can force-activate it)
        try {
            // Nap mode or child sleeping activates quiet mode externally
            if (napMode.hasAnySleeping() || isChildSleeping(seatMap)) {
                quietMode.activateExternal(audioAlerter)
            }
            quietMode.update(seatMap, audioAlerter, isJapanese)?.let {
                if (it.message.isNotEmpty()) events.add(it)
            }
        } catch (e: Exception) { Log.w(TAG, "QuietMode failed", e) }

        // Feature 3: Fatigue Comfort
        try {
            fatigueComfort.update(
                result.driverEyesClosed,
                result.driverYawning,
                currentBrightness,
                isJapanese,
                applyBrightness
            )?.let { event ->
                events.add(event)
                comfortEventCount++
                // Apply HVAC offset: negative = cool, 0 = restore base
                if (event.hvacOffset != 0f) {
                    applyHvac?.invoke(currentHvac + event.hvacOffset)
                } else if (!fatigueComfort.active) {
                    // Deactivated — restore base HVAC
                    applyHvac?.invoke(currentHvac)
                }
            }
        } catch (e: Exception) { Log.w(TAG, "FatigueComfort failed", e) }

        // Feature 8: Arrival (before Eco — arrival fires first)
        try {
            arrival.update(Config.VEHICLE_SPEED_KMH, isParked, now, isJapanese)?.let {
                events.add(it)
                comfortEventCount++
            }
        } catch (e: Exception) { Log.w(TAG, "Arrival failed", e) }

        // Feature 7: Eco Cabin (last — blocked by nap mode)
        try {
            if (!napMode.hasAnySleeping()) {
                ecoCabin.update(
                    seatMap, isParked, currentBrightness, currentHvac,
                    isJapanese, applyBrightness, applyHvac
                )?.let {
                    events.add(it)
                    comfortEventCount++
                }
            }
        } catch (e: Exception) { Log.w(TAG, "EcoCabin failed", e) }

        return events
    }

    private fun isChildSleeping(seatMap: SeatMap?): Boolean {
        if (seatMap == null) return false
        // Check rear seats for sleeping children (heuristic: child_present + sleeping state)
        return (seatMap.rearLeft.occupied && seatMap.rearLeft.state == "Sleeping") ||
            (seatMap.rearCenter.occupied && seatMap.rearCenter.state == "Sleeping") ||
            (seatMap.rearRight.occupied && seatMap.rearRight.state == "Sleeping")
    }

    private fun accumulateStats(result: OutputResult, nowMs: Long) {
        // Score tracking
        val score = AlertOrchestrator.computeScore(result)
        scoreSum += score
        scoreCount++

        // Streak tracking (consecutive all-clear)
        val hasDanger = AlertOrchestrator.activeDangers(result).isNotEmpty()
        if (hasDanger) {
            val streak = nowMs - currentStreakStartMs
            if (streak > bestStreakMs) bestStreakMs = streak
            currentStreakStartMs = nowMs
        }

        // Max passengers
        if (result.passengerCount > maxPassengers) maxPassengers = result.passengerCount

        // Child frames
        if (result.childPresent) childPresentFrames++

        // Detection counts
        if (result.driverUsingPhone) detectionCounts.merge("phone", 1, Int::plus)
        if (result.driverEyesClosed) detectionCounts.merge("eyes_closed", 1, Int::plus)
        if (result.driverYawning) detectionCounts.merge("yawning", 1, Int::plus)
        if (result.driverDistracted) detectionCounts.merge("distracted", 1, Int::plus)
        if (result.driverEatingDrinking) detectionCounts.merge("eating", 1, Int::plus)
        if (result.childPresent) detectionCounts.merge("child_present", 1, Int::plus)
    }

    /** Build TripStats for session summary. */
    fun buildTripStats(durationMs: Long, nightDrive: Boolean, nowMs: Long = System.currentTimeMillis()): TripStats {
        // Finalize best streak
        val finalStreak = if (currentStreakStartMs > 0) {
            val lastStreak = nowMs - currentStreakStartMs
            maxOf(bestStreakMs, lastStreak)
        } else bestStreakMs

        return TripStats(
            durationMs = durationMs,
            totalFrames = wellnessCoach.getTotalFrames(),
            uprightFrames = wellnessCoach.getUprightFrames(),
            detectionCounts = detectionCounts.toMap(),
            avgScore = if (scoreCount > 0) (scoreSum / scoreCount).toInt() else 100,
            bestStreakMs = finalStreak,
            comfortEvents = comfortEventCount,
            wellnessMilestones = wellnessCoach.getLastMilestone().let {
                when {
                    it >= Config.WELLNESS_MILESTONE_3_MIN -> 3
                    it >= Config.WELLNESS_MILESTONE_2_MIN -> 2
                    it >= Config.WELLNESS_MILESTONE_1_MIN -> 1
                    else -> 0
                }
            },
            nightDrive = nightDrive,
            maxPassengers = maxPassengers
        )
    }

    fun resetState() {
        wellnessCoach.reset()
        quietMode.reset(audioAlerter)
        fatigueComfort.reset()
        napMode.reset()
        childComfort.reset()
        ecoCabin.reset()
        arrival.reset()
        comfortEventCount = 0
        scoreSum = 0
        scoreCount = 0
        bestStreakMs = 0
        currentStreakStartMs = 0
        maxPassengers = 0
        childPresentFrames = 0
        detectionCounts.clear()
    }
}

/**
 * Event emitted by a comfort feature for TTS announcement.
 */
data class CabinEvent(
    val priority: Int,
    val message: String,
    val hvacOffset: Float = 0f
) {
    companion object {
        const val INFO = 0
        const val IMPORTANT = 1
    }
}
