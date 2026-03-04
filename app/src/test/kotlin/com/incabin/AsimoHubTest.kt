package com.incabin

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Comprehensive tests for AsimoHub pure functions.
 * Covers detection key resolution, glow color/pulse, detection labels,
 * danger field classification, label tint, hub mode, status routing,
 * and applyAlpha color math.
 */
class AsimoHubTest {

    // Save/restore mutable Config values
    private var origLanguage = "en"
    private var origPreview = false

    @Before
    fun saveConfig() {
        origLanguage = Config.LANGUAGE
        origPreview = Config.ENABLE_PREVIEW
    }

    @After
    fun restoreConfig() {
        Config.LANGUAGE = origLanguage
        Config.ENABLE_PREVIEW = origPreview
    }

    // --- Helpers ---

    private fun makeResult(
        phone: Boolean = false,
        eyes: Boolean = false,
        handsOff: Boolean = false,
        yawn: Boolean = false,
        distracted: Boolean = false,
        eating: Boolean = false,
        posture: Boolean = false,
        slouch: Boolean = false,
        riskLevel: String = "low",
        driverDetected: Boolean = true
    ): OutputResult = OutputResult(
        timestamp = "2026-01-01T00:00:00Z",
        passengerCount = 1,
        driverUsingPhone = phone,
        driverEyesClosed = eyes,
        handsOffWheel = handsOff,
        driverYawning = yawn,
        driverDistracted = distracted,
        driverEatingDrinking = eating,
        dangerousPosture = posture,
        childPresent = false,
        childSlouching = slouch,
        riskLevel = riskLevel,
        earValue = 0.25f,
        marValue = 0.2f,
        headYaw = 0f,
        headPitch = 0f,
        distractionDurationS = 0,
        driverDetected = driverDetected
    )

    // -------------------------------------------------------------------------
    // resolveDetectionKey — Pose Priority (9 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_all_clear_returns_empty_key() {
        val result = makeResult()
        assertEquals("", AsimoHub.resolveDetectionKey(result))
    }

    @Test
    fun test_phone_is_highest_priority() {
        val result = makeResult(phone = true, eyes = true, distracted = true)
        assertEquals("driverUsingPhone", AsimoHub.resolveDetectionKey(result))
    }

    @Test
    fun test_eyes_closed_second_priority() {
        val result = makeResult(eyes = true, distracted = true, yawn = true)
        assertEquals("driverEyesClosed", AsimoHub.resolveDetectionKey(result))
    }

    @Test
    fun test_hands_off_wheel_third_priority() {
        val result = makeResult(handsOff = true, distracted = true, yawn = true)
        assertEquals("handsOffWheel", AsimoHub.resolveDetectionKey(result))
    }

    @Test
    fun test_distracted_fourth_priority() {
        val result = makeResult(distracted = true, yawn = true, eating = true)
        assertEquals("driverDistracted", AsimoHub.resolveDetectionKey(result))
    }

    @Test
    fun test_yawning_fifth_priority() {
        val result = makeResult(yawn = true, eating = true, posture = true)
        assertEquals("driverYawning", AsimoHub.resolveDetectionKey(result))
    }

    @Test
    fun test_eating_sixth_priority() {
        val result = makeResult(eating = true, posture = true, slouch = true)
        assertEquals("driverEatingDrinking", AsimoHub.resolveDetectionKey(result))
    }

    @Test
    fun test_posture_seventh_priority() {
        val result = makeResult(posture = true, slouch = true)
        assertEquals("dangerousPosture", AsimoHub.resolveDetectionKey(result))
    }

    @Test
    fun test_child_slouching_lowest_priority() {
        val result = makeResult(slouch = true)
        assertEquals("childSlouching", AsimoHub.resolveDetectionKey(result))
    }

    @Test
    fun test_single_detection_phone_only() {
        val result = makeResult(phone = true)
        assertEquals("driverUsingPhone", AsimoHub.resolveDetectionKey(result))
    }

    // -------------------------------------------------------------------------
    // resolveGlowCategory (3 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_glow_high_risk_is_danger() {
        assertEquals(AsimoHub.GlowCategory.DANGER, AsimoHub.resolveGlowCategory("high"))
    }

    @Test
    fun test_glow_medium_risk_is_caution() {
        assertEquals(AsimoHub.GlowCategory.CAUTION, AsimoHub.resolveGlowCategory("medium"))
    }

    @Test
    fun test_glow_low_risk_is_safe() {
        assertEquals(AsimoHub.GlowCategory.SAFE, AsimoHub.resolveGlowCategory("low"))
    }

    // -------------------------------------------------------------------------
    // shouldPulse (3 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_pulse_on_high_risk() {
        assertTrue(AsimoHub.shouldPulse("high"))
    }

    @Test
    fun test_no_pulse_on_medium_risk() {
        assertFalse(AsimoHub.shouldPulse("medium"))
    }

    @Test
    fun test_no_pulse_on_low_risk() {
        assertFalse(AsimoHub.shouldPulse("low"))
    }

    // -------------------------------------------------------------------------
    // getDetectionLabel — English (9 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_label_en_phone() {
        assertEquals("PHONE DETECTED", AsimoHub.getDetectionLabel("driverUsingPhone", "en"))
    }

    @Test
    fun test_label_en_eyes() {
        assertEquals("EYES CLOSED", AsimoHub.getDetectionLabel("driverEyesClosed", "en"))
    }

    @Test
    fun test_label_en_yawning() {
        assertEquals("YAWNING", AsimoHub.getDetectionLabel("driverYawning", "en"))
    }

    @Test
    fun test_label_en_distracted() {
        assertEquals("DISTRACTED", AsimoHub.getDetectionLabel("driverDistracted", "en"))
    }

    @Test
    fun test_label_en_eating() {
        assertEquals("EATING / DRINKING", AsimoHub.getDetectionLabel("driverEatingDrinking", "en"))
    }

    @Test
    fun test_label_en_posture() {
        assertEquals("DANGEROUS POSTURE", AsimoHub.getDetectionLabel("dangerousPosture", "en"))
    }

    @Test
    fun test_label_en_child_slouch() {
        assertEquals("CHILD SLOUCHING", AsimoHub.getDetectionLabel("childSlouching", "en"))
    }

    @Test
    fun test_label_en_hands_off_wheel() {
        assertEquals("HANDS OFF WHEEL", AsimoHub.getDetectionLabel("handsOffWheel", "en"))
    }

    @Test
    fun test_label_en_no_driver() {
        assertEquals("NO DRIVER DETECTED", AsimoHub.getDetectionLabel("noDriverDetected", "en"))
    }

    @Test
    fun test_label_en_unknown_key_returns_key() {
        assertEquals("SOMETHINGELSE", AsimoHub.getDetectionLabel("somethingElse", "en"))
    }

    // -------------------------------------------------------------------------
    // getDetectionLabel — Japanese (8 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_label_ja_phone() {
        assertEquals("スマホ検出", AsimoHub.getDetectionLabel("driverUsingPhone", "ja"))
    }

    @Test
    fun test_label_ja_eyes() {
        assertEquals("目を閉じている", AsimoHub.getDetectionLabel("driverEyesClosed", "ja"))
    }

    @Test
    fun test_label_ja_yawning() {
        assertEquals("あくび", AsimoHub.getDetectionLabel("driverYawning", "ja"))
    }

    @Test
    fun test_label_ja_distracted() {
        assertEquals("よそ見", AsimoHub.getDetectionLabel("driverDistracted", "ja"))
    }

    @Test
    fun test_label_ja_eating() {
        assertEquals("飲食中", AsimoHub.getDetectionLabel("driverEatingDrinking", "ja"))
    }

    @Test
    fun test_label_ja_posture() {
        assertEquals("危険な姿勢", AsimoHub.getDetectionLabel("dangerousPosture", "ja"))
    }

    @Test
    fun test_label_ja_child_slouch() {
        assertEquals("子供の姿勢不良", AsimoHub.getDetectionLabel("childSlouching", "ja"))
    }

    @Test
    fun test_label_ja_hands_off_wheel() {
        assertEquals("ハンドル未把持", AsimoHub.getDetectionLabel("handsOffWheel", "ja"))
    }

    @Test
    fun test_label_ja_no_driver() {
        assertEquals("運転者未検出", AsimoHub.getDetectionLabel("noDriverDetected", "ja"))
    }

    // -------------------------------------------------------------------------
    // getDetectionLabel — empty key (1 test)
    // -------------------------------------------------------------------------

    @Test
    fun test_label_empty_key_returns_null() {
        assertNull(AsimoHub.getDetectionLabel("", "en"))
    }

    // -------------------------------------------------------------------------
    // isDangerField (4 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_phone_is_danger_field() {
        assertTrue(AsimoHub.isDangerField("driverUsingPhone"))
    }

    @Test
    fun test_eyes_is_danger_field() {
        assertTrue(AsimoHub.isDangerField("driverEyesClosed"))
    }

    @Test
    fun test_hands_off_wheel_is_danger_field() {
        assertTrue(AsimoHub.isDangerField("handsOffWheel"))
    }

    @Test
    fun test_yawning_is_not_danger_field() {
        assertFalse(AsimoHub.isDangerField("driverYawning"))
    }

    @Test
    fun test_posture_is_not_danger_field() {
        assertFalse(AsimoHub.isDangerField("dangerousPosture"))
    }

    // -------------------------------------------------------------------------
    // computeLabelTint (3 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_label_tint_sets_20_percent_alpha() {
        // Red (0xFFE74C3C) → tinted (0x33E74C3C)
        val red = 0xFFE74C3C.toInt()
        val tinted = AsimoHub.computeLabelTint(red)
        assertEquals(0x33E74C3C.toInt(), tinted)
    }

    @Test
    fun test_label_tint_preserves_rgb() {
        val color = 0xFF5B8DEF.toInt()
        val tinted = AsimoHub.computeLabelTint(color)
        // RGB should be preserved
        assertEquals(color and 0x00FFFFFF, tinted and 0x00FFFFFF)
        // Alpha should be 0x33
        assertEquals(0x33, (tinted ushr 24) and 0xFF)
    }

    @Test
    fun test_label_tint_on_zero_color() {
        val tinted = AsimoHub.computeLabelTint(0x00000000)
        assertEquals(0x33000000, tinted)
    }

    // -------------------------------------------------------------------------
    // resolveHubMode (2 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_hub_mode_full_when_preview_off() {
        assertEquals(AsimoHub.HubMode.FULL, AsimoHub.resolveHubMode(false))
    }

    @Test
    fun test_hub_mode_compact_when_preview_on() {
        assertEquals(AsimoHub.HubMode.COMPACT, AsimoHub.resolveHubMode(true))
    }

    // -------------------------------------------------------------------------
    // resolveStatusTarget (2 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_status_routes_to_bubble_when_preview_off() {
        assertEquals("bubble", AsimoHub.resolveStatusTarget(false))
    }

    @Test
    fun test_status_routes_to_overlay_when_preview_on() {
        assertEquals("overlay", AsimoHub.resolveStatusTarget(true))
    }

    // -------------------------------------------------------------------------
    // applyAlpha (6 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_apply_alpha_full_opaque() {
        val color = 0x00FF0000  // red, zero alpha
        val result = AsimoHub.applyAlpha(color, 1.0f)
        assertEquals(0xFFFF0000.toInt(), result)
    }

    @Test
    fun test_apply_alpha_fully_transparent() {
        val color = 0xFFFF0000.toInt()  // red, full alpha
        val result = AsimoHub.applyAlpha(color, 0.0f)
        assertEquals(0x00FF0000, result)
    }

    @Test
    fun test_apply_alpha_half() {
        val color = 0xFF00FF00.toInt()  // green, full alpha
        val result = AsimoHub.applyAlpha(color, 0.5f)
        // 0.5 * 255 = 127 = 0x7F
        assertEquals(0x7F00FF00, result)
    }

    @Test
    fun test_apply_alpha_preserves_rgb() {
        val color = 0x12345678
        val result = AsimoHub.applyAlpha(color, 0.25f)
        // RGB should be 0x345678
        assertEquals(0x345678, result and 0x00FFFFFF)
    }

    @Test
    fun test_apply_alpha_clamps_above_1() {
        val color = 0x00AABBCC
        val result = AsimoHub.applyAlpha(color, 2.0f)
        // Should clamp to 255 = 0xFF
        assertEquals(0xFF, (result ushr 24) and 0xFF)
    }

    @Test
    fun test_apply_alpha_clamps_below_0() {
        val color = 0xFFAABBCC.toInt()
        val result = AsimoHub.applyAlpha(color, -1.0f)
        // Should clamp to 0
        assertEquals(0x00, (result ushr 24) and 0xFF)
    }

    // -------------------------------------------------------------------------
    // Integration: resolveDetectionKey + resolveGlowCategory (3 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_phone_detection_produces_danger_glow() {
        val result = makeResult(phone = true, riskLevel = "high")
        val key = AsimoHub.resolveDetectionKey(result)
        assertEquals("driverUsingPhone", key)
        assertEquals(AsimoHub.GlowCategory.DANGER, AsimoHub.resolveGlowCategory(result.riskLevel))
        assertTrue(AsimoHub.shouldPulse(result.riskLevel))
    }

    @Test
    fun test_yawning_detection_produces_caution_glow() {
        val result = makeResult(yawn = true, riskLevel = "medium")
        val key = AsimoHub.resolveDetectionKey(result)
        assertEquals("driverYawning", key)
        assertEquals(AsimoHub.GlowCategory.CAUTION, AsimoHub.resolveGlowCategory(result.riskLevel))
        assertFalse(AsimoHub.shouldPulse(result.riskLevel))
    }

    @Test
    fun test_all_clear_produces_safe_glow() {
        val result = makeResult(riskLevel = "low")
        val key = AsimoHub.resolveDetectionKey(result)
        assertEquals("", key)
        assertEquals(AsimoHub.GlowCategory.SAFE, AsimoHub.resolveGlowCategory(result.riskLevel))
        assertFalse(AsimoHub.shouldPulse(result.riskLevel))
    }

    // -------------------------------------------------------------------------
    // Integration: detection key → label + danger classification (3 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_phone_key_to_danger_label() {
        val key = "driverUsingPhone"
        assertTrue(AsimoHub.isDangerField(key))
        assertEquals("PHONE DETECTED", AsimoHub.getDetectionLabel(key, "en"))
    }

    @Test
    fun test_yawning_key_to_caution_label() {
        val key = "driverYawning"
        assertFalse(AsimoHub.isDangerField(key))
        assertEquals("YAWNING", AsimoHub.getDetectionLabel(key, "en"))
    }

    @Test
    fun test_all_clear_key_to_null_label() {
        val key = ""
        assertNull(AsimoHub.getDetectionLabel(key, "en"))
    }

    // -------------------------------------------------------------------------
    // Label maps completeness (2 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_en_and_ja_maps_have_same_keys() {
        assertEquals(AsimoHub.DETECTION_LABELS_EN.keys, AsimoHub.DETECTION_LABELS_JA.keys)
    }

    @Test
    fun test_all_pose_priority_fields_have_labels() {
        val poseFields = listOf(
            "driverUsingPhone", "driverEyesClosed", "handsOffWheel",
            "driverDistracted", "driverYawning", "driverEatingDrinking",
            "dangerousPosture", "childSlouching"
        )
        for (field in poseFields) {
            assertTrue(
                "EN label missing for $field",
                AsimoHub.DETECTION_LABELS_EN.containsKey(field)
            )
            assertTrue(
                "JA label missing for $field",
                AsimoHub.DETECTION_LABELS_JA.containsKey(field)
            )
        }
    }

    // -------------------------------------------------------------------------
    // Edge cases (2 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_unknown_risk_level_defaults_to_safe() {
        assertEquals(AsimoHub.GlowCategory.SAFE, AsimoHub.resolveGlowCategory("unknown"))
        assertFalse(AsimoHub.shouldPulse("unknown"))
    }

    @Test
    fun test_empty_risk_level_defaults_to_safe() {
        assertEquals(AsimoHub.GlowCategory.SAFE, AsimoHub.resolveGlowCategory(""))
        assertFalse(AsimoHub.shouldPulse(""))
    }

    // -------------------------------------------------------------------------
    // Compact label maps (3 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_compact_en_and_ja_maps_have_same_keys() {
        assertEquals(AsimoHub.COMPACT_LABELS_EN.keys, AsimoHub.COMPACT_LABELS_JA.keys)
    }

    @Test
    fun test_compact_maps_cover_all_detection_keys() {
        for (key in AsimoHub.DETECTION_LABELS_EN.keys) {
            assertTrue("Compact EN missing: $key", AsimoHub.COMPACT_LABELS_EN.containsKey(key))
            assertTrue("Compact JA missing: $key", AsimoHub.COMPACT_LABELS_JA.containsKey(key))
        }
    }

    @Test
    fun test_compact_labels_are_short() {
        for ((key, label) in AsimoHub.COMPACT_LABELS_EN) {
            assertTrue("Compact label too long for $key: '$label'", label.length <= 12)
        }
    }
}
