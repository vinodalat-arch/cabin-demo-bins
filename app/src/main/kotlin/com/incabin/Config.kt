package com.incabin

object Config {
    // Camera
    const val CAMERA_WIDTH = 1280
    const val CAMERA_HEIGHT = 720
    const val INFERENCE_INTERVAL_MS = 100L

    // YOLO
    const val YOLO_CONFIDENCE = 0.35f
    const val YOLO_NMS_IOU = 0.45f
    const val YOLO_PHONE_CLASS = 67
    val FOOD_DRINK_CLASSES = intArrayOf(39, 40, 41, 42, 43, 44, 45, 46, 47, 48)

    // Face analysis
    const val EAR_THRESHOLD = 0.21f
    const val MAR_THRESHOLD = 0.5f
    const val HEAD_YAW_THRESHOLD = 30.0f
    const val HEAD_PITCH_THRESHOLD = 35.0f

    // Pose analysis
    const val POSTURE_LEAN_THRESHOLD = 30.0f
    const val CHILD_SLOUCH_THRESHOLD = 20.0f
    const val HEAD_TURN_THRESHOLD = 0.3f
    const val CHILD_BBOX_RATIO = 0.75f
    const val KP_CONF_THRESHOLD = 0.5f
    const val WRIST_CROP_SIZE = 200

    // Smoother
    const val SMOOTHER_WINDOW = 3
    const val SMOOTHER_THRESHOLD = 0.6f
    const val FAST_CLEAR_FRAMES = 2

    // Audio
    val DISTRACTION_ALERT_THRESHOLDS = intArrayOf(5, 10, 20)
    const val DISTRACTION_BEEP_THRESHOLD_S = 20

    // Preview (toggleable at runtime via UI; persisted in SharedPreferences)
    @JvmStatic @Volatile var ENABLE_PREVIEW = false

    // V4L2
    const val V4L2_SELECT_TIMEOUT_S = 2
    const val V4L2_MAX_CONSECUTIVE_FAILURES = 3
    const val V4L2_RECONNECT_INITIAL_DELAY_MS = 2000L
    const val V4L2_RECONNECT_MAX_DELAY_MS = 30000L

    // Face recognition
    const val FACE_RECOGNITION_THRESHOLD = 0.5f
    const val FACE_RECOGNITION_INTERVAL = 5
    const val FACE_EMBEDDING_DIM = 512

    // Risk weights
    const val RISK_WEIGHT_PHONE = 3
    const val RISK_WEIGHT_EYES = 3
    const val RISK_WEIGHT_YAWNING = 2
    const val RISK_WEIGHT_DISTRACTED = 2
    const val RISK_WEIGHT_POSTURE = 2
    const val RISK_WEIGHT_EATING = 1
    const val RISK_WEIGHT_SLOUCH = 1
    const val RISK_HIGH_THRESHOLD = 3
    const val RISK_MEDIUM_THRESHOLD = 1
}
