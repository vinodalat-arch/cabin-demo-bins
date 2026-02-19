# SA8155 Android Port — In-Cabin AI Perception

## Status
Implementation complete with pre-deployment hardening, architectural hardening pass, and visual debugging overlay. Build verified (`assembleDebug` + all 64 unit tests pass). APK size: 64 MB.

## Target
Qualcomm SA8155P (Kryo 485 CPU-only). Android Automotive 14. Debug build. USB webcam.

## What it does
Captures USB webcam at 1fps, runs local ML inference on CPU, outputs JSON with 16 fields: passenger_count, driver_using_phone, driver_eyes_closed, driver_yawning, driver_distracted, driver_eating_drinking, dangerous_posture, child_present, child_slouching, risk_level, distraction_duration_s, ear_value, mar_value, head_yaw, head_pitch, timestamp.

## Full Specification
**See `SA8155_PORT_SPEC.md`** for complete implementation details including every algorithm, formula, landmark index, threshold, tensor shape, postprocessing step, and all 64 unit test specifications. That document is the single source of truth for this port.

## Architecture
```
USB Webcam
  → V4L2 ioctl (/dev/videoN, YUYV 1280x720, mmap) → YUYV→BGR (C++ via JNI)
    [fallback: Camera2 ImageReader → YUV_420_888 → BT.601 YUV→BGR]
  → YOLOv8n-pose (ONNX Runtime C++, CPU EP) → posture, child, passenger count
  → YOLOv8n detection (ONNX Runtime C++, CPU EP) → phone, food/drink in driver ROI
  → MediaPipe FaceLandmarker (Kotlin, tasks-vision SDK) → EAR, MAR, solvePnP head pose
  → Merger (Kotlin) → risk scoring
  → Temporal Smoother (Kotlin) → 5-frame sliding window, 60% threshold
  → Overlay Renderer (Kotlin) → bboxes, skeleton, face landmarks, metrics on bitmap
  → FrameHolder → bitmap + OutputResult → MainActivity dashboard
  → Audio Alerter (Android TextToSpeech, queued sequential playback)
  → JSON output (Logcat)
```

### Camera Strategy
The Honda SA8155P BSP has `config.disable_cameraservice=true` and no External Camera HAL, so Camera2 API cannot see USB webcams. The app uses **V4L2 direct ioctl access** as the primary camera path, with Camera2 as a fallback.
- `V4l2CameraManager` (Kotlin) → `V4l2Camera` (C++/JNI) → `/dev/videoN`
- Scans `/dev/video0-63`, skips Qualcomm internal devices (`cam-req-mgr`, `cam_sync`)
- Configures YUYV capture, 4 mmap buffers, converts YUYV→BGR in native code
- If no V4L2 device found, falls back to `CameraManager` (Camera2 API)

## Tech Stack
| Layer | Language | Purpose |
|---|---|---|
| Android lifecycle, camera, TTS, UI | Kotlin | V4L2 manager, Camera2 fallback, TextToSpeech, foreground service, overlay + dashboard |
| Inference hot path | C++ (NDK) | ONNX Runtime, YOLO preprocessing/postprocessing, V4L2 camera access |
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
- V4L2 reconnect: 3 consecutive failures triggers disconnect, backoff 2s→30s max

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
- TTS retry: if init fails, schedules one retry after 3s via stored Handler; `ttsRetried` flag prevents loops; Handler callbacks cancelled in `close()` to prevent leak if service destroyed within 3s

### Distraction Duration
- Fields: phone, eyes, yawning, distracted, eating (NOT posture/child)
- Increment +1 per frame if any active, reset to 0 when all clear
- Injected into result after smoothing, before output

### Detection Overlay (OverlayRenderer)
- Renders onto camera preview bitmap after merge+smooth, before posting to FrameHolder
- **Person bounding boxes**: green=driver, blue=passengers, with "Driver 95%"/"Passenger 87%" labels
- **COCO skeleton**: 16 bone connections between keypoints (confidence > 0.5)
- **Keypoint dots**: red circles at each confident keypoint
- **Face landmarks**: eye contours (green=open, red=closed), mouth outline (green=normal, orange=yawning), cyan nose tip
- **Metric labels**: EAR, MAR, Yaw, Pitch in top-left corner with dark background
- Pre-allocated Paint objects; ~3-5ms overhead on 1280x720

### Status Dashboard (MainActivity)
- Compact top row: status text + start/stop button
- Camera preview (ImageView) with overlay bitmaps from FrameHolder
- Dashboard panel (visible when monitoring):
  - Risk banner: color-coded (red=HIGH, orange=MEDIUM, green=LOW)
  - Metrics row: EAR | MAR | Yaw | Pitch
  - Info row: Passengers | Distraction timer
  - Active detections: pipe-separated labels (Phone | Eyes Closed | Yawning | etc.)
- Polls FrameHolder every 500ms for bitmap + OutputResult

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
│   │   ├── v4l2_camera, pose_analyzer, yolo_utils, image_utils, jni_bridge
│   │   └── CMakeLists.txt
│   ├── kotlin/com/incabin/
│   │   ├── Config, InCabinService, V4l2CameraManager, CameraManager
│   │   ├── FaceAnalyzer, PoseAnalyzerBridge, Merger, TemporalSmoother
│   │   ├── AudioAlerter, OutputResult, NativeLib, MainActivity
│   │   ├── OverlayRenderer, FrameHolder
│   └── res/             (layout, strings, notification icon)
├── app/src/test/kotlin/  (MergerTest, TemporalSmootherTest, OutputResultTest)
└── build configs         (build.gradle.kts, settings.gradle.kts, etc.)
```

## Testing
- 64 unit tests (all passing):
  - MergerTest: 19 tests (risk scoring + merge logic)
  - TemporalSmootherTest: 20 tests (voting, face-gating, fast-clear, passenger mode, risk recomputation)
  - OutputResultTest: 25 tests (schema validation — valid and invalid payloads)
- On-device validated on Honda SA8155P (ALPSALPINE IVI-SYSTEM, Android 14, 8 CPUs, 7.6 GB RAM) with Logitech C270 via V4L2

## Performance Budget
~500-800ms/frame estimated on Kryo 485 (FP32). Within 1000ms (1fps) budget. INT8 quantization available as optimization if needed.

## Pre-Deployment Hardening
- **TTS retry**: One-time retry after 3s if TextToSpeech init fails (AudioAlerter); stored Handler cancelled in `close()` to prevent TTS leak if service destroyed within 3s
- **Native lib safety**: `System.loadLibrary("incabin")` wrapped in try-catch in NativeLib and PoseAnalyzerBridge; `loaded`/`nativeLoaded` flags prevent JNI calls if lib missing
- **Thread safety — pipeline lock**: `ReentrantLock` in `InCabinService` guards `processFrame()` (camera thread, `tryLock`) vs `onDestroy()` (main thread, `lock`), preventing JNI use-after-free on native PoseAnalyzer and V4L2Camera pointers
- **Thread safety — atomic counter**: `distractionDurationS` uses `AtomicInteger` (`incrementAndGet`/`set`) instead of `@Volatile` read-modify-write
- **Bitmap lifecycle safety**: `processFrame()` wraps bitmap in `try-finally { bitmap.recycle() }`; `OverlayRenderer.render()` wraps draw calls in `try-catch` returning partial overlay on error — prevents ~3.7 MB leak per failed frame
- **C++ buffer pre-allocation**: Detect model path uses pre-allocated `detect_letterbox_buf_` + `detect_tensor_buf_` (~6.1 MB once) instead of allocating per call (was ~24 MB alloc/dealloc per frame); crop extraction uses reusable `crop_buf_` member
- **ONNX output size validation**: `runInference()` validates output tensor size matches expected dimensions (pose: 56×8400, detect: 84×8400) before accessing data; returns empty on mismatch to prevent out-of-bounds reads from corrupted/mismatched models
- **JNI OOM safety**: `NewByteArray` null returns logged with `LOGE` and early-return `nullptr` in both YUV→BGR and V4L2 frame JNI functions
- **V4L2 robustness**: `select()` retries on `EINTR` instead of dropping frames; `xioctl()` EINTR retry capped at 100 iterations; `select()` timeout reduced from 5s to 2s for faster disconnect detection
- **V4L2 disconnect/reconnect**: After 3 consecutive null frames, destroys native camera and enters reconnect mode; scans for device with exponential backoff (2s → 4s → 8s → ... → 30s max); on reconnect, resets all state and resumes capture; handles device path changes (`/dev/videoN` may differ after replug)
- **Build-time asset check**: Gradle `verifyAssets` task fails build if model files missing
- **Diagnostic logging at startup**: device info (model, Android version, CPU count, RAM), asset verification (file sizes), component init timing, OpenCV version
- **Periodic stats (every 30 frames)**: avg/min/max frame time, Java heap, native heap, distraction duration
- **First-frame confirmation**: CameraManager logs actual frame dimensions, format, and plane count
- **V4L2 permission diagnostics**: `findCaptureDevice()` logs `permission denied` with fix hint when device nodes have restrictive permissions

## Deployment

### Honda SA8155P BSP Setup (one-time per boot)
```bash
# 1. Remove ODK hook that blocks UVC webcam binding
adb shell rmmod odk_hook_module

# 2. Reconnect USB webcam physically, then verify it appeared
adb shell "ls -la /dev/video*"
adb shell "for d in /sys/class/video4linux/video*; do echo \$(basename \$d): \$(cat \$d/name 2>/dev/null); done"
# Should see e.g. "video2: C270 HD WEBCAM"

# 3. Fix device node permissions (resets on each webcam reconnect)
adb shell chmod 666 /dev/video2 /dev/video3   # adjust numbers to match webcam

# 4. Install APK
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 5. Grant permissions (app runs as user 10 on this BSP)
adb shell pm grant --user 10 com.incabin android.permission.CAMERA

# 6. Start service and monitor
adb shell am start-foreground-service -a com.incabin.START com.incabin/.InCabinService
adb logcat -s 'InCabin:*' 'InCabin-V4L2:*' 'InCabin-JNI:*' 'InCabin-Camera:*' 'AudioAlerter:*'
```

### Generic Android (Camera2 fallback)
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant com.incabin android.permission.CAMERA
adb shell am start-foreground-service -a com.incabin.START com.incabin/.InCabinService
adb logcat -s 'InCabin:*' 'InCabin-Camera:*' 'AudioAlerter:*'
```

### Known Honda BSP Quirks
- **ODK hook module**: `odk_hook_module` blocks UVC driver binding. Must `rmmod` each boot before webcam use.
- **Camera service disabled**: `config.disable_cameraservice=true` means Camera2 API sees no cameras. V4L2 is the only path.
- **User 10**: App runs under Android user 10 (not user 0). Use `--user 10` for `pm grant`.
- **Device permissions**: `/dev/video*` nodes are `660 system:camera`. App needs `chmod 666` to open them via V4L2. Resets on webcam reconnect.
- **SELinux**: Permissive on this BSP (no SELinux blocks).
