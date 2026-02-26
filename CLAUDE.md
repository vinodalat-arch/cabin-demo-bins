# In-Cabin AI Perception

## Status
Implementation complete with pre-deployment hardening, architectural hardening pass, on-device performance tuning, premium UI redesign, face recognition, multi-platform support, automated camera setup, runtime preview toggle, premium audio alerter redesign, detection accuracy fine-tuning, WiFi camera (MJPEG) support, user flow documentation, IVI deployment robustness hardening, seat-side driver identification with face alignment and driver-absent detection, settings/status UI separation with hidden settings overlay (5-tap gesture), emulator camera support, independent face registration (own camera + FaceDetectorLite), multi-modal IVI alert orchestrator with 5-level escalation and vehicle channel integration, and remote VLM inference mode (HTTP polling to laptop-hosted VLM server with configurable FPS). Build verified (`assembleDebug` + all 583 unit tests pass). On-device validated: ~2-3s detection latency, ~630ms avg frame time. APK size: 84 MB.

## Target
Qualcomm SA8155P / SA8295P (Kryo 485/585 CPU-only). Android Automotive 14. Debug build. USB webcam. Single APK supports both platforms + generic Android + Android emulator (Mac webcam via Camera2).

## What it does
Two inference modes (selectable at runtime):
- **Local mode**: Captures USB webcam at configurable FPS (1-3), runs on-device ML inference on CPU
- **Remote VLM mode**: Polls a laptop-hosted VLM server (Qwen2.5-VL) via HTTP at configurable FPS (1-3), no local camera or model loading needed

Both modes output JSON with 18 fields: passenger_count, driver_detected, driver_using_phone, driver_eyes_closed, driver_yawning, driver_distracted, driver_eating_drinking, dangerous_posture, child_present, child_slouching, risk_level, distraction_duration_s, ear_value, mar_value, head_yaw, head_pitch, driver_name, timestamp.

## Full Specification
**See `SPEC.md`** for complete implementation details including every algorithm, formula, landmark index, threshold, tensor shape, postprocessing step, and unit test specifications. That document is the single source of truth for this project.

## Architecture
```
LOCAL MODE (default):
  USB Webcam (default)
    → V4L2 ioctl (/dev/videoN, YUYV 1280x720, mmap) → YUYV→BGR (C++ via JNI)
      [fallback: Camera2 ImageReader → YUV_420_888 → BT.601 YUV→BGR]
  WiFi Camera (optional, via Settings)
    → MjpegCameraManager → HTTP GET → multipart/x-mixed-replace → JPEG decode → ARGB→BGR
  → YOLOv8n-pose (ONNX Runtime C++, CPU EP) → posture, child, passenger count
  → YOLOv8n detection (ONNX Runtime C++, CPU EP) → phone, food/drink in driver ROI
  → MediaPipe FaceLandmarker (Kotlin, tasks-vision SDK) → EAR, MAR, solvePnP head pose
  → MobileFaceNet (ONNX Runtime C++, CPU EP) → 512-dim face embedding   ← every 5th frame
  → FaceStore (Kotlin) → cosine similarity matching → driver name        ← cached between runs
  → Merger (Kotlin) → risk scoring

REMOTE VLM MODE (optional):
  → VlmClient (HTTP polling) → laptop VLM server /api/detect → JSON OutputResult
  → distraction_duration_s computed on-device (safety-critical)

SHARED PIPELINE (both modes):
  → Temporal Smoother (Kotlin) → 3-frame sliding window, 60% threshold
  → Alert Orchestrator → AudioAlerter (TTS) + VehicleChannelManager (VHAL)
  → JSON output (Logcat)                                               ← core path
  → FrameHolder.postResult() → OutputResult → Dashboard               ← fast UI path
  → Overlay Renderer (Kotlin) → bboxes, skeleton, face landmarks      ← optional UI path (local only)
  → FrameHolder.postFrame() → bitmap → ImageView preview              ← optional UI path (local only)
```

### Core Pipeline Isolation (Safety-Critical Requirement)
The inference → merge → smooth → audio alert → JSON log path is **safety-critical**. It must never be interrupted by UI features (overlay, preview, dashboard, notifications). All code changes must preserve these invariants:

1. **Execution order**: Core path completes before any UI rendering. Audio alerts and JSON logging must never depend on overlay success.
2. **Failure isolation**: UI operations (overlay rendering, FrameHolder, notification posting) must be wrapped in try-catch. A UI failure must never propagate into the core path.
3. **No shared serialization**: UI-only data (overlay persons/keypoints) must not cause core detection data loss if malformed. Currently mitigated by Gson's robustness, but noted as architectural debt — a future refactor should separate overlay data from the PoseResult JSON.
4. **No shared bitmap lifecycle**: FrameHolder must not recycle bitmaps that the Activity may be reading. TOCTOU on a recycled bitmap crashes the main thread, which kills the Service process.
5. **Activity crash safety**: MainActivity wraps all FrameHolder access in try-catch. An unhandled Activity exception would kill the shared process and the Service with it.
6. **Notification isolation**: Notification posting/cancellation in AudioAlerter is wrapped in try-catch. TTS queue and alert state updates must complete regardless of notification API failures.

### Multi-Platform Support
Single APK supports SA8155, SA8295, generic Android, and Android emulator. `PlatformProfile.detect()` runs once at startup, reading `Build.MANUFACTURER`, `Build.HARDWARE`, `Build.FINGERPRINT`, and `Build.SOC_MODEL` to select the appropriate tuning profile. Emulator detection (`isEmulator()`) checks for `ranchu`/`goldfish` hardware or `generic` in fingerprint — logs "Running on emulator — using Camera2 with host webcam" and maps to GENERIC profile:

| Setting | SA8155 | SA8295 | Generic / Emulator |
|---|---|---|---|
| Pose threads / affinity | 4 / cores 4-7 | 4 / OS-scheduled | 4 / OS-scheduled |
| Face rec threads / affinity | 2 / core 5 | 2 / OS-scheduled | 2 / OS-scheduled |
| Audio usage | ASSISTANCE_SONIFICATION | ALARM | ALARM |
| Camera strategy | V4L2 first | V4L2 first | Camera2 first |
| Automated setup (ODK, chmod, pm grant) | Yes | Yes | No |
| Boot auto-start | Yes | Yes | No |

### Camera Strategy
Camera strategy is selected by `PlatformProfile.cameraStrategy`:
- **V4L2_FIRST** (SA8155, SA8295): `V4l2CameraManager` → `V4l2Camera` (C++/JNI) → `/dev/videoN`. Falls back to Camera2 if no V4L2 device found.
- **CAMERA2_FIRST** (generic): Goes straight to Camera2 API.
- **WiFi Camera (MJPEG)**: When `Config.WIFI_CAMERA_URL` is set (non-blank), overrides USB camera. `MjpegCameraManager` connects via HTTP, parses `multipart/x-mixed-replace` boundary stream, decodes JPEG frames, converts ARGB→BGR. Status posted to `FrameHolder.CameraStatus`. Falls back to NOT_CONNECTED on connection failure.
- V4L2 path scans `/dev/video0-63`, skips Qualcomm internal devices (`cam-req-mgr`, `cam_sync`)
- Configures YUYV capture, 2 mmap buffers (reduced from 4 for lower latency), converts YUYV→BGR in native code
- USB camera is the default; WiFi camera is opt-in via UI settings

### Automated Camera Setup (DeviceSetup)
On automotive BSPs (`isAutomotiveBsp`), the Start button triggers a multi-stage setup flow before monitoring:
1. **ODK module unload**: `su -c rmmod odk_hook_module` (non-fatal if already removed)
2. **Camera polling**: Polls for V4L2 device every 2s, up to 2 minutes
3. **Permission setup**: `chmod 666 /dev/video*`, `pm grant` for CAMERA and POST_NOTIFICATIONS
4. **Ready**: Transitions to standard monitoring start
- Runs on background thread with cancellation support
- Camera status indicator shows progress (Cam: None → ... → Ready → Active)
- On app restart, skips setup if camera is already available
- On failure, allows manual start (doesn't block the user)
- On generic Android, skips entirely and goes straight to permission dialogs

### Webcam Preview Toggle
Runtime toggle in settings panel (hidden by default, 5-tap to open) enables/disables camera preview:
- **Off (default)**: No bitmap creation, no overlay rendering, no GC pressure. Core pipeline unaffected.
- **On**: Full overlay (bboxes, skeleton, landmarks, metrics) rendered on camera frames and displayed in ImageView.
- State persisted across app restarts via SharedPreferences.
- `Config.ENABLE_PREVIEW` is `@Volatile var` for cross-thread visibility (UI thread writes, service thread reads).

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
- EAR < 0.21 → eyes closed (fallback); after 10-frame calibration: EAR < baseline × 0.65
- MAR > 0.5 → yawning
- |yaw| > 30° or |pitch| > 35° → distracted (fallback); after 10-frame calibration: |pitch - baseline| > 25°
- Yaw/pitch smoothed via 3-frame moving average before thresholding
- YOLO confidence > 0.35 (pose), Phone > 0.45, Food/drink > 0.50, NMS IoU 0.45
- Phone: COCO class 67, Food/drink: classes 39-48
- Posture lean > 30°, Child slouch > 20°
- Head turn: nose offset > 30% of shoulder width
- Child: bbox height < 65% of driver
- Keypoint confidence threshold: 0.5
- Wrist crop: 200x200px
- Smoother: 3-frame window, 60% threshold, fast-clear on 2 consecutive high-EAR frames
- Sustained detection: eyes 2 frames, yawning 2 frames, distracted 2 frames, eating 2 frames, posture 2 frames, hands_off 3 frames, child slouch 3 frames
- V4L2 reconnect: 3 consecutive failures triggers disconnect, backoff 2s→30s max
- Face recognition: cosine similarity > 0.5, every 5th frame, 512-dim embedding

## Risk Scoring
```
phone=3, eyes=3, hands_off=3, yawn=2, distracted=2, posture=2, eating=1, slouch=1
score >= 3 → high, >= 1 → medium, else → low
```

## Detection Algorithms Summary

### Face Analysis (Kotlin + MediaPipe + OpenCV)
- **EAR**: Right eye [33,160,158,133,153,144], Left eye [362,385,387,263,373,380]. Formula: (dist(p2,p6)+dist(p3,p5))/(2*dist(p1,p4))
- **EAR auto-baseline**: First 10 frames with face detected accumulate EAR samples; baseline = mean. After calibration, eyes closed = `ear < baseline × 0.65` (adapts to individual eye geometry). Before calibration, falls back to fixed `ear < 0.21`
- **MAR**: Landmarks 13,14,61,291,0,17. Formula: (dist(top,bottom)+dist(upper_mid,lower_mid))/(2*dist(left,right))
- **Head Pose**: solvePnP with landmarks [1,152,33,263,61,291], 3D model points, Euler angle extraction
- **Angle smoothing**: Yaw and pitch values smoothed via 3-frame moving average (`ArrayDeque`) before boolean thresholding. Eliminates solvePnP single-frame spikes
- **Pitch auto-baseline**: First 10 frames accumulate smoothed pitch; baseline = mean. After calibration, distracted = `|pitch - baseline| > 25°` (eliminates camera mounting angle bias). Before calibration, falls back to fixed `|pitch| > 35°`
- **Baseline lifecycle**: FaceAnalyzer is recreated each monitoring session — baselines reset automatically on stop/start
- **Face-to-driver spatial validation**: When driver bbox is available from pose analysis, nose landmark (index 1) must fall within the driver bbox expanded by 20% margin. Face outside driver region → returns `FaceResult.NO_FACE`. Pure `isFaceInDriverRegion()` companion function for testability
- **Import note**: `FaceLandmarkerOptions` is a nested class — import as `FaceLandmarker.FaceLandmarkerOptions`

### Pose Analysis (C++ + ONNX Runtime)
- **YOLOv8n-pose**: Output [1,56,8400] → transpose → filter conf>0.35 → NMS IoU 0.45
- **YOLOv8n detect**: Output [1,84,8400] → transpose → argmax class scores → filter → NMS. Per-class confidence thresholds threaded through `runDetectModel` → `parseDetectOutput`
- **Driver**: Largest bbox by area on the driver's side of the frame (seat side configurable via `Config.DRIVER_SEAT_SIDE`). If no person on driver side, `driver_detected=false` and driver-specific detections (phone, posture, eating, face analysis) are skipped
- **Posture**: 4 checks (torso lean, head droop, head turn, face not visible)
- **Phone**: 2-strategy (driver ROI + 20% pad → wrist crops 200x200px), confidence 0.45 (higher than default 0.35 to reduce false positives)
- **Food/drink**: Driver ROI + 20% pad, COCO classes 39-48, confidence 0.50
- **Child**: bbox height ratio < 0.75, slouch check at 20°

### Temporal Smoother
- Standard fields: majority voting over buffer
- Face-gated fields: eyes→ear_value, yawning→mar_value, distracted→head_yaw (skip null frames)
- Sustained detection thresholds (streak counters): eyes 2 frames, yawning 2 frames, distracted 2 frames, eating 2 frames, posture 2 frames, hands_off 3 frames, child slouch 3 frames. Majority voting must agree AND streak must reach min_frames before detection fires
- Fast-clear: 2 consecutive frames with ear >= 0.21 → immediately clear eyes_closed
- Passenger count: mode of buffer
- Risk: recomputed from smoothed booleans

### Audio Alerter (Premium Redesign)
- **Priority tiers**: CRITICAL (phone, eyes_closed) > WARNING (yawning, distracted, eating, posture, slouching) > INFO (all-clear, duration milestones)
- **Bounded priority queue**: `ArrayBlockingQueue(3)` — when full, drain → sort by priority → keep top messages, drop lower priority
- **Staleness check** (worker thread): drops messages older than 4s (`ALERT_STALENESS_MS`) or whose danger already resolved (reads `FrameHolder.getLatestResult()`)
- **Per-danger cooldown**: 10s (`ALERT_COOLDOWN_MS`) per danger type. Re-announcement suppressed within cooldown. All cooldowns clear on all-clear transition
- **All-clear flush**: Drains queue, stops TTS mid-sentence (`tts.stop()`), speaks "All clear" immediately, clears cooldown + escalation maps
- **Escalation ladder** (replaces fixed [5,10,20] thresholds):
  - 10s: "Still distracted, 10 seconds" (WARNING)
  - 20s: [1kHz beep 1s] → "Warning. Distracted 20 seconds" (CRITICAL)
  - 30s+: beep repeats every 10s with duration update
- **Beep-TTS coordination**: Single worker thread — beep plays inline (1s, blocking) → 200ms gap → TTS speak. No overlap, no separate Beep-Player thread
- **Shorter messages**: "Phone", "Eyes closed", "Yawning", "Distracted", "Eating", "Posture", "Child slouching" (Japanese equivalents follow same brevity)
- **Multiple simultaneous dangers**: Joined as single message sorted by priority — "Phone. Eyes closed" (CRITICAL parts first, then WARNING)
- **Audio routing**: Platform-specific — `USAGE_ASSISTANCE_SONIFICATION` on SA8155 (Honda BSP quirk), `USAGE_ALARM` on SA8295 and generic Android. Parameterized via `audioUsage` constructor parameter
- First frame: store state only, no announcement
- TTS retry: if init fails, schedules one retry after 3s via stored Handler; `ttsRetried` flag prevents loops; Handler callbacks cancelled in `close()` to prevent leak if service destroyed within 3s
- **Testability**: `buildAlerts()` and `buildEscalationAlert()` are companion functions with no Android dependencies — fully unit-testable

### Face Recognition (C++ ONNX Runtime + Kotlin)
- **Model**: MobileFaceNet (w600k_mbf) FP16, 112x112 input, 512-dim embedding output, ~6.5 MB
- **Pipeline**: face landmarks → bbox with 20% padding → BGR crop → bilinear resize to 112x112 → BGR→RGB + CHW + normalize (pixel/127.5 - 1.0) → ONNX inference → L2-normalize 512-dim → cosine similarity scan
- **Frequency**: Every 5th inference frame (~5s at 1fps webcam cadence), cached between runs
- **Threshold**: cosine similarity ≥ 0.5 for positive match
- **Thread pinning**: 2 intra-op threads pinned to Gold core 5 (avoids PoseAnalyzer contention on 4-7)
- **Storage**: `faces.json` flat file in app internal storage, Gson serialization
- **Registration**: `FaceRegistrationActivity` — fully self-contained with own camera and face detection (see below)
- **Pipeline isolation**: Recognition wrapped in try-catch; failure returns cached name. Core path never blocked.
- **Graceful degradation**: If model file missing, `FaceRecognizerBridge.nativePtr=0`, all calls return null, `driverName` stays null

### Face Registration (Independent Camera)
- **Self-contained**: `FaceRegistrationActivity` owns its own camera — does NOT depend on InCabinService or FrameHolder
- **Camera selection**: Uses `PlatformProfile.detect()` to choose V4L2, Camera2, or MJPEG (same strategy as service). Camera starts in `onResume()`, stops in `onPause()`
- **Camera startup retry**: 5 attempts × 1s delay to handle race with service teardown releasing the USB device
- **Face detection**: `FaceDetectorLite` — lightweight MediaPipe FaceLandmarker in `IMAGE` mode (no timestamps, no EAR/MAR/solvePnP/calibration). Returns bbox with 20% padding
- **Live preview**: BGR→Bitmap with green face bbox overlay, pushed directly from camera callback (no polling)
- **Capture flow**: BGR face crop extracted inline from `latestBgrData` using `FaceDetectorLite.FaceDetection` padded bbox → `FaceRecognizerBridge.computeEmbedding()` → quality gate (pairwise cosine ≥ 0.7) → average → save via FaceStore
- **Monitoring stops first**: MainActivity stops InCabinService before launching registration (only one USB camera consumer at a time). No session summary dialog shown. User taps START to resume after returning
- **MJPEG safety**: `onBgrFrame()` copies BGR data defensively to avoid data race with MjpegCameraManager's reused internal buffer
- **Thread safety**: Camera retry thread cancelled via `@Volatile` flag on `onPause()`, preventing background camera reopen

### Remote VLM Inference (VlmClient)
- **Mode**: `Config.INFERENCE_MODE` = "local" (default) or "remote". Mode locked at service start, not changeable during monitoring
- **VlmClient**: HTTP polling client. Polls `{VLM_SERVER_URL}/api/detect` at `Config.inferenceIntervalMs()` interval
- **Server**: `scripts/vlm_server.py` — FastAPI server running Qwen2.5-VL-7B on laptop GPU. Mock mode available for testing
- **Launcher GUI**: `scripts/vlm_launcher.py` — tkinter GUI to manage vlm_server.py (start/stop, health check, test query, log viewer)
- **Pipeline**: VlmClient → JSON parse → OutputResult → smoother → alerts → dashboard. No camera, no local models
- **Confidence scoring**: VLM returns per-detection confidence (0.0-1.0), server applies per-detection thresholds (phone=0.6, eyes=0.5, etc.)
- **Safety**: `distraction_duration_s` always computed on-device regardless of mode
- **Camera status**: Shows "VLM: Connecting/Active/Lost" in remote mode
- **Inference badge**: Shows "LOCAL" (blue) or "VLM" (purple) pill during monitoring, captured at start time
- **Health check**: One-shot `VlmClient.checkHealthOnce()` on URL save and monitoring start (advisory toast)
- **Watchdog**: `restartPipeline()` is mode-aware — restarts VlmClient in remote mode
- **Activity safety**: `@Volatile isActivityDestroyed` flag guards background health check threads
- **Parsing**: `parseDetectResponse()` pure companion function — 13 unit tests
- **Error handling**: HTTP errors post LOST/NOT_CONNECTED status, exponential backoff on repeated failures

### Multi-Modal Alert Orchestrator
- **AlertOrchestrator**: Wraps `AudioAlerter` (unchanged) + `VehicleChannelManager`. Called from `InCabinService` instead of direct `AudioAlerter.checkAndAnnounce()`
- **5-level escalation**: L1 Nudge (0s) → L2 Warning (5s) → L3 Urgent (10s) → L4 Intervention (20s) → L5 Emergency (30s+)
- **Per-detection caps**: phone/eyes/hands_off/distracted→L5, yawning→L4, eating/posture/slouch→L3
- **6 VHAL channels**: CabinLight, SeatHaptic, SeatThermal, SteeringHeat, Window, ADAS
- **Car API**: Accessed via reflection (no compile-time dependency) — graceful no-op on generic Android
- **Speed-gating**: L1 suppressed when PARKED
- **Platform gate**: `PlatformProfile.enableVehicleChannels` — true on SA8155/SA8255/SA8295, false on GENERIC
- **Manifest**: `android.car` uses-library (required=false), 9 car permissions, `distractionOptimized=true` on all activities (required for vehicle-moving state)

### Distraction Duration
- Fields: phone, eyes, yawning, distracted, eating (NOT posture/child)
- Increment +1 per frame if any active, reset to 0 when all clear
- Injected into result after smoothing, before output

### Detection Overlay (OverlayRenderer)
- Renders in-place onto source bitmap (no 3.6MB copy per frame)
- Controlled by `Config.ENABLE_PREVIEW` flag (runtime toggle via UI button, persisted in SharedPreferences, default off)
- **Person bounding boxes**: green=driver, blue=passengers, with "Driver 95%"/"Passenger 87%" labels (driver label replaced with recognized name when available)
- **COCO skeleton**: 16 bone connections between keypoints (confidence > 0.5)
- **Keypoint dots**: red circles at each confident keypoint
- **Face landmarks**: eye contours (green=open, red=closed), mouth outline (green=normal, orange=yawning), cyan nose tip
- **Metric labels**: EAR, MAR, Yaw, Pitch in top-left corner with dark background
- Pre-allocated Paint objects; ~3-5ms overhead on 1280x720

### Status Dashboard (MainActivity) — Luxury IVI Three-Zone Layout
- **Decoupled from camera preview**: Dashboard reads from `FrameHolder.getLatestResult()` (result-only channel), camera preview reads from `FrameHolder.getLatest()` (bitmap channel). Dashboard updates at pipeline speed (~2-3s) regardless of preview rendering
- **Three-zone horizontal layout** optimized for 1920x720 automotive landscape display:
  - **Left panel (100dp)**: Slim status strip — score arc (64dp, animated), streak/session timers (TextMicro condensed), camera status dot (tap for tooltip), Start/Stop button. No settings buttons visible
  - **Center (flexible)**: Camera preview (or idle branding when not monitoring), AI status message overlay with gradient scrim at bottom. Expanded to fill space recovered from slim left panel
  - **Right panel (380dp)**: Risk pill, driver name, passenger count, distraction timer, detection labels, ticker (visible during monitoring only)
- **Settings overlay** (hidden by default):
  - **5-tap gesture**: Tap root layout 5 times within 3s to open. Visual hint (flash) on 4th tap
  - **Settings panel (340dp)**: Slides in from left edge between left panel and center. Background: `surface_elevated` (#1A1B24) at 95% opacity, 12dp rounded right corners
  - **Scrim**: 50% black overlay on center zone, clickable to dismiss
  - **Controls**: Segmented buttons (LEFT/RIGHT seat side, EN/JA language, LOCAL/REMOTE inference mode, 1/2/3 FPS), ON/OFF toggle buttons (audio, preview), action buttons (WiFi Camera dialog, VLM Server URL dialog, Manage Faces activity), passenger detail (minimal/detailed), ASIMO mascot size (S/M/L), bottom widget (none/stats/tips)
  - **Close**: Close button (x), tap scrim, or 5-tap again
  - **Animation**: Slide right 300ms decelerate (open), slide left 200ms accelerate (close)
- **Design system**: Centralized color palette (`res/values/colors.xml`), typography styles (`res/values/styles.xml`), spacing system (`res/values/dimens.xml`)
- **Color palette**: `#0A0A0F` background, `#12131A` surface, `#E8E9ED` text, `#5B8DEF` accent, `#2ECC71` safe, `#F39C12` caution, `#E74C3C` danger, `#F1C40F` gold
- **Typography**: 6 styles from TextDisplay (48sp bold) to TextMicro (11sp), system Roboto
- **Animations** (framework only, no libraries):
  - Score arc: `ValueAnimator` 400ms decelerate with glow via `BlurMaskFilter`
  - Risk pill: `ValueAnimator.ofArgb()` 500ms color transitions between states
  - Detection labels: fade in 200ms / fade out 300ms (vertical stack with colored dots)
  - AI status: crossfade 150ms (alpha out → set text → alpha in)
  - Panel show/hide: alpha animate 300ms/200ms
  - Settings panel: translateX slide 300ms/200ms with scrim alpha fade
- Risk pill: compact rounded-corner pill (12dp radius), states: LOW/MEDIUM/HIGH/NO OCCUPANTS
- Detection labels: vertical stack, `● Phone Detected` with danger/caution colored dot, "All Clear" in safe color when empty
- Ticker: static last 3 events on separate lines (replaces marquee)
- Footer: 11sp `text_muted`, "KPIT Technologies, India"
- Idle state: slim left status strip visible, center shows "Honda Smart Cabin" branding, right panel hidden
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
│   │   ├── Config, ConfigPrefs, InCabinService, V4l2CameraManager, CameraManager, MjpegCameraManager
│   │   ├── FaceAnalyzer, FaceDetectorLite, FaceRecognizerBridge, FaceStore, PoseAnalyzerBridge
│   │   ├── Merger, TemporalSmoother, AudioAlerter, OutputResult, NativeLib
│   │   ├── AlertOrchestrator, VehicleChannelManager, VlmClient
│   │   ├── PlatformProfile, DeviceSetup, BootReceiver
│   │   ├── PipelineWatchdog, MemoryPolicy, CrashLog
│   │   ├── MainActivity, FaceRegistrationActivity
│   │   ├── OverlayRenderer, FrameHolder, ScoreArcView
│   └── res/             (layout, strings, colors, dimens, styles, drawables, notification icon, app icon)
├── app/src/test/kotlin/  (AudioAlerterTest, MergerTest, TemporalSmootherTest, OutputResultTest, FaceStoreTest, PlatformProfileTest, FlowMonitoringTest, FlowEscalationTest, FlowConfigToggleTest, FlowFaceRecognitionTest, FlowWifiCameraTest, FlowDriverIdentificationTest, FlowVlmRemoteTest, FlowMultiModalEscalationTest, VlmClientTest, ConfigConstantsTest, EscalationLevelTest, AlertOrchestratorTest, VehicleChannelManagerTest, ChannelPropertyTest, BootReceiverTest, PipelineWatchdogTest, MemoryPolicyTest, CrashLogTest, ServiceHealthTest, MjpegReconnectTest, InferenceErrorTest)
├── scripts/              (vlm_server.py, vlm_launcher.py, requirements.txt, convert_fp16.py)
└── build configs         (build.gradle.kts, settings.gradle.kts, etc.)
```

## Testing
- 583 unit tests (all passing):
  - AudioAlerterTest: 35 tests (onset, priority ordering, all-clear flush, cooldown, escalation ladder, edge cases, Japanese locale, DangerSnapshot)
  - AlertOrchestratorTest: 30 tests (escalation levels, vehicle channel activation, per-detection caps, speed gating)
  - ConfigConstantsTest: 33 tests (all Config constant values match documented spec)
  - OutputResultTest: 29 tests (schema validation — valid/invalid payloads + driver_name)
  - PlatformProfileTest: 28 tests (SA8155/SA8295/generic detection, profile values, audio usage, camera strategy, automotive BSP flag)
  - TemporalSmootherTest: 23 tests (voting, face-gating, fast-clear, sustained thresholds incl. yawning min_frames, passenger mode, risk recomputation)
  - VehicleChannelManagerTest: 20 tests (VHAL property mapping, channel activation/deactivation, reflection-based Car API)
  - MergerTest: 19 tests (risk scoring + merge logic)
  - FlowDriverIdentificationTest: 16 tests (seat-side selection, face region validation, driver_detected schema/merger/smoother, config toggle)
  - VlmClientTest: 13 tests (parseDetectResponse JSON→OutputResult, health check, error handling)
  - FlowMonitoringTest: 12 tests (full pipeline chain — mergeResults → smooth → validate for detection sequences)
  - FlowEscalationTest: 12 tests (escalation ladder timing, cooldown, multi-danger, all-clear reset, Japanese messages)
  - FlowMultiModalEscalationTest: 12 tests (L1-L5 escalation with vehicle channels, per-detection caps, speed gating)
  - EscalationLevelTest: 10 tests (level progression, duration thresholds, per-detection max level)
  - FlowVlmRemoteTest: 10 tests (VLM pipeline end-to-end: polling → parse → smooth → validate)
  - ChannelPropertyTest: 8 tests (VHAL property IDs, pulse durations, channel enable/disable)
  - FlowConfigToggleTest: 8 tests (Config defaults, toggle state, language effects on alerts, smoother/risk config)
  - FlowFaceRecognitionTest: 8 tests (driver name schema, cosine similarity matching, pipeline survival, JSON round-trip)
  - FaceStoreTest: 8 tests (cosine similarity — identical, orthogonal, opposite, threshold, edge cases)
  - CrashLogTest: 7 tests (formatLine format, field inclusion, shouldRotate boundary cases)
  - FlowWifiCameraTest: 6 tests (Config.WIFI_CAMERA_URL state management, priority logic)
  - PipelineWatchdogTest: 6 tests (isStalled logic — not started, within timeout, beyond timeout, boundary, heartbeat reset)
  - MemoryPolicyTest: 5 tests (decideAction at various trim levels — no action, low, critical, high, zero)
  - BootReceiverTest: 4 tests (shouldAutoStart for SA8155, SA8255, SA8295, GENERIC)
  - ServiceHealthTest: 3 tests (heartbeat age default, clear reset, isServiceRunning default)
  - MjpegReconnectTest: 3 tests (nextBackoffDelay doubling, cap at max, stays at max)
  - InferenceErrorTest: 2 tests (shouldReinitialize at threshold, below threshold)
- On-device validated on Honda SA8155P (ALPSALPINE IVI-SYSTEM, Android 14, 8 CPUs, 7.6 GB RAM) with Logitech C270 via V4L2

## Performance Budget
On-device measured: **avg 630ms/frame** on Kryo 485 (FP32). Pose ~550ms, Face ~80ms. Frame cadence ~0.85s. Detection latency ~2-3s (smoother window=3 + webcam framerate). Memory stable at heap=19MB, native=208MB.

### On-Device Performance Tuning
- **INFERENCE_FPS**: Configurable 1/2/3 FPS via settings (shared between local and VLM modes). `Config.inferenceIntervalMs()` computes `1000/fps` dynamically. Webcam at ~1fps is the actual bottleneck in local mode
- **SMOOTHER_WINDOW**: Reduced from 5 to 3 frames for faster detection response (need 2/3 majority instead of 3/5)
- **HEAD_PITCH_THRESHOLD**: Increased from 25° to 35° to eliminate false `driver_distracted` from camera mounting angle
- **V4L2 mmap buffers**: Reduced from 4 to 2 to minimize frame latency
- **ENABLE_PREVIEW**: Runtime-toggleable `@Volatile var` to disable overlay rendering + bitmap posting. Preview rendering was causing ~10s perceived delay due to bitmap GC pressure on SA8155P. With preview disabled, audio alerts fire in ~2-3s. Toggle state persisted across restarts via SharedPreferences
- **Decoupled result delivery**: `FrameHolder.postResult()` delivers OutputResult to dashboard immediately, independent of bitmap rendering. Dashboard and audio alerts update at pipeline speed regardless of preview state
- **In-place overlay rendering**: OverlayRenderer draws directly on source bitmap instead of creating 3.6MB ARGB_8888 copy per frame

### Performance Optimizations
- **FP16 models**: ONNX models converted from FP32 to FP16 via `scripts/convert_fp16.py`. Halves model memory (~12MB → ~6.5MB each) and reduces memory bandwidth. SA8155P Kryo 485 has `asimdhp` (ARM FP16 hardware support). ORT handles FP16↔FP32 cast at I/O boundary automatically.
- **Thread pinning to Gold+Prime cores**: ONNX Runtime intra-op threads pinned via `session.intra_op_thread_affinities` (parameterized per platform via `PlatformProfile`). SA8155: cores 4-7 (2131-2419MHz). SA8295/generic: OS-scheduled (conservative until profiled).
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
- **Dynamic pixelBuffer**: `InCabinService.bgrToBitmap()` resizes `pixelBuffer` when actual frame dimensions exceed pre-allocated size (prevents JNI out-of-bounds write with non-standard MJPEG resolutions)
- **V4L2 actual dimensions**: `V4l2CameraManager` queries actual negotiated width/height from native camera via `nativeGetV4l2Width/Height` JNI methods (prevents wrong stride when driver negotiates different resolution than requested)

## IVI Deployment Robustness
System-level hardening for unattended automotive IVI deployment:

- **Boot auto-start** (`BootReceiver`): `ACTION_BOOT_COMPLETED` receiver auto-starts `InCabinService` on SA8155/SA8255/SA8295. Disabled on generic Android. Pure `shouldAutoStart(platform)` companion for testability
- **Pipeline watchdog** (`PipelineWatchdog`): `HandlerThread`-based monitor checks `@Volatile lastHeartbeatMs` every 5s. If no heartbeat for 30s (`WATCHDOG_TIMEOUT_MS`), invokes `restartPipeline()` callback (mode-aware: restarts VlmClient in remote mode, camera in local mode). Pure `isStalled()` companion. Heartbeat recorded at end of each `processFrame()`
- **Memory pressure handling** (`MemoryPolicy`): `onTrimMemory(level)` override in `InCabinService` calls pure `MemoryPolicy.decideAction(trimLevel)`. Level >= 10 (RUNNING_LOW): disables preview + clears FrameHolder buffers. Level >= 15 (RUNNING_CRITICAL): also requests GC. Zero per-frame cost
- **Persistent crash log** (`CrashLog`): Singleton writes to `crash_log.txt` in app internal storage. `@Synchronized` thread-safe writes. 500KB cap with rotation to `crash_log_prev.txt`. Uncaught exception handler installed in `onCreate()`. Pipeline errors, watchdog stalls, and memory events logged. Pure `formatLine()` and `shouldRotate()` functions for testability
- **Bounded init timeout**: `CountDownLatch.await()` replaced with `await(30s, TimeUnit.MILLISECONDS)`. Logs error + continues with partial init on timeout. Null checks downstream already handle missing components
- **MJPEG auto-reconnect**: Outer `while(running)` reconnect loop in `MjpegCameraManager` with exponential backoff (2s→30s, reusing V4L2 constants). Pure `nextBackoffDelay(current, max)` companion. Posts `LOST` on disconnect, `ACTIVE` on reconnect. Backoff resets on successful connection
- **Service alive check from UI**: `FrameHolder` adds `serviceHeartbeatMs: AtomicLong` + `serviceRunning: AtomicBoolean`. `postHeartbeat()` called at end of `processFrame()`. `MainActivity.updateCameraStatus()` checks `getHeartbeatAgeMs() > 15s` → shows "Stalled" with danger dot
- **Inference error tracking**: `consecutiveInferenceErrors` counter in `InCabinService`. Reset to 0 on successful frame. On error: increment + log to CrashLog. At threshold (10): close+recreate PoseAnalyzer, FaceAnalyzer, and TemporalSmoother. Pure `shouldReinitialize(count, threshold)` companion

## Deployment

### Honda SA8155P BSP Setup
The app automates most setup steps via `DeviceSetup` when running on an automotive BSP. Simply tap "Start Monitoring" and the app will:
1. Remove ODK hook module (via `su -c rmmod`)
2. Wait for USB webcam connection
3. Fix device permissions (`chmod 666`)
4. Grant app permissions (`pm grant`)

**Manual fallback** (if automated setup fails or for adb-based workflows):
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

# 6. Grant car permissions (required for vehicle-moving state)
adb shell pm grant --user 10 com.incabin android.car.permission.CAR_SPEED
adb shell pm grant --user 10 com.incabin android.car.permission.CAR_POWERTRAIN
adb shell pm grant --user 10 com.incabin android.car.permission.CAR_ENERGY
adb shell pm grant --user 10 com.incabin android.car.permission.CAR_DRIVING_STATE

# 7. Start service and monitor
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
