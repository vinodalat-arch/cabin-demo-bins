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
- **Camera disconnect**: After 3 consecutive null frames, V4L2 enters reconnect mode with exponential backoff (2s → 30s max). Pipeline watchdog detects stall after 30s and triggers camera restart. Dashboard shows "Stalled" status with danger dot if service heartbeat exceeds 15s
- **Baseline calibration**: First 10 face-detected frames build EAR and pitch baselines. Before calibration completes, fallback thresholds apply (EAR < 0.21, |pitch| > 35°)
- **UI crash safety**: If overlay rendering fails, core pipeline (detection + audio alerts + JSON) continues unaffected

---

## Flow 2: Face Registration & Recognition

### Preconditions
- App installed and not currently monitoring (or monitoring — registration works in both states)
- At least one face visible to the camera

### Steps
1. From the dashboard, open settings (5-tap) and tap **Manage Faces**. If monitoring, it stops first (only one camera consumer at a time).
2. `FaceRegistrationActivity` opens with its own camera (V4L2/Camera2/MJPEG — same strategy as service). Live preview shows face bbox overlay.
3. Position face in view. Tap **Capture**. The activity:
   - Extracts BGR face crop from live camera data using `FaceDetectorLite` bbox
   - Computes 512-dim face embedding via its own `FaceRecognizerBridge` instance
   - Quality gate: pairwise cosine ≥ 0.7 between captures
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
- **Invalid URL**: Camera connection fails; MJPEG auto-reconnect retries with exponential backoff (2s → 30s)
- **Network loss**: Stream disconnects; auto-reconnect loop activates. Camera status transitions to LOST, then ACTIVE on reconnect. Backoff resets on successful connection
- **Empty URL treated as disabled**: `isNotBlank()` check ensures empty string doesn't activate WiFi camera mode

---

## Flow 4: Configuration & Settings

### Preconditions
- App installed, dashboard visible
- Settings overlay accessible via 5-tap gesture on root layout

### Steps
1. **Open Settings**: Tap root layout 5 times within 3s. Visual flash on 4th tap. Settings panel slides in from left.
2. **Preview Toggle**: ON/OFF button.
   - Off (default): No bitmap creation, no overlay rendering. Core pipeline unaffected.
   - On: Full overlay rendered on camera frames and displayed in center panel.
3. **Audio Toggle**: ON/OFF button.
   - On (default): TTS alerts fire on detections.
   - Off: TTS suppressed. Core pipeline still runs.
4. **Language Toggle**: EN/JA segmented button.
   - Next alert uses new language. No restart needed.
5. **Driver Seat Side**: LEFT/RIGHT segmented button.
   - Affects which person is identified as the driver.
6. **Inference Mode**: LOCAL/REMOTE segmented button.
   - LOCAL: On-device ML inference via camera.
   - REMOTE: HTTP polling to VLM server (no local camera/models needed).
   - Takes effect on next monitoring start (mode locked during monitoring).
7. **Inference FPS**: 1/2/3 segmented button.
   - Shared between local and VLM modes. Controls frame throttling / poll interval.
8. **VLM Server URL**: Button opens dialog to enter VLM server URL.
   - On save, fires one-shot health check toast ("VLM Online — model" or "VLM Offline").
9. **WiFi Camera URL**: Button opens dialog to enter MJPEG stream URL.
10. **Manage Faces**: Opens FaceRegistrationActivity.
11. **Close Settings**: Tap close button (x), tap scrim, or 5-tap again.

### Expected Observations
- All settings persist across app restarts and cold boots via SharedPreferences + `ConfigPrefs.loadIntoConfig()`
- Preview off: ~2-3s detection latency. Preview on: ~10s perceived delay on SA8155 due to GC pressure
- Audio toggle does not affect detection pipeline — only TTS output
- Inference mode change requires stop/start monitoring to take effect

### Edge Cases
- **Toggle during monitoring**: `@Volatile var` ensures cross-thread visibility. Service thread reads Config each frame
- **FPS change during monitoring**: Takes effect on next frame interval (no restart needed for local, no restart needed for VLM polling)
- **VLM URL save with health check**: Background thread runs health check; `@Volatile isActivityDestroyed` flag prevents callback on destroyed Activity
- **All settings survive cold boot**: BootReceiver path calls `ConfigPrefs.loadIntoConfig()` before service start

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

---

## Flow 6: IVI Deployment Robustness

### Preconditions
- APK installed on automotive BSP (SA8155/SA8255/SA8295)

### Boot Auto-Start
1. Device boots. `BootReceiver` receives `ACTION_BOOT_COMPLETED`.
2. `BootReceiver.shouldAutoStart()` checks platform — returns true for automotive BSPs.
3. Calls `startForegroundService()` with `ACTION_START`. Service initializes and begins monitoring automatically.
4. On generic Android: `shouldAutoStart()` returns false — no auto-start.

### Pipeline Watchdog
1. `PipelineWatchdog` starts after component init, checks every 5s.
2. Each successful `processFrame()` records a heartbeat via `watchdog.recordHeartbeat()`.
3. If no heartbeat for 30s (e.g., camera hung, inference deadlock), watchdog fires `restartCamera()`.
4. `restartCamera()` stops all camera managers, then calls `startCamera()` to reconnect.
5. CrashLog records the stall event to `crash_log.txt`.

### Memory Pressure Handling
1. Android calls `onTrimMemory(level)` when system is under memory pressure.
2. `MemoryPolicy.decideAction(level)` returns actions based on severity:
   - Level >= 10 (RUNNING_LOW): disables camera preview (`Config.ENABLE_PREVIEW = false`), clears FrameHolder buffers.
   - Level >= 15 (RUNNING_CRITICAL): same + requests GC.
3. Core inference pipeline continues unaffected — only UI features degrade.

### Persistent Crash Log
1. `CrashLog.init(filesDir)` runs in `Service.onCreate()`. Uncaught exception handler installed.
2. Pipeline errors, watchdog stalls, memory events, and uncaught exceptions all write to `crash_log.txt`.
3. File capped at 500KB; rotates to `crash_log_prev.txt` when exceeded.
4. View via: `adb shell cat /data/data/com.incabin/files/crash_log.txt`

### Service Stall Indicator
1. Each `processFrame()` calls `FrameHolder.postHeartbeat()`.
2. `MainActivity.updateCameraStatus()` checks `FrameHolder.getHeartbeatAgeMs()`.
3. If heartbeat age > 15s while monitoring is active, camera status shows "Stalled" with danger-colored dot.
4. Resumes to "Active" once heartbeat resumes.

### Inference Error Recovery
1. Each successful frame resets `consecutiveInferenceErrors` to 0.
2. Each pipeline exception increments the counter and logs to CrashLog.
3. At 10 consecutive errors: `reinitializeInference()` closes and recreates PoseAnalyzer, FaceAnalyzer, and TemporalSmoother. Counter resets.

### Edge Cases
- **Boot without camera**: Service starts but camera connection fails. Watchdog monitors for stalls. Manual camera connection triggers reconnect
- **Model file corruption**: Inference errors trigger reinitialize after 10 failures. CrashLog captures stack traces for post-mortem analysis
- **Low memory kill**: Android kills service due to memory pressure. `START_STICKY` flag requests restart. Boot receiver re-starts on next reboot
- **CrashLog full**: Rotation to `crash_log_prev.txt` ensures at most ~1MB disk usage

---

## Flow 7: Remote VLM Inference

### Preconditions
- Laptop running `vlm_server.py` (or `vlm_launcher.py` GUI) on same network as Android device
- App installed on device

### Steps — Server Setup (Laptop)
1. Run `python3 scripts/vlm_launcher.py` on the laptop.
2. GUI shows auto-detected LAN IP and URL (e.g., `http://192.168.1.42:8000`).
3. Click **Start Server**. Server log streams in real-time. Status: "Running".
4. Click **Test Health** to verify server responds. Click **Test Query** to verify inference.
5. Copy the URL (or use the Copy button).

### Steps — Android Configuration
1. Open settings overlay (5-tap gesture).
2. Set **Inference Mode** to **REMOTE**.
3. Tap **VLM Server** button. Enter the server URL from the launcher. Tap **Save**.
   - Toast shows "VLM Online — Qwen2.5-VL-7B" or "VLM Offline".
4. Optionally adjust **FPS** (1/2/3) — controls VLM polling interval.
5. Close settings. Tap **Start Monitoring**.
   - Sanity check runs: "Checking VLM server..." → advisory toast.
   - Inference badge shows "VLM" (purple pill).
   - Camera status shows "VLM: Active".
6. Dashboard updates with detections from VLM server. Audio alerts fire normally.
7. Tap **Stop Monitoring** to end session.

### Expected Observations
- No camera permission requested in remote mode
- No local model loading — faster startup
- `distraction_duration_s` computed on-device (safety-critical), not from server
- VLM server uses laptop GPU for inference — higher quality detection than on-device models
- Camera status shows "VLM: Connecting" → "VLM: Active" → "VLM: Lost" on disconnect

### Edge Cases
- **Server unreachable**: Health check toast warns "VLM Offline". Monitoring still starts (advisory only). VlmClient retries with backoff
- **Server stops mid-session**: Camera status transitions to "VLM: Lost". Watchdog detects stall after 30s → `restartPipeline()` restarts VlmClient
- **Network latency**: VLM polls at configured FPS interval. If inference takes longer than interval, next poll starts immediately (no sleep)
- **Switch modes**: Must stop monitoring before switching LOCAL↔REMOTE (mode locked at start)
- **Activity destroyed during health check**: `@Volatile isActivityDestroyed` flag prevents runOnUiThread callback on destroyed Activity
