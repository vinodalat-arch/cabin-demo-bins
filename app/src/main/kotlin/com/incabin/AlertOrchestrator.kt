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
 * window, ADAS state writes.
 */
class AlertOrchestrator(
    private val audioAlerter: AudioAlerter,
    private val vehicleChannelManager: VehicleChannelManager?
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
    }

    private var previousLevel: EscalationLevel? = null

    /**
     * Main entry point — called once per frame with the smoothed+enriched result.
     *
     * 1. Delegates to AudioAlerter (existing behavior, unchanged)
     * 2. Resolves escalation level from duration + active dangers
     * 3. Dispatches vehicle channels on level changes
     * 4. Restores all channels on all-clear
     */
    fun evaluate(result: OutputResult) {
        // Step 1: Existing audio behavior (always runs first — safety-critical path)
        audioAlerter.checkAndAnnounce(result)

        // Step 2: Resolve escalation level
        val dangers = activeDangers(result)
        val level = EscalationLevel.resolveLevel(result.distractionDurationS, dangers)

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
