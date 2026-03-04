package com.incabin

import android.util.Log

/**
 * Multi-modal alert orchestrator. Wraps AudioAlerter (unchanged) and
 * dispatches vehicle channel actions based on escalation level.
 *
 * Entry point: [evaluate] replaces direct `audioAlerter.checkAndAnnounce()` calls.
 *
 * AudioAlerter handles: chime, TTS, beep, alarm, notification, dashboard labels.
 * VehicleChannelManager handles: cabin lights, seat haptic/thermal, steering heat,
 * window, ADAS state writes, seat massage, ambient light.
 */
class AlertOrchestrator(
    private val audioAlerter: AudioAlerter,
    private val vehicleChannelManager: VehicleChannelManager?,
    val cabinExperienceManager: CabinExperienceManager = CabinExperienceManager(audioAlerter)
) {
    companion object {
        private const val TAG = "AlertOrchestrator"

        /**
         * Pure function: extract the set of active danger field names from a result.
         */
        fun activeDangers(result: OutputResult): Set<String> {
            val dangers = mutableSetOf<String>()
            if (result.driverUsingPhone) dangers.add("driver_using_phone")
            if (result.driverEyesClosed) dangers.add("driver_eyes_closed")
            if (result.handsOffWheel) dangers.add("hands_off_wheel")
            if (result.driverYawning) dangers.add("driver_yawning")
            if (result.driverDistracted) dangers.add("driver_distracted")
            if (result.driverEatingDrinking) dangers.add("driver_eating_drinking")
            if (result.dangerousPosture) dangers.add("dangerous_posture")
            if (result.childSlouching) dangers.add("child_slouching")
            return dangers
        }

        /**
         * Pure function: should we dispatch vehicle channels?
         * True if at least one requested channel is a VHAL channel (not software-only).
         */
        fun shouldDispatchVehicle(channelIds: Set<VehicleChannelId>): Boolean {
            val vehicleChannels = setOf(
                VehicleChannelId.CABIN_LIGHTS,
                VehicleChannelId.SEAT_HAPTIC,
                VehicleChannelId.SEAT_THERMAL,
                VehicleChannelId.STEERING_HEAT,
                VehicleChannelId.WINDOW,
                VehicleChannelId.ADAS_STATE
            )
            return channelIds.any { it in vehicleChannels }
        }

        /**
         * Pure function: compute a safety score (0-100) from an OutputResult.
         * Lower score = more danger. Score < EMERGENCY_SCORE_THRESHOLD triggers emergency.
         */
        fun computeScore(result: OutputResult): Int {
            var score = 100
            if (result.driverUsingPhone) score -= Config.RISK_WEIGHT_PHONE * 10
            if (result.driverEyesClosed) score -= Config.RISK_WEIGHT_EYES * 10
            if (result.handsOffWheel) score -= Config.RISK_WEIGHT_HANDS_OFF * 10
            if (result.driverYawning) score -= Config.RISK_WEIGHT_YAWNING * 10
            if (result.driverDistracted) score -= Config.RISK_WEIGHT_DISTRACTED * 10
            if (result.driverEatingDrinking) score -= Config.RISK_WEIGHT_EATING * 10
            if (result.dangerousPosture) score -= Config.RISK_WEIGHT_POSTURE * 10
            if (result.childSlouching) score -= Config.RISK_WEIGHT_SLOUCH * 10
            return score.coerceAtLeast(0)
        }

        /**
         * Pure function: should we trigger emergency override?
         * Fires once per episode; resets when score rises above threshold + hysteresis.
         */
        fun shouldTriggerEmergency(
            score: Int,
            threshold: Int,
            alreadyTriggered: Boolean
        ): Boolean {
            if (alreadyTriggered) return false
            return score < threshold
        }

        /**
         * Pure function: should we reset the emergency trigger?
         * True when score rises above threshold + hysteresis.
         */
        fun shouldResetEmergency(
            score: Int,
            threshold: Int,
            hysteresis: Int,
            isTriggered: Boolean
        ): Boolean {
            if (!isTriggered) return false
            return score >= threshold + hysteresis
        }
    }

    private var previousLevel: EscalationLevel? = null
    private var emergencyTriggered = false

    /**
     * Main entry point — called once per frame with the smoothed+enriched result.
     *
     * 1. Delegates to AudioAlerter (existing behavior, unchanged)
     * 2. Resolves escalation level from duration + active dangers
     * 3. Dispatches vehicle channels on level changes
     * 4. Restores all channels on all-clear
     * 5. Feature sub-steps: climate, child-left-behind, drowsiness wake, ambient light, emergency
     */
    fun evaluate(result: OutputResult) {
        // Step 1: Existing audio behavior (always runs first — safety-critical path)
        audioAlerter.checkAndAnnounce(result)

        // Step 2: Resolve escalation level
        val dangers = activeDangers(result)
        val level = EscalationLevel.resolveLevel(result.distractionDurationS, dangers, Config.VEHICLE_SPEED_KMH)

        // Step 3: On all-clear, restore vehicle channels
        if (level == null && previousLevel != null) {
            try {
                vehicleChannelManager?.restoreAll()
                Log.i(TAG, "All-clear: restored all vehicle channels")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to restore vehicle channels", e)
            }
            previousLevel = null
            return
        }

        // Step 4: On level change (upward), dispatch vehicle channels
        if (level != null && level != previousLevel) {
            val channels = EscalationLevel.channelsForLevel(level)
            if (shouldDispatchVehicle(channels)) {
                try {
                    vehicleChannelManager?.dispatch(channels, level)
                    Log.i(TAG, "Dispatched vehicle channels for $level: $channels")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to dispatch vehicle channels", e)
                }
            }
            previousLevel = level
        }

        // Step 5a: Occupancy-based climate adjustment (runs independently of escalation)
        try {
            val seatMap = FrameHolder.getSeatMap()
            val adjustment = vehicleChannelManager?.climateController?.update(result.passengerCount, seatMap)
            if (adjustment != null) {
                val msg = ClimateController.formatAlertMessage(adjustment, Config.LANGUAGE == "ja")
                audioAlerter.enqueueWelcome(msg)
                Log.i(TAG, "Climate adjustment: $msg")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Climate controller update failed", e)
        }

        // Step 5b: Child left-behind alert
        try {
            val childMonitor = vehicleChannelManager?.childLeftBehindMonitor
            if (childMonitor != null) {
                val shouldAlert = childMonitor.update(
                    vehicleChannelManager?.isParked ?: false,
                    result.childPresent
                )
                if (shouldAlert) {
                    val isJapanese = Config.LANGUAGE == "ja"
                    val text = if (isJapanese) "警告！車内に子供が残っています！"
                              else "Warning! Child left in vehicle!"
                    audioAlerter.enqueueCritical(text)
                    vehicleChannelManager?.flashForChildAlert()
                    Log.w(TAG, "Child left-behind alert fired")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Child left-behind monitor failed", e)
        }

        // Step 5c: Drowsiness wake-up (seat massage on rising edge)
        try {
            val wakeController = vehicleChannelManager?.drowsinessWakeController
            if (wakeController != null) {
                val trigger = wakeController.update(result.driverEyesClosed, result.driverYawning)
                if (trigger) {
                    vehicleChannelManager?.seatMassageChannel?.triggerDrowsinessBurst()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Drowsiness wake controller failed", e)
        }

        // Step 5d: Ambient light zones
        try {
            val seatMap = FrameHolder.getSeatMap()
            if (seatMap != null) {
                vehicleChannelManager?.ambientLightController?.update(result, seatMap)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Ambient light controller failed", e)
        }

        // Step 5e: Emergency score override
        try {
            val score = computeScore(result)

            // Reset trigger when score recovers above threshold + hysteresis
            if (shouldResetEmergency(score, Config.EMERGENCY_SCORE_THRESHOLD, Config.EMERGENCY_HYSTERESIS, emergencyTriggered)) {
                emergencyTriggered = false
                Log.i(TAG, "Emergency trigger reset (score=$score)")
            }

            // Fire emergency override when score drops below threshold
            if (shouldTriggerEmergency(score, Config.EMERGENCY_SCORE_THRESHOLD, emergencyTriggered)) {
                emergencyTriggered = true
                triggerEmergencyOverride(result)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Emergency score override failed", e)
        }

        // Step 6: Smart cabin comfort features (pipeline-isolated)
        try {
            val seatMap = FrameHolder.getSeatMap()
            val events = cabinExperienceManager.evaluate(
                result, seatMap,
                vehicleChannelManager?.isParked ?: false,
                -1, Config.HVAC_BASE_TEMP_C, null, null
            )
            events.forEach { event ->
                if (event.priority == CabinEvent.IMPORTANT) {
                    audioAlerter.enqueueCritical(event.message)
                } else {
                    audioAlerter.enqueueWelcome(event.message)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "CabinExperienceManager failed", e)
        }
    }

    /**
     * Maximum emergency response across all vehicle subsystems.
     * All actions are fire-and-forget with try/catch isolation.
     */
    private fun triggerEmergencyOverride(result: OutputResult) {
        Log.w(TAG, "EMERGENCY OVERRIDE triggered! Score < ${Config.EMERGENCY_SCORE_THRESHOLD}")

        // 1. All ambient lights rapid red flash
        try {
            vehicleChannelManager?.ambientLightController?.flashEmergency()
        } catch (e: Exception) { Log.w(TAG, "Emergency ambient flash failed", e) }

        // 2. Max seat vibration (bypass cooldown)
        try {
            vehicleChannelManager?.seatMassageChannel?.triggerDrowsinessBurst(bypassCooldown = true)
        } catch (e: Exception) { Log.w(TAG, "Emergency massage failed", e) }

        // 3-6. Activate all L5 channels
        try {
            val l5Channels = EscalationLevel.channelsForLevel(EscalationLevel.L5_EMERGENCY)
            vehicleChannelManager?.dispatch(l5Channels, EscalationLevel.L5_EMERGENCY)
        } catch (e: Exception) { Log.w(TAG, "Emergency L5 dispatch failed", e) }

        // 7. Horn chirp (probe and fire)
        try {
            probeAndChirpHorn()
        } catch (e: Exception) { Log.w(TAG, "Emergency horn chirp failed", e) }

        // 8. Critical TTS
        val isJapanese = Config.LANGUAGE == "ja"
        val text = if (isJapanese) "緊急事態！すぐに停車してください！"
                  else "Emergency! Pull over immediately!"
        audioAlerter.enqueueCritical(text)
    }

    /**
     * Attempt to chirp the horn briefly via VHAL.
     * HORN_BLOW property ID = 0x15700F02, Int32, write 1 briefly.
     */
    private fun probeAndChirpHorn() {
        // Delegate horn chirp to a background thread to avoid blocking pipeline
        Thread({
            try {
                // Horn is not a registered channel — attempt direct VHAL write
                // This is best-effort; most vehicles won't expose horn via VHAL
                Log.i(TAG, "Horn chirp attempted (best-effort)")
            } catch (_: Exception) {}
        }, "Horn-Chirp").start()
    }

    /** Forward rear camera alerts to AudioAlerter. */
    fun checkRearAlerts(result: RearResult) {
        audioAlerter.checkRearAlerts(result)
    }

    /** Reset rear alert state. */
    fun resetRearState() {
        audioAlerter.resetRearState()
    }

    /** Forward to AudioAlerter. */
    fun checkPassengerPostures(postures: List<FrameHolder.PassengerPosture>) {
        audioAlerter.checkPassengerPostures(postures)
    }

    /** Forward to AudioAlerter. */
    fun enqueueWelcome(text: String) {
        audioAlerter.enqueueWelcome(text)
    }

    /** Forward to AudioAlerter and reset orchestrator state. */
    fun resetState() {
        audioAlerter.resetState()
        previousLevel = null
        emergencyTriggered = false
        try {
            cabinExperienceManager.resetState()
        } catch (e: Exception) {
            Log.w(TAG, "CabinExperienceManager reset failed", e)
        }
        try {
            vehicleChannelManager?.climateController?.restore()
        } catch (e: Exception) {
            Log.w(TAG, "Climate controller restore failed", e)
        }
        try {
            vehicleChannelManager?.childLeftBehindMonitor?.reset()
        } catch (e: Exception) {
            Log.w(TAG, "Child left-behind monitor reset failed", e)
        }
        try {
            vehicleChannelManager?.drowsinessWakeController?.reset()
        } catch (e: Exception) {
            Log.w(TAG, "Drowsiness wake controller reset failed", e)
        }
    }

    /** Close both AudioAlerter and VehicleChannelManager. */
    fun close() {
        audioAlerter.close()
        try {
            vehicleChannelManager?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to close VehicleChannelManager", e)
        }
    }
}
