# User Flows — In-Cabin AI Perception

This document describes the 5 primary user flows, each with preconditions, step-by-step actions, expected observations, and edge cases.

---

## Flow 1: Basic Monitoring & Detection

### Preconditions
- APK installed, permissions granted (CAMERA, POST_NOTIFICATIONS)
- On SA8155/SA8295: ODK hook module removed, USB webcam connected, `/dev/video*` permissions set (or let automated setup handle it)
- On generic Android: Camera available via Camera2 API
- ONNX models and `face_landmarker.task` present in `assets/`

### Steps
1. Launch the app. Dashboard shows idle state: left panel visible with "Start Monitoring" button, center shows "Honda Smart Cabin" branding, right panel hidden.
2. Tap **Start Monitoring**.
   - On automotive BSP: `DeviceSetup` runs (ODK rmmod → camera polling → chmod → pm grant). Camera status indicator progresses: `Cam: None` → `...` → `Ready`.
   - On generic Android: Permission dialogs appear if not already granted.
3. `InCabinService` starts as a foreground service. Models load in parallel (`CountDownLatch`): FaceAnalyzer (MediaPipe), PoseAnalyzerBridge (ONNX), FaceRecognizerBridge (MobileFaceNet).
4. Camera begins capture at ~1fps. Each frame flows through:
   - V4L2 (or Camera2) → BGR conversion → PoseAnalyzer (YOLO pose + detection) → FaceAnalyzer (landmarks, EAR, MAR, head pose) → `mergeResults()` → `TemporalSmoother.smooth()` → `AudioAlerter.checkAndAnnounce()` → JSON logcat output → `FrameHolder.postResult()` for dashboard.
5. Dashboard updates every ~500ms polling cycle. Score arc animates, detection labels appear/disappear with fade animations, risk pill transitions between LOW/MEDIUM/HIGH.
6. When a detection fires (e.g., phone, eyes closed), the audio alerter announces it via TTS. Detection must sustain for the minimum frame count (phone: immediate after smoother majority, eyes: 3 frames, yawning: 2 frames, etc.).
7. Tap **Stop Monitoring**. Service stops, session summary dialog appears with duration and detection counts.

### Expected Observations
- Clean startup (no detections): risk = LOW, all detection labels show "All Clear", score arc at 0
- Phone detected: risk jumps to HIGH (weight 3), TTS announces "Phone", label appears with danger dot
- Eyes closed for 3+ frames: risk = HIGH, TTS announces "Eyes closed"
- Multiple detections: alerts joined as single message ("Phone. Eyes closed"), CRITICAL parts first
- JSON output in logcat contains all 17 fields with valid types

### Edge Cases
- **No face detected**: `FaceResult.NO_FACE` → EAR/MAR/yaw/pitch are null. Smoother face-gates these fields (won't trigger false eyes_closed/yawning/distracted)
- **Camera disconnect**: After 3 consecutive null frames, V4L2 enters reconnect mode with exponential backoff (2s → 30s max). Core pipeline pauses, dashboard shows stale data
- **Baseline calibration**: First 10 face-detected frames build EAR and pitch baselines. Before calibration completes, fallback thresholds apply (EAR < 0.21, |pitch| > 35°)
- **UI crash safety**: If overlay rendering fails, core pipeline (detection + audio alerts + JSON) continues unaffected

---

## Flow 2: Face Registration & Recognition

### Preconditions
- App installed and not currently monitoring (or monitoring — registration works in both states)
- At least one face visible to the camera

### Steps
1. From the dashboard, tap **Faces** button (left panel).
2. `FaceRegistrationActivity` opens. Camera preview shows the current frame.
3. Position face in view. Tap **Capture**. The activity:
   - Reads BGR crop from `FrameHolder.getCaptureData()`
   - Computes 512-dim face embedding via temporary `FaceRecognizerBridge`
   - Prompts for a name
4. Enter name (e.g., "Vinod") and tap **Save**. `FaceStore.register()` persists name + embedding to `faces.json`.
5. Return to dashboard. Tap **Start Monitoring** (or if already monitoring, recognition resumes).
6. Every 5th inference frame (~5s at 1fps), the pipeline:
   - Extracts face crop from landmarks (bbox + 20% padding)
   - Runs MobileFaceNet → 512-dim embedding → L2 normalize
   - Scans `FaceStore` for best match (cosine similarity ≥ 0.5)
7. If matched, `driverName` is set in `OutputResult`. Dashboard shows driver name. Overlay labels driver bbox with name instead of "Driver".

### Expected Observations
- Registered face correctly matched: `driver_name` field populated in JSON output
- Unregistered face: `driver_name` is null
- Name persists across app restarts (stored in `faces.json` on internal storage)

### Edge Cases
- **Multiple registered faces**: `findBestMatch()` scans all entries, returns highest cosine similarity above threshold
- **Recognition failure**: Wrapped in try-catch; returns cached name. Core pipeline never blocked
- **Model missing**: `FaceRecognizerBridge.nativePtr=0`, all calls return null, `driverName` stays null
- **Activity crash**: `FaceRegistrationActivity` uses its own `FaceRecognizerBridge` instance; crash doesn't affect the service

---

## Flow 3: WiFi Camera (MJPEG) Setup

### Preconditions
- A WiFi camera streaming MJPEG on the local network
- App installed, device connected to same network

### Steps
1. From the dashboard, tap **WiFi Cam** button (left panel).
2. A dialog appears with a text field for the MJPEG stream URL.
3. Enter the URL (e.g., `http://192.168.1.100:8080/video`) and tap **Save**.
   - `Config.WIFI_CAMERA_URL` is set and persisted in SharedPreferences.
4. Tap **Start Monitoring**. The service checks `Config.WIFI_CAMERA_URL.isNotBlank()`:
   - If set: Uses WiFi MJPEG camera instead of V4L2/Camera2.
   - If empty: Falls back to platform camera strategy (V4L2 or Camera2).
5. Frames arrive via MJPEG stream → decoded → BGR conversion → same inference pipeline.
6. To revert to USB camera: tap **WiFi Cam** again, clear the URL field, tap **Save**.

### Expected Observations
- WiFi camera URL persists across app restarts
- Setting a URL overrides the platform camera strategy
- Clearing the URL reverts to USB/Camera2
- HTTPS URLs are accepted

### Edge Cases
- **Invalid URL**: Camera connection fails; service falls back or shows error
- **Network loss**: Stream disconnects; reconnect logic applies
- **Empty URL treated as disabled**: `isNotBlank()` check ensures empty string doesn't activate WiFi camera mode

---

## Flow 4: Configuration & Preview Toggle

### Preconditions
- App installed, dashboard visible

### Steps
1. **Preview Toggle**: Tap the preview toggle button (top row, left panel).
   - Off → On: Camera frames render in center panel with full overlay (bboxes, skeleton, landmarks, metrics). `Config.ENABLE_PREVIEW = true`.
   - On → Off: Center panel shows idle branding. No bitmap creation, no GC pressure. `Config.ENABLE_PREVIEW = false`.
   - State persisted in SharedPreferences across restarts.
2. **Audio Toggle**: Tap the audio toggle.
   - On (default): TTS alerts fire on detections.
   - Off: `Config.ENABLE_AUDIO_ALERTS = false`. TTS suppressed. Core pipeline still runs.
3. **Language Toggle**: Tap to switch between English ("en") and Japanese ("ja").
   - `Config.LANGUAGE` updates. Next alert uses new language.
   - English: "Phone", "Eyes closed", "All clear"
   - Japanese: "スマホ", "目を閉じています", "安全です"
4. **Driver Seat Side**: Toggle between "left" (LHD) and "right" (RHD).
   - `Config.DRIVER_SEAT_SIDE` updates. Affects which person is identified as the driver.

### Expected Observations
- Preview off: ~2-3s detection latency (no bitmap overhead). Preview on: ~10s perceived delay on SA8155 due to GC pressure
- Audio toggle does not affect detection pipeline — only TTS output
- Language change takes effect on next alert (no restart needed)
- All toggles persist across app restarts

### Edge Cases
- **Preview toggle during monitoring**: `@Volatile var` ensures cross-thread visibility. Service thread reads `Config.ENABLE_PREVIEW` each frame
- **Multiple rapid toggles**: Each write is atomic (`@Volatile`), last write wins
- **Smoother uses Config constants**: `SMOOTHER_WINDOW=3`, `SMOOTHER_THRESHOLD=0.6` are compile-time constants, not runtime-toggleable

---

## Flow 5: Sustained Distraction & Escalation

### Preconditions
- Monitoring active, audio alerts enabled, driver detected

### Steps
1. **Initial detection**: Driver picks up phone. After smoother majority voting (2/3 frames) and phone has no sustained counter (fires on majority), onset alert fires: TTS "Phone" (CRITICAL priority).
2. **Cooldown**: Within 10s of the onset alert, if phone is momentarily lost and re-detected, the re-announcement is suppressed (per-danger cooldown).
3. **Duration tracking**: `distractionDurationS` increments +1 each frame while any distraction field is active (phone, eyes, yawning, distracted, eating). Resets to 0 when all clear.
4. **Escalation ladder**:
   - At 10s duration: TTS "Still distracted, 10 seconds" (WARNING priority, no beep)
   - At 20s duration: 1kHz beep (1s) → 200ms gap → TTS "Warning. Distracted 20 seconds" (CRITICAL priority)
   - At 30s, 40s, ... (every 10s): beep repeats with updated duration
5. **Multi-danger**: If eyes also close while phone is active, onset fires for eyes: "Eyes closed" (or joined "Phone. Eyes closed" if both are new on same frame). CRITICAL parts sorted first in joined message.
6. **All-clear**: Driver puts down phone and opens eyes. All detections clear simultaneously:
   - Queue drains, TTS stops mid-sentence
   - "All clear" spoken immediately (INFO priority)
   - Cooldown maps and escalation maps clear
   - `distractionDurationS` resets to 0

### Expected Observations
- Onset alert within ~2-3s of detection start
- No duplicate alerts within 10s cooldown window
- Escalation messages at correct duration thresholds
- Beep plays only at 20s+ escalation levels
- All-clear fires immediately when all dangers resolve

### Edge Cases
- **Escalation suppressed during onset**: If a new danger appears on the same frame as an escalation threshold, the onset message takes priority (escalation only fires when `alerts.isEmpty()`)
- **Cooldown expiry**: After 10s, re-detection of the same danger triggers a fresh onset alert
- **Duration resets escalation**: When duration drops to 0 (all-clear), `escalationMap` clears. Next distraction starts fresh from 0s
- **Bounded priority queue**: Queue capacity is 3. When full, drain → sort by priority → keep top messages, drop lower priority
- **Staleness check**: Worker thread drops messages older than 4s or whose danger already resolved (reads `FrameHolder.getLatestResult()`)
- **First frame**: `AudioAlerter` stores state only on first frame, no announcement (avoids false onset on startup)
