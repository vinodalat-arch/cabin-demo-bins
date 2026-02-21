# Roadmap — In-Cabin AI Perception (SA8155)

Features are discussed and specced here first. Once implemented, they move to `SPEC.md` and `CLAUDE.md`.

### Guidelines for Feature Design
- **Review the entire design** before proposing or implementing any new feature. Consider how it interacts with every existing component — not just the files being modified.
- **No performance degradation**: Every feature must be evaluated against the current performance budget (~630ms/frame, ~2-3s detection latency). Quantify the expected overhead. If a feature adds latency to the core pipeline, justify it or redesign.
- **Core pipeline isolation is non-negotiable**: The inference → merge → smooth → audio alert → JSON log path is safety-critical. New features must never block, delay, or interfere with this path. All non-core work (UI, persistence, registration) must be failure-isolated (try-catch) and run after the core path completes.
- **Critically review your own plan**: Challenge assumptions. Look for thread-safety issues, memory leaks, lifecycle bugs, edge cases on the SA8155P BSP, and failure modes. If a simpler approach exists, use it.
- **Measure, don't guess**: When a feature's performance impact is uncertain, spec how it will be measured on-device before committing to the design.
- **Spec completely before implementing**: Every feature must have its full spec written in this roadmap — UI layout, state transitions, file changes, error handling, edge cases — before any code is written. If it's not in the spec, it doesn't get built.
- **Account for SA8155P BSP quirks**: This is not a generic Android device. Always consider: user 10, no camera service, V4L2-only camera, audio routing via USAGE_ASSISTANCE_SONIFICATION, SELinux permissive, ODK module. A feature that works on a Pixel may not work here.
- **Test coverage required**: Every new feature must include unit tests. No feature is complete until tests pass alongside all existing tests (currently 218).
- **Track APK size**: Current APK is 84 MB. New assets (models, images) must justify their size. Prefer lightweight solutions — a 4 MB model is acceptable, a 50 MB model needs strong justification.
- **Avoid per-frame allocations**: The pipeline runs at ~1 fps on constrained hardware. Any per-frame allocation (bitmaps, byte arrays, JNI objects) risks GC pressure that degrades detection latency. Pre-allocate buffers, reuse objects, and avoid creating garbage in the hot path.
- **Specs define behavior and constraints, not implementation**: Feature specs should describe what the user sees, how the app behaves, and what constraints apply (performance, safety, BSP quirks). Leave technical design decisions — data structures, concurrency patterns, persistence mechanisms, class names, annotations — to the implementer. Say *"persist across restarts"*, not *"use SharedPreferences with key X"*.
- **Post-implementation critical review**: After every feature implementation, critically assess the entire design — not just the new code, but how it interacts with all existing components. Identify bugs, performance regressions, thread-safety issues, edge cases, and architectural debt introduced by the change. Fix high-priority and safety-critical issues immediately. Document lower-priority findings as recommendations for future work. This review is part of the feature — a feature is not complete until the review is done and critical fixes are shipped.

---

## Feature 1: Automated Camera Setup Flow

### Problem
The Honda SA8155P requires 5 manual adb shell commands before the app can use the USB webcam (ODK module unload, webcam reconnect, device permission fix, app permission grants). This requires adb access and technical knowledge. The app should handle all of this automatically.

### Solution
Replace the single "Start Monitoring" button with a multi-stage setup flow that automates all pre-requisites.

### Setup Flow (on button tap)

#### Stage 1: Platform Detection
- Detect whether running on Honda SA8155P or generic Android
- Honda SA8155P: proceed through full setup (stages 2-4)
- Generic Android: skip directly to camera detection (stage 4)

#### Stage 2: ODK Module Unload
- Status: "Removing ODK hook module..."
- Unload the ODK hook module that blocks USB webcam binding
- On success: prompt user to connect webcam
- On failure: show manual instructions, allow user to proceed manually

#### Stage 3: Wait for Camera Connection
- Status: "Waiting for webcam... Connect USB camera"
- Button disabled
- Auto-poll for webcam appearance (reuse existing device scanning logic)
- On webcam detected: proceed to stage 4

#### Stage 4: Permission & Device Setup
- Status: "Setting up camera..."
- Fix device node permissions on discovered webcam nodes
- Grant required app permissions (CAMERA, POST_NOTIFICATIONS)
- On failure: fall back to standard Android permission dialogs; show toast for permission errors

#### Stage 5: Ready
- Status: "Camera ready!"
- Button becomes "Start Monitoring" — tapping starts the existing monitoring pipeline

### Camera Status Indicator
- **Location**: Top row, persistent — visible during setup, monitoring, and idle
- **States**:
  - "Not connected" (red) — no webcam detected
  - "Connecting..." (orange) — polling / setting up
  - "Ready" (green) — webcam detected and accessible
  - "Active" (green) — camera streaming during monitoring
  - "Lost" (red) — webcam disconnected during monitoring (ties into existing V4L2 reconnect logic)

### Constraints
- **Root required on SA8155P**: Module unload and device permission changes require root. Must degrade gracefully if root unavailable — show manual instructions instead of failing silently
- **No state replay on restart**: If app is killed and restarted, re-detect current state (is module loaded? is camera visible? are permissions granted?) rather than blindly re-running all steps
- **No impact on InCabinService**: Service starts only after setup is complete. Setup flow lives entirely in the activity layer
- **Core pipeline isolation**: Setup is a pre-condition, not a runtime concern. Once monitoring starts, the existing pipeline runs unchanged

### Status: Implemented

---

## Feature 2: Webcam Preview Toggle

### Problem
The webcam preview (overlay rendering + bitmap display) is currently a compile-time constant, disabled for performance. Users must rebuild the app to enable it. They should be able to toggle preview on/off at runtime for debugging or demonstration.

### Solution
Add a toggle button in the top row that enables/disables webcam preview rendering at runtime.

### UI

#### Toggle Button
- **Location**: Top row, between camera status text and Faces button
- **Layout**: `[statusText] [cameraStatusText] [Preview toggle] [Faces btn] [Start btn]`
- **Label**: "Preview" — visually indicates on/off state
- **Default state**: Off

#### Behavior
- **Toggle ON**: Enable webcam preview — overlay (bboxes, skeleton, face landmarks, metric labels) drawn on camera frames and displayed in the preview area
- **Toggle OFF**: Disable preview — no bitmap creation/posting, no overlay rendering. Core pipeline continues unaffected
- **During monitoring**: Toggle takes effect immediately on next frame. No need to restart monitoring
- **While idle**: Toggle state is stored but has no visible effect until monitoring starts

### Constraints
- **Persist across restarts**: Toggle state survives app kill and restart
- **No performance impact when off**: Must not add any overhead to the core pipeline when preview is disabled
- **Performance impact when on**: Same as current preview behavior (~10s perceived delay due to bitmap GC on SA8155P). Users are opting in knowingly
- **Thread safety**: Toggle is set from UI thread and read from service thread — ensure cross-thread visibility
- **Core pipeline isolation**: Preview toggle must never block or interfere with inference → merge → smooth → alert → log

### Status: Implemented

---

## Feature 3: Multi-Platform Support (SA8295)

### Problem
The app has SA8155-specific tuning hardcoded throughout: CPU core affinity pinned to SA8155's Gold cores (4-7), thread counts sized for its 4+4 core layout, and audio routed through `USAGE_ASSISTANCE_SONIFICATION` to work around a Honda BSP quirk where `STREAM_ALARM` is inaudible. Deploying the same APK on an SA8295 would run inference on the wrong cores and potentially route audio incorrectly.

### Solution
Auto-detect the platform at startup and load a platform-appropriate tuning profile. Single APK, no build variants.

### What Gets Tuned Per Platform

#### CPU Thread Affinity
- Detect SoC at startup and select core affinity mask + thread count for ONNX Runtime inference
- SA8155: current tuning (4 threads on Gold cores 4-7, face recognition on core 5)
- SA8295: profile on-device and determine optimal core mapping for its architecture
- Unknown platform: conservative defaults — no core pinning, let the OS scheduler decide

#### Audio Routing
- SA8155 (Honda BSP): `USAGE_ASSISTANCE_SONIFICATION` (current behavior — `STREAM_ALARM` is inaudible on this BSP)
- SA8295: determine which audio usage/stream produces audible output on the target BSP
- Unknown platform: standard Android audio (`USAGE_ALARM` or `USAGE_NOTIFICATION`) — works on most devices

#### Camera Strategy Priority
- SA8155 (Honda BSP): V4L2 first, Camera2 fallback (camera service disabled)
- SA8295: determine whether camera service is enabled on the target BSP; adjust priority accordingly
- Unknown platform: Camera2 first, V4L2 fallback (standard Android behavior)

### What Stays the Same Across Platforms
- ML models (FP16 ONNX — both SoCs have ARM FP16 hardware)
- Detection pipeline (YOLO → MediaPipe → Merger → Smoother → Alerter)
- V4L2 device scanning logic (Qualcomm device filtering applies to both)
- ABI (`arm64-v8a`)
- Camera resolution (1280x720 — USB webcam capability, not SoC-dependent)
- All thresholds, risk scoring, temporal smoothing

### Behavior
- **Detection**: At app startup (before any inference), identify the platform once
- **Profile loading**: Apply tuning values before ONNX session creation and audio init — these are set-once-at-startup, not toggled at runtime
- **Logging**: Log detected platform and applied profile at startup for diagnostics
- **Graceful fallback**: If platform is unrecognized, use conservative defaults that work everywhere (no core pinning, standard audio, Camera2 first). The app must never fail to start because of an unknown platform

### Constraints
- **Zero runtime overhead**: Platform detection and profile loading happen once at startup. No per-frame branching or checks
- **No new dependencies**: Use standard Android APIs for detection (`Build.SOC_MODEL`, `Build.HARDWARE`, etc.)
- **Testable without hardware**: Platform profiles should be unit-testable — given a platform identifier, verify the correct tuning values are selected
- **Core pipeline unchanged**: The inference pipeline itself is identical across platforms. Only the ONNX session configuration and audio setup differ
- **SA8295 tuning is provisional**: Optimal values for SA8295 require on-device profiling. Initial implementation should use conservative defaults for SA8295 until profiled

### Status: Implemented

---

<!-- Future features go here -->
