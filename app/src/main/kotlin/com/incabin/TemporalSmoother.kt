package com.incabin

/**
 * Sliding-window temporal smoother that suppresses single-frame noise
 * through majority voting.
 *
 * - Standard boolean fields use simple majority voting over the full buffer.
 * - Face-gated fields (eyes, yawning, distracted) only count frames where
 *   the corresponding face metric is non-null.
 * - Eyes-closed has a fast-clear mechanism: 2 consecutive frames with
 *   ear_value >= 0.21 immediately clears the detection regardless of history.
 * - Passenger count uses mode (highest count wins on tie).
 * - Risk level is recomputed from the smoothed boolean fields.
 * - distraction_duration_s passes through as-is (managed by the main loop).
 */
class TemporalSmoother(
    private val windowSize: Int = Config.SMOOTHER_WINDOW,
    private val threshold: Float = Config.SMOOTHER_THRESHOLD
) {
    private val _buffer = ArrayDeque<OutputResult>()
    private var _eyesOpenStreak: Int = 0
    private var _eyesClosedStreak: Int = 0
    private var _yawningStreak: Int = 0
    private var _distractedStreak: Int = 0
    private var _eatingStreak: Int = 0
    private var _postureStreak: Int = 0
    private var _childSlouchStreak: Int = 0

    /** True when the buffer has accumulated at least [windowSize] frames. */
    val warm: Boolean
        get() = _buffer.size >= windowSize

    /**
     * Map of face-gated boolean fields to their gate keys.
     * A frame is excluded from voting for a gated field when its gate value is null.
     */
    private companion object {
        /** Standard (non-gated) boolean fields. */
        val STANDARD_FIELDS = listOf(
            "driver_using_phone",
            "driver_eating_drinking",
            "dangerous_posture",
            "child_present",
            "child_slouching"
        )

        /** Face-gated fields mapped to their gate key. */
        val FACE_GATED_FIELDS = mapOf(
            "driver_eyes_closed" to "ear_value",
            "driver_yawning" to "mar_value",
            "driver_distracted" to "head_yaw"
        )
    }

    /**
     * Smooths the given [result] using temporal majority voting.
     *
     * @param result The latest raw [OutputResult] from the merger.
     * @return A new [OutputResult] with smoothed boolean fields, mode-based
     *         passenger count, and recomputed risk level.
     */
    fun smooth(result: OutputResult): OutputResult {
        // Add to buffer, drop oldest if full
        _buffer.addLast(result)
        if (_buffer.size > windowSize) {
            _buffer.removeFirst()
        }

        val n = _buffer.size

        // --- Track fast-clear streak for eyes ---
        val ear = result.earValue
        if (ear != null && ear >= Config.EAR_THRESHOLD) {
            _eyesOpenStreak++
        } else {
            _eyesOpenStreak = 0
        }

        // --- Single-pass counting over buffer (avoids per-field filter/count allocations) ---
        var earValidCount = 0; var eyesClosedCount = 0
        var marValidCount = 0; var yawningCount = 0
        var yawValidCount = 0; var distractedCount = 0
        var phoneCount = 0; var eatingCount = 0; var postureCount = 0
        var childPresentCount = 0; var childSlouchCount = 0
        // Passenger/child/adult count mode via small frequency arrays (max ~10)
        val passengerFreq = IntArray(16)  // index = passenger count, value = frequency
        val childCountFreq = IntArray(16)
        val adultCountFreq = IntArray(16)
        var maxPassengerCount = 0
        var maxChildCount = 0
        var maxAdultCount = 0

        for (frame in _buffer) {
            if (frame.earValue != null) { earValidCount++; if (frame.driverEyesClosed) eyesClosedCount++ }
            if (frame.marValue != null) { marValidCount++; if (frame.driverYawning) yawningCount++ }
            if (frame.headYaw != null) { yawValidCount++; if (frame.driverDistracted) distractedCount++ }
            if (frame.driverUsingPhone) phoneCount++
            if (frame.driverEatingDrinking) eatingCount++
            if (frame.dangerousPosture) postureCount++
            if (frame.childPresent) childPresentCount++
            if (frame.childSlouching) childSlouchCount++
            val pc = frame.passengerCount.coerceIn(0, passengerFreq.size - 1)
            passengerFreq[pc]++
            if (pc > maxPassengerCount) maxPassengerCount = pc
            val cc = frame.childCount.coerceIn(0, childCountFreq.size - 1)
            childCountFreq[cc]++
            if (cc > maxChildCount) maxChildCount = cc
            val ac = frame.adultCount.coerceIn(0, adultCountFreq.size - 1)
            adultCountFreq[ac]++
            if (ac > maxAdultCount) maxAdultCount = ac
        }

        // --- Smooth driver_eyes_closed (fast-clear + face-gated + sustained) ---
        val rawEyesClosed: Boolean = if (_eyesOpenStreak >= Config.FAST_CLEAR_FRAMES) {
            false
        } else {
            if (earValidCount == 0) false
            else (eyesClosedCount.toFloat() / earValidCount) >= threshold
        }
        if (rawEyesClosed) _eyesClosedStreak++ else _eyesClosedStreak = 0
        val smoothedEyesClosed = _eyesClosedStreak >= Config.EYES_CLOSED_MIN_FRAMES

        // --- Smooth driver_yawning (face-gated by mar_value + sustained) ---
        val rawYawning = if (marValidCount == 0) false
            else (yawningCount.toFloat() / marValidCount) >= threshold
        if (rawYawning) _yawningStreak++ else _yawningStreak = 0
        val smoothedYawning = _yawningStreak >= Config.YAWNING_MIN_FRAMES

        // --- Smooth driver_distracted (face-gated by head_yaw + sustained) ---
        val rawDistracted = if (yawValidCount == 0) false
            else (distractedCount.toFloat() / yawValidCount) >= threshold
        if (rawDistracted) _distractedStreak++ else _distractedStreak = 0
        val smoothedDistracted = _distractedStreak >= Config.DISTRACTED_MIN_FRAMES

        // --- Smooth standard boolean fields ---
        val smoothedPhone = (phoneCount.toFloat() / n) >= threshold

        val rawEating = (eatingCount.toFloat() / n) >= threshold
        if (rawEating) _eatingStreak++ else _eatingStreak = 0
        val smoothedEating = _eatingStreak >= Config.EATING_MIN_FRAMES

        val rawPosture = (postureCount.toFloat() / n) >= threshold
        if (rawPosture) _postureStreak++ else _postureStreak = 0
        val smoothedPosture = _postureStreak >= Config.POSTURE_MIN_FRAMES

        val smoothedChildPresent = (childPresentCount.toFloat() / n) >= threshold

        val rawChildSlouching = (childSlouchCount.toFloat() / n) >= threshold
        if (rawChildSlouching) _childSlouchStreak++ else _childSlouchStreak = 0
        val smoothedChildSlouching = _childSlouchStreak >= Config.CHILD_SLOUCH_MIN_FRAMES

        // --- Passenger count mode (highest count wins on tie) ---
        val smoothedPassengerCount: Int = run {
            if (n == 0) return@run 0
            var bestCount = 0; var bestFreq = 0
            for (i in 0..maxPassengerCount) {
                if (passengerFreq[i] > bestFreq || (passengerFreq[i] == bestFreq && i > bestCount)) {
                    bestFreq = passengerFreq[i]; bestCount = i
                }
            }
            bestCount
        }

        // --- Child count mode (highest count wins on tie) ---
        val smoothedChildCount: Int = run {
            if (n == 0) return@run 0
            var bestCount = 0; var bestFreq = 0
            for (i in 0..maxChildCount) {
                if (childCountFreq[i] > bestFreq || (childCountFreq[i] == bestFreq && i > bestCount)) {
                    bestFreq = childCountFreq[i]; bestCount = i
                }
            }
            bestCount
        }

        // --- Adult count mode (highest count wins on tie) ---
        val smoothedAdultCount: Int = run {
            if (n == 0) return@run 0
            var bestCount = 0; var bestFreq = 0
            for (i in 0..maxAdultCount) {
                if (adultCountFreq[i] > bestFreq || (adultCountFreq[i] == bestFreq && i > bestCount)) {
                    bestFreq = adultCountFreq[i]; bestCount = i
                }
            }
            bestCount
        }

        // --- Recompute risk from smoothed booleans ---
        val smoothedRisk = computeRisk(
            driverUsingPhone = smoothedPhone,
            driverEyesClosed = smoothedEyesClosed,
            dangerousPosture = smoothedPosture,
            childSlouching = smoothedChildSlouching,
            driverYawning = smoothedYawning,
            driverDistracted = smoothedDistracted,
            driverEatingDrinking = smoothedEating
        )

        // --- Build smoothed result ---
        // Keep ear_value, mar_value, head_yaw, head_pitch from the latest frame.
        // Keep distraction_duration_s as-is (managed by main loop).
        return result.copy(
            passengerCount = smoothedPassengerCount,
            childCount = smoothedChildCount,
            adultCount = smoothedAdultCount,
            driverUsingPhone = smoothedPhone,
            driverEyesClosed = smoothedEyesClosed,
            driverYawning = smoothedYawning,
            driverDistracted = smoothedDistracted,
            driverEatingDrinking = smoothedEating,
            dangerousPosture = smoothedPosture,
            childPresent = smoothedChildPresent,
            childSlouching = smoothedChildSlouching,
            riskLevel = smoothedRisk
        )
    }
}
