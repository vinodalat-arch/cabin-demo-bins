package com.incabin

import java.util.concurrent.atomic.AtomicInteger

/**
 * Factory for creating synthetic OutputResult instances for instrumented tests.
 * Each result gets a unique auto-incrementing timestamp to avoid dedup in the UI poller.
 */
object TestResultFactory {

    private val counter = AtomicInteger(0)

    private fun nextTimestamp(): String = "2026-01-01T00:00:%02d.000Z".format(counter.incrementAndGet())

    fun reset() { counter.set(0) }

    fun allClear(passengerCount: Int = 1): OutputResult = OutputResult(
        timestamp = nextTimestamp(),
        passengerCount = passengerCount,
        driverUsingPhone = false,
        driverEyesClosed = false,
        driverYawning = false,
        driverDistracted = false,
        driverEatingDrinking = false,
        dangerousPosture = false,
        childPresent = false,
        childSlouching = false,
        riskLevel = "low",
        earValue = 0.28f,
        marValue = 0.3f,
        headYaw = 5.0f,
        headPitch = 3.0f,
        distractionDurationS = 0,
        driverName = null,
        driverDetected = true
    )

    fun phoneDetected(): OutputResult = OutputResult(
        timestamp = nextTimestamp(),
        passengerCount = 1,
        driverUsingPhone = true,
        driverEyesClosed = false,
        driverYawning = false,
        driverDistracted = false,
        driverEatingDrinking = false,
        dangerousPosture = false,
        childPresent = false,
        childSlouching = false,
        riskLevel = "high",
        earValue = 0.28f,
        marValue = 0.3f,
        headYaw = 5.0f,
        headPitch = 3.0f,
        distractionDurationS = 3,
        driverName = null,
        driverDetected = true
    )

    fun eyesClosed(): OutputResult = OutputResult(
        timestamp = nextTimestamp(),
        passengerCount = 1,
        driverUsingPhone = false,
        driverEyesClosed = true,
        driverYawning = false,
        driverDistracted = false,
        driverEatingDrinking = false,
        dangerousPosture = false,
        childPresent = false,
        childSlouching = false,
        riskLevel = "high",
        earValue = 0.15f,
        marValue = 0.3f,
        headYaw = 5.0f,
        headPitch = 3.0f,
        distractionDurationS = 2,
        driverName = null,
        driverDetected = true
    )

    fun yawning(): OutputResult = OutputResult(
        timestamp = nextTimestamp(),
        passengerCount = 1,
        driverUsingPhone = false,
        driverEyesClosed = false,
        driverYawning = true,
        driverDistracted = false,
        driverEatingDrinking = false,
        dangerousPosture = false,
        childPresent = false,
        childSlouching = false,
        riskLevel = "medium",
        earValue = 0.28f,
        marValue = 0.65f,
        headYaw = 5.0f,
        headPitch = 3.0f,
        distractionDurationS = 1,
        driverName = null,
        driverDetected = true
    )

    fun distracted(): OutputResult = OutputResult(
        timestamp = nextTimestamp(),
        passengerCount = 1,
        driverUsingPhone = false,
        driverEyesClosed = false,
        driverYawning = false,
        driverDistracted = true,
        driverEatingDrinking = false,
        dangerousPosture = false,
        childPresent = false,
        childSlouching = false,
        riskLevel = "medium",
        earValue = 0.28f,
        marValue = 0.3f,
        headYaw = 45.0f,
        headPitch = 3.0f,
        distractionDurationS = 4,
        driverName = null,
        driverDetected = true
    )

    fun eating(): OutputResult = OutputResult(
        timestamp = nextTimestamp(),
        passengerCount = 1,
        driverUsingPhone = false,
        driverEyesClosed = false,
        driverYawning = false,
        driverDistracted = false,
        driverEatingDrinking = true,
        dangerousPosture = false,
        childPresent = false,
        childSlouching = false,
        riskLevel = "medium",
        earValue = 0.28f,
        marValue = 0.3f,
        headYaw = 5.0f,
        headPitch = 3.0f,
        distractionDurationS = 2,
        driverName = null,
        driverDetected = true
    )

    fun posture(): OutputResult = OutputResult(
        timestamp = nextTimestamp(),
        passengerCount = 1,
        driverUsingPhone = false,
        driverEyesClosed = false,
        driverYawning = false,
        driverDistracted = false,
        driverEatingDrinking = false,
        dangerousPosture = true,
        childPresent = false,
        childSlouching = false,
        riskLevel = "medium",
        earValue = 0.28f,
        marValue = 0.3f,
        headYaw = 5.0f,
        headPitch = 3.0f,
        distractionDurationS = 0,
        driverName = null,
        driverDetected = true
    )

    fun childSlouching(): OutputResult = OutputResult(
        timestamp = nextTimestamp(),
        passengerCount = 2,
        driverUsingPhone = false,
        driverEyesClosed = false,
        driverYawning = false,
        driverDistracted = false,
        driverEatingDrinking = false,
        dangerousPosture = false,
        childPresent = true,
        childSlouching = true,
        riskLevel = "medium",
        earValue = 0.28f,
        marValue = 0.3f,
        headYaw = 5.0f,
        headPitch = 3.0f,
        distractionDurationS = 0,
        driverName = null,
        driverDetected = true
    )

    fun noOccupants(): OutputResult = OutputResult(
        timestamp = nextTimestamp(),
        passengerCount = 0,
        driverUsingPhone = false,
        driverEyesClosed = false,
        driverYawning = false,
        driverDistracted = false,
        driverEatingDrinking = false,
        dangerousPosture = false,
        childPresent = false,
        childSlouching = false,
        riskLevel = "low",
        earValue = null,
        marValue = null,
        headYaw = null,
        headPitch = null,
        distractionDurationS = 0,
        driverName = null,
        driverDetected = false
    )

    fun noDriver(): OutputResult = OutputResult(
        timestamp = nextTimestamp(),
        passengerCount = 1,
        driverUsingPhone = false,
        driverEyesClosed = false,
        driverYawning = false,
        driverDistracted = false,
        driverEatingDrinking = false,
        dangerousPosture = false,
        childPresent = false,
        childSlouching = false,
        riskLevel = "low",
        earValue = null,
        marValue = null,
        headYaw = null,
        headPitch = null,
        distractionDurationS = 0,
        driverName = null,
        driverDetected = false
    )

    fun withDriverName(name: String): OutputResult = OutputResult(
        timestamp = nextTimestamp(),
        passengerCount = 1,
        driverUsingPhone = false,
        driverEyesClosed = false,
        driverYawning = false,
        driverDistracted = false,
        driverEatingDrinking = false,
        dangerousPosture = false,
        childPresent = false,
        childSlouching = false,
        riskLevel = "low",
        earValue = 0.28f,
        marValue = 0.3f,
        headYaw = 5.0f,
        headPitch = 3.0f,
        distractionDurationS = 0,
        driverName = name,
        driverDetected = true
    )

    fun withDistraction(seconds: Int): OutputResult = OutputResult(
        timestamp = nextTimestamp(),
        passengerCount = 1,
        driverUsingPhone = true,
        driverEyesClosed = false,
        driverYawning = false,
        driverDistracted = false,
        driverEatingDrinking = false,
        dangerousPosture = false,
        childPresent = false,
        childSlouching = false,
        riskLevel = "high",
        earValue = 0.28f,
        marValue = 0.3f,
        headYaw = 5.0f,
        headPitch = 3.0f,
        distractionDurationS = seconds,
        driverName = null,
        driverDetected = true
    )

    fun highRiskMultiple(): OutputResult = OutputResult(
        timestamp = nextTimestamp(),
        passengerCount = 2,
        driverUsingPhone = true,
        driverEyesClosed = true,
        driverYawning = false,
        driverDistracted = true,
        driverEatingDrinking = false,
        dangerousPosture = false,
        childPresent = true,
        childSlouching = true,
        riskLevel = "high",
        earValue = 0.12f,
        marValue = 0.3f,
        headYaw = 40.0f,
        headPitch = 3.0f,
        distractionDurationS = 15,
        driverName = null,
        driverDetected = true
    )
}
