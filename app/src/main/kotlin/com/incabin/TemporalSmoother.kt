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

        // --- Smooth driver_eyes_closed (fast-clear + face-gated + sustained) ---
        val rawEyesClosed: Boolean = if (_eyesOpenStreak >= Config.FAST_CLEAR_FRAMES) {
            // Fast-clear: 2+ consecutive open-eye frames override buffer history
            false
        } else {
            // Face-gated: only count frames where ear_value is present
            val faceFrames = _buffer.filter { it.earValue != null }
            if (faceFrames.isEmpty()) {
                false
            } else {
                val trueCount = faceFrames.count { it.driverEyesClosed }
                (trueCount.toFloat() / faceFrames.size) >= threshold
            }
        }

        // Require sustained detection for EYES_CLOSED_MIN_FRAMES (~3s) to filter blinks
        if (rawEyesClosed) {
            _eyesClosedStreak++
        } else {
            _eyesClosedStreak = 0
        }
        val smoothedEyesClosed = _eyesClosedStreak >= Config.EYES_CLOSED_MIN_FRAMES

        // --- Smooth driver_yawning (face-gated by mar_value) ---
        val smoothedYawning: Boolean = run {
            val gated = _buffer.filter { it.marValue != null }
            if (gated.isEmpty()) {
                false
            } else {
                val trueCount = gated.count { it.driverYawning }
                (trueCount.toFloat() / gated.size) >= threshold
            }
        }

        // --- Smooth driver_distracted (face-gated by head_yaw + sustained) ---
        val rawDistracted: Boolean = run {
            val gated = _buffer.filter { it.headYaw != null }
            if (gated.isEmpty()) {
                false
            } else {
                val trueCount = gated.count { it.driverDistracted }
                (trueCount.toFloat() / gated.size) >= threshold
            }
        }
        if (rawDistracted) _distractedStreak++ else _distractedStreak = 0
        val smoothedDistracted = _distractedStreak >= Config.DISTRACTED_MIN_FRAMES

        // --- Smooth standard boolean fields ---
        val smoothedPhone: Boolean =
            (_buffer.count { it.driverUsingPhone }.toFloat() / n) >= threshold

        // --- Eating/drinking (sustained) ---
        val rawEating: Boolean =
            (_buffer.count { it.driverEatingDrinking }.toFloat() / n) >= threshold
        if (rawEating) _eatingStreak++ else _eatingStreak = 0
        val smoothedEating = _eatingStreak >= Config.EATING_MIN_FRAMES

        // --- Dangerous posture (sustained) ---
        val rawPosture: Boolean =
            (_buffer.count { it.dangerousPosture }.toFloat() / n) >= threshold
        if (rawPosture) _postureStreak++ else _postureStreak = 0
        val smoothedPosture = _postureStreak >= Config.POSTURE_MIN_FRAMES

        val smoothedChildPresent: Boolean =
            (_buffer.count { it.childPresent }.toFloat() / n) >= threshold

        // --- Child slouching (sustained) ---
        val rawChildSlouching: Boolean =
            (_buffer.count { it.childSlouching }.toFloat() / n) >= threshold
        if (rawChildSlouching) _childSlouchStreak++ else _childSlouchStreak = 0
        val smoothedChildSlouching = _childSlouchStreak >= Config.CHILD_SLOUCH_MIN_FRAMES

        // --- Passenger count: mode (highest count wins on tie) ---
        val smoothedPassengerCount: Int = run {
            val counts = _buffer.map { it.passengerCount }
            val freq = counts.groupingBy { it }.eachCount()
            val maxFreq = freq.values.max()
            // Among values with max frequency, pick the highest count
            freq.filter { it.value == maxFreq }.keys.max()
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
