package com.incabin

/**
 * Pure functions for ASIMO Companion Hub logic.
 *
 * All functions are free of Android dependencies and fully unit-testable.
 * MainActivity delegates to these for pose resolution, glow color, detection
 * labels, hub mode, and AI status routing.
 */
object AsimoHub {

    // Pose priority order — highest severity first
    private val POSE_PRIORITY_FIELDS = listOf(
        "driverUsingPhone",
        "driverEyesClosed",
        "handsOffWheel",
        "driverDistracted",
        "driverYawning",
        "driverEatingDrinking",
        "dangerousPosture",
        "childSlouching"
    )

    // Detection labels
    val DETECTION_LABELS_EN = mapOf(
        "noDriverDetected" to "No Driver Detected",
        "driverUsingPhone" to "Phone Detected",
        "driverEyesClosed" to "Eyes Closed",
        "handsOffWheel" to "Hands Off Wheel",
        "driverYawning" to "Yawning",
        "driverDistracted" to "Distracted",
        "driverEatingDrinking" to "Eating / Drinking",
        "dangerousPosture" to "Dangerous Posture",
        "childSlouching" to "Child Slouching"
    )

    val DETECTION_LABELS_JA = mapOf(
        "noDriverDetected" to "運転者未検出",
        "driverUsingPhone" to "スマホ検出",
        "driverEyesClosed" to "目を閉じている",
        "handsOffWheel" to "ハンドル未把持",
        "driverYawning" to "あくび",
        "driverDistracted" to "よそ見",
        "driverEatingDrinking" to "飲食中",
        "dangerousPosture" to "危険な姿勢",
        "childSlouching" to "子供の姿勢不良"
    )

    // Critical (danger-colored) fields
    val DANGER_FIELDS = setOf("driverUsingPhone", "driverEyesClosed", "handsOffWheel")

    // Glow color categories
    enum class GlowCategory { DANGER, CAUTION, SAFE }

    // Hub display modes
    enum class HubMode { FULL, COMPACT }

    /**
     * Resolves which detection key has highest priority from the result.
     * Returns empty string when no detection is active (all-clear).
     */
    fun resolveDetectionKey(result: OutputResult): String {
        val fieldValues = mapOf(
            "driverUsingPhone" to result.driverUsingPhone,
            "driverEyesClosed" to result.driverEyesClosed,
            "handsOffWheel" to result.handsOffWheel,
            "driverDistracted" to result.driverDistracted,
            "driverYawning" to result.driverYawning,
            "driverEatingDrinking" to result.driverEatingDrinking,
            "dangerousPosture" to result.dangerousPosture,
            "childSlouching" to result.childSlouching
        )
        return POSE_PRIORITY_FIELDS.firstOrNull { fieldValues[it] == true } ?: ""
    }

    /**
     * Maps risk level string to glow color category.
     */
    fun resolveGlowCategory(riskLevel: String): GlowCategory = when (riskLevel) {
        "high" -> GlowCategory.DANGER
        "medium" -> GlowCategory.CAUTION
        else -> GlowCategory.SAFE
    }

    /**
     * Whether the glow should pulse (high risk only).
     */
    fun shouldPulse(riskLevel: String): Boolean = riskLevel == "high"

    /**
     * Returns the detection label text for the given key and language.
     * Returns the key itself as fallback if not found in the map.
     * Returns null for empty key (no detection active).
     */
    fun getDetectionLabel(detectionKey: String, language: String): String? {
        if (detectionKey.isEmpty()) return null
        val map = if (language == "ja") DETECTION_LABELS_JA else DETECTION_LABELS_EN
        return (map[detectionKey] ?: detectionKey).uppercase()
    }

    /**
     * Whether a detection key belongs to the "danger" (critical) tier.
     * Danger fields get red color; others get caution/orange color.
     */
    fun isDangerField(detectionKey: String): Boolean = detectionKey in DANGER_FIELDS

    /**
     * Computes 20% alpha tint of a color for detection label background.
     * Input: ARGB color int.
     * Output: same RGB with alpha set to 0x33 (20%).
     */
    fun computeLabelTint(color: Int): Int = color and 0x00FFFFFF or 0x33000000

    /**
     * Determines hub display mode: FULL when camera preview is off,
     * COMPACT when preview is on (robot shrinks to corner).
     */
    fun resolveHubMode(enablePreview: Boolean): HubMode =
        if (enablePreview) HubMode.COMPACT else HubMode.FULL

    /**
     * Determines whether AI status messages should route to the speech bubble
     * (preview OFF) or the overlay text (preview ON).
     * Returns "bubble" or "overlay".
     */
    fun resolveStatusTarget(enablePreview: Boolean): String =
        if (enablePreview) "overlay" else "bubble"

    /**
     * Applies alpha to a color value.
     * Used by AsimoBgGlowView for radial gradient rendering.
     */
    fun applyAlpha(color: Int, alpha: Float): Int {
        val a = (alpha * 255).toInt().coerceIn(0, 255)
        return (color and 0x00FFFFFF) or (a shl 24)
    }

    // Score color categories
    enum class ScoreColor { SAFE, CAUTION, DANGER }

    /**
     * Maps a driving score (0-100) to a color category.
     * >= 75 → SAFE (green), >= 40 → CAUTION (orange), < 40 → DANGER (red).
     */
    fun scoreColorCategory(score: Float): ScoreColor = when {
        score >= 75f -> ScoreColor.SAFE
        score >= 40f -> ScoreColor.CAUTION
        else -> ScoreColor.DANGER
    }

    /**
     * Checks if any distraction field is active in the result.
     * Distraction fields: phone, eyes, yawning, distracted, eating.
     * NOTE: posture and child_slouching are NOT distraction fields.
     */
    fun isDistracted(result: OutputResult): Boolean =
        result.driverUsingPhone || result.driverEyesClosed ||
            result.handsOffWheel || result.driverYawning ||
            result.driverDistracted || result.driverEatingDrinking

    /**
     * Computes the score penalty for a given result.
     * Returns 0f when no detection is active (recovery mode).
     */
    fun computeScorePenalty(result: OutputResult): Float {
        var penalty = 0f
        if (result.driverUsingPhone) penalty += 2.0f
        if (result.driverEyesClosed) penalty += 2.0f
        if (result.handsOffWheel) penalty += 2.0f
        if (result.driverDistracted) penalty += 1.5f
        if (result.driverYawning) penalty += 1.0f
        if (result.driverEatingDrinking) penalty += 1.0f
        if (result.dangerousPosture) penalty += 1.0f
        if (result.childSlouching) penalty += 0.5f
        return penalty
    }
}
