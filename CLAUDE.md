# SA8155 Android Port — In-Cabin AI Perception

## Status
Implementation complete with pre-deployment hardening. Build verified (`assembleDebug` + all 64 unit tests pass). APK size: 64 MB.

## Target
Qualcomm SA8155P (Kryo 485 CPU-only). Android Automotive 14. Debug build. USB webcam.

## What it does
Captures USB webcam at 1fps, runs local ML inference on CPU, outputs JSON with 16 fields: passenger_count, driver_using_phone, driver_eyes_closed, driver_yawning, driver_distracted, driver_eating_drinking, dangerous_posture, child_present, child_slouching, risk_level, distraction_duration_s, ear_value, mar_value, head_yaw, head_pitch, timestamp.

## Full Specification
**See `SA8155_PORT_SPEC.md`** for complete implementation details including every algorithm, formula, landmark index, threshold, tensor shape, postprocessing step, and all 64 unit test specifications. That document is the single source of truth for this port.

## Architecture
```
USB Webcam (Camera2 ImageReader, 1280x720, YUV_420_888)
  → BT.601 YUV→BGR (C++ via JNI, manual conversion in image_utils.cpp)
  → YOLOv8n-pose (ONNX Runtime C++, CPU EP) → posture, child, passenger count
  → YOLOv8n detection (ONNX Runtime C++, CPU EP) → phone, food/drink in driver ROI
  → MediaPipe FaceLandmarker (Kotlin, tasks-vision SDK) → EAR, MAR, solvePnP head pose
  → Merger (Kotlin) → risk scoring
  → Temporal Smoother (Kotlin) → 5-frame sliding window, 60% threshold
  → Audio Alerter (Android TextToSpeech, queued sequential playback)
  → JSON output (Logcat)
```

## Tech Stack
| Layer | Language | Purpose |
|---|---|---|
| Android lifecycle, camera, TTS | Kotlin | Camera2, TextToSpeech, foreground service |
| Inference hot path | C++ (NDK) | ONNX Runtime, YOLO preprocessing/postprocessing |
| Face analysis | Kotlin | MediaPipe FaceLandmarker, OpenCV solvePnP |
| Bridge | JNI | Frame data + results between Kotlin ↔ C++ |

## Dependencies
| Library | Version | Integration |
|---|---|---|
| ONNX Runtime Android | 1.19.2 | Maven + extracted headers/lib in `cpp/onnxruntime/` |
| MediaPipe Tasks Vision | 0.10.14 | Maven: `com.google.mediapipe:tasks-vision:0.10.14` |
| OpenCV Android SDK | 4.10.0 | Maven: `org.opencv:opencv:4.10.0` |
| Gson | 2.11.0 | Maven: `com.google.code.gson:gson:2.11.0` |

## Models (in `app/src/main/assets/`, gitignored)
| Model | Format | Size | Source |
|---|---|---|---|
| `yolov8n-pose.onnx` | ONNX FP32 | 12.9 MB | `yolo export model=yolov8n-pose.pt format=onnx opset=17` |
| `yolov8n.onnx` | ONNX FP32 | 12.3 MB | `yolo export model=yolov8n.pt format=onnx opset=17` |
| `face_landmarker.task` | TFLite bundle | 3.6 MB | Google AI Edge (float16) |

## Key Thresholds
- EAR < 0.21 → eyes closed
- MAR > 0.5 → yawning
- |yaw| > 30° or |pitch| > 25° → distracted
- YOLO confidence > 0.35, NMS IoU 0.45
- Phone: COCO class 67, Food/drink: classes 39-48
- Posture lean > 30°, Child slouch > 20°
- Head turn: nose offset > 30% of shoulder width
- Child: bbox height < 75% of driver
- Keypoint confidence threshold: 0.5
- Wrist crop: 200x200px
- Smoother: 5-frame window, 60% threshold, fast-clear on 2 consecutive high-EAR frames

## Risk Scoring
```
phone=3, eyes=3, yawn=2, distracted=2, posture=2, eating=1, slouch=1
score >= 3 → high, >= 1 → medium, else → low
```

## Detection Algorithms Summary

### Face Analysis (Kotlin + MediaPipe + OpenCV)
- **EAR**: Right eye [33,160,158,133,153,144], Left eye [362,385,387,263,373,380]. Formula: (dist(p2,p6)+dist(p3,p5))/(2*dist(p1,p4))
- **MAR**: Landmarks 13,14,61,291,0,17. Formula: (dist(top,bottom)+dist(upper_mid,lower_mid))/(2*dist(left,right))
- **Head Pose**: solvePnP with landmarks [1,152,33,263,61,291], 3D model points, Euler angle extraction
- **Import note**: `FaceLandmarkerOptions` is a nested class — import as `FaceLandmarker.FaceLandmarkerOptions`

### Pose Analysis (C++ + ONNX Runtime)
- **YOLOv8n-pose**: Output [1,56,8400] → transpose → filter conf>0.35 → NMS IoU 0.45
- **YOLOv8n detect**: Output [1,84,8400] → transpose → argmax class scores → filter → NMS
- **Driver**: Largest bbox by area
- **Posture**: 4 checks (torso lean, head droop, head turn, face not visible)
- **Phone**: 2-strategy (driver ROI + 20% pad → wrist crops 200x200px)
- **Food/drink**: Driver ROI + 20% pad, COCO classes 39-48
- **Child**: bbox height ratio < 0.75, slouch check at 20°

### Temporal Smoother
- Standard fields: majority voting over buffer
- Face-gated fields: eyes→ear_value, yawning→mar_value, distracted→head_yaw (skip null frames)
- Fast-clear: 2 consecutive frames with ear >= 0.21 → immediately clear eyes_closed
- Passenger count: mode of buffer
- Risk: recomputed from smoothed booleans

### Audio Alerter
- Priority: new dangers → risk change → all-clear (overrides) → distraction duration (only if no other)
- Danger names: phone detected, eyes closed, yawning detected, driver distracted, eating or drinking, dangerous posture, child slouching
- Duration thresholds: [5, 10, 20] seconds, one per cycle, reset when duration=0
- First frame: store state only, no announcement
- TTS retry: if init fails, schedules one retry after 3s via Handler; `ttsRetried` flag prevents loops

### Distraction Duration
- Fields: phone, eyes, yawning, distracted, eating (NOT posture/child)
- Increment +1 per frame if any active, reset to 0 when all clear
- Injected into result after smoothing, before output

## Build System
- Gradle 8.9 (Kotlin DSL), AGP 8.7.3, Kotlin 2.0.21
- NDK 26.1.10909125, CMake 3.22.1, C++17
- ABI: `arm64-v8a` only
- minSdk 29, targetSdk 34, compileSdk 34
- ONNX Runtime: headers/lib extracted from AAR to `cpp/onnxruntime/` (no prefab)
- Debug keystore (no OEM platform keys needed)
- `verifyAssets` Gradle task: fails build if model files missing from `assets/` (runs on `preBuild`)

## Build Commands
```bash
# Set environment
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools

# Build APK
./gradlew assembleDebug

# Run unit tests
./gradlew test
```

## Project Structure
```
in_cabin_poc-sa8155/
├── app/src/main/
│   ├── assets/          (ONNX models + face_landmarker.task, gitignored)
│   ├── cpp/
│   │   ├── onnxruntime/ (extracted headers + arm64-v8a lib from AAR)
│   │   ├── pose_analyzer, yolo_utils, image_utils, jni_bridge
│   │   └── CMakeLists.txt
│   ├── kotlin/com/incabin/
│   │   ├── Config, InCabinService, CameraManager, FaceAnalyzer
│   │   ├── PoseAnalyzerBridge, Merger, TemporalSmoother
│   │   ├── AudioAlerter, OutputResult, NativeLib, MainActivity
│   └── res/             (layout, strings, notification icon)
├── app/src/test/kotlin/  (MergerTest, TemporalSmootherTest, OutputResultTest)
└── build configs         (build.gradle.kts, settings.gradle.kts, etc.)
```

## Testing
- 64 unit tests (all passing):
  - MergerTest: 19 tests (risk scoring + merge logic)
  - TemporalSmootherTest: 20 tests (voting, face-gating, fast-clear, passenger mode, risk recomputation)
  - OutputResultTest: 25 tests (schema validation — valid and invalid payloads)
- On-device validation pending (requires SA8155P hardware)

## Performance Budget
~500-800ms/frame estimated on Kryo 485 (FP32). Within 1000ms (1fps) budget. INT8 quantization available as optimization if needed.

## Pre-Deployment Hardening
- **TTS retry**: One-time retry after 3s if TextToSpeech init fails (AudioAlerter)
- **Native lib safety**: `System.loadLibrary("incabin")` wrapped in try-catch in NativeLib and PoseAnalyzerBridge; `loaded`/`nativeLoaded` flags prevent JNI calls if lib missing
- **Thread safety**: `@Volatile` on `distractionDurationS` (accessed from camera thread and main thread)
- **Build-time asset check**: Gradle `verifyAssets` task fails build if model files missing
- **Diagnostic logging at startup**: device info (model, Android version, CPU count, RAM), asset verification (file sizes), component init timing, OpenCV version
- **Periodic stats (every 30 frames)**: avg/min/max frame time, Java heap, native heap, distraction duration
- **First-frame confirmation**: CameraManager logs actual frame dimensions, format, and plane count

## Deployment
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant com.incabin android.permission.CAMERA
adb shell pm grant com.incabin android.permission.FOREGROUND_SERVICE
adb shell pm grant com.incabin android.permission.FOREGROUND_SERVICE_CAMERA
adb shell am start -n com.incabin/.MainActivity
adb logcat -s InCabin:* InCabin-Camera:* AudioAlerter:* OnnxRuntime:*
```
