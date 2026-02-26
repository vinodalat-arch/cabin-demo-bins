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
        assertEquals(0.35f, Config.YOLO_CONFIDENCE, 0.001f)
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
        assertEquals(10, Config.BASELINE_FRAMES)
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
        assertEquals(0.65f, Config.CHILD_BBOX_RATIO, 0.001f)
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
        assertEquals(2, Config.EYES_CLOSED_MIN_FRAMES)
        assertEquals(2, Config.DISTRACTED_MIN_FRAMES)
        assertEquals(2, Config.EATING_MIN_FRAMES)
        assertEquals(2, Config.POSTURE_MIN_FRAMES)
        assertEquals(2, Config.YAWNING_MIN_FRAMES)
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
}
