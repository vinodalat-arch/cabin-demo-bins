# SA8155 Android Port — Complete Project Specification

Use this document as the sole input to implement the Android port from scratch. It contains every algorithm, threshold, landmark index, data flow, platform-specific detail, and test case needed. No additional context required.

## 1. Project Overview

Port an in-cabin driver monitoring system to a native Android application running on Qualcomm SA8155 (CPU-only). The system captures 1 frame per second from a USB webcam, runs local ML inference, and outputs a structured JSON result with audio alerts on state changes.

### What the System Detects

| Feature | Source Model | Method |
|---|---|---|
| Eyes closed | MediaPipe Face Mesh (468 landmarks) | Eye Aspect Ratio (EAR) |
| Yawning | MediaPipe Face Mesh | Mouth Aspect Ratio (MAR) |
| Head pose / distraction | MediaPipe Face Mesh | cv2.solvePnP → Euler angles |
| Phone usage | YOLOv8n detection (COCO class 67) | Driver ROI + wrist crops |
| Food/drink | YOLOv8n detection (COCO classes 39-48) | Driver ROI crop |
| Dangerous posture | YOLOv8n-pose (17 keypoints) | Geometric heuristics |
| Passenger count | YOLOv8n-pose | Person bbox count |
| Child present/slouching | YOLOv8n-pose | Bbox height ratio + posture |
| Distraction duration | Logic layer | Continuous seconds counter |

### Output JSON Schema

Every inference cycle produces:

```json
{
  "timestamp": "2026-01-01T00:00:00+00:00",
  "passenger_count": 1,
  "driver_using_phone": false,
  "driver_eyes_closed": false,
  "driver_yawning": false,
  "driver_distracted": false,
  "driver_eating_drinking": false,
  "dangerous_posture": false,
  "child_present": false,
  "child_slouching": false,
  "risk_level": "low",
  "ear_value": 0.25,
  "mar_value": 0.20,
  "head_yaw": -5.0,
  "head_pitch": 3.0,
  "distraction_duration_s": 0
}
```

**Field types:**
- `timestamp`: ISO-8601 UTC string (required)
- `passenger_count`: integer >= 0 (required)
- `driver_using_phone`, `driver_eyes_closed`, `driver_yawning`, `driver_distracted`, `driver_eating_drinking`, `dangerous_posture`, `child_present`, `child_slouching`: boolean (all required)
- `risk_level`: enum "low" | "medium" | "high" (required)
- `distraction_duration_s`: integer >= 0 (required)
- `ear_value`, `mar_value`, `head_yaw`, `head_pitch`: float or null (optional — null when no face detected)

No additional properties allowed.

### JSON Schema Definition (for validation)

```kotlin
// OutputResult schema — equivalent JSON Schema (Draft 7)
val OUTPUT_SCHEMA = mapOf(
    "type" to "object",
    "properties" to mapOf(
        "timestamp" to mapOf("type" to "string"),
        "passenger_count" to mapOf("type" to "integer", "minimum" to 0),
        "driver_using_phone" to mapOf("type" to "boolean"),
        "driver_eyes_closed" to mapOf("type" to "boolean"),
        "driver_yawning" to mapOf("type" to "boolean"),
        "driver_distracted" to mapOf("type" to "boolean"),
        "driver_eating_drinking" to mapOf("type" to "boolean"),
        "dangerous_posture" to mapOf("type" to "boolean"),
        "child_present" to mapOf("type" to "boolean"),
        "child_slouching" to mapOf("type" to "boolean"),
        "risk_level" to mapOf("type" to "string", "enum" to listOf("low", "medium", "high")),
        "ear_value" to mapOf("type" to listOf("number", "null")),
        "mar_value" to mapOf("type" to listOf("number", "null")),
        "head_yaw" to mapOf("type" to listOf("number", "null")),
        "head_pitch" to mapOf("type" to listOf("number", "null")),
        "distraction_duration_s" to mapOf("type" to "integer", "minimum" to 0),
    ),
    "required" to listOf(
        "timestamp", "passenger_count",
        "driver_using_phone", "driver_eyes_closed", "driver_yawning",
        "driver_distracted", "driver_eating_drinking", "dangerous_posture",
        "child_present", "child_slouching", "risk_level", "distraction_duration_s",
    ),
    "additionalProperties" to false,
)
```

**Validation function:**
```kotlin
fun validateOutput(data: Map<String, Any?>): List<String> {
    // Validate against schema, return list of error messages (empty if valid)
    // Use a JSON schema validator library or manual field-by-field checks
}
```

---

## 2. Target Hardware & Platform

### Hardware
- **SoC**: Qualcomm SA8155P
- **CPU**: Kryo 485 — 4x Gold (Cortex-A76, up to 2.84 GHz) + 4x Silver (Cortex-A55, up to 1.8 GHz)
- **ISA**: ARMv8.2-A (supports UDOT/SDOT dot product, FP16, NEON SIMD)
- **RAM**: 8 GB typical
- **GPU**: Adreno 640 — **NOT USED** (CPU-only constraint)
- **NPU**: Hexagon 690 — **NOT USED** (CPU-only constraint)
- **Camera**: USB webcam (UVC class) connected to USB port

### Platform
- **OS**: Android Automotive OS 14 (API 34)
- **Build type**: Debug (userdebug) — standard debug keystore, ADB install, no OEM platform keys
- **ABI**: `arm64-v8a` only

### Performance Budget
- **Inference interval**: 1 frame per second (1000ms budget)
- **Target RAM**: < 500 MB
- **Estimated per-frame latency on Kryo 485 (FP32)**:
  - YOLOv8n-pose: 150-350ms
  - YOLOv8n detection (driver ROI): 100-250ms
  - MediaPipe FaceLandmarker: 50-100ms
  - solvePnP + smoother + merger: < 10ms
  - YUV→BGR conversion: ~20ms
  - **Total: ~500-800ms** (within 1000ms budget)

---

## 3. Technology Stack

### Language Stack

| Layer | Language | Purpose |
|---|---|---|
| Android lifecycle, camera, TTS, UI | **Kotlin** | Camera2 API, TextToSpeech, foreground service, overlay renderer, dashboard |
| Inference hot path | **C++ (NDK)** | ONNX Runtime C API, image preprocessing |
| Bridge | **JNI** | Pass camera frames Kotlin→C++, return JSON result C++→Kotlin |

### Dependencies

| Library | Version | Purpose | Integration |
|---|---|---|---|
| ONNX Runtime Android | 1.19.2 | YOLOv8n inference (CPU EP) | Maven: `com.microsoft.onnxruntime:onnxruntime-android:1.19.2`, headers/lib extracted to `cpp/onnxruntime/` for CMake |
| MediaPipe Tasks Vision | 0.10.14 | FaceLandmarker (468 landmarks) | Maven: `com.google.mediapipe:tasks-vision:0.10.14` |
| OpenCV Android SDK | 4.10.0 | solvePnP (Kotlin-side head pose) | Maven: `org.opencv:opencv:4.10.0` |
| Gson | 2.11.0 | JSON output serialization | Maven: `com.google.code.gson:gson:2.11.0` |

### Models to Ship in APK Assets

| Model | Source | Format | Size |
|---|---|---|---|
| `yolov8n-pose-fp16.onnx` | FP32 export → `scripts/convert_fp16.py` | ONNX FP16 | ~6.5 MB |
| `yolov8n-fp16.onnx` | FP32 export → `scripts/convert_fp16.py` | ONNX FP16 | ~6.5 MB |
| `face_landmarker.task` | Google AI Edge MediaPipe models (float16) | TFLite bundle | 3.6 MB |

**Note:** Model files are gitignored (large binaries). Export FP32 models first, then run `python scripts/convert_fp16.py` to generate FP16 variants. The Gradle `verifyAssets` task will fail the build if any model file is missing from `app/src/main/assets/`.

### Build System

```
Android Studio / Gradle 8.9 (Kotlin DSL)
├── build.gradle.kts           (AGP 8.7.3, Kotlin 2.0.21)
├── settings.gradle.kts
├── gradle.properties
├── app/build.gradle.kts
│   ├── android.defaultConfig.ndk.abiFilters = ["arm64-v8a"]
│   ├── android.externalNativeBuild.cmake (version 3.22.1)
│   ├── ndkVersion = "26.1.10909125"
│   ├── dependencies: onnxruntime-android, mediapipe tasks-vision, opencv, gson
│   └── minSdk = 29, targetSdk = 34, compileSdk = 34
├── app/src/main/cpp/
│   ├── CMakeLists.txt         (C++17, links onnxruntime + system libs)
│   ├── onnxruntime/           (extracted headers + lib from AAR)
│   │   ├── include/           (onnxruntime_cxx_api.h, etc.)
│   │   └── lib/arm64-v8a/     (libonnxruntime.so)
│   ├── pose_analyzer.cpp/h    (ONNX Runtime inference + postprocessing)
│   ├── yolo_utils.cpp/h       (letterbox, NMS, tensor parsing)
│   ├── image_utils.cpp/h      (BT.601 YUV→BGR conversion)
│   └── jni_bridge.cpp         (JNI entry points)
├── app/src/main/kotlin/com/incabin/
│   ├── Config.kt              (all thresholds from §12)
│   ├── InCabinService.kt      (foreground service, 10-step main loop)
│   ├── CameraManager.kt       (Camera2 + ImageReader, 1fps throttle)
│   ├── FaceAnalyzer.kt        (MediaPipe FaceLandmarker + EAR/MAR/solvePnP + face overlay data)
│   ├── PoseAnalyzerBridge.kt  (JNI bridge to C++ PoseAnalyzer + overlay person data)
│   ├── Merger.kt              (merge face+pose results, risk scoring)
│   ├── TemporalSmoother.kt    (5-frame sliding window, face-gating, fast-clear)
│   ├── AudioAlerter.kt        (TextToSpeech + background queue)
│   ├── OutputResult.kt        (16-field data class + JSON + validation)
│   ├── NativeLib.kt           (JNI external fun declarations)
│   ├── OverlayRenderer.kt     (draws bboxes, skeleton, face landmarks, metrics on bitmap)
│   ├── FrameHolder.kt         (thread-safe bitmap + OutputResult holder for UI)
│   └── MainActivity.kt        (start/stop service, dashboard, permission handling)
├── app/src/test/kotlin/com/incabin/
│   ├── MergerTest.kt          (19 tests)
│   ├── TemporalSmootherTest.kt (20 tests)
│   └── OutputResultTest.kt    (25 tests)
└── app/src/main/assets/
    ├── yolov8n-pose-fp16.onnx (~6.5 MB, gitignored)
    ├── yolov8n-fp16.onnx     (~6.5 MB, gitignored)
    └── face_landmarker.task   (3.6 MB, gitignored)
```

---

## 4. Camera — USB Webcam via Camera2

### Frame Pipeline

```
USB Webcam (UVC)
  → Camera2 ImageReader (YUV_420_888, 1280x720)
  → ImageProxy.getPlanes() → Y/U/V ByteBuffers + row strides
  → JNI → Manual BT.601 YUV→BGR conversion (image_utils.cpp)
  → BGR byte array (1280x720x3)
  → inference pipeline
```

### Implementation Details

1. **Enumerate cameras** via `CameraManager.getCameraIdList()`. USB webcams appear as external cameras (`CameraCharacteristics.LENS_FACING_EXTERNAL`). Filter for external facing.

2. **Open camera session** with `CameraDevice.createCaptureSession()`. Use a single `ImageReader` surface at 1280x720, format `ImageFormat.YUV_420_888`.

3. **Frame capture at 1fps**: Use a repeating capture request but only process frames at 1-second intervals. Drop intermediate frames by checking timestamp delta in `ImageReader.OnImageAvailableListener`. Call `image.close()` immediately for dropped frames.

4. **YUV→BGR conversion**: Pass Y, U, V plane byte arrays + row strides to C++ via JNI. Uses manual BT.601 integer-approximation conversion in `image_utils.cpp` (handles stride/padding differences across devices, no libyuv dependency needed).

5. **Permissions** (debug build):
```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-feature android:name="android.hardware.usb.host" />
```
On userdebug builds, camera permission can be auto-granted via `adb shell pm grant`.

### Fallback
If Camera2 doesn't enumerate the USB camera, use `android.hardware.usb.UsbManager` with a UVC library (e.g., `UVCCamera` by saki4510t). This is unlikely needed on AAOS 14 which has good USB camera support.

---

## 5. Face Analysis — MediaPipe FaceLandmarker (Kotlin Side)

### Initialization

```kotlin
val options = FaceLandmarkerOptions.builder()
    .setBaseOptions(BaseOptions.builder()
        .setModelAssetPath("face_landmarker.task")
        .build())
    .setRunningMode(RunningMode.VIDEO)
    .setNumFaces(1)
    .setMinFaceDetectionConfidence(0.5f)
    .setMinFacePresenceConfidence(0.5f)
    .setMinTrackingConfidence(0.5f)
    .build()

val landmarker = FaceLandmarker.createFromOptions(context, options)
```

### Per-Frame Processing

```kotlin
val mpImage = MPImage(bitmap)  // or from MediaImage
val result = landmarker.detectForVideo(mpImage, timestampMs)
```

**Timestamp handling:** Use a monotonically increasing timestamp. Increment by ~33ms per call (simulates ~30fps to satisfy MediaPipe's VIDEO mode expectation, even though actual capture is 1fps). Store as instance variable, start at 0.

If `result.faceLandmarks()` is empty → return no-face defaults (all null/false).

Otherwise extract the first face's 468 landmarks (each has `.x()`, `.y()`, `.z()` in normalized [0,1] coordinates).

### 5a. Eye Aspect Ratio (EAR)

**Landmark indices per eye:**
- Right eye: `[33, 160, 158, 133, 153, 144]` — [lateral, upper1, upper2, medial, lower1, lower2]
- Left eye: `[362, 385, 387, 263, 373, 380]`

**Formula (per eye):**
```
p1=lateral, p2=upper1, p3=upper2, p4=medial, p5=lower1, p6=lower2

Convert normalized coords to pixel coords: px = landmark.x * frameWidth, py = landmark.y * frameHeight

vertical_1 = euclidean_dist(p2, p6)
vertical_2 = euclidean_dist(p3, p5)
horizontal = euclidean_dist(p1, p4)

EAR = (vertical_1 + vertical_2) / (2.0 * horizontal)
```

**Combined:**
```
avg_ear = (left_ear + right_ear) / 2.0
eyes_closed = avg_ear < 0.21
```

**Edge case:** If `horizontal < 1e-6`, return `EAR = 0.0`.

**Output:** `ear_value = round(avg_ear, 4)` or `null` if no face.

### 5b. Mouth Aspect Ratio (MAR)

**Landmark indices:**
- Top lip: `13`
- Bottom lip: `14`
- Left corner: `61`
- Right corner: `291`
- Upper mid: `0`
- Lower mid: `17`

**Formula:**
```
vertical_1 = euclidean_dist(top, bottom)        // landmarks 13, 14
vertical_2 = euclidean_dist(upper_mid, lower_mid) // landmarks 0, 17
horizontal = euclidean_dist(left, right)         // landmarks 61, 291

MAR = (vertical_1 + vertical_2) / (2.0 * horizontal)
```

**Threshold:** `MAR > 0.5` → yawning.

**Edge case:** If `horizontal < 1e-6`, return `MAR = 0.0`.

**Output:** `mar_value = round(mar, 4)` or `null` if no face.

### 5c. Head Pose Estimation (solvePnP)

**6 landmark indices for PnP:**
```
Index 1   → nose tip
Index 152 → chin
Index 33  → left eye left corner
Index 263 → right eye right corner
Index 61  → left mouth corner
Index 291 → right mouth corner
```

**3D model points (generic face, arbitrary scale):**
```
nose_tip:     ( 0.0,    0.0,    0.0  )
chin:         ( 0.0, -330.0,  -65.0  )
left_eye:     (-225.0, 170.0, -135.0 )
right_eye:    ( 225.0, 170.0, -135.0 )
left_mouth:   (-150.0,-150.0, -125.0 )
right_mouth:  ( 150.0,-150.0, -125.0 )
```

**Camera matrix (approximate):**
```
focal_length = frame_width
cx = frame_width / 2.0
cy = frame_height / 2.0

camera_matrix = [[focal_length, 0, cx],
                 [0, focal_length, cy],
                 [0, 0, 1]]

dist_coeffs = [0, 0, 0, 0]
```

**Solve:**
```
solvePnP(model_3d, image_2d, camera_matrix, dist_coeffs, flags=SOLVEPNP_ITERATIVE)
→ rotation_vector
→ Rodrigues(rotation_vector) → rotation_matrix (3x3)
```

**If solvePnP fails:** return `(yaw=0.0, pitch=0.0)`.

**Euler angle extraction:**
```
sy = sqrt(R[0,0]^2 + R[1,0]^2)
if sy > 1e-6:
    pitch = atan2(-R[2,0], sy)
    yaw = atan2(R[1,0], R[0,0])
else:
    pitch = atan2(-R[2,0], sy)
    yaw = 0.0

yaw_deg = degrees(yaw)
pitch_deg = degrees(pitch)
```

**Thresholds:**
```
driver_distracted = |yaw_deg| > 30 OR |pitch_deg| > 25
```

**Output:** `head_yaw = round(yaw_deg, 1)`, `head_pitch = round(pitch_deg, 1)`, or `null` if no face.

**On Android:** Use OpenCV Android SDK's `Calib3d.solvePnP()`. Can run in Kotlin or C++.

### 5d. Face Overlay Data

After computing EAR, MAR, and head pose, the FaceAnalyzer also extracts pixel-coordinate landmarks for visual overlay:

```kotlin
data class OverlayLandmark(val x: Float, val y: Float)
data class FaceOverlayData(
    val rightEye: List<OverlayLandmark>,   // 6 landmarks (RIGHT_EYE_INDICES)
    val leftEye: List<OverlayLandmark>,    // 6 landmarks (LEFT_EYE_INDICES)
    val mouth: List<OverlayLandmark>,      // 6 landmarks (MAR_TOP, MAR_BOTTOM, MAR_LEFT, MAR_RIGHT, MAR_UPPER_MID, MAR_LOWER_MID)
    val noseTip: OverlayLandmark           // landmark index 1
)
```

Populated from the same MediaPipe landmarks used for EAR/MAR computation. Returned as `FaceResult.faceOverlay` (null when no face detected).

### 5e. No-Face Default Return

When no face detected, return:
```json
{
  "driver_eyes_closed": false,
  "ear_value": null,
  "driver_yawning": false,
  "mar_value": null,
  "driver_distracted": false,
  "head_yaw": null,
  "head_pitch": null,
  "faceOverlay": null
}
```

### 5f. Complete FaceAnalyzer Reference Implementation

```
class FaceAnalyzer:
    state:
        _landmarker: FaceLandmarker instance
        _frame_ts_ms: int = 0  // monotonically increasing

    init(context):
        Create FaceLandmarker with options from §5 init
        _frame_ts_ms = 0

    analyze(frame: BGR image) -> FaceResult:
        Convert BGR → RGB
        Create MPImage from RGB
        _frame_ts_ms += 33  // monotonic increment
        result = _landmarker.detectForVideo(mpImage, _frame_ts_ms)

        if result.faceLandmarks is empty:
            return NO_FACE_DEFAULTS

        landmarks = result.faceLandmarks[0]
        h, w = frame dimensions

        // EAR
        left_ear = computeEar(landmarks, LEFT_EYE, w, h)
        right_ear = computeEar(landmarks, RIGHT_EYE, w, h)
        avg_ear = (left_ear + right_ear) / 2.0
        eyes_closed = avg_ear < 0.21

        // MAR
        mar = computeMar(landmarks, w, h)
        yawning = mar > 0.5

        // Head pose
        yaw, pitch = estimateHeadPose(landmarks, w, h)
        distracted = |yaw| > 30 OR |pitch| > 25

        return {
            driver_eyes_closed: eyes_closed,
            ear_value: round(avg_ear, 4),
            driver_yawning: yawning,
            mar_value: round(mar, 4),
            driver_distracted: distracted,
            head_yaw: round(yaw, 1),
            head_pitch: round(pitch, 1),
        }

    close():
        _landmarker.close()
```

---

## 6. Pose & Object Analysis — ONNX Runtime (C++ Side)

### Model Export (Pre-Build Step)

Run on development machine before building the APK:

```bash
pip install ultralytics onnx onnxconverter-common
yolo export model=yolov8n-pose.pt format=onnx opset=17
yolo export model=yolov8n.pt format=onnx opset=17
python scripts/convert_fp16.py
```

Place `yolov8n-pose-fp16.onnx` and `yolov8n-fp16.onnx` in `app/src/main/assets/`. FP32 originals can remain as fallback but are not used at runtime.

### ONNX Runtime Setup (C++)

```cpp
Ort::Env env(ORT_LOGGING_LEVEL_WARNING, "InCabinPose");
Ort::SessionOptions opts;
opts.SetIntraOpNumThreads(4);
opts.SetGraphOptimizationLevel(GraphOptimizationLevel::ORT_ENABLE_ALL);

// Pin intra-op threads to Gold+Prime cores (4-7) for higher clock speeds
opts.AddConfigEntry("session.intra_op_thread_affinities", "4;5;6");

// Load FP16 models — ORT handles FP16→FP32 cast at I/O boundary automatically
Ort::Session pose_session(env, "yolov8n-pose-fp16.onnx", opts);
Ort::Session detect_session(env, "yolov8n-fp16.onnx", opts);
```

### YOLOv8 Input Preprocessing

Both models expect input tensor: `[1, 3, 640, 640]` float32, pixel values normalized to [0, 1].

```
1. Resize frame (or crop) to 640x640 (letterbox with gray padding to maintain aspect ratio)
2. Convert BGR→RGB
3. Normalize: pixel / 255.0
4. Transpose HWC→CHW: [H,W,3] → [3,H,W]
5. Add batch dimension: [1,3,640,640]
```

### 6a. YOLOv8n-Pose Output Parsing

**Output tensor shape:** `[1, 56, 8400]` — transpose to `[8400, 56]`.

Each of 8400 predictions has 56 values:
```
[0:4]   = cx, cy, w, h (box center + size, in 640x640 space)
[4]     = confidence
[5:56]  = 17 keypoints × 3 (x, y, confidence)
```

**Postprocessing:**
1. Filter by confidence > 0.35
2. Apply Non-Maximum Suppression (NMS) with IoU threshold 0.45
3. Scale boxes/keypoints back to original frame coordinates (undo letterbox)
4. Count persons → `passenger_count`
5. Identify driver as **largest bounding box by area**: `area = (x2-x1) * (y2-y1)`, `driver_idx = argmax(areas)`

**COCO keypoint indices used:**
```
NOSE = 0
L_SHOULDER = 5,  R_SHOULDER = 6
L_WRIST = 9,     R_WRIST = 10
L_HIP = 11,      R_HIP = 12
```

**Keypoint confidence threshold:** 0.5 (below = keypoint not reliable)

### 6b. Dangerous Posture Check

Input: driver's 17 keypoints (x,y) + confidences, threshold_deg.

Four checks — **any one** triggers `dangerous_posture = true`:

**Check 1 — Torso lean** (requires both shoulders + both hips confident):
```
shoulder_mid = (L_SHOULDER + R_SHOULDER) / 2
hip_mid = (L_HIP + R_HIP) / 2
dx = shoulder_mid.x - hip_mid.x
dy = hip_mid.y - shoulder_mid.y   // note: image Y is inverted

if dy < 1e-6: return true  // shoulders at or below hips

lean_angle = degrees(atan2(abs(dx), dy))
if lean_angle > threshold_deg: return true
```

**Check 2 — Head droop** (requires shoulders + hips + nose confident):
```
// Only checked inside the shoulders+hips block, after lean check
if nose.y > shoulder_mid.y: return true  // nose below shoulder line (image Y)
```

**Check 3 — Head turn** (requires both shoulders confident + nose checked):
```
shoulder_width = abs(L_SHOULDER.x - R_SHOULDER.x)

if shoulder_width > 1e-6:
    // First check if nose is not visible at all
    if nose_confidence <= 0.5: return true   // face not visible = dangerous

    // Then check nose offset
    nose_offset = abs(nose.x - shoulder_mid.x)
    if nose_offset > shoulder_width * 0.3: return true
```

**Check 4 — Face not visible** (embedded in Check 3):
```
// If nose confidence <= 0.5 AND shoulders visible → return true
// This is checked before nose_offset in the shoulder block
```

**For child posture:** Same algorithm but threshold = 20° instead of 30°.

### 6b-ref. Complete Posture Check Reference Implementation

```
function checkPosture(keypoints[17][2], kp_conf[17], threshold_deg) -> bool:
    shoulders_ok = kp_conf[L_SHOULDER] > 0.5 AND kp_conf[R_SHOULDER] > 0.5
    hips_ok = kp_conf[L_HIP] > 0.5 AND kp_conf[R_HIP] > 0.5

    // Block 1: Torso lean + head droop (needs shoulders AND hips)
    if shoulders_ok AND hips_ok:
        shoulder_mid = (keypoints[L_SHOULDER] + keypoints[R_SHOULDER]) / 2.0
        hip_mid = (keypoints[L_HIP] + keypoints[R_HIP]) / 2.0

        dx = shoulder_mid.x - hip_mid.x
        dy = hip_mid.y - shoulder_mid.y

        if dy < 1e-6: return true  // shoulders at/below hips

        lean_angle = degrees(atan2(abs(dx), dy))
        if lean_angle > threshold_deg: return true

        // Head droop check (nose below shoulder line)
        if kp_conf[NOSE] > 0.5:
            if keypoints[NOSE].y > shoulder_mid.y: return true

    // Block 2: Head turn + face visibility (needs shoulders only)
    if shoulders_ok:
        shoulder_mid = (keypoints[L_SHOULDER] + keypoints[R_SHOULDER]) / 2.0
        shoulder_width = abs(keypoints[L_SHOULDER].x - keypoints[R_SHOULDER].x)

        if shoulder_width > 1e-6:
            if kp_conf[NOSE] <= 0.5:
                return true  // face not visible

            nose_offset = abs(keypoints[NOSE].x - shoulder_mid.x)
            if nose_offset > shoulder_width * 0.3:
                return true  // head turned

    return false
```

### 6c. Child Detection

```
driver_height = driver_box.y2 - driver_box.y1

for each other person (i != driver_idx):
    person_height = box.y2 - box.y1
    if driver_height < 1e-6: skip (not a child)
    ratio = person_height / driver_height
    if ratio < 0.75: child_present = true
        if checkPosture(child_kp, child_conf, threshold=20): child_slouching = true
```

### 6d. Phone Detection (Two-Strategy)

**Strategy 1 — Driver bbox ROI:**
```
Crop frame to driver bounding box + 20% padding on each side
Clamp to frame bounds
Run YOLOv8n detection on crop
If COCO class 67 (cell phone) detected with conf > 0.35: return true
```

**Strategy 2 — Wrist crops** (fallback if Strategy 1 returns false):
```
For each wrist (L_WRIST=9, R_WRIST=10):
    if wrist_confidence < 0.5: skip
    wx, wy = wrist keypoint position (integer)
    half = 100  // WRIST_CROP_SIZE / 2
    Crop 200x200px centered on (wx, wy)
    Clamp to frame bounds: [max(0, wx-half), max(0, wy-half), min(W, wx+half), min(H, wy+half)]
    if crop is empty: skip
    Run YOLOv8n detection on crop (NO additional padding, pad_ratio=0.0)
    If class 67 detected: return true
```

### 6e. Food/Drink Detection

Same as phone Strategy 1 but check for COCO classes `[39, 40, 41, 42, 43, 44, 45, 46, 47, 48]`:

```
COCO class mapping:
39 = bottle, 40 = wine glass, 41 = cup, 42 = fork
43 = knife,  44 = spoon,      45 = bowl, 46 = banana
47 = apple,  48 = sandwich
```

```
Crop driver bounding box + 20% padding
Clamp to frame bounds
Run YOLOv8n detection on crop
If ANY of the food/drink classes detected with conf > 0.35: driver_eating_drinking = true
```

### 6e-ref. ROI Crop Padding Logic (shared by phone/food detection)

```
function cropWithPadding(frame, box[x1,y1,x2,y2], pad_ratio) -> cropped_image:
    h, w = frame dimensions
    pad_x = (x2 - x1) * pad_ratio
    pad_y = (y2 - y1) * pad_ratio
    cx1 = max(0, int(x1 - pad_x))
    cy1 = max(0, int(y1 - pad_y))
    cx2 = min(w, int(x2 + pad_x))
    cy2 = min(h, int(y2 + pad_y))
    return frame[cy1:cy2, cx1:cx2]
```

### 6f. YOLOv8n Detection Output Parsing

**Output tensor shape:** `[1, 84, 8400]` — transpose to `[8400, 84]`.

Each prediction: `[0:4] = cx, cy, w, h`, `[4:84] = 80 class scores`.

**Postprocessing:**
1. For each prediction: `class_id = argmax(scores[4:84])`, `confidence = scores[4 + class_id]`
2. Filter by confidence > 0.35
3. NMS with IoU threshold 0.45
4. Check if desired class_id is in results

### 6g. Overlay Person Data

After identifying the driver, the PoseAnalyzer populates a `persons` vector in the result with per-person geometry for visual overlay:

```cpp
struct OverlayKeypoint { float x, y, conf; };
struct OverlayPerson {
    float x1, y1, x2, y2;   // bounding box in original frame coords
    float confidence;
    bool is_driver;
    OverlayKeypoint keypoints[17];  // COCO keypoints
};
// PoseResult.persons: std::vector<OverlayPerson>
```

Serialized in `toJson()` as a `"persons"` array. On the Kotlin side, parsed automatically by Gson into `List<OverlayPerson>` with `List<OverlayKeypoint>`.

### 6h. Complete PoseAnalyzer Reference Implementation

```
class PoseAnalyzer:
    state:
        _pose_model: ONNX Runtime session (yolov8n-pose-fp16.onnx)
        _detect_model: ONNX Runtime session (yolov8n-fp16.onnx)

    analyze(frame: BGR image) -> PoseResult:
        result = {
            passenger_count: 0,
            driver_using_phone: false,
            dangerous_posture: false,
            child_present: false,
            child_slouching: false,
            driver_eating_drinking: false,
        }

        // 1. Run pose model
        pose_preds = _pose_model.run(preprocess(frame))
        if no persons detected: return result

        boxes = pose_preds.boxes (xyxy format)
        kp_xy = pose_preds.keypoints.xy    // (N, 17, 2)
        kp_conf = pose_preds.keypoints.conf // (N, 17)

        result.passenger_count = len(boxes)

        // 2. Identify driver (largest bbox by area)
        areas = (boxes[:, 2] - boxes[:, 0]) * (boxes[:, 3] - boxes[:, 1])
        driver_idx = argmax(areas)
        driver_height = boxes[driver_idx].y2 - boxes[driver_idx].y1

        // 3. Check driver posture
        result.dangerous_posture = checkPosture(kp_xy[driver_idx], kp_conf[driver_idx], 30)

        // 4. Check for children
        for each person i != driver_idx:
            person_height = box[i].y2 - box[i].y1
            if driver_height >= 1e-6 AND (person_height / driver_height) < 0.75:
                result.child_present = true
                if checkPosture(kp_xy[i], kp_conf[i], 20):
                    result.child_slouching = true

        // 5. Phone detection (2-strategy)
        driver_box = boxes[driver_idx]
        // Strategy 1: driver ROI with 20% padding
        if detectObjectsInCrop(frame, driver_box, [67], pad_ratio=0.2):
            result.driver_using_phone = true
        else:
            // Strategy 2: wrist crops (200x200px, no padding)
            for wrist_idx in [L_WRIST=9, R_WRIST=10]:
                if kp_conf[driver_idx][wrist_idx] < 0.5: continue
                wx, wy = int(kp_xy[driver_idx][wrist_idx])
                half = 100
                wrist_box = [max(0, wx-half), max(0, wy-half),
                             min(W, wx+half), min(H, wy+half)]
                if detectObjectsInCrop(frame, wrist_box, [67], pad_ratio=0.0):
                    result.driver_using_phone = true
                    break

        // 6. Food/drink detection (driver ROI with 20% padding)
        result.driver_eating_drinking = detectObjectsInCrop(
            frame, driver_box, [39,40,41,42,43,44,45,46,47,48], pad_ratio=0.2
        )

        return result
```

---

## 7. Merger

Combines face analysis and pose analysis results into one output dict.

### Implementation

```kotlin
fun mergeResults(faceResult: FaceResult, poseResult: PoseResult): OutputResult {
    // Extract face fields with defaults
    val driverEyesClosed = faceResult.driverEyesClosed ?: false
    val driverYawning = faceResult.driverYawning ?: false
    val driverDistracted = faceResult.driverDistracted ?: false

    // Extract pose fields with defaults
    val passengerCount = poseResult.passengerCount ?: 0
    val driverUsingPhone = poseResult.driverUsingPhone ?: false
    val dangerousPosture = poseResult.dangerousPosture ?: false
    val childPresent = poseResult.childPresent ?: false
    val childSlouching = poseResult.childSlouching ?: false
    val driverEatingDrinking = poseResult.driverEatingDrinking ?: false

    val riskLevel = computeRisk(
        driverUsingPhone, driverEyesClosed, dangerousPosture,
        childSlouching, driverYawning, driverDistracted, driverEatingDrinking
    )

    return OutputResult(
        timestamp = Instant.now().toString(),  // ISO-8601 UTC
        passengerCount = passengerCount,
        driverUsingPhone = driverUsingPhone,
        driverEyesClosed = driverEyesClosed,
        driverYawning = driverYawning,
        driverDistracted = driverDistracted,
        driverEatingDrinking = driverEatingDrinking,
        dangerousPosture = dangerousPosture,
        childPresent = childPresent,
        childSlouching = childSlouching,
        riskLevel = riskLevel,
        earValue = faceResult.earValue,    // null passthrough
        marValue = faceResult.marValue,    // null passthrough
        headYaw = faceResult.headYaw,      // null passthrough
        headPitch = faceResult.headPitch,  // null passthrough
    )
}
```

**Key behaviors:**
- Missing keys in face/pose result default to `false` / `0` / `null`
- `ear_value`, `mar_value`, `head_yaw`, `head_pitch` pass through as-is (including `null`)
- `timestamp` is generated at merge time (ISO-8601 UTC)

### Risk Scoring

```kotlin
fun computeRisk(
    driverUsingPhone: Boolean,
    driverEyesClosed: Boolean,
    dangerousPosture: Boolean,
    childSlouching: Boolean,
    driverYawning: Boolean = false,
    driverDistracted: Boolean = false,
    driverEatingDrinking: Boolean = false,
): String {
    var score = 0
    if (driverUsingPhone)    score += 3
    if (driverEyesClosed)    score += 3
    if (dangerousPosture)    score += 2
    if (childSlouching)      score += 1
    if (driverYawning)       score += 2
    if (driverDistracted)    score += 2
    if (driverEatingDrinking) score += 1

    return when {
        score >= 3 -> "high"
        score >= 1 -> "medium"
        else -> "low"
    }
}
```

**Weight table:**

| Field | Weight | Solo Result |
|---|---|---|
| driver_using_phone | 3 | high |
| driver_eyes_closed | 3 | high |
| driver_yawning | 2 | medium |
| driver_distracted | 2 | medium |
| dangerous_posture | 2 | medium |
| driver_eating_drinking | 1 | medium |
| child_slouching | 1 | medium |

---

## 8. Temporal Smoother

Sliding window majority voting to suppress single-frame noise.

### Configuration
- **Window size**: 5 frames (at 1fps = 5 second reaction time)
- **Threshold**: 0.6 (60% of frames must agree to flip a boolean to true)

### Boolean Fields Smoothed
```
driver_using_phone, driver_eyes_closed, driver_yawning,
driver_distracted, driver_eating_drinking, dangerous_posture,
child_present, child_slouching
```

### Smoothing Rules

**Standard fields** (phone, eating, posture, child_present, child_slouching):
```
true_count = count of True in buffer for this field
smoothed = (true_count / buffer_size) >= 0.6
```

**Face-gated fields** — skip frames where no face was detected:

| Field | Gate key | Meaning |
|---|---|---|
| `driver_eyes_closed` | `ear_value` | Skip frames where `ear_value == null` |
| `driver_yawning` | `mar_value` | Skip frames where `mar_value == null` |
| `driver_distracted` | `head_yaw` | Skip frames where `head_yaw == null` |

```
gated_frames = buffer frames where gate_key != null
if gated_frames is empty: smoothed = false
else: smoothed = (true_count in gated_frames / gated_frame_count) >= 0.6
```

**Eyes closed special — Fast-clear:**
Track consecutive frames where `ear_value != null AND ear_value >= 0.21`.
If streak reaches **2 consecutive frames**: immediately set `driver_eyes_closed = false`, regardless of buffer history. Any frame with `ear_value == null` or `ear_value < 0.21` resets the streak to 0.

Fast-clear is checked BEFORE the standard face-gated voting for eyes_closed.

### Passenger Count
Mode (most frequent value) across the buffer window. On tie, use the highest count that appears most.

### Risk Recomputation
After smoothing all booleans, recompute `risk_level` from the **smoothed** values using the same scoring formula. Do NOT use the raw risk from the merger.

### 8a. Complete TemporalSmoother Reference Implementation

```
class TemporalSmoother:
    state:
        window_size: int
        threshold: float
        _buffer: deque[dict] (maxlen=window_size)
        _eyes_open_streak: int = 0

    property warm -> bool:
        return len(_buffer) >= window_size

    smooth(result: dict) -> dict:
        _buffer.append(result)
        smoothed = copy(result)
        n = len(_buffer)

        // Track fast-clear streak
        ear = result.get("ear_value")
        if ear != null AND ear >= 0.21:
            _eyes_open_streak += 1
        else:
            _eyes_open_streak = 0

        // Process each boolean field
        for field in bool_fields:
            if field == "driver_eyes_closed":
                // Fast-clear check FIRST
                if _eyes_open_streak >= 2:
                    smoothed[field] = false
                    continue
                // Face-gated: only count frames with ear_value != null
                face_frames = [r for r in _buffer if r["ear_value"] != null]
                if len(face_frames) == 0:
                    smoothed[field] = false
                else:
                    true_count = count(r[field] == true for r in face_frames)
                    smoothed[field] = (true_count / len(face_frames)) >= threshold

            elif field in {"driver_yawning", "driver_distracted"}:
                // Face-gated with appropriate gate key
                gate_key = FACE_GATED_FIELDS[field]  // mar_value or head_yaw
                gated = [r for r in _buffer if r[gate_key] != null]
                if len(gated) == 0:
                    smoothed[field] = false
                else:
                    true_count = count(r[field] == true for r in gated)
                    smoothed[field] = (true_count / len(gated)) >= threshold

            else:  // phone, eating, posture, child_present, child_slouching
                true_count = count(r[field] == true for r in _buffer)
                smoothed[field] = (true_count / n) >= threshold

        // Passenger count: mode
        counts = [r["passenger_count"] for r in _buffer]
        smoothed["passenger_count"] = mode(counts)  // max(set(counts), key=counts.count)

        // Recompute risk from smoothed booleans
        smoothed["risk_level"] = computeRisk(
            smoothed.driver_using_phone, smoothed.driver_eyes_closed,
            smoothed.dangerous_posture, smoothed.child_slouching,
            smoothed.driver_yawning, smoothed.driver_distracted,
            smoothed.driver_eating_drinking,
        )

        return smoothed
```

---

## 9. Distraction Duration Timer

Tracked in the main loop, NOT inside the smoother.

### Distraction Fields
```
driver_using_phone, driver_eyes_closed, driver_yawning,
driver_distracted, driver_eating_drinking
```

### Logic (per frame, after smoothing)
```
if ANY distraction field is true:
    distraction_duration_s += 1
else:
    distraction_duration_s = 0

Inject distraction_duration_s into the result before output.
```

Since inference runs at 1fps, each increment = 1 second.

---

## 10. Audio Alerter — Android TextToSpeech

### Initialization

```kotlin
val tts = TextToSpeech(context) { status ->
    if (status == SUCCESS) {
        tts.language = Locale.US
        tts.setSpeechRate(0.8f)  // equivalent to ~140 wpm
    }
}
```

### Architecture
- **Background thread** with a message queue
- Messages are spoken sequentially — each `tts.speak()` with `QUEUE_ADD` mode
- One message per inference cycle maximum

### Audio Focus (AAOS)
```kotlin
val audioAttrs = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
    .build()
tts.setAudioAttributes(audioAttrs)
```

### Danger Fields and Friendly Names

```kotlin
val DANGER_FIELDS = listOf(
    "driver_using_phone",
    "driver_eyes_closed",
    "driver_yawning",
    "driver_distracted",
    "driver_eating_drinking",
    "dangerous_posture",
    "child_slouching",
)

val FRIENDLY_NAMES = mapOf(
    "driver_using_phone"     to "phone detected",
    "driver_eyes_closed"     to "eyes closed",
    "driver_yawning"         to "yawning detected",
    "driver_distracted"      to "driver distracted",
    "driver_eating_drinking" to "eating or drinking",
    "dangerous_posture"      to "dangerous posture",
    "child_slouching"        to "child slouching",
)
```

### Message Coalescing Logic

Each cycle, build a single message from priority-ordered parts:

**Step 1 — New dangers activated:**
```
For each danger field IN ORDER: if current=true AND previous=false
    → add friendly name to parts list
```

**Step 2 — Risk level change:**
```
if risk changed: add "risk {new_level}" to parts
```

**Step 3 — All-clear (overrides Steps 1-2):**
```
if any danger was active last frame AND no danger active now:
    replace entire parts list with ["all clear"]
```

**Step 4 — Distraction duration** (only if no other message from Steps 1-3):
```
Thresholds: [5, 10, 20] seconds
If parts is empty AND duration >= threshold AND threshold not yet announced:
    add "distracted {threshold} seconds" to parts
    mark threshold as announced
    (only one threshold per cycle — break after first match)

Reset announced set when duration returns to 0.
```

**Speak:** Join parts with ". " separator.

**First frame:** On the very first call (prev_state is null), just store current state and return without speaking.

### 10a. Complete AudioAlerter Reference Implementation

```
class AudioAlerter:
    state:
        prev_state: {risk_level: string, dangers: map[string, bool]} | null = null
        _announced_durations: set[int] = empty
        _queue: Queue[string | null]  // null = stop signal
        _worker_thread: Thread

    init():
        Initialize TTS
        Start background worker thread
        prev_state = null

    _worker():
        loop:
            message = _queue.get()  // blocks
            if message == null: break
            tts.speak(message, QUEUE_ADD, null, utteranceId)

    checkAndAnnounce(result: dict):
        current_risk = result["risk_level"] ?? "low"
        current_dangers = {f: result[f] ?? false for f in DANGER_FIELDS}
        duration = result["distraction_duration_s"] ?? 0

        if prev_state == null:
            prev_state = {risk_level: current_risk, dangers: current_dangers}
            return  // skip first frame

        prev_risk = prev_state.risk_level
        prev_dangers = prev_state.dangers
        parts = []

        // Step 1: New dangers activated (in DANGER_FIELDS order)
        for field in DANGER_FIELDS:
            if current_dangers[field] AND NOT prev_dangers[field]:
                parts.add(FRIENDLY_NAMES[field])

        // Step 2: Risk level change
        if current_risk != prev_risk:
            parts.add("risk " + current_risk)

        // Step 3: All-clear override
        any_prev = any value true in prev_dangers
        any_curr = any value true in current_dangers
        if any_prev AND NOT any_curr:
            parts = ["all clear"]

        // Step 4: Distraction duration (only if no other message)
        if parts is empty:
            for threshold in [5, 10, 20]:
                if duration >= threshold AND threshold NOT in _announced_durations:
                    _announced_durations.add(threshold)
                    parts.add("distracted " + threshold + " seconds")
                    break  // one per cycle

        // Reset announced durations when distraction clears
        if duration == 0:
            _announced_durations.clear()
            _beep_played = false

        // Loud beep at 20s distraction (separate from TTS)
        if duration >= DISTRACTION_BEEP_THRESHOLD_S AND NOT _beep_played:
            _beep_played = true
            play_beep()  // 1kHz sine, 2s, AudioTrack on USAGE_ASSISTANCE_SONIFICATION

        if parts is not empty:
            message = parts.join(". ")
            _queue.put(message)

        prev_state = {risk_level: current_risk, dangers: current_dangers}

    close():
        _queue.put(null)  // stop signal
        tts.stop()
        tts.shutdown()
```

### Loud Beep at 20s Distraction
When distraction duration reaches `DISTRACTION_BEEP_THRESHOLD_S` (20 seconds), a loud 1kHz sine wave plays for 2 seconds via `AudioTrack` on `USAGE_ASSISTANCE_SONIFICATION` (same audio path as TTS — important for Honda BSP where `STREAM_ALARM` may be muted/unrouted). The beep plays on a separate thread to avoid blocking the pipeline. A `beepPlayed` flag (separate from `announcedDurations`) ensures one beep per distraction episode; resets when distraction clears.

The PCM buffer (44100Hz, 16-bit mono, 2 seconds = 88200 samples) is pre-generated at init time to avoid allocation during the alert.

### TTS Retry on Init Failure
If TTS initialization fails (status != SUCCESS), schedule a single retry after 3 seconds using `Handler(Looper.getMainLooper()).postDelayed()`. A `ttsRetried` flag prevents infinite loops. If the retry also fails, TTS remains unavailable and messages are dropped with a warning log.

### Cleanup
Call `tts.stop()` and `tts.shutdown()` on service destroy.

---

## 11. Main Loop

### Android Architecture

The inference loop runs as a **foreground service** (`InCabinService`) to maintain camera access when the app is in background.

```
InCabinService (foreground service)
  ├── CameraManager (Camera2 ImageReader, 1fps frame callback)
  ├── FaceAnalyzer (MediaPipe, Kotlin)
  ├── PoseAnalyzer (ONNX Runtime via JNI, C++)
  ├── Merger (Kotlin)
  ├── TemporalSmoother (Kotlin)
  ├── OverlayRenderer (Kotlin) → annotated bitmap
  ├── FrameHolder (bitmap + OutputResult → MainActivity)
  ├── AudioAlerter (TextToSpeech, Kotlin)
  └── OutputManager (JSON serialization, Logcat, optional broadcast)

MainActivity (UI)
  ├── Camera preview (ImageView) with overlay bitmaps
  └── Dashboard panel (risk banner, metrics, detections)
```

### Initialization

```
Log device info (manufacturer, model, Android version, SDK, CPUs, RAM)
Verify all 3 asset files exist (log sizes or MISSING error)
Initialize OpenCV (log version string)
Initialize FaceAnalyzer (log timing in ms)
Initialize PoseAnalyzerBridge (log timing in ms)
smoother = TemporalSmoother(window_size=5, threshold=0.6)
distraction_duration_s = 0  // @Volatile for thread safety
frame_count = 0
recent_frame_times = []
```

### Distraction Fields (for duration tracking)

```kotlin
val DISTRACTION_FIELDS = setOf(
    "driver_using_phone",
    "driver_eyes_closed",
    "driver_yawning",
    "driver_distracted",
    "driver_eating_drinking",
)
```

Note: `dangerous_posture`, `child_present`, `child_slouching` are NOT distraction fields.

### Per-Frame Flow

Steps 1-12 are divided into **core** (safety-critical) and **UI** (optional) paths. The core path must complete in full before the UI path runs, and a UI failure must never interrupt core execution. See **Core Pipeline Isolation** below.

```
--- CORE PATH (safety-critical, must always complete) ---
1. ImageReader callback fires (1fps throttled)
2. Convert YUV→BGR via JNI + libyuv
3. Run PoseAnalyzer (C++/JNI):
   a. YOLOv8n-pose on full frame → persons, keypoints
   b. Identify driver (largest bbox)
   c. Populate overlay persons (bbox, confidence, is_driver, 17 keypoints)
   d. Check posture, children
   e. YOLOv8n detection on driver ROI → phone
   f. YOLOv8n detection on driver ROI → food/drink
   g. YOLOv8n detection on wrist crops → phone fallback
   h. Return PoseResult (with persons array)
4. BGR→Bitmap conversion for FaceAnalyzer
5. Run FaceAnalyzer (Kotlin/MediaPipe):
   a. FaceLandmarker.detectForVideo()
   b. Compute EAR, MAR, head pose
   c. Build FaceOverlayData (eye contours, mouth, nose tip)
   d. Return FaceResult (with faceOverlay)
6. Merge results → compute risk
7. Smooth results → temporal voting
8. Update distraction duration counter:
   any_distraction = ANY of DISTRACTION_FIELDS is true in smoothed result
   if any_distraction: distraction_duration_s += 1
   else: distraction_duration_s = 0
9. Inject distraction_duration_s into result
10. Speak alerts if state changed (alerter.checkAndAnnounce)
11. Log JSON to Logcat

--- UI PATH (optional, isolated in try-catch) ---
12. Render overlay (OverlayRenderer) — wrapped in try-catch:
    a. Draw person bboxes (green=driver, blue=others) with labels
    b. Draw COCO skeleton (16 bones, confidence > 0.5)
    c. Draw face landmarks (eyes, mouth, nose)
    d. Draw metric labels (EAR, MAR, Yaw, Pitch)
    e. Return annotated bitmap
    f. Post overlay bitmap + OutputResult to FrameHolder
    On failure: log warning, skip preview update, core path unaffected.

13. Track frame timing; every 30 frames log performance stats (avg/min/max ms, heap sizes)
```

### Core Pipeline Isolation (Safety-Critical Requirement)

The inference → merge → smooth → audio alert → JSON log path is **safety-critical**. It must never be interrupted by UI features. All code changes must preserve these invariants:

1. **Execution order**: Core path (steps 1-11) completes before UI path (step 12). Audio alerts and JSON logging must never depend on overlay success.
2. **Failure isolation**: UI operations (overlay rendering, FrameHolder, notification posting) must be wrapped in try-catch. A UI failure must never propagate into the core path.
3. **No shared serialization risk**: UI-only data (overlay persons/keypoints) is serialized in the same PoseResult JSON as core detection fields. Currently mitigated by Gson robustness, but noted as architectural debt — a future refactor should separate overlay data from the PoseResult JSON to eliminate coupling.
4. **No shared bitmap lifecycle**: FrameHolder does not recycle old bitmaps to avoid TOCTOU races where the Activity reads a bitmap that gets recycled mid-use. A recycled-bitmap exception on the UI thread would crash the entire process including InCabinService.
5. **Activity crash safety**: MainActivity wraps all FrameHolder/bitmap access in try-catch. An unhandled Activity exception would kill the shared process and the Service with it.
6. **Notification isolation**: Notification posting/cancellation in AudioAlerter is wrapped in try-catch. TTS queue and alert state updates always complete regardless of notification API failures.

### Output

For debug builds, output goes to:
- **Logcat** (`Log.i("InCabin", jsonString)`) — filterable via `adb logcat -s InCabin`
- **Visual overlay** — annotated camera preview with bounding boxes, skeleton, face landmarks, and metric labels
- **Dashboard** — risk banner, metrics, passenger count, distraction timer, active detections

---

## 11b. Detection Overlay & Status Dashboard

### OverlayRenderer

Draws detection visualizations onto the camera preview bitmap. Runs after merge+smooth, before posting to FrameHolder. Returns a new annotated bitmap (source is not modified).

**Drawings:**
- **Person bounding boxes**: green stroke (driver), blue stroke (others), with "Driver 95%" / "Passenger 87%" labels on dark background
- **COCO skeleton**: 16 bone connections in yellow between keypoints with confidence > 0.5
  - Bones: nose-eyes, eyes-ears, shoulder-shoulder, arms (shoulder-elbow-wrist), shoulders-hips, hip-hip, legs (hip-knee-ankle)
- **Keypoint dots**: 4px red circles at each confident keypoint
- **Face eye contours**: green=open, red=closed (based on `driverEyesClosed`), drawn as closed polygon from 6 landmarks per eye
- **Face mouth outline**: green=normal, orange=yawning (based on `driverYawning`), drawn as closed polygon from 6 mouth landmarks
- **Nose tip**: 5px cyan circle
- **Metric labels**: "EAR: 0.250  MAR: 0.200" and "Yaw: -5.0  Pitch: 3.0" in top-left corner with semi-transparent dark background

**Performance:** Pre-allocated Paint objects to avoid GC per frame. ~3-5ms overhead on 1280x720 bitmap (negligible at 1fps).

### FrameHolder

Thread-safe singleton holding the latest annotated bitmap + OutputResult for the UI. Old bitmaps are **not** recycled in `postFrame()` — they are left for GC finalizer to avoid a TOCTOU race where the Activity reads a bitmap that gets recycled by the service thread (a recycled-bitmap exception on the UI thread would crash the entire process including InCabinService):

```kotlin
object FrameHolder {
    data class FrameData(val bitmap: Bitmap, val result: OutputResult)

    fun postFrame(bitmap: Bitmap, result: OutputResult)  // service writes (no recycle)
    fun getLatest(): FrameData?                           // activity reads
    fun getLatestFrame(): Bitmap?                         // backward compat
    fun clear()                                           // clears reference
}
```

### Status Dashboard (MainActivity)

Layout: vertical LinearLayout with black background, 8dp padding.

**Top row** (horizontal): status text (weight=1) + start/stop button
**Camera preview**: ImageView (weight=1, fitCenter, #111 background)
**Dashboard panel** (visibility=gone when idle, shown when monitoring):
- **Risk banner**: full-width TextView, bold 20sp, color-coded background:
  - HIGH: red (#F44336), white text
  - MEDIUM: orange (#FF9800), black text
  - LOW: green (#4CAF50), black text
- **Metrics row** (4 columns): EAR | MAR | Yaw | Pitch — 13sp, gray text, center-aligned
- **Info row** (2 columns): Passengers count | Distraction timer — 13sp, gray text
- **Active detections**: red text (#FF5252), center-aligned, pipe-separated labels (Phone | Eyes Closed | Yawning | Distracted | Eating/Drinking | Bad Posture | Child Slouching)

**Polling**: `Handler.postDelayed` every 500ms reads `FrameHolder.getLatest()`, updates ImageView and calls `updateDashboard(result)`.

---

## 12. All Thresholds (Config)

Single source of truth — all values in one config class/object:

```kotlin
object Config {
    // Camera
    const val CAMERA_WIDTH = 1280
    const val CAMERA_HEIGHT = 720
    const val INFERENCE_INTERVAL_MS = 100L   // tuned: webcam ~1fps is the real bottleneck

    // YOLO
    const val YOLO_CONFIDENCE = 0.35f
    const val YOLO_NMS_IOU = 0.45f
    const val YOLO_PHONE_CLASS = 67
    val FOOD_DRINK_CLASSES = intArrayOf(39, 40, 41, 42, 43, 44, 45, 46, 47, 48)

    // Face analysis
    const val EAR_THRESHOLD = 0.21f
    const val MAR_THRESHOLD = 0.5f
    const val HEAD_YAW_THRESHOLD = 30.0f   // degrees
    const val HEAD_PITCH_THRESHOLD = 35.0f // degrees (tuned: camera mount angle causes ~5-10° baseline)

    // Pose analysis
    const val POSTURE_LEAN_THRESHOLD = 30.0f   // degrees from vertical
    const val CHILD_SLOUCH_THRESHOLD = 20.0f   // degrees
    const val HEAD_TURN_THRESHOLD = 0.3f       // nose offset / shoulder width
    const val CHILD_BBOX_RATIO = 0.75f
    const val KP_CONF_THRESHOLD = 0.5f
    const val WRIST_CROP_SIZE = 200            // pixels

    // Smoother
    const val SMOOTHER_WINDOW = 3              // tuned: 3-frame window for ~2s detection latency
    const val SMOOTHER_THRESHOLD = 0.6f
    const val FAST_CLEAR_FRAMES = 2

    // Audio
    val DISTRACTION_ALERT_THRESHOLDS = intArrayOf(5, 10, 20)
    const val DISTRACTION_BEEP_THRESHOLD_S = 20  // loud beep at this duration

    // Preview
    const val ENABLE_PREVIEW = false  // disable camera preview rendering for performance
}
```

---

## 13. Project Structure

```
in_cabin_poc-sa8155/
├── .gitignore
├── CLAUDE.md
├── SA8155_PORT_SPEC.md
├── build.gradle.kts              (AGP 8.7.3 + Kotlin 2.0.21)
├── settings.gradle.kts
├── gradle.properties
├── gradlew / gradlew.bat
├── gradle/wrapper/
│   └── gradle-wrapper.properties (Gradle 8.9)
├── app/
│   ├── build.gradle.kts
│   ├── src/
│   │   ├── main/
│   │   │   ├── AndroidManifest.xml
│   │   │   ├── assets/
│   │   │   │   ├── yolov8n-pose-fp16.onnx (~6.5 MB, gitignored)
│   │   │   │   ├── yolov8n-fp16.onnx    (~6.5 MB, gitignored)
│   │   │   │   └── face_landmarker.task  (3.6 MB, gitignored)
│   │   │   ├── cpp/
│   │   │   │   ├── CMakeLists.txt
│   │   │   │   ├── onnxruntime/
│   │   │   │   │   ├── include/          (headers extracted from AAR)
│   │   │   │   │   └── lib/arm64-v8a/    (libonnxruntime.so, gitignored)
│   │   │   │   ├── pose_analyzer.cpp/h   (ONNX Runtime YOLO inference + overlay person data)
│   │   │   │   ├── yolo_utils.cpp/h      (letterbox, NMS, postprocess)
│   │   │   │   ├── image_utils.cpp/h     (BT.601 YUV→BGR conversion)
│   │   │   │   └── jni_bridge.cpp
│   │   │   ├── kotlin/com/incabin/
│   │   │   │   ├── Config.kt
│   │   │   │   ├── InCabinService.kt     (foreground service, 14-step main loop)
│   │   │   │   ├── CameraManager.kt      (Camera2 + ImageReader, 1fps throttle)
│   │   │   │   ├── FaceAnalyzer.kt       (MediaPipe FaceLandmarker + EAR/MAR/solvePnP + face overlay)
│   │   │   │   ├── PoseAnalyzerBridge.kt (JNI bridge to C++ PoseAnalyzer + overlay person data)
│   │   │   │   ├── Merger.kt             (merge results + risk scoring)
│   │   │   │   ├── TemporalSmoother.kt   (5-frame window, face-gating, fast-clear)
│   │   │   │   ├── AudioAlerter.kt       (TextToSpeech + background queue)
│   │   │   │   ├── OutputResult.kt       (16-field data class + JSON + validation)
│   │   │   │   ├── NativeLib.kt          (JNI external fun declarations)
│   │   │   │   ├── OverlayRenderer.kt    (draws bboxes, skeleton, face, metrics on bitmap)
│   │   │   │   ├── FrameHolder.kt        (thread-safe bitmap + OutputResult for UI)
│   │   │   │   └── MainActivity.kt       (start/stop service, dashboard, permission handling)
│   │   │   └── res/
│   │   │       ├── layout/activity_main.xml
│   │   │       ├── values/strings.xml
│   │   │       └── drawable/ic_notification.xml
│   │   └── test/
│   │       └── kotlin/com/incabin/
│   │           ├── MergerTest.kt          (19 tests)
│   │           ├── TemporalSmootherTest.kt (20 tests)
│   │           └── OutputResultTest.kt    (25 tests)
└── local.properties               (gitignored, sdk.dir)
```

---

## 14. Implementation Status

All phases are complete. The project builds successfully (`assembleDebug` produces a 64 MB APK) and all 64 unit tests pass.

### Phase 1 — Skeleton + Camera (Complete)
- Android project with Gradle 8.9, NDK r26, CMake 3.22.1
- Camera2 USB webcam with 1fps throttle
- BT.601 YUV→BGR conversion via JNI (manual implementation, no libyuv)

### Phase 2 — Face Analysis (Complete)
- MediaPipe FaceLandmarker (tasks-vision 0.10.14)
- EAR (6 landmarks per eye), MAR (6 mouth landmarks)
- solvePnP head pose via OpenCV (Kotlin-side, `org.opencv:opencv:4.10.0`)

### Phase 3 — Pose & Object Analysis (Complete)
- C++ ONNX Runtime 1.19.2 (headers/lib extracted from AAR, no prefab)
- YOLOv8n-pose + YOLOv8n detection with letterbox, NMS, postprocessing
- Posture (4 checks), phone (2-strategy), food/drink, child detection
- JNI bridge with JSON result return

### Phase 4 — Pipeline Integration (Complete)
- Merger with 7-weight risk scoring
- TemporalSmoother with face-gating, fast-clear, passenger mode
- AudioAlerter with 4-step priority, background TTS queue
- InCabinService 9-step main loop with distraction duration tracking
- All 64 unit tests (MergerTest, TemporalSmootherTest, OutputResultTest)

### Phase 5 — Polish & Validate
- Build verified: `assembleDebug` and `test` both succeed
- On-device validation pending (requires SA8155P hardware)

### Phase 6 — Pre-Deployment Hardening (Complete)
- TTS retry: one-time retry after 3s if TextToSpeech init fails (AudioAlerter)
- Native library safety: `System.loadLibrary` wrapped in try-catch in NativeLib and PoseAnalyzerBridge; `loaded`/`nativeLoaded` flags guard against JNI calls when lib is missing
- Thread safety: `@Volatile` on `distractionDurationS` (written from camera thread, reset from main thread in onDestroy)
- Build-time asset verification: Gradle `verifyAssets` task fails build if model files are missing from `assets/`
- Diagnostic logging at startup: device model/Android version/SDK/CPU count/RAM, asset file sizes, component init timing (ms), OpenCV version string
- Periodic performance stats: every 30 frames logs avg/min/max frame time, Java heap, native heap, distraction duration
- First-frame resolution confirmation: CameraManager logs actual dimensions, format, and plane count on first processed frame

### Phase 7 — Detection Overlay & Status Dashboard (Complete)
- C++ `OverlayPerson` struct exposes bbox, confidence, is_driver flag, and 17 keypoints through JNI JSON
- Kotlin `OverlayKeypoint`, `OverlayPerson` data classes (Gson auto-parse, defaults for backward compat)
- `FaceOverlayData` exposes eye contours, mouth outline, nose tip from existing MediaPipe landmarks
- `OverlayRenderer` draws bounding boxes (green=driver, blue=others), COCO skeleton (16 bones), face landmarks (eye/mouth contours, nose), and EAR/MAR/Yaw/Pitch metric labels
- `FrameHolder` expanded to hold `FrameData(bitmap, OutputResult)` for thread-safe UI consumption
- Pipeline reordered: merge+smooth before overlay, overlay bitmap posted to FrameHolder with result
- `MainActivity` dashboard: color-coded risk banner, metrics row, passenger count, distraction timer, active detection labels
- Build verified: `assembleDebug` + all 64 unit tests pass (new fields have defaults, no test changes needed)

---

## 15. Testing Strategy

### Unit Tests (JVM, no device needed)
- **MergerTest**: Risk scoring all weight combinations (19 tests)
- **TemporalSmootherTest**: Voting, face-gated filtering, fast-clear, passenger mode (20 tests)
- **OutputResultTest**: JSON serialization, field validation (25 tests)

### Instrumented Tests (on SA8155 device)
- Camera opens and captures frames
- MediaPipe returns landmarks for a test image
- ONNX Runtime loads models and runs inference on a test image
- Full pipeline produces valid JSON output
- Audio TTS speaks without error

### Manual Validation Scenarios
7-phase protocol:
1. **Baseline** (15s): No distractions → all clear
2. **Phone near face** (15s): Hold phone up → phone=true
3. **Phone at side** (15s): Phone at lap → phone=true (known: low recall)
4. **Eyes closed** (10s): Close eyes → eyes_closed=true
5. **Yawning** (10s): Open mouth wide → yawning=true
6. **Head turn** (10s): Look away → distracted=true
7. **All clear** (10s): Normal → all clear

---

## 16. Complete Unit Test Specifications

All 64 tests are specified below with exact inputs, logic, and expected outputs. These are the tests to implement in the Android project.

### 16a. MergerTest (19 tests)

**Test helper — `computeRisk` signature:**
```
computeRisk(phone: bool, eyes: bool, posture: bool, slouch: bool,
            yawning: bool = false, distracted: bool = false, eating: bool = false) -> string
```

#### Risk Scoring Tests (14 tests)

| # | Test Name | Inputs | Expected |
|---|---|---|---|
| 1 | `test_all_clear_returns_low` | `(false, false, false, false)` | `"low"` |
| 2 | `test_phone_only_returns_high` | `(true, false, false, false)` | `"high"` |
| 3 | `test_eyes_closed_only_returns_high` | `(false, true, false, false)` | `"high"` |
| 4 | `test_posture_only_returns_medium` | `(false, false, true, false)` | `"medium"` |
| 5 | `test_child_slouching_only_returns_medium` | `(false, false, false, true)` | `"medium"` |
| 6 | `test_phone_and_eyes_returns_high` | `(true, true, false, false)` | `"high"` |
| 7 | `test_posture_and_slouching_returns_high` | `(false, false, true, true)` — score=2+1=3 | `"high"` |
| 8 | `test_all_true_returns_high` | `(true, true, true, true)` | `"high"` |
| 9 | `test_yawning_only_returns_medium` | `(false, false, false, false, yawning=true)` | `"medium"` |
| 10 | `test_distracted_only_returns_medium` | `(false, false, false, false, distracted=true)` | `"medium"` |
| 11 | `test_eating_only_returns_medium` | `(false, false, false, false, eating=true)` | `"medium"` |
| 12 | `test_yawning_plus_posture_returns_high` | `(false, false, true, false, yawning=true)` — score=2+2=4 | `"high"` |
| 13 | `test_distracted_plus_eating_returns_high` | `(false, false, false, false, distracted=true, eating=true)` — score=2+1=3 | `"high"` |
| 14 | `test_all_new_fields_returns_high` | `(false, false, false, false, yawning=true, distracted=true, eating=true)` — score=2+2+1=5 | `"high"` |

#### Merge Results Tests (5 tests)

**Test 15: `test_merges_all_fields`**
```
face = {driver_eyes_closed: true, ear_value: 0.15, driver_yawning: false,
        mar_value: 0.3, driver_distracted: false, head_yaw: 5.0, head_pitch: 3.0}
pose = {passenger_count: 2, driver_using_phone: true, dangerous_posture: false,
        child_present: true, child_slouching: false, driver_eating_drinking: false}
result = mergeResults(face, pose)
→ result.driver_eyes_closed == true
→ result.passenger_count == 2
→ result.driver_using_phone == true
→ result.dangerous_posture == false
→ result.child_present == true
→ result.child_slouching == false
→ result.driver_yawning == false
→ result.driver_distracted == false
→ result.driver_eating_drinking == false
→ result.timestamp exists
→ result.risk_level == "high"
```

**Test 16: `test_ear_value_passed_through`**
```
face = {driver_eyes_closed: false, ear_value: 0.28, driver_yawning: false,
        mar_value: 0.2, driver_distracted: false, head_yaw: 0.0, head_pitch: 0.0}
pose = {passenger_count: 1, driver_using_phone: false, dangerous_posture: false,
        child_present: false, child_slouching: false, driver_eating_drinking: false}
→ result.ear_value == 0.28
```

**Test 17: `test_ear_value_none_when_no_face`**
```
face = {driver_eyes_closed: false, ear_value: null, driver_yawning: false,
        mar_value: null, driver_distracted: false, head_yaw: null, head_pitch: null}
pose = {passenger_count: 1, driver_using_phone: false, dangerous_posture: false,
        child_present: false, child_slouching: false, driver_eating_drinking: false}
→ result.ear_value == null
→ result.mar_value == null
→ result.head_yaw == null
→ result.head_pitch == null
```

**Test 18: `test_defaults_when_keys_missing`**
```
face = {}  (empty)
pose = {}  (empty)
→ result.driver_eyes_closed == false
→ result.passenger_count == 0
→ result.ear_value == null
→ result.driver_yawning == false
→ result.driver_distracted == false
→ result.driver_eating_drinking == false
```

**Test 19: `test_new_diagnostic_values_passed_through`**
```
face = {driver_eyes_closed: false, ear_value: 0.25, driver_yawning: true,
        mar_value: 0.65, driver_distracted: true, head_yaw: 35.0, head_pitch: -10.0}
pose = {passenger_count: 1, driver_using_phone: false, dangerous_posture: false,
        child_present: false, child_slouching: false, driver_eating_drinking: true}
→ result.mar_value == 0.65
→ result.head_yaw == 35.0
→ result.head_pitch == -10.0
→ result.driver_yawning == true
→ result.driver_distracted == true
→ result.driver_eating_drinking == true
→ result.risk_level == "high"
```

---

### 16b. TemporalSmootherTest (20 tests)

**Test helper — `makeResult`:**
```
function makeResult(eyes=false, phone=false, posture=false, child=false, slouch=false,
                    yawn=false, distracted=false, eating=false,
                    passengers=1, ear=0.25, mar=0.2, head_yaw=0.0, head_pitch=0.0):
    return {
        timestamp: "2026-01-01T00:00:00+00:00",
        passenger_count: passengers,
        driver_using_phone: phone,
        driver_eyes_closed: eyes,
        driver_yawning: yawn,
        driver_distracted: distracted,
        driver_eating_drinking: eating,
        dangerous_posture: posture,
        child_present: child,
        child_slouching: slouch,
        risk_level: "low",
        ear_value: ear,
        mar_value: mar,
        head_yaw: head_yaw,
        head_pitch: head_pitch,
    }
```

#### Basic Smoothing (4 tests)

**Test 1: `test_single_frame_below_threshold`**
```
s = TemporalSmoother(window=5, threshold=0.6)
result = s.smooth(makeResult(eyes=true))
→ result.driver_eyes_closed == true
// 1/1 = 1.0 >= 0.6
```

**Test 2: `test_warm_property`**
```
s = TemporalSmoother(window=3, threshold=0.6)
→ s.warm == false
s.smooth(makeResult())
→ s.warm == false
s.smooth(makeResult())
→ s.warm == false
s.smooth(makeResult())
→ s.warm == true
```

**Test 3: `test_majority_voting`**
```
s = TemporalSmoother(window=5, threshold=0.6)
3x smooth(makeResult(phone=false))
2x smooth(makeResult(phone=true))
→ result.driver_using_phone == false
// 2/5 = 0.4 < 0.6
```

**Test 4: `test_threshold_met`**
```
s = TemporalSmoother(window=5, threshold=0.6)
2x smooth(makeResult(phone=false))
3x smooth(makeResult(phone=true))
→ result.driver_using_phone == true
// 3/5 = 0.6 >= 0.6
```

#### No-Face Handling (7 tests)

**Test 5: `test_no_face_frames_skipped_for_eyes`**
```
s = TemporalSmoother(window=5, threshold=0.6)
smooth(makeResult(eyes=true, ear=0.10))
smooth(makeResult(eyes=false, ear=null))   // no face
smooth(makeResult(eyes=true, ear=0.08))
smooth(makeResult(eyes=false, ear=null))   // no face
result = smooth(makeResult(eyes=true, ear=0.05))
→ result.driver_eyes_closed == true
// face_frames=3, true_count=3, 3/3=1.0 >= 0.6
```

**Test 6: `test_no_face_still_counts_for_phone`**
```
s = TemporalSmoother(window=5, threshold=0.6)
smooth(makeResult(phone=true, ear=null))
smooth(makeResult(phone=true, ear=0.25))
smooth(makeResult(phone=false, ear=null))
smooth(makeResult(phone=false, ear=0.25))
result = smooth(makeResult(phone=false, ear=null))
→ result.driver_using_phone == false
// 2/5 = 0.4 < 0.6 (phone is NOT face-gated)
```

**Test 7: `test_all_no_face_eyes_returns_false`**
```
s = TemporalSmoother(window=3, threshold=0.6)
3x smooth(makeResult(eyes=true, ear=null))
→ result.driver_eyes_closed == false
// 0 face frames → default false
```

**Test 8: `test_no_face_frames_skipped_for_yawning`**
```
s = TemporalSmoother(window=5, threshold=0.6)
smooth(makeResult(yawn=true, mar=0.6))
smooth(makeResult(yawn=false, mar=null))
smooth(makeResult(yawn=true, mar=0.7))
smooth(makeResult(yawn=false, mar=null))
result = smooth(makeResult(yawn=true, mar=0.8))
→ result.driver_yawning == true
// face_frames=3, true_count=3, 3/3=1.0 >= 0.6
```

**Test 9: `test_all_no_face_yawning_returns_false`**
```
s = TemporalSmoother(window=3, threshold=0.6)
3x smooth(makeResult(yawn=true, mar=null))
→ result.driver_yawning == false
```

**Test 10: `test_no_face_frames_skipped_for_distracted`**
```
s = TemporalSmoother(window=5, threshold=0.6)
smooth(makeResult(distracted=true, head_yaw=35.0))
smooth(makeResult(distracted=false, head_yaw=null))
smooth(makeResult(distracted=true, head_yaw=40.0))
smooth(makeResult(distracted=false, head_yaw=null))
result = smooth(makeResult(distracted=true, head_yaw=38.0))
→ result.driver_distracted == true
// face_frames=3, true_count=3, 3/3=1.0 >= 0.6
```

**Test 11: `test_all_no_face_distracted_returns_false`**
```
s = TemporalSmoother(window=3, threshold=0.6)
3x smooth(makeResult(distracted=true, head_yaw=null))
→ result.driver_distracted == false
```

#### New Fields (2 tests)

**Test 12: `test_eating_drinking_majority_voting`**
```
s = TemporalSmoother(window=5, threshold=0.6)
2x smooth(makeResult(eating=false))
3x smooth(makeResult(eating=true))
→ result.driver_eating_drinking == true
// 3/5 = 0.6 >= 0.6
```

**Test 13: `test_eating_drinking_below_threshold`**
```
s = TemporalSmoother(window=5, threshold=0.6)
3x smooth(makeResult(eating=false))
2x smooth(makeResult(eating=true))
→ result.driver_eating_drinking == false
// 2/5 = 0.4 < 0.6
```

#### Passenger Count (1 test)

**Test 14: `test_mode_of_counts`**
```
s = TemporalSmoother(window=5, threshold=0.6)
smooth(makeResult(passengers=1))
smooth(makeResult(passengers=2))
smooth(makeResult(passengers=2))
smooth(makeResult(passengers=1))
result = smooth(makeResult(passengers=2))
→ result.passenger_count == 2
// mode: 2 appears 3 times, 1 appears 2 times
```

#### Fast-Clear (3 tests)

**Test 15: `test_fast_clear_eyes_on_high_ear`**
```
s = TemporalSmoother(window=5, threshold=0.6)
5x smooth(makeResult(eyes=true, ear=0.10))
smooth(makeResult(eyes=false, ear=0.25))
result = smooth(makeResult(eyes=false, ear=0.28))
→ result.driver_eyes_closed == false
// 2 consecutive high-EAR frames → fast-clear overrides buffer
```

**Test 16: `test_no_fast_clear_with_single_high_ear`**
```
s = TemporalSmoother(window=5, threshold=0.6)
5x smooth(makeResult(eyes=true, ear=0.10))
result = smooth(makeResult(eyes=false, ear=0.28))
→ result.driver_eyes_closed == true
// Only 1 high-EAR frame, no fast-clear; buffer: 5/6 face-frames have eyes=true
```

**Test 17: `test_fast_clear_streak_resets_on_low_ear`**
```
s = TemporalSmoother(window=5, threshold=0.6)
5x smooth(makeResult(eyes=true, ear=0.10))
smooth(makeResult(eyes=false, ear=0.28))   // streak=1
smooth(makeResult(eyes=true, ear=0.10))    // streak reset to 0
result = smooth(makeResult(eyes=false, ear=0.28))  // streak=1
→ result.driver_eyes_closed == true
// Streak was reset, only 1 high-EAR frame after reset
```

#### Risk Recomputed (3 tests)

**Test 18: `test_risk_recomputed_from_smoothed`**
```
s = TemporalSmoother(window=3, threshold=0.6)
3x smooth(makeResult(phone=true))
→ result.risk_level == "high"
```

**Test 19: `test_risk_low_when_smoothed_clears`**
```
s = TemporalSmoother(window=3, threshold=0.6)
smooth(makeResult(phone=true))
smooth(makeResult(phone=false))
result = smooth(makeResult(phone=false))
→ result.risk_level == "low"
// 1/3 = 0.33 < 0.6 → phone=false → risk=low
```

**Test 20: `test_risk_includes_new_fields`**
```
s = TemporalSmoother(window=3, threshold=0.6)
3x smooth(makeResult(yawn=true, mar=0.6))
→ result.risk_level == "medium"
// yawning=true (score=2) → medium
```

---

### 16c. OutputResultTest / Schema Validation Test (25 tests)

**Test helper — `validResult`:**
```
function validResult(**overrides):
    base = {
        timestamp: "2026-01-01T00:00:00+00:00",
        passenger_count: 1,
        driver_using_phone: false,
        driver_eyes_closed: false,
        driver_yawning: false,
        driver_distracted: false,
        driver_eating_drinking: false,
        dangerous_posture: false,
        child_present: false,
        child_slouching: false,
        risk_level: "low",
        ear_value: 0.25,
        mar_value: 0.2,
        head_yaw: 5.0,
        head_pitch: 3.0,
        distraction_duration_s: 0,
    }
    base.update(overrides)
    return base
```

#### Valid Payloads (11 tests)

| # | Test Name | Input Override | Expected |
|---|---|---|---|
| 1 | `test_valid_all_clear` | (none) | valid (0 errors) |
| 2 | `test_valid_high_risk` | `phone=true, eyes=true, risk="high"` | valid |
| 3 | `test_valid_ear_null` | `ear_value=null` | valid |
| 4 | `test_valid_ear_zero` | `ear_value=0.0` | valid |
| 5 | `test_valid_medium_risk` | `risk_level="medium"` | valid |
| 6 | `test_valid_mar_null` | `mar_value=null` | valid |
| 7 | `test_valid_head_yaw_null` | `head_yaw=null` | valid |
| 8 | `test_valid_head_pitch_null` | `head_pitch=null` | valid |
| 9 | `test_valid_with_new_booleans_true` | `yawning=true, distracted=true, eating=true, risk="high"` | valid |
| 10 | `test_valid_distraction_duration` | `distraction_duration_s=15` | valid |
| 11 | `test_valid_without_optional_diagnostics` | delete ear_value, mar_value, head_yaw, head_pitch | valid |

#### Invalid Payloads (14 tests)

| # | Test Name | Input Mutation | Expected |
|---|---|---|---|
| 12 | `test_missing_timestamp` | delete timestamp | 1+ errors |
| 13 | `test_missing_risk_level` | delete risk_level | 1+ errors |
| 14 | `test_invalid_risk_value` | `risk_level="critical"` | 1+ errors |
| 15 | `test_negative_passenger_count` | `passenger_count=-1` | 1+ errors |
| 16 | `test_passenger_count_string` | `passenger_count="two"` | 1+ errors |
| 17 | `test_boolean_field_as_string` | `driver_using_phone="true"` | 1+ errors |
| 18 | `test_extra_field_rejected` | add `unknown_field="foo"` | 1+ errors |
| 19 | `test_ear_value_as_string` | `ear_value="0.25"` | 1+ errors |
| 20 | `test_missing_driver_yawning` | delete driver_yawning | 1+ errors |
| 21 | `test_missing_distraction_duration` | delete distraction_duration_s | 1+ errors |
| 22 | `test_negative_distraction_duration` | `distraction_duration_s=-1` | 1+ errors |
| 23 | `test_distraction_duration_as_float` | `distraction_duration_s=5.5` | 1+ errors |
| 24 | `test_yawning_as_string` | `driver_yawning="true"` | 1+ errors |
| 25 | `test_mar_value_as_string` | `mar_value="0.5"` | 1+ errors |

---

## 17. Known Limitations

- Phone at side/lap: ~7% recall — COCO class 67 struggles with occluded phones
- Phone near face: ~87% recall — works well with ROI crop
- Eyes closed: ~90% recall — sensitive to face angle/distance from camera
- Yawning: MAR threshold (0.5) may need per-user tuning — mouth shape varies
- Head pose: solvePnP accuracy degrades at extreme face angles
- Food/drink: COCO classes detect bottles/cups but may miss hand-held food
- Child detection: bbox height ratio is a rough heuristic, not age estimation
- Single camera: no depth info, driver = largest bounding box assumption
- All detection accuracy is sensitive to lighting, distance, and camera angle
