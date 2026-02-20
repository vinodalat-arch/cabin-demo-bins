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

    // Face analysis (EAR and HEAD_PITCH are overridable per platform at service startup)
    @JvmStatic var EAR_THRESHOLD = 0.21f
    const val EAR_BASELINE_RATIO = 0.65f        // closed = ear < baseline * ratio
    const val MAR_THRESHOLD = 0.5f
    const val HEAD_YAW_THRESHOLD = 30.0f
    @JvmStatic var HEAD_PITCH_THRESHOLD = 35.0f
    const val PITCH_BASELINE_DEVIATION = 25.0f   // distracted = |pitch - baseline| > deviation
    const val BASELINE_FRAMES = 10               // frames to accumulate for auto-baseline
    const val ANGLE_SMOOTH_WINDOW = 3            // moving average window for yaw/pitch

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
    const val EYES_CLOSED_MIN_FRAMES = 3      // ~3s at 1fps before reporting eyes_closed
    const val DISTRACTED_MIN_FRAMES = 3       // ~3s — allow quick mirror/blind-spot glances
    const val EATING_MIN_FRAMES = 3           // ~3s — allow quick sips
    const val POSTURE_MIN_FRAMES = 3          // ~3s — allow brief adjustments
    const val YAWNING_MIN_FRAMES = 2           // ~2s — filter single-frame mouth-open
    const val CHILD_SLOUCH_MIN_FRAMES = 5     // ~5s — kids shift constantly

    // Audio — escalation ladder
    const val ALERT_COOLDOWN_MS = 10_000L
    const val ALERT_STALENESS_MS = 4_000L
    const val ALERT_ESCALATION_FIRST_S = 10
    const val ALERT_ESCALATION_BEEP_S = 20
    const val ALERT_ESCALATION_REPEAT_S = 10
    const val ALERT_BEEP_DURATION_MS = 1000
    const val ALERT_QUEUE_CAPACITY = 3
    const val DISTRACTION_GRACE_FRAMES = 2  // consecutive clean frames before reset

    // Preview (toggleable at runtime via UI; persisted in SharedPreferences)
    @JvmStatic @Volatile var ENABLE_PREVIEW = false

    // Audio alerts (toggleable at runtime via UI; persisted in SharedPreferences)
    @JvmStatic @Volatile var ENABLE_AUDIO_ALERTS = true

    // Language: "en" or "ja" (toggleable at runtime via UI; persisted in SharedPreferences)
    @JvmStatic @Volatile var LANGUAGE = "en"

    // WiFi camera MJPEG stream URL (empty = disabled; persisted in SharedPreferences)
    @JvmStatic @Volatile var WIFI_CAMERA_URL = ""

    // Driver seat side: "left" (LHD) or "right" (RHD)
    @JvmStatic @Volatile var DRIVER_SEAT_SIDE = "left"

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
