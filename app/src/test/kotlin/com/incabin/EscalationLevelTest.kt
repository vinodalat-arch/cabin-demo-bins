package com.incabin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for EscalationLevel pure functions: resolveLevel(), channelsForLevel(),
 * escalation caps, and boundary conditions.
 */
class EscalationLevelTest {

    // -------------------------------------------------------------------------
    // resolveLevel — Duration Mapping
    // -------------------------------------------------------------------------

    @Test
    fun resolveLevel_noActiveDangers_returnsNull() {
        assertNull(EscalationLevel.resolveLevel(0, emptySet()))
    }

    @Test
    fun resolveLevel_noActiveDangers_highDuration_returnsNull() {
        assertNull(EscalationLevel.resolveLevel(60, emptySet()))
    }

    @Test
    fun resolveLevel_zeroDuration_returnsL1() {
        val level = EscalationLevel.resolveLevel(0, setOf("driver_using_phone"))
        assertEquals(EscalationLevel.L1_NUDGE, level)
    }

    @Test
    fun resolveLevel_4s_belowL2Threshold_returnsL1() {
        val level = EscalationLevel.resolveLevel(4, setOf("driver_using_phone"))
        assertEquals(EscalationLevel.L1_NUDGE, level)
    }

    @Test
    fun resolveLevel_5s_exactL2Threshold_returnsL2() {
        val level = EscalationLevel.resolveLevel(5, setOf("driver_using_phone"))
        assertEquals(EscalationLevel.L2_WARNING, level)
    }

    @Test
    fun resolveLevel_9s_belowL3Threshold_returnsL2() {
        val level = EscalationLevel.resolveLevel(9, setOf("driver_using_phone"))
        assertEquals(EscalationLevel.L2_WARNING, level)
    }

    @Test
    fun resolveLevel_10s_exactL3Threshold_returnsL3() {
        val level = EscalationLevel.resolveLevel(10, setOf("driver_using_phone"))
        assertEquals(EscalationLevel.L3_URGENT, level)
    }

    @Test
    fun resolveLevel_19s_belowL4Threshold_returnsL3() {
        val level = EscalationLevel.resolveLevel(19, setOf("driver_using_phone"))
        assertEquals(EscalationLevel.L3_URGENT, level)
    }

    @Test
    fun resolveLevel_20s_exactL4Threshold_returnsL4() {
        val level = EscalationLevel.resolveLevel(20, setOf("driver_using_phone"))
        assertEquals(EscalationLevel.L4_INTERVENTION, level)
    }

    @Test
    fun resolveLevel_29s_belowL5Threshold_returnsL4() {
        val level = EscalationLevel.resolveLevel(29, setOf("driver_using_phone"))
        assertEquals(EscalationLevel.L4_INTERVENTION, level)
    }

    @Test
    fun resolveLevel_30s_exactL5Threshold_returnsL5() {
        val level = EscalationLevel.resolveLevel(30, setOf("driver_using_phone"))
        assertEquals(EscalationLevel.L5_EMERGENCY, level)
    }

    @Test
    fun resolveLevel_100s_staysL5() {
        val level = EscalationLevel.resolveLevel(100, setOf("driver_using_phone"))
        assertEquals(EscalationLevel.L5_EMERGENCY, level)
    }

    // -------------------------------------------------------------------------
    // resolveLevel — Per-Detection Caps
    // -------------------------------------------------------------------------

    @Test
    fun resolveLevel_yawning_cappedAtL4() {
        val level = EscalationLevel.resolveLevel(60, setOf("driver_yawning"))
        assertEquals(EscalationLevel.L4_INTERVENTION, level)
    }

    @Test
    fun resolveLevel_eating_cappedAtL3() {
        val level = EscalationLevel.resolveLevel(60, setOf("driver_eating_drinking"))
        assertEquals(EscalationLevel.L3_URGENT, level)
    }

    @Test
    fun resolveLevel_posture_cappedAtL3() {
        val level = EscalationLevel.resolveLevel(60, setOf("dangerous_posture"))
        assertEquals(EscalationLevel.L3_URGENT, level)
    }

    @Test
    fun resolveLevel_childSlouching_cappedAtL3() {
        val level = EscalationLevel.resolveLevel(60, setOf("child_slouching"))
        assertEquals(EscalationLevel.L3_URGENT, level)
    }

    @Test
    fun resolveLevel_phone_notCapped() {
        val level = EscalationLevel.resolveLevel(60, setOf("driver_using_phone"))
        assertEquals(EscalationLevel.L5_EMERGENCY, level)
    }

    @Test
    fun resolveLevel_eyesClosed_notCapped() {
        val level = EscalationLevel.resolveLevel(60, setOf("driver_eyes_closed"))
        assertEquals(EscalationLevel.L5_EMERGENCY, level)
    }

    @Test
    fun resolveLevel_handsOff_notCapped() {
        val level = EscalationLevel.resolveLevel(60, setOf("hands_off_wheel"))
        assertEquals(EscalationLevel.L5_EMERGENCY, level)
    }

    @Test
    fun resolveLevel_distracted_notCapped() {
        val level = EscalationLevel.resolveLevel(60, setOf("driver_distracted"))
        assertEquals(EscalationLevel.L5_EMERGENCY, level)
    }

    // -------------------------------------------------------------------------
    // resolveLevel — Mixed Dangers (highest cap wins)
    // -------------------------------------------------------------------------

    @Test
    fun resolveLevel_mixedDangers_highestCapWins() {
        // Eating (L3 cap) + Phone (L5 cap) → highest cap is L5
        val level = EscalationLevel.resolveLevel(60, setOf("driver_eating_drinking", "driver_using_phone"))
        assertEquals(EscalationLevel.L5_EMERGENCY, level)
    }

    @Test
    fun resolveLevel_mixedAdvisoryOnly_cappedAtHighestAdvisory() {
        // Eating (L3) + Yawning (L4) → highest cap is L4
        val level = EscalationLevel.resolveLevel(60, setOf("driver_eating_drinking", "driver_yawning"))
        assertEquals(EscalationLevel.L4_INTERVENTION, level)
    }

    @Test
    fun resolveLevel_unknownDanger_cappedAtL1() {
        // Unknown danger has no entry in ESCALATION_CAPS → maxCap defaults to L1
        val level = EscalationLevel.resolveLevel(60, setOf("unknown_danger"))
        assertEquals(EscalationLevel.L1_NUDGE, level)
    }

    // -------------------------------------------------------------------------
    // thresholdsForSpeed — Speed Tier Mapping
    // -------------------------------------------------------------------------

    @Test
    fun thresholdsForSpeed_stationary_normalThresholds() {
        val t = EscalationLevel.thresholdsForSpeed(0f)
        assertEquals(Config.ESCALATION_L2_THRESHOLD_S, t[0])
        assertEquals(Config.ESCALATION_L3_THRESHOLD_S, t[1])
        assertEquals(Config.ESCALATION_L4_THRESHOLD_S, t[2])
        assertEquals(Config.ESCALATION_L5_THRESHOLD_S, t[3])
    }

    @Test
    fun thresholdsForSpeed_slow_normalThresholds() {
        val t = EscalationLevel.thresholdsForSpeed(25f)
        assertEquals(Config.ESCALATION_L2_THRESHOLD_S, t[0])
        assertEquals(Config.ESCALATION_L3_THRESHOLD_S, t[1])
        assertEquals(Config.ESCALATION_L4_THRESHOLD_S, t[2])
        assertEquals(Config.ESCALATION_L5_THRESHOLD_S, t[3])
    }

    @Test
    fun thresholdsForSpeed_moderate_compressedThresholds() {
        val t = EscalationLevel.thresholdsForSpeed(60f)
        assertEquals(Config.ESCALATION_MODERATE_L2_S, t[0])
        assertEquals(Config.ESCALATION_MODERATE_L3_S, t[1])
        assertEquals(Config.ESCALATION_MODERATE_L4_S, t[2])
        assertEquals(Config.ESCALATION_MODERATE_L5_S, t[3])
    }

    @Test
    fun thresholdsForSpeed_fast_aggressiveThresholds() {
        val t = EscalationLevel.thresholdsForSpeed(100f)
        assertEquals(Config.ESCALATION_FAST_L2_S, t[0])
        assertEquals(Config.ESCALATION_FAST_L3_S, t[1])
        assertEquals(Config.ESCALATION_FAST_L4_S, t[2])
        assertEquals(Config.ESCALATION_FAST_L5_S, t[3])
    }

    @Test
    fun thresholdsForSpeed_unavailable_normalThresholds() {
        val t = EscalationLevel.thresholdsForSpeed(-1f)
        assertEquals(Config.ESCALATION_L2_THRESHOLD_S, t[0])
        assertEquals(Config.ESCALATION_L3_THRESHOLD_S, t[1])
        assertEquals(Config.ESCALATION_L4_THRESHOLD_S, t[2])
        assertEquals(Config.ESCALATION_L5_THRESHOLD_S, t[3])
    }

    // -------------------------------------------------------------------------
    // resolveLevel — Speed-Scaled Escalation
    // -------------------------------------------------------------------------

    @Test
    fun resolveLevel_sameDuration_higherSpeedHigherLevel() {
        val dangers = setOf("driver_using_phone")
        // 4s: L1 at slow (L2 threshold=5s), L3 at fast (L2=0s, L3=3s, 4>=3)
        assertEquals(EscalationLevel.L1_NUDGE, EscalationLevel.resolveLevel(4, dangers, 25f))
        assertEquals(EscalationLevel.L3_URGENT, EscalationLevel.resolveLevel(4, dangers, 100f))
    }

    @Test
    fun resolveLevel_fastSpeed_immediateL2() {
        // 0s duration at >80 km/h → L2 (fast L2 threshold is 0s)
        val level = EscalationLevel.resolveLevel(0, setOf("driver_using_phone"), 100f)
        assertEquals(EscalationLevel.L2_WARNING, level)
    }

    @Test
    fun resolveLevel_moderateSpeed_L3at5s() {
        // 5s at moderate → L3 (moderate L3 threshold is 5s; normal would be L2)
        val level = EscalationLevel.resolveLevel(5, setOf("driver_using_phone"), 60f)
        assertEquals(EscalationLevel.L3_URGENT, level)
    }

    @Test
    fun resolveLevel_unavailableSpeed_normalBehavior() {
        // -1f → same as no speed, existing behavior
        val level = EscalationLevel.resolveLevel(5, setOf("driver_using_phone"), -1f)
        assertEquals(EscalationLevel.L2_WARNING, level)
    }

    @Test
    fun resolveLevel_capsStillApply_withSpeed() {
        // Eating capped at L3 even with fast speed and high duration
        val level = EscalationLevel.resolveLevel(60, setOf("driver_eating_drinking"), 100f)
        assertEquals(EscalationLevel.L3_URGENT, level)
    }

    // -------------------------------------------------------------------------
    // channelsForLevel
    // -------------------------------------------------------------------------

    @Test
    fun channelsForLevel_L1_hasDashboardAndChime() {
        val channels = EscalationLevel.channelsForLevel(EscalationLevel.L1_NUDGE)
        assertTrue(channels.contains(VehicleChannelId.CHIME))
        assertTrue(channels.contains(VehicleChannelId.DASHBOARD))
        assertFalse(channels.contains(VehicleChannelId.CABIN_LIGHTS))
    }

    @Test
    fun channelsForLevel_L3_hasCabinLightsAndHaptic() {
        val channels = EscalationLevel.channelsForLevel(EscalationLevel.L3_URGENT)
        assertTrue(channels.contains(VehicleChannelId.CABIN_LIGHTS))
        assertTrue(channels.contains(VehicleChannelId.SEAT_HAPTIC))
        assertFalse(channels.contains(VehicleChannelId.SEAT_THERMAL))
    }

    @Test
    fun channelsForLevel_L4_hasThermalAndSteeringHeat() {
        val channels = EscalationLevel.channelsForLevel(EscalationLevel.L4_INTERVENTION)
        assertTrue(channels.contains(VehicleChannelId.SEAT_THERMAL))
        assertTrue(channels.contains(VehicleChannelId.STEERING_HEAT))
        assertFalse(channels.contains(VehicleChannelId.WINDOW))
    }

    @Test
    fun channelsForLevel_L5_hasAllChannels() {
        val channels = EscalationLevel.channelsForLevel(EscalationLevel.L5_EMERGENCY)
        assertTrue(channels.contains(VehicleChannelId.WINDOW))
        assertTrue(channels.contains(VehicleChannelId.ADAS_STATE))
        assertTrue(channels.contains(VehicleChannelId.ALARM))
        assertTrue(channels.contains(VehicleChannelId.CABIN_LIGHTS))
        assertTrue(channels.contains(VehicleChannelId.SEAT_HAPTIC))
        assertTrue(channels.contains(VehicleChannelId.SEAT_THERMAL))
        assertTrue(channels.contains(VehicleChannelId.STEERING_HEAT))
    }

    // -------------------------------------------------------------------------
    // Independent channels not in escalation ladder
    // -------------------------------------------------------------------------

    @Test
    fun independentChannels_notInAnyEscalationLevel() {
        val independentChannels = setOf(
            VehicleChannelId.SEAT_MASSAGE,
            VehicleChannelId.AMBIENT_LIGHT,
            VehicleChannelId.HORN
        )
        for (level in EscalationLevel.values()) {
            val ladderChannels = EscalationLevel.channelsForLevel(level)
            for (ch in independentChannels) {
                assertFalse(
                    "Channel $ch should not be in $level escalation ladder",
                    ladderChannels.contains(ch)
                )
            }
        }
    }
}
