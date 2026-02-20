# SA8155 Android Port — In-Cabin AI Perception

## Status
Implementation complete with pre-deployment hardening, architectural hardening pass, on-device performance tuning, UI polish, and face recognition. Build verified (`assembleDebug` + all 76 unit tests pass). On-device validated: ~2-3s detection latency, ~630ms avg frame time. APK size: 84 MB.

## Target
Qualcomm SA8155P (Kryo 485 CPU-only). Android Automotive 14. Debug build. USB webcam.

## What it does
Captures USB webcam at 1fps, runs local ML inference on CPU, outputs JSON with 17 fields: passenger_count, driver_using_phone, driver_eyes_closed, driver_yawning, driver_distracted, driver_eating_drinking, dangerous_posture, child_present, child_slouching, risk_level, distraction_duration_s, ear_value, mar_value, head_yaw, head_pitch, driver_name, timestamp.

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
  → MobileFaceNet (ONNX Runtime C++, CPU EP) → 128-dim face embedding   ← every 5th frame
  → FaceStore (Kotlin) → cosine similarity matching → driver name        ← cached between runs
  → Merger (Kotlin) → risk scoring
  → Temporal Smoother (Kotlin) → 3-frame sliding window, 60% threshold
  → Audio Alerter (Android TextToSpeech + AudioTrack beep at 20s)     ← core path
  → JSON output (Logcat)                                               ← core path
  → FrameHolder.postResult() → OutputResult → Dashboard               ← fast UI path
  → Overlay Renderer (Kotlin) → bboxes, skeleton, face landmarks      ← optional UI path
  → FrameHolder.postFrame() → bitmap → ImageView preview              ← optional UI path
```

### Core Pipeline Isolation (Safety-Critical Requirement)
The inference → merge → smooth → audio alert → JSON log path is **safety-critical**. It must never be interrupted by UI features (overlay, preview, dashboard, notifications). All code changes must preserve these invariants:

1. **Execution order**: Core path completes before any UI rendering. Audio alerts and JSON logging must never depend on overlay success.
2. **Failure isolation**: UI operations (overlay rendering, FrameHolder, notification posting) must be wrapped in try-catch. A UI failure must never propagate into the core path.
3. **No shared serialization**: UI-only data (overlay persons/keypoints) must not cause core detection data loss if malformed. Currently mitigated by Gson's robustness, but noted as architectural debt — a future refactor should separate overlay data from the PoseResult JSON.
4. **No shared bitmap lifecycle**: FrameHolder must not recycle bitmaps that the Activity may be reading. TOCTOU on a recycled bitmap crashes the main thread, which kills the Service process.
5. **Activity crash safety**: MainActivity wraps all FrameHolder access in try-catch. An unhandled Activity exception would kill the shared process and the Service with it.
6. **Notification isolation**: Notification posting/cancellation in AudioAlerter is wrapped in try-catch. TTS queue and alert state updates must complete regardless of notification API failures.

### Camera Strategy
The Honda SA8155P BSP has `config.disable_cameraservice=true` and no External Camera HAL, so Camera2 API cannot see USB webcams. The app uses **V4L2 direct ioctl access** as the primary camera path, with Camera2 as a fallback.
- `V4l2CameraManager` (Kotlin) → `V4l2Camera` (C++/JNI) → `/dev/videoN`
- Scans `/dev/video0-63`, skips Qualcomm internal devices (`cam-req-mgr`, `cam_sync`)
- Configures YUYV capture, 2 mmap buffers (reduced from 4 for lower latency), converts YUYV→BGR in native code
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
| `yolov8n-pose-fp16.onnx` | ONNX FP16 | ~6.5 MB | FP32 export → `scripts/convert_fp16.py` |
| `yolov8n-fp16.onnx` | ONNX FP16 | ~6.5 MB | FP32 export → `scripts/convert_fp16.py` |
| `face_landmarker.task` | TFLite bundle | 3.6 MB | Google AI Edge (float16) |
| `mobilefacenet-fp16.onnx` | ONNX FP16 | ~6.5 MB | InsightFace buffalo_sc w600k_mbf → FP16 convert |

## Key Thresholds
- EAR < 0.21 → eyes closed
- MAR > 0.5 → yawning
- |yaw| > 30° or |pitch| > 35° → distracted
- YOLO confidence > 0.35, NMS IoU 0.45
- Phone: COCO class 67, Food/drink: classes 39-48
- Posture lean > 30°, Child slouch > 20°
- Head turn: nose offset > 30% of shoulder width
- Child: bbox height < 75% of driver
- Keypoint confidence threshold: 0.5
- Wrist crop: 200x200px
- Smoother: 3-frame window, 60% threshold, fast-clear on 2 consecutive high-EAR frames
- V4L2 reconnect: 3 consecutive failures triggers disconnect, backoff 2s→30s max
- Face recognition: cosine similarity > 0.5, every 5th frame, 512-dim embedding

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
- **Loud beep at 20s**: 1kHz sine wave, 2 seconds, via AudioTrack on USAGE_ASSISTANCE_SONIFICATION (same audio path as TTS). Separate `beepPlayed` flag, resets when distraction clears
- First frame: store state only, no announcement
- TTS retry: if init fails, schedules one retry after 3s via stored Handler; `ttsRetried` flag prevents loops; Handler callbacks cancelled in `close()` to prevent leak if service destroyed within 3s

### Face Recognition (C++ ONNX Runtime + Kotlin)
- **Model**: MobileFaceNet (w600k_mbf) FP16, 112x112 input, 512-dim embedding output, ~6.5 MB
- **Pipeline**: face landmarks → bbox with 20% padding → BGR crop → bilinear resize to 112x112 → BGR→RGB + CHW + normalize (pixel/127.5 - 1.0) → ONNX inference → L2-normalize 512-dim → cosine similarity scan
- **Frequency**: Every 5th inference frame (~5s at 1fps webcam cadence), cached between runs
- **Threshold**: cosine similarity ≥ 0.5 for positive match
- **Thread pinning**: 2 intra-op threads pinned to Gold core 5 (avoids PoseAnalyzer contention on 4-7)
- **Storage**: `faces.json` flat file in app internal storage, Gson serialization
- **Registration**: `FaceRegistrationActivity` — captures BGR crop from `FrameHolder.getCaptureData()`, computes embedding via temporary `FaceRecognizerBridge`, saves name+embedding to `FaceStore`
- **Pipeline isolation**: Recognition wrapped in try-catch; failure returns cached name. Core path never blocked.
- **Graceful degradation**: If model file missing, `FaceRecognizerBridge.nativePtr=0`, all calls return null, `driverName` stays null

### Distraction Duration
- Fields: phone, eyes, yawning, distracted, eating (NOT posture/child)
- Increment +1 per frame if any active, reset to 0 when all clear
- Injected into result after smoothing, before output

### Detection Overlay (OverlayRenderer)
- Renders in-place onto source bitmap (no 3.6MB copy per frame)
- Controlled by `Config.ENABLE_PREVIEW` flag (currently disabled for performance)
- **Person bounding boxes**: green=driver, blue=passengers, with "Driver 95%"/"Passenger 87%" labels (driver label replaced with recognized name when available)
- **COCO skeleton**: 16 bone connections between keypoints (confidence > 0.5)
- **Keypoint dots**: red circles at each confident keypoint
- **Face landmarks**: eye contours (green=open, red=closed), mouth outline (green=normal, orange=yawning), cyan nose tip
- **Metric labels**: EAR, MAR, Yaw, Pitch in top-left corner with dark background
- Pre-allocated Paint objects; ~3-5ms overhead on 1280x720

### Status Dashboard (MainActivity)
- **Decoupled from camera preview**: Dashboard reads from `FrameHolder.getLatestResult()` (result-only channel), camera preview reads from `FrameHolder.getLatest()` (bitmap channel). Dashboard updates at pipeline speed (~2-3s) regardless of preview rendering
- Compact top row: status text + "Faces" button + start/stop button
- AI status message bar with varied contextual messages
- Score arc + distraction-free streak + session timer
- Camera preview (ImageView, optional via ENABLE_PREVIEW)
- Dashboard panel (visible when monitoring):
  - Risk banner: color-coded (red=HIGH 32sp, orange=MEDIUM, green=LOW)
  - Driver name: "Driver: Name" (22sp bold, blue, hidden when unknown)
  - Info row: Passengers | Distraction timer (26sp bold)
  - Active detections: pipe-separated labels (Phone | Eyes Closed | Yawning | etc.)
  - EAR/MAR/Yaw/Pitch metrics: hidden (engineering data, still in layout as `gone`)
- Detection history ticker (marquee)
- Session summary dialog on stop
- Polls FrameHolder every 500ms
- Custom app icon: dark blue background (#1A237E) with white eye/monitoring symbol

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
│   │   ├── v4l2_camera, pose_analyzer, face_recognizer, yolo_utils, image_utils, jni_bridge
│   │   └── CMakeLists.txt
│   ├── kotlin/com/incabin/
│   │   ├── Config, InCabinService, V4l2CameraManager, CameraManager
│   │   ├── FaceAnalyzer, FaceRecognizerBridge, FaceStore, PoseAnalyzerBridge
│   │   ├── Merger, TemporalSmoother, AudioAlerter, OutputResult, NativeLib
│   │   ├── MainActivity, FaceRegistrationActivity
│   │   ├── OverlayRenderer, FrameHolder, ScoreArcView
│   └── res/             (layout, strings, notification icon, app icon)
├── app/src/test/kotlin/  (MergerTest, TemporalSmootherTest, OutputResultTest, FaceStoreTest)
└── build configs         (build.gradle.kts, settings.gradle.kts, etc.)
```

## Testing
- 76 unit tests (all passing):
  - MergerTest: 19 tests (risk scoring + merge logic)
  - TemporalSmootherTest: 20 tests (voting, face-gating, fast-clear, passenger mode, risk recomputation)
  - OutputResultTest: 29 tests (schema validation — valid/invalid payloads + driver_name)
  - FaceStoreTest: 8 tests (cosine similarity — identical, orthogonal, opposite, threshold, edge cases)
- On-device validated on Honda SA8155P (ALPSALPINE IVI-SYSTEM, Android 14, 8 CPUs, 7.6 GB RAM) with Logitech C270 via V4L2

## Performance Budget
On-device measured: **avg 630ms/frame** on Kryo 485 (FP32). Pose ~550ms, Face ~80ms. Frame cadence ~0.85s. Detection latency ~2-3s (smoother window=3 + webcam framerate). Memory stable at heap=19MB, native=208MB.

### On-Device Performance Tuning
- **INFERENCE_INTERVAL_MS**: Reduced from 1000ms to 100ms (webcam at ~1fps is the actual bottleneck, not sleep)
- **SMOOTHER_WINDOW**: Reduced from 5 to 3 frames for faster detection response (need 2/3 majority instead of 3/5)
- **HEAD_PITCH_THRESHOLD**: Increased from 25° to 35° to eliminate false `driver_distracted` from camera mounting angle
- **V4L2 mmap buffers**: Reduced from 4 to 2 to minimize frame latency
- **ENABLE_PREVIEW**: Flag to disable overlay rendering + bitmap posting. Preview rendering was causing ~10s perceived delay due to bitmap GC pressure on SA8155P. With preview disabled, audio alerts fire in ~2-3s
- **Decoupled result delivery**: `FrameHolder.postResult()` delivers OutputResult to dashboard immediately, independent of bitmap rendering. Dashboard and audio alerts update at pipeline speed regardless of preview state
- **In-place overlay rendering**: OverlayRenderer draws directly on source bitmap instead of creating 3.6MB ARGB_8888 copy per frame

### Performance Optimizations
- **FP16 models**: ONNX models converted from FP32 to FP16 via `scripts/convert_fp16.py`. Halves model memory (~12MB → ~6.5MB each) and reduces memory bandwidth. SA8155P Kryo 485 has `asimdhp` (ARM FP16 hardware support). ORT handles FP16↔FP32 cast at I/O boundary automatically.
- **Thread pinning to Gold+Prime cores**: ONNX Runtime intra-op threads pinned to cores 4-7 (2131-2419MHz) via `session.intra_op_thread_affinities`. Prevents inference threads from landing on Silver cores (1785MHz).
- **BGR→Bitmap in C++/JNI**: Pixel conversion (BGR→ARGB, 921K pixels) moved from Kotlin loop to native C++ via `nativeBgrToArgbPixels()`. Pre-allocated `IntArray` buffer eliminates 3.5 MB allocation per frame. Saves ~15-25ms/frame.
- **solvePnP Mat pre-allocation**: 7 OpenCV Mat objects (`cameraMat`, `distCoeffs`, `modelPoints3d`, `imagePoints2d`, `rvec`, `tvec`, `rotationMatrix`) are class members in FaceAnalyzer, initialized once and reused. Camera matrix set on first frame. Eliminates 14 JNI crossings and 7 native mallocs per frame. Saves ~1-2ms/frame.
- **Parallel service init**: FaceAnalyzer (MediaPipe), PoseAnalyzerBridge (ONNX Runtime), and FaceRecognizerBridge (MobileFaceNet) model loading run concurrently via `CountDownLatch`. Startup is `max(face, pose, facerec)` instead of sequential. Saves ~2s on startup.

## Pre-Deployment Hardening
- **TTS retry**: One-time retry after 3s if TextToSpeech init fails (AudioAlerter); stored Handler cancelled in `close()` to prevent TTS leak if service destroyed within 3s
- **Native lib safety**: `System.loadLibrary("incabin")` wrapped in try-catch in NativeLib and PoseAnalyzerBridge; `loaded`/`nativeLoaded` flags prevent JNI calls if lib missing
- **Thread safety — pipeline lock**: `ReentrantLock` in `InCabinService` guards `processFrame()` (camera thread, `tryLock`) vs `onDestroy()` (main thread, `lock`), preventing JNI use-after-free on native PoseAnalyzer and V4L2Camera pointers
- **Thread safety — atomic counter**: `distractionDurationS` uses `AtomicInteger` (`incrementAndGet`/`set`) instead of `@Volatile` read-modify-write
- **Bitmap lifecycle safety**: When `ENABLE_PREVIEW=false`, bitmap is recycled immediately after face analysis. When preview enabled, bitmap ownership transfers to FrameHolder. `OverlayRenderer.render()` draws in-place with try-catch returning partial overlay on error
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
- **Pipeline decoupling**: Core path (inference → merge → smooth → audio alerts → JSON log) runs before and independently of UI path (overlay rendering → FrameHolder). Overlay is wrapped in its own try-catch; a failure in rendering never disrupts detection or alerts
- **Notification isolation**: `postAlertNotification()` and `notificationManager.cancel()` in AudioAlerter wrapped in try-catch; TTS message queue and `prevState` update always complete regardless of notification API failures
- **FrameHolder crash safety**: Old bitmaps are not recycled in `postFrame()` — left for GC finalizer to avoid TOCTOU race where Activity reads a bitmap recycled by the service thread (recycled-bitmap exception on UI thread would kill the entire process including InCabinService)
- **Activity preview safety**: `previewPoller` in MainActivity wraps all FrameHolder access and bitmap operations in try-catch to prevent UI exceptions from crashing the shared process
- **Face recognition isolation**: `recognizeDriver()` wrapped in try-catch in `processFrame()`; failure returns cached name. Recognition uses raw BGR bytes (no bitmap lifecycle risk). `FaceStore` is `@Synchronized`. `FaceRegistrationActivity` creates its own `FaceRecognizerBridge` instance; activity crash doesn't affect service

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
adb shell pm grant --user 10 com.incabin android.permission.POST_NOTIFICATIONS

# 6. Start service and monitor
adb shell am start-foreground-service -a com.incabin.START com.incabin/.InCabinService
adb logcat -s 'InCabin:*' 'InCabin-V4L2:*' 'InCabin-JNI:*' 'InCabin-Camera:*' 'AudioAlerter:*'
```

### Generic Android (Camera2 fallback)
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant com.incabin android.permission.CAMERA
adb shell pm grant com.incabin android.permission.POST_NOTIFICATIONS
adb shell am start-foreground-service -a com.incabin.START com.incabin/.InCabinService
adb logcat -s 'InCabin:*' 'InCabin-Camera:*' 'AudioAlerter:*'
```

### Known Honda BSP Quirks
- **ODK hook module**: `odk_hook_module` blocks UVC driver binding. Must `rmmod` each boot before webcam use.
- **Camera service disabled**: `config.disable_cameraservice=true` means Camera2 API sees no cameras. V4L2 is the only path.
- **User 10**: App runs under Android user 10 (not user 0). Use `--user 10` for `pm grant`.
- **Device permissions**: `/dev/video*` nodes are `660 system:camera`. App needs `chmod 666` to open them via V4L2. Resets on webcam reconnect.
- **SELinux**: Permissive on this BSP (no SELinux blocks).
