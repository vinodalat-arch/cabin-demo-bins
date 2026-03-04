package com.incabin

object Config {
    // Camera
    const val CAMERA_WIDTH = 1280
    const val CAMERA_HEIGHT = 720

    // YOLO
    const val YOLO_CONFIDENCE = 0.35f
    const val YOLO_NMS_IOU = 0.45f
    const val YOLO_PHONE_CLASS = 67
    val FOOD_DRINK_CLASSES = intArrayOf(39, 40, 41, 42, 43, 44, 45, 46, 47, 48)

    // Face analysis (EAR and HEAD_PITCH are overridable per platform at service startup)
    @JvmStatic @Volatile var EAR_THRESHOLD = 0.21f
    const val EAR_BASELINE_RATIO = 0.65f        // closed = ear < baseline * ratio
    const val MAR_THRESHOLD = 0.5f
    const val HEAD_YAW_THRESHOLD = 30.0f
    @JvmStatic @Volatile var HEAD_PITCH_THRESHOLD = 35.0f
    const val PITCH_BASELINE_DEVIATION = 25.0f   // distracted = |pitch - baseline| > deviation
    const val BASELINE_FRAMES = 10               // frames to accumulate for auto-baseline
    const val ANGLE_SMOOTH_WINDOW = 3            // moving average window for yaw/pitch

    // Pose analysis
    const val POSTURE_LEAN_THRESHOLD = 30.0f
    const val CHILD_SLOUCH_THRESHOLD = 20.0f
    const val HEAD_TURN_THRESHOLD = 0.3f
    const val CHILD_BBOX_RATIO = 0.65f
    const val KP_CONF_THRESHOLD = 0.5f
    const val WRIST_CROP_SIZE = 200

    // Smoother
    const val SMOOTHER_WINDOW = 3
    const val SMOOTHER_THRESHOLD = 0.6f
    const val FAST_CLEAR_FRAMES = 2
    const val EYES_CLOSED_MIN_FRAMES = 2      // ~2s at 1fps before reporting eyes_closed
    const val DISTRACTED_MIN_FRAMES = 2       // ~2s — allow quick mirror/blind-spot glances
    const val EATING_MIN_FRAMES = 2           // ~2s — allow quick sips
    const val POSTURE_MIN_FRAMES = 2          // ~2s — allow brief adjustments
    const val HANDS_OFF_MIN_FRAMES = 3         // ~3s — confirm hands truly off wheel
    const val YAWNING_MIN_FRAMES = 2           // ~2s — filter single-frame mouth-open
    const val CHILD_SLOUCH_MIN_FRAMES = 3     // ~3s — kids shift constantly

    // Audio — escalation ladder
    const val ALERT_COOLDOWN_MS = 10_000L
    const val ALERT_STALENESS_MS = 4_000L
    const val ALERT_ESCALATION_FIRST_S = 5
    const val ALERT_ESCALATION_BEEP_S = 10
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

    // Inference mode: "local" or "remote" (persisted in SharedPreferences)
    @JvmStatic @Volatile var INFERENCE_MODE = "local"

    // VLM server URL (e.g., "http://192.168.1.100:8000")
    @JvmStatic @Volatile var VLM_SERVER_URL = ""

    // Inference FPS: 1, 2, or 3 — shared between local and VLM modes
    // Local: controls INFERENCE_INTERVAL_MS = 1000/fps
    // VLM: controls poll interval = 1000/fps
    @JvmStatic @Volatile var INFERENCE_FPS = 1

    /** Compute the poll/inference interval in milliseconds from current FPS. */
    @JvmStatic fun inferenceIntervalMs(): Long = (1000L / INFERENCE_FPS.coerceIn(1, 3))

    // Driver seat side: "left" (LHD) or "right" (RHD)
    @JvmStatic @Volatile var DRIVER_SEAT_SIDE = "left"

    // Passenger info detail: "minimal" or "detailed" (toggleable at runtime; persisted in SharedPreferences)
    @JvmStatic @Volatile var PASSENGER_INFO_DETAIL = "minimal"

    // ASIMO mascot size: "s", "m", or "l" (toggleable at runtime; persisted in SharedPreferences)
    @JvmStatic @Volatile var ASIMO_SIZE = "m"

    // Bottom widget: "none", "stats", or "tips" (toggleable at runtime; persisted in SharedPreferences)
    @JvmStatic @Volatile var BOTTOM_WIDGET = "none"

    // Brand: "honda" or "konfluence" (toggleable at runtime; persisted in SharedPreferences)
    @JvmStatic @Volatile var BRAND = "honda"

    // V4L2
    const val V4L2_SELECT_TIMEOUT_S = 2
    const val V4L2_MAX_CONSECUTIVE_FAILURES = 3
    const val V4L2_RECONNECT_INITIAL_DELAY_MS = 2000L
    const val V4L2_RECONNECT_MAX_DELAY_MS = 30000L

    // Face recognition
    const val FACE_RECOGNITION_THRESHOLD = 0.5f
    const val FACE_RECOGNITION_INTERVAL = 5
    const val FACE_EMBEDDING_DIM = 512

    // Pipeline watchdog
    const val WATCHDOG_TIMEOUT_MS = 30_000L
    const val WATCHDOG_CHECK_INTERVAL_MS = 5_000L

    // Init timeout (bounded latch.await)
    const val INIT_TIMEOUT_MS = 30_000L

    // Service alive check (UI stall indicator)
    const val SERVICE_STALL_THRESHOLD_MS = 15_000L

    // Inference error tracking
    const val MAX_CONSECUTIVE_INFERENCE_ERRORS = 10

    // Multi-modal escalation thresholds (seconds) — normal/stationary/slow/unavailable
    const val ESCALATION_L2_THRESHOLD_S = 5   // aligns with ALERT_ESCALATION_FIRST_S
    const val ESCALATION_L3_THRESHOLD_S = 10  // aligns with ALERT_ESCALATION_BEEP_S
    const val ESCALATION_L4_THRESHOLD_S = 20
    const val ESCALATION_L5_THRESHOLD_S = 30

    // Speed-compressed escalation thresholds — MODERATE (31-80 km/h)
    const val ESCALATION_MODERATE_L2_S = 3
    const val ESCALATION_MODERATE_L3_S = 5
    const val ESCALATION_MODERATE_L4_S = 10
    const val ESCALATION_MODERATE_L5_S = 20

    // Speed-compressed escalation thresholds — FAST (>80 km/h)
    const val ESCALATION_FAST_L2_S = 0
    const val ESCALATION_FAST_L3_S = 3
    const val ESCALATION_FAST_L4_S = 5
    const val ESCALATION_FAST_L5_S = 10

    // Vehicle speed from VHAL (-1 = unavailable)
    @JvmStatic @Volatile var VEHICLE_SPEED_KMH = -1f
    const val SPEED_VHAL_PROPERTY_ID = 0x11600207  // PERF_VEHICLE_SPEED, Float, m/s
    const val SPEED_SLOW_MAX_KMH = 30f
    const val SPEED_MODERATE_MAX_KMH = 80f

    // HVAC Climate Control — occupancy-based
    const val HVAC_TEMPERATURE_SET_PROPERTY_ID = 0x15600503  // Float, °C
    const val HVAC_FAN_SPEED_PROPERTY_ID = 0x1560050A        // Int32, 0-10
    @JvmStatic @Volatile var HVAC_BASE_TEMP_C = 22.0f        // User's comfort baseline
    const val CLIMATE_OFFSET_PER_PERSON_C = 0.5f
    const val CLIMATE_MAX_ADJUSTMENT_C = 2.0f
    const val CLIMATE_MIN_TEMP_C = 16.0f
    const val CLIMATE_MAX_TEMP_C = 28.0f
    const val CLIMATE_DEBOUNCE_FRAMES = 5                     // ~5s at 1fps
    const val CLIMATE_RAMP_STEP_C = 0.5f                      // Max °C change per update

    // Auto climate toggle (toggleable at runtime via UI; persisted in SharedPreferences)
    @JvmStatic @Volatile var ENABLE_AUTO_CLIMATE = false

    // HVAC zone control — per-zone temperature management
    @JvmStatic @Volatile var ENABLE_ZONE_HVAC = false
    const val HVAC_ZONE_ECO_OFFSET_C = 3.0f
    const val HVAC_AREA_ROW1_LEFT = 0x0001
    const val HVAC_AREA_ROW1_RIGHT = 0x0004
    const val HVAC_AREA_ROW2_LEFT = 0x0010
    const val HVAC_AREA_ROW2_RIGHT = 0x0040

    // Vehicle action pulse durations (ms)
    const val VEHICLE_PULSE_CABIN_LIGHTS_MS = 3000L
    const val VEHICLE_PULSE_LUMBAR_MS = 2000L
    const val VEHICLE_PULSE_SEAT_THERMAL_MS = 5000L
    const val VEHICLE_WINDOW_SLIDE_MS = 30000L

    // Rear camera (WiFi MJPEG rear-view)
    @JvmStatic @Volatile var REVERSE_GEAR_ACTIVE = false  // set by VHAL listener or manual toggle
    const val REAR_PERSON_CLASS = 0
    const val REAR_CAT_CLASS = 15
    const val REAR_DOG_CLASS = 16
    const val REAR_PERSON_CONFIDENCE = 0.45f
    const val REAR_ANIMAL_CONFIDENCE = 0.40f
    const val REAR_SMOOTHER_WINDOW = 2   // faster response for reversing safety
    const val REAR_PERSON_MIN_FRAMES = 1 // immediate alert
    const val REAR_ANIMAL_MIN_FRAMES = 2

    // In-cabin risk dampening during reverse
    const val REVERSE_RISK_CAP = "medium"  // cap in-cabin risk at medium during reverse

    // Driver profiles (per-driver preference persistence)
    @JvmStatic @Volatile var ENABLE_DRIVER_PROFILES = false
    @JvmStatic @Volatile var CURRENT_DRIVER_AMBIENT_COLOR = ""

    // Child left-behind alert (safety feature — on by default)
    @JvmStatic @Volatile var ENABLE_CHILD_LEFT_BEHIND = true
    const val CHILD_LEFT_BEHIND_DEBOUNCE_FRAMES = 3
    const val CHILD_LEFT_BEHIND_CABIN_FLASH_MS = 500L

    // Seat massage / drowsiness wake-up
    @JvmStatic @Volatile var ENABLE_SEAT_MASSAGE = true
    const val SEAT_MASSAGE_COOLDOWN_MS = 30_000L
    const val SEAT_MASSAGE_DURATION_MS = 3_000L

    // Ambient light zones
    @JvmStatic @Volatile var ENABLE_AMBIENT_LIGHT = false
    @JvmStatic @Volatile var ENABLE_AMBIENT_COMFORT = false

    // Emergency score override
    const val EMERGENCY_SCORE_THRESHOLD = 20
    const val EMERGENCY_HORN_CHIRP_MS = 300L
    const val EMERGENCY_HYSTERESIS = 10

    // Seat assignment
    const val SEAT_FRONT_ROW_AREA_RATIO = 0.40f  // bbox area ratio for front/rear discrimination

    // Risk weights
    const val RISK_WEIGHT_PHONE = 3
    const val RISK_WEIGHT_EYES = 3
    const val RISK_WEIGHT_YAWNING = 2
    const val RISK_WEIGHT_DISTRACTED = 2
    const val RISK_WEIGHT_POSTURE = 2
    const val RISK_WEIGHT_EATING = 1
    const val RISK_WEIGHT_HANDS_OFF = 3
    const val RISK_WEIGHT_SLOUCH = 1
    const val RISK_HIGH_THRESHOLD = 3
    const val RISK_MEDIUM_THRESHOLD = 1
}
