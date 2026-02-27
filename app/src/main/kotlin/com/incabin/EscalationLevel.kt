package com.incabin

/**
 * 5-level escalation model for multi-modal IVI alert orchestration.
 * All functions are pure (no Android deps) for testability.
 */
enum class EscalationLevel(val ordinalLevel: Int) {
    L1_NUDGE(1),
    L2_WARNING(2),
    L3_URGENT(3),
    L4_INTERVENTION(4),
    L5_EMERGENCY(5);

    companion object {
        /**
         * Per-detection escalation caps. Safety-critical detections can escalate
         * to L5; advisory detections are capped lower.
         */
        val ESCALATION_CAPS: Map<String, EscalationLevel> = mapOf(
            "driver_using_phone" to L5_EMERGENCY,
            "driver_eyes_closed" to L5_EMERGENCY,
            "hands_off_wheel" to L5_EMERGENCY,
            "driver_distracted" to L5_EMERGENCY,
            "driver_yawning" to L4_INTERVENTION,
            "driver_eating_drinking" to L3_URGENT,
            "dangerous_posture" to L3_URGENT,
            "child_slouching" to L3_URGENT
        )

        /**
         * Return [L2, L3, L4, L5] threshold seconds for the given speed.
         * Higher speed → compressed thresholds for faster escalation.
         * Unavailable (-1) or stationary/slow → normal thresholds.
         */
        fun thresholdsForSpeed(speedKmh: Float): IntArray {
            return when {
                speedKmh < 0f -> intArrayOf(
                    Config.ESCALATION_L2_THRESHOLD_S, Config.ESCALATION_L3_THRESHOLD_S,
                    Config.ESCALATION_L4_THRESHOLD_S, Config.ESCALATION_L5_THRESHOLD_S
                )
                speedKmh > Config.SPEED_MODERATE_MAX_KMH -> intArrayOf(
                    Config.ESCALATION_FAST_L2_S, Config.ESCALATION_FAST_L3_S,
                    Config.ESCALATION_FAST_L4_S, Config.ESCALATION_FAST_L5_S
                )
                speedKmh > Config.SPEED_SLOW_MAX_KMH -> intArrayOf(
                    Config.ESCALATION_MODERATE_L2_S, Config.ESCALATION_MODERATE_L3_S,
                    Config.ESCALATION_MODERATE_L4_S, Config.ESCALATION_MODERATE_L5_S
                )
                else -> intArrayOf(
                    Config.ESCALATION_L2_THRESHOLD_S, Config.ESCALATION_L3_THRESHOLD_S,
                    Config.ESCALATION_L4_THRESHOLD_S, Config.ESCALATION_L5_THRESHOLD_S
                )
            }
        }

        /**
         * Resolve escalation level from distraction duration and active dangers.
         * Returns null if no dangers are active (nothing to escalate).
         *
         * The resolved level is capped by the highest-cap active detection,
         * so advisory-only detections (eating, posture) never trigger vehicle
         * interventions beyond L3.
         *
         * @param speedKmh current vehicle speed (-1f = unavailable, uses normal thresholds)
         */
        fun resolveLevel(durationS: Int, activeDangers: Set<String>, speedKmh: Float = -1f): EscalationLevel? {
            if (activeDangers.isEmpty()) return null

            val t = thresholdsForSpeed(speedKmh)
            val rawLevel = when {
                durationS >= t[3] -> L5_EMERGENCY
                durationS >= t[2] -> L4_INTERVENTION
                durationS >= t[1] -> L3_URGENT
                durationS >= t[0] -> L2_WARNING
                else -> L1_NUDGE
            }

            // Cap by the highest-cap detection present
            val maxCap = activeDangers
                .mapNotNull { ESCALATION_CAPS[it] }
                .maxByOrNull { it.ordinalLevel }
                ?: L1_NUDGE

            return if (rawLevel.ordinalLevel <= maxCap.ordinalLevel) rawLevel else maxCap
        }

        /**
         * Which vehicle channels should fire at each escalation level.
         * Levels are cumulative — L3 includes everything from L1+L2.
         */
        fun channelsForLevel(level: EscalationLevel): Set<VehicleChannelId> = when (level) {
            L1_NUDGE -> setOf(
                VehicleChannelId.CHIME,
                VehicleChannelId.DASHBOARD
            )
            L2_WARNING -> setOf(
                VehicleChannelId.CHIME,
                VehicleChannelId.DASHBOARD,
                VehicleChannelId.TTS,
                VehicleChannelId.NOTIFICATION
            )
            L3_URGENT -> setOf(
                VehicleChannelId.CHIME,
                VehicleChannelId.DASHBOARD,
                VehicleChannelId.TTS,
                VehicleChannelId.NOTIFICATION,
                VehicleChannelId.BEEP,
                VehicleChannelId.CABIN_LIGHTS,
                VehicleChannelId.SEAT_HAPTIC
            )
            L4_INTERVENTION -> setOf(
                VehicleChannelId.CHIME,
                VehicleChannelId.DASHBOARD,
                VehicleChannelId.TTS,
                VehicleChannelId.NOTIFICATION,
                VehicleChannelId.BEEP,
                VehicleChannelId.CABIN_LIGHTS,
                VehicleChannelId.SEAT_HAPTIC,
                VehicleChannelId.SEAT_THERMAL,
                VehicleChannelId.STEERING_HEAT
            )
            L5_EMERGENCY -> setOf(
                VehicleChannelId.CHIME,
                VehicleChannelId.DASHBOARD,
                VehicleChannelId.TTS,
                VehicleChannelId.NOTIFICATION,
                VehicleChannelId.BEEP,
                VehicleChannelId.ALARM,
                VehicleChannelId.CABIN_LIGHTS,
                VehicleChannelId.SEAT_HAPTIC,
                VehicleChannelId.SEAT_THERMAL,
                VehicleChannelId.STEERING_HEAT,
                VehicleChannelId.WINDOW,
                VehicleChannelId.ADAS_STATE
            )
        }
    }
}

/**
 * Speed tier classification for speed-scaled escalation.
 */
enum class SpeedTier { STATIONARY, SLOW, MODERATE, FAST, UNAVAILABLE }

/**
 * Identifiers for vehicle alert channels (both software and VHAL-backed).
 */
enum class VehicleChannelId {
    // Software channels (handled by AudioAlerter / dashboard)
    CHIME,
    DASHBOARD,
    TTS,
    NOTIFICATION,
    BEEP,
    ALARM,
    // VHAL-backed vehicle channels
    CABIN_LIGHTS,
    SEAT_HAPTIC,
    SEAT_THERMAL,
    STEERING_HEAT,
    WINDOW,
    ADAS_STATE
}
