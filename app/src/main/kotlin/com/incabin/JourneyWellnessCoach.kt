package com.incabin

/**
 * Tracks driving time, announces milestones, and requests cabin cooling on long drives.
 * All decision logic in pure companion functions — no Android dependencies.
 */
class JourneyWellnessCoach {

    private var startTimeMs: Long = 0L
    private var lastAnnouncedMinute: Int = 0
    private var uprightFrames: Int = 0
    private var totalFrames: Int = 0

    fun start(nowMs: Long) {
        startTimeMs = nowMs
        lastAnnouncedMinute = 0
        uprightFrames = 0
        totalFrames = 0
    }

    /**
     * Called once per frame. Returns a CabinEvent if a milestone announcement is due, else null.
     */
    fun update(result: OutputResult, nowMs: Long, isJapanese: Boolean): CabinEvent? {
        if (!Config.ENABLE_WELLNESS_COACH) return null
        if (startTimeMs == 0L) return null

        totalFrames++
        if (!result.dangerousPosture && result.driverDetected) uprightFrames++

        val minutes = ((nowMs - startTimeMs) / 60_000).toInt()
        val milestone = shouldAnnounce(minutes, lastAnnouncedMinute) ?: return null

        lastAnnouncedMinute = milestone
        val posture = computePosturePercent(uprightFrames, totalFrames)
        val msg = formatMessage(milestone, posture, isJapanese)
        val hvacOffset = hvacOffsetForDuration(milestone)
        return CabinEvent(
            if (milestone >= Config.WELLNESS_MILESTONE_2_MIN) CabinEvent.IMPORTANT else CabinEvent.INFO,
            msg,
            hvacOffset
        )
    }

    fun reset() {
        startTimeMs = 0L
        lastAnnouncedMinute = 0
        uprightFrames = 0
        totalFrames = 0
    }

    /** Expose for TripStats accumulation. */
    fun getUprightFrames(): Int = uprightFrames
    fun getTotalFrames(): Int = totalFrames
    fun getLastMilestone(): Int = lastAnnouncedMinute

    companion object {
        fun shouldAnnounce(drivingMinutes: Int, lastAnnouncedMinute: Int): Int? {
            val milestones = listOf(
                Config.WELLNESS_MILESTONE_1_MIN,
                Config.WELLNESS_MILESTONE_2_MIN,
                Config.WELLNESS_MILESTONE_3_MIN
            )
            // Check fixed milestones
            for (m in milestones) {
                if (drivingMinutes >= m && lastAnnouncedMinute < m) return m
            }
            // After milestone 3, repeat every REPEAT_INTERVAL
            if (drivingMinutes >= Config.WELLNESS_MILESTONE_3_MIN) {
                val lastRepeat = Config.WELLNESS_MILESTONE_3_MIN +
                    ((drivingMinutes - Config.WELLNESS_MILESTONE_3_MIN) / Config.WELLNESS_REPEAT_INTERVAL_MIN) *
                    Config.WELLNESS_REPEAT_INTERVAL_MIN
                if (lastRepeat > lastAnnouncedMinute) return lastRepeat
            }
            return null
        }

        fun computePosturePercent(uprightFrames: Int, totalFrames: Int): Int {
            if (totalFrames <= 0) return 0
            return ((uprightFrames.toLong() * 100) / totalFrames).toInt().coerceIn(0, 100)
        }

        fun hvacOffsetForDuration(minutes: Int): Float = when {
            minutes >= Config.WELLNESS_MILESTONE_3_MIN -> -2.0f
            minutes >= Config.WELLNESS_MILESTONE_2_MIN -> -1.5f
            minutes >= Config.WELLNESS_MILESTONE_1_MIN -> -1.0f
            else -> 0.0f
        }

        fun formatMessage(minutes: Int, posturePercent: Int, isJapanese: Boolean): String {
            return if (isJapanese) {
                when {
                    minutes >= Config.WELLNESS_MILESTONE_3_MIN ->
                        "運転${minutes}分。休憩が効果的です。車内を冷却しました。"
                    minutes >= Config.WELLNESS_MILESTONE_2_MIN ->
                        "運転${minutes}分。休憩をご検討ください。車内を冷却します。"
                    else ->
                        "運転${minutes}分。集中力抜群です！"
                }
            } else {
                when {
                    minutes >= Config.WELLNESS_MILESTONE_3_MIN ->
                        "$minutes minutes driving. A break will help. Cabin cooled."
                    minutes >= Config.WELLNESS_MILESTONE_2_MIN ->
                        "$minutes minutes driving. Consider a break. Cooling cabin."
                    else ->
                        "$minutes minutes of driving. Great focus!"
                }
            }
        }
    }
}
