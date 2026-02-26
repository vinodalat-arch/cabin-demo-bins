package com.incabin

/**
 * 2-frame temporal smoother for rear camera detections.
 *
 * Uses a shorter window than in-cabin (2 vs 3) since reverse situations
 * need faster response. Majority voting on person/cat/dog booleans.
 * Person count uses mode (highest count wins on tie).
 */
class RearSmoother(
    private val windowSize: Int = Config.REAR_SMOOTHER_WINDOW
) {
    private val buffer = ArrayDeque<RearResult>()
    private var personStreak = 0
    private var catStreak = 0
    private var dogStreak = 0

    companion object {
        /**
         * Pure function: compute the mode of person counts in a buffer.
         * On tie, highest count wins.
         */
        fun personCountMode(counts: List<Int>): Int {
            if (counts.isEmpty()) return 0
            val freq = IntArray(16)
            var maxCount = 0
            for (c in counts) {
                val clamped = c.coerceIn(0, freq.size - 1)
                freq[clamped]++
                if (clamped > maxCount) maxCount = clamped
            }
            var bestCount = 0
            var bestFreq = 0
            for (i in 0..maxCount) {
                if (freq[i] > bestFreq || (freq[i] == bestFreq && i > bestCount)) {
                    bestFreq = freq[i]
                    bestCount = i
                }
            }
            return bestCount
        }
    }

    /**
     * Smooth the given [result] using temporal majority voting.
     */
    fun smooth(result: RearResult): RearResult {
        buffer.addLast(result)
        if (buffer.size > windowSize) {
            buffer.removeFirst()
        }

        val n = buffer.size
        val threshold = 0.5f  // majority in 2-frame window = at least 1

        // Count detections over buffer
        var personCount = 0
        var catCount = 0
        var dogCount = 0
        val personCounts = mutableListOf<Int>()

        for (frame in buffer) {
            if (frame.personDetected) personCount++
            if (frame.catDetected) catCount++
            if (frame.dogDetected) dogCount++
            personCounts.add(frame.personCount)
        }

        // Majority voting
        val rawPerson = (personCount.toFloat() / n) >= threshold
        val rawCat = (catCount.toFloat() / n) >= threshold
        val rawDog = (dogCount.toFloat() / n) >= threshold

        // Sustained detection streaks
        if (rawPerson) personStreak++ else personStreak = 0
        if (rawCat) catStreak++ else catStreak = 0
        if (rawDog) dogStreak++ else dogStreak = 0

        val smoothedPerson = personStreak >= Config.REAR_PERSON_MIN_FRAMES
        val smoothedCat = catStreak >= Config.REAR_ANIMAL_MIN_FRAMES
        val smoothedDog = dogStreak >= Config.REAR_ANIMAL_MIN_FRAMES

        val smoothedPersonCount = personCountMode(personCounts)
        val risk = RearResult.computeRisk(smoothedPerson, smoothedCat, smoothedDog)

        return result.copy(
            personDetected = smoothedPerson,
            personCount = smoothedPersonCount,
            catDetected = smoothedCat,
            dogDetected = smoothedDog,
            riskLevel = risk
        )
    }

    /** Reset smoother state (e.g., when rear camera stops). */
    fun reset() {
        buffer.clear()
        personStreak = 0
        catStreak = 0
        dogStreak = 0
    }
}
