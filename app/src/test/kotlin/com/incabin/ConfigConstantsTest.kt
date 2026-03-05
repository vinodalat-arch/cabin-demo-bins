package com.incabin

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for Config constants, verifying documented values match implementation.
 * Constants should never change without updating CLAUDE.md and this test file.
 */
class ConfigConstantsTest {

    // -------------------------------------------------------------------------
    // Camera constants (3 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_camera_dimensions() {
        assertEquals(1280, Config.CAMERA_WIDTH)
        assertEquals(720, Config.CAMERA_HEIGHT)
    }

    @Test
    fun test_inference_fps_interval() {
        val origFps = Config.INFERENCE_FPS
        try {
            Config.INFERENCE_FPS = 1
            assertEquals(1000L, Config.inferenceIntervalMs())
            Config.INFERENCE_FPS = 2
            assertEquals(500L, Config.inferenceIntervalMs())
            Config.INFERENCE_FPS = 3
            assertEquals(333L, Config.inferenceIntervalMs())
            // Edge cases: out-of-range values clamped
            Config.INFERENCE_FPS = 0
            assertEquals(1000L, Config.inferenceIntervalMs())  // coerced to 1
            Config.INFERENCE_FPS = 5
            assertEquals(333L, Config.inferenceIntervalMs())   // coerced to 3
        } finally {
            Config.INFERENCE_FPS = origFps
        }
    }

    @Test
    fun test_v4l2_reconnect_constants() {
        assertEquals(2, Config.V4L2_SELECT_TIMEOUT_S)
        assertEquals(3, Config.V4L2_MAX_CONSECUTIVE_FAILURES)
        assertEquals(2000L, Config.V4L2_RECONNECT_INITIAL_DELAY_MS)
        assertEquals(30000L, Config.V4L2_RECONNECT_MAX_DELAY_MS)
    }

    // -------------------------------------------------------------------------
    // YOLO thresholds (3 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_yolo_confidence_thresholds() {
        assertEquals(0.45f, Config.YOLO_CONFIDENCE, 0.001f)
        assertEquals(0.45f, Config.YOLO_NMS_IOU, 0.001f)
    }

    @Test
    fun test_yolo_phone_class() {
        assertEquals(67, Config.YOLO_PHONE_CLASS)
    }

    @Test
    fun test_food_drink_classes_are_39_to_48() {
        assertArrayEquals(
            intArrayOf(39, 40, 41, 42, 43, 44, 45, 46, 47, 48),
            Config.FOOD_DRINK_CLASSES
        )
    }

    // -------------------------------------------------------------------------
    // Face analysis thresholds (6 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_ear_threshold() {
        assertEquals(0.21f, Config.EAR_THRESHOLD, 0.001f)
    }

    @Test
    fun test_ear_baseline_ratio() {
        assertEquals(0.65f, Config.EAR_BASELINE_RATIO, 0.001f)
    }

    @Test
    fun test_mar_threshold() {
        assertEquals(0.5f, Config.MAR_THRESHOLD, 0.001f)
    }

    @Test
    fun test_head_yaw_threshold() {
        assertEquals(30.0f, Config.HEAD_YAW_THRESHOLD, 0.001f)
    }

    @Test
    fun test_head_pitch_threshold() {
        assertEquals(35.0f, Config.HEAD_PITCH_THRESHOLD, 0.001f)
    }

    @Test
    fun test_baseline_calibration_constants() {
        assertEquals(3, Config.BASELINE_FRAMES)
        assertEquals(25.0f, Config.PITCH_BASELINE_DEVIATION, 0.001f)
        assertEquals(3, Config.ANGLE_SMOOTH_WINDOW)
    }

    // -------------------------------------------------------------------------
    // Pose analysis thresholds (4 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_posture_lean_threshold() {
        assertEquals(30.0f, Config.POSTURE_LEAN_THRESHOLD, 0.001f)
    }

    @Test
    fun test_child_slouch_threshold() {
        assertEquals(20.0f, Config.CHILD_SLOUCH_THRESHOLD, 0.001f)
    }

    @Test
    fun test_child_bbox_ratio() {
        assertEquals(0.50f, Config.CHILD_BBOX_RATIO, 0.001f)
    }

    @Test
    fun test_keypoint_and_wrist_constants() {
        assertEquals(0.5f, Config.KP_CONF_THRESHOLD, 0.001f)
        assertEquals(200, Config.WRIST_CROP_SIZE)
    }

    // -------------------------------------------------------------------------
    // Smoother constants (5 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_smoother_window_and_threshold() {
        assertEquals(3, Config.SMOOTHER_WINDOW)
        assertEquals(0.6f, Config.SMOOTHER_THRESHOLD, 0.001f)
    }

    @Test
    fun test_fast_clear_frames() {
        assertEquals(2, Config.FAST_CLEAR_FRAMES)
    }

    @Test
    fun test_sustained_detection_thresholds() {
        assertEquals(1, Config.EYES_CLOSED_MIN_FRAMES)
        assertEquals(2, Config.DISTRACTED_MIN_FRAMES)
        assertEquals(2, Config.EATING_MIN_FRAMES)
        assertEquals(2, Config.POSTURE_MIN_FRAMES)
        assertEquals(1, Config.YAWNING_MIN_FRAMES)
        assertEquals(3, Config.HANDS_OFF_MIN_FRAMES)
        assertEquals(3, Config.CHILD_SLOUCH_MIN_FRAMES)
    }

    @Test
    fun test_distraction_grace_frames() {
        assertEquals(2, Config.DISTRACTION_GRACE_FRAMES)
    }

    @Test
    fun test_head_turn_threshold() {
        assertEquals(0.3f, Config.HEAD_TURN_THRESHOLD, 0.001f)
    }

    // -------------------------------------------------------------------------
    // Audio/escalation constants (4 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_alert_cooldown() {
        assertEquals(10_000L, Config.ALERT_COOLDOWN_MS)
    }

    @Test
    fun test_alert_staleness() {
        assertEquals(4_000L, Config.ALERT_STALENESS_MS)
    }

    @Test
    fun test_escalation_thresholds() {
        assertEquals(5, Config.ALERT_ESCALATION_FIRST_S)
        assertEquals(10, Config.ALERT_ESCALATION_BEEP_S)
        assertEquals(10, Config.ALERT_ESCALATION_REPEAT_S)
    }

    @Test
    fun test_alert_beep_and_queue() {
        assertEquals(1000, Config.ALERT_BEEP_DURATION_MS)
        assertEquals(3, Config.ALERT_QUEUE_CAPACITY)
    }

    // -------------------------------------------------------------------------
    // Face recognition constants (3 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_face_recognition_threshold() {
        assertEquals(0.5f, Config.FACE_RECOGNITION_THRESHOLD, 0.001f)
    }

    @Test
    fun test_face_recognition_interval() {
        assertEquals(5, Config.FACE_RECOGNITION_INTERVAL)
    }

    @Test
    fun test_face_embedding_dim() {
        assertEquals(512, Config.FACE_EMBEDDING_DIM)
    }

    // -------------------------------------------------------------------------
    // IVI robustness constants (4 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_watchdog_timeout() {
        assertEquals(30_000L, Config.WATCHDOG_TIMEOUT_MS)
        assertEquals(5_000L, Config.WATCHDOG_CHECK_INTERVAL_MS)
    }

    @Test
    fun test_init_timeout() {
        assertEquals(30_000L, Config.INIT_TIMEOUT_MS)
    }

    @Test
    fun test_service_stall_threshold() {
        assertEquals(15_000L, Config.SERVICE_STALL_THRESHOLD_MS)
    }

    @Test
    fun test_max_consecutive_inference_errors() {
        assertEquals(10, Config.MAX_CONSECUTIVE_INFERENCE_ERRORS)
    }

    // -------------------------------------------------------------------------
    // Risk weights and thresholds (2 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_risk_weights() {
        assertEquals(3, Config.RISK_WEIGHT_PHONE)
        assertEquals(3, Config.RISK_WEIGHT_EYES)
        assertEquals(3, Config.RISK_WEIGHT_HANDS_OFF)
        assertEquals(2, Config.RISK_WEIGHT_YAWNING)
        assertEquals(2, Config.RISK_WEIGHT_DISTRACTED)
        assertEquals(2, Config.RISK_WEIGHT_POSTURE)
        assertEquals(1, Config.RISK_WEIGHT_EATING)
        assertEquals(1, Config.RISK_WEIGHT_SLOUCH)
    }

    @Test
    fun test_risk_thresholds() {
        assertEquals(3, Config.RISK_HIGH_THRESHOLD)
        assertEquals(1, Config.RISK_MEDIUM_THRESHOLD)
    }

    // -------------------------------------------------------------------------
    // Mutable config defaults (3 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_preview_default_off() {
        // Note: other tests may toggle this, but the compiled default is false
        assertFalse(Config.ENABLE_PREVIEW)
    }

    @Test
    fun test_audio_default_on() {
        assertTrue(Config.ENABLE_AUDIO_ALERTS)
    }

    @Test
    fun test_language_default_english() {
        assertEquals("en", Config.LANGUAGE)
    }

    @Test
    fun test_seat_side_default_left() {
        assertEquals("left", Config.DRIVER_SEAT_SIDE)
    }

    @Test
    fun test_wifi_camera_default_empty() {
        assertEquals("", Config.WIFI_CAMERA_URL)
    }

    @Test
    fun test_brand_default_honda() {
        assertEquals("honda", Config.BRAND)
    }

    // -------------------------------------------------------------------------
    // Rear camera constants (5 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_rear_coco_classes() {
        assertEquals(0, Config.REAR_PERSON_CLASS)
        assertEquals(15, Config.REAR_CAT_CLASS)
        assertEquals(16, Config.REAR_DOG_CLASS)
    }

    @Test
    fun test_rear_confidence_thresholds() {
        assertEquals(0.45f, Config.REAR_PERSON_CONFIDENCE)
        assertEquals(0.40f, Config.REAR_ANIMAL_CONFIDENCE)
    }

    @Test
    fun test_rear_smoother_window() {
        assertEquals(2, Config.REAR_SMOOTHER_WINDOW)
    }

    @Test
    fun test_rear_min_frames() {
        assertEquals(1, Config.REAR_PERSON_MIN_FRAMES)
        assertEquals(2, Config.REAR_ANIMAL_MIN_FRAMES)
    }

    @Test
    fun test_reverse_risk_cap() {
        assertEquals("medium", Config.REVERSE_RISK_CAP)
    }

    @Test
    fun test_gear_reverse_constant() {
        assertEquals(0x0020, VehicleChannelManager.GEAR_REVERSE)
    }

    // -------------------------------------------------------------------------
    // Speed tier and compressed escalation constants (4 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_speed_tier_thresholds() {
        assertEquals(30f, Config.SPEED_SLOW_MAX_KMH, 0.001f)
        assertEquals(80f, Config.SPEED_MODERATE_MAX_KMH, 0.001f)
    }

    @Test
    fun test_speed_vhal_property_id() {
        assertEquals(0x11600207, Config.SPEED_VHAL_PROPERTY_ID)
    }

    @Test
    fun test_escalation_moderate_thresholds() {
        assertEquals(3, Config.ESCALATION_MODERATE_L2_S)
        assertEquals(5, Config.ESCALATION_MODERATE_L3_S)
        assertEquals(10, Config.ESCALATION_MODERATE_L4_S)
        assertEquals(20, Config.ESCALATION_MODERATE_L5_S)
    }

    @Test
    fun test_escalation_fast_thresholds() {
        assertEquals(0, Config.ESCALATION_FAST_L2_S)
        assertEquals(3, Config.ESCALATION_FAST_L3_S)
        assertEquals(5, Config.ESCALATION_FAST_L4_S)
        assertEquals(10, Config.ESCALATION_FAST_L5_S)
    }

    // -------------------------------------------------------------------------
    // Climate control constants (4 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_climate_offset_per_person() {
        assertEquals(0.5f, Config.CLIMATE_OFFSET_PER_PERSON_C, 0.001f)
    }

    @Test
    fun test_climate_max_adjustment() {
        assertEquals(2.0f, Config.CLIMATE_MAX_ADJUSTMENT_C, 0.001f)
    }

    @Test
    fun test_climate_temp_range() {
        assertEquals(16.0f, Config.CLIMATE_MIN_TEMP_C, 0.001f)
        assertEquals(28.0f, Config.CLIMATE_MAX_TEMP_C, 0.001f)
    }

    @Test
    fun test_climate_debounce_and_ramp() {
        assertEquals(5, Config.CLIMATE_DEBOUNCE_FRAMES)
        assertEquals(0.5f, Config.CLIMATE_RAMP_STEP_C, 0.001f)
    }

    // -------------------------------------------------------------------------
    // Seat assignment (1 test)
    // -------------------------------------------------------------------------

    @Test
    fun test_seat_front_row_area_ratio() {
        assertEquals(0.55f, Config.SEAT_FRONT_ROW_AREA_RATIO, 0.001f)
    }

    // -------------------------------------------------------------------------
    // Driver profile constants (1 test)
    // -------------------------------------------------------------------------

    @Test
    fun test_driver_profile_defaults() {
        assertFalse(Config.ENABLE_DRIVER_PROFILES)
        assertEquals("", Config.CURRENT_DRIVER_AMBIENT_COLOR)
    }

    // -------------------------------------------------------------------------
    // Child left-behind constants (1 test)
    // -------------------------------------------------------------------------

    @Test
    fun test_child_left_behind_constants() {
        assertTrue(Config.ENABLE_CHILD_LEFT_BEHIND)  // on by default (safety)
        assertEquals(3, Config.CHILD_LEFT_BEHIND_DEBOUNCE_FRAMES)
        assertEquals(500L, Config.CHILD_LEFT_BEHIND_CABIN_FLASH_MS)
    }

    // -------------------------------------------------------------------------
    // Seat massage constants (1 test)
    // -------------------------------------------------------------------------

    @Test
    fun test_seat_massage_constants() {
        assertTrue(Config.ENABLE_SEAT_MASSAGE)
        assertEquals(30_000L, Config.SEAT_MASSAGE_COOLDOWN_MS)
        assertEquals(3_000L, Config.SEAT_MASSAGE_DURATION_MS)
    }

    // -------------------------------------------------------------------------
    // Ambient light constants (1 test)
    // -------------------------------------------------------------------------

    @Test
    fun test_ambient_light_defaults() {
        assertFalse(Config.ENABLE_AMBIENT_LIGHT)
        assertFalse(Config.ENABLE_AMBIENT_COMFORT)
    }

    // -------------------------------------------------------------------------
    // Emergency override constants (1 test)
    // -------------------------------------------------------------------------

    @Test
    fun test_emergency_override_constants() {
        assertEquals(20, Config.EMERGENCY_SCORE_THRESHOLD)
        assertEquals(300L, Config.EMERGENCY_HORN_CHIRP_MS)
        assertEquals(10, Config.EMERGENCY_HYSTERESIS)
    }

    // -------------------------------------------------------------------------
    // HVAC zone constants (1 test)
    // -------------------------------------------------------------------------

    @Test
    fun test_hvac_zone_constants() {
        assertFalse(Config.ENABLE_ZONE_HVAC)
        assertEquals(3.0f, Config.HVAC_ZONE_ECO_OFFSET_C, 0.001f)
        assertEquals(0x0001, Config.HVAC_AREA_ROW1_LEFT)
        assertEquals(0x0004, Config.HVAC_AREA_ROW1_RIGHT)
        assertEquals(0x0010, Config.HVAC_AREA_ROW2_LEFT)
        assertEquals(0x0040, Config.HVAC_AREA_ROW2_RIGHT)
    }

    // -------------------------------------------------------------------------
    // ConfigPrefs new keys exist (1 test)
    // -------------------------------------------------------------------------

    @Test
    fun test_configPrefs_new_keys() {
        assertEquals("driver_profiles_enabled", ConfigPrefs.PREF_DRIVER_PROFILES)
        assertEquals("child_left_behind_enabled", ConfigPrefs.PREF_CHILD_LEFT_BEHIND)
        assertEquals("seat_massage_enabled", ConfigPrefs.PREF_SEAT_MASSAGE)
        assertEquals("ambient_light_enabled", ConfigPrefs.PREF_AMBIENT_LIGHT)
        assertEquals("ambient_comfort_enabled", ConfigPrefs.PREF_AMBIENT_COMFORT)
        assertEquals("zone_hvac_enabled", ConfigPrefs.PREF_ZONE_HVAC)
    }

    // -------------------------------------------------------------------------
    // Wellness coach constants (1 test)
    // -------------------------------------------------------------------------

    @Test
    fun test_wellness_coach_constants() {
        assertTrue(Config.ENABLE_WELLNESS_COACH)
        assertEquals(45, Config.WELLNESS_MILESTONE_1_MIN)
        assertEquals(90, Config.WELLNESS_MILESTONE_2_MIN)
        assertEquals(120, Config.WELLNESS_MILESTONE_3_MIN)
        assertEquals(30, Config.WELLNESS_REPEAT_INTERVAL_MIN)
    }

    // -------------------------------------------------------------------------
    // Quiet mode constants (1 test)
    // -------------------------------------------------------------------------

    @Test
    fun test_quiet_mode_default() {
        assertTrue(Config.ENABLE_QUIET_MODE)
    }

    // -------------------------------------------------------------------------
    // Fatigue comfort constants (1 test)
    // -------------------------------------------------------------------------

    @Test
    fun test_fatigue_comfort_constants() {
        assertTrue(Config.ENABLE_FATIGUE_COMFORT)
        assertEquals(50, Config.FATIGUE_BRIGHTNESS_BOOST)
        assertEquals(-1.5f, Config.FATIGUE_HVAC_OFFSET_C, 0.001f)
        assertEquals(30, Config.FATIGUE_DEACTIVATE_FRAMES)
    }

    // -------------------------------------------------------------------------
    // Nap mode constants (1 test)
    // -------------------------------------------------------------------------

    @Test
    fun test_nap_mode_constants() {
        assertTrue(Config.ENABLE_NAP_MODE)
        assertEquals(1.0f, Config.NAP_HVAC_WARM_OFFSET_C, 0.001f)
    }

    // -------------------------------------------------------------------------
    // Child comfort constants (1 test)
    // -------------------------------------------------------------------------

    @Test
    fun test_child_comfort_constants() {
        assertTrue(Config.ENABLE_CHILD_COMFORT)
        assertEquals(1.0f, Config.CHILD_COMFORT_HVAC_OFFSET_C, 0.001f)
        assertEquals(30, Config.CHILD_REMINDER_INTERVAL_MIN)
    }

    // -------------------------------------------------------------------------
    // Eco cabin constants (1 test)
    // -------------------------------------------------------------------------

    @Test
    fun test_eco_cabin_constants() {
        assertTrue(Config.ENABLE_ECO_CABIN)
        assertEquals(10, Config.ECO_DEBOUNCE_FRAMES)
        assertEquals(20, Config.ECO_BRIGHTNESS)
    }

    // -------------------------------------------------------------------------
    // Arrival prep constants (1 test)
    // -------------------------------------------------------------------------

    @Test
    fun test_arrival_prep_constants() {
        assertTrue(Config.ENABLE_ARRIVAL_PREP)
        assertEquals(10, Config.ARRIVAL_SPEED_HISTORY_SIZE)
    }

    // -------------------------------------------------------------------------
    // ConfigPrefs comfort feature keys (1 test)
    // -------------------------------------------------------------------------

    @Test
    fun test_configPrefs_comfort_keys() {
        assertEquals("wellness_coach_enabled", ConfigPrefs.PREF_WELLNESS_COACH)
        assertEquals("quiet_mode_enabled", ConfigPrefs.PREF_QUIET_MODE)
        assertEquals("fatigue_comfort_enabled", ConfigPrefs.PREF_FATIGUE_COMFORT)
        assertEquals("nap_mode_enabled", ConfigPrefs.PREF_NAP_MODE)
        assertEquals("child_comfort_enabled", ConfigPrefs.PREF_CHILD_COMFORT)
        assertEquals("eco_cabin_enabled", ConfigPrefs.PREF_ECO_CABIN)
        assertEquals("arrival_prep_enabled", ConfigPrefs.PREF_ARRIVAL_PREP)
    }
}
