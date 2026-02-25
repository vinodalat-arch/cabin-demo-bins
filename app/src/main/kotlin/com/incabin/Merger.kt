package com.incabin

import java.time.Instant

/**
 * Computes the risk level from individual detection booleans.
 *
 * Weight table:
 *   phone=3, eyes=3, yawning=2, distracted=2, posture=2, eating=1, slouch=1
 *
 * Scoring:
 *   score >= 3 -> "high"
 *   score >= 1 -> "medium"
 *   else       -> "low"
 */
fun computeRisk(
    driverUsingPhone: Boolean,
    driverEyesClosed: Boolean,
    dangerousPosture: Boolean,
    childSlouching: Boolean,
    driverYawning: Boolean = false,
    driverDistracted: Boolean = false,
    driverEatingDrinking: Boolean = false,
    handsOffWheel: Boolean = false
): String {
    var score = 0
    if (driverUsingPhone)     score += Config.RISK_WEIGHT_PHONE
    if (driverEyesClosed)     score += Config.RISK_WEIGHT_EYES
    if (dangerousPosture)     score += Config.RISK_WEIGHT_POSTURE
    if (childSlouching)       score += Config.RISK_WEIGHT_SLOUCH
    if (driverYawning)        score += Config.RISK_WEIGHT_YAWNING
    if (driverDistracted)     score += Config.RISK_WEIGHT_DISTRACTED
    if (driverEatingDrinking) score += Config.RISK_WEIGHT_EATING
    if (handsOffWheel)        score += Config.RISK_WEIGHT_HANDS_OFF

    return when {
        score >= Config.RISK_HIGH_THRESHOLD   -> "high"
        score >= Config.RISK_MEDIUM_THRESHOLD -> "medium"
        else -> "low"
    }
}

/**
 * Merges face analysis and pose analysis results into a single [OutputResult].
 *
 * - Face booleans are used directly (FaceResult.NO_FACE provides false defaults).
 * - Pose fields are used directly (PoseResult defaults provide false/0 defaults).
 * - distraction_duration_s is set to 0 here (injected later by the main loop).
 * - timestamp is generated at merge time (ISO-8601 UTC via [Instant.now]).
 */
fun mergeResults(faceResult: FaceResult, poseResult: PoseResult): OutputResult {
    val riskLevel = computeRisk(
        poseResult.driverUsingPhone, faceResult.driverEyesClosed,
        poseResult.dangerousPosture, poseResult.childSlouching,
        faceResult.driverYawning, faceResult.driverDistracted,
        poseResult.driverEatingDrinking, poseResult.handsOffWheel
    )

    // Derive adult_count: total persons - driver (if detected) - children
    val adultCount = maxOf(0, poseResult.passengerCount -
        (if (poseResult.driverDetected) 1 else 0) - poseResult.childCount)

    return OutputResult(
        timestamp = Instant.now().toString(),
        passengerCount = poseResult.passengerCount,
        childCount = poseResult.childCount,
        adultCount = adultCount,
        driverUsingPhone = poseResult.driverUsingPhone,
        driverEyesClosed = faceResult.driverEyesClosed,
        driverYawning = faceResult.driverYawning,
        driverDistracted = faceResult.driverDistracted,
        driverEatingDrinking = poseResult.driverEatingDrinking,
        handsOffWheel = poseResult.handsOffWheel,
        dangerousPosture = poseResult.dangerousPosture,
        childPresent = poseResult.childPresent,
        childSlouching = poseResult.childSlouching,
        riskLevel = riskLevel,
        earValue = faceResult.earValue,
        marValue = faceResult.marValue,
        headYaw = faceResult.headYaw,
        headPitch = faceResult.headPitch,
        distractionDurationS = 0,
        driverDetected = poseResult.driverDetected
    )
}
