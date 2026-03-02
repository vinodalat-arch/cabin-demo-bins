# In-Cabin AI Perception

Real-time driver monitoring system for Android Automotive IVI platforms. Detects phone use, drowsiness, distraction, eating, dangerous posture, and child safety — then alerts via voice, dashboard, and vehicle hardware actions.

**Target**: Qualcomm SA8155P / SA8295P | Android Automotive 14 | Single APK

## Two Inference Modes

| Mode | How it works | What you need |
|------|-------------|---------------|
| **Local** (default) | USB webcam + on-device ML models (YOLO, MediaPipe, MobileFaceNet) | SA8155/SA8295 or any Android device with camera |
| **Remote VLM** | Polls a laptop-hosted bridge server that queries external vLLM (Qwen3-VL-4B) via HTTP | Laptop with GPU running vLLM + Android device on same network |

## Quick Start

### 1. Build & Install the Android App

```bash
# Prerequisites: JDK 17, Android SDK, NDK 26
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools

# Build
./gradlew assembleDebug

# Deploy (builds, installs, grants permissions, launches)
./scripts/deploy.sh
```

### 2. Download ML Models (Local Mode Only)

Place these in `app/src/main/assets/` (gitignored due to size):

| Model | Size | Source |
|-------|------|--------|
| `yolov8n-pose-fp16.onnx` | ~6.5 MB | YOLOv8n-pose FP32 → `scripts/convert_fp16.py` |
| `yolov8n-fp16.onnx` | ~6.5 MB | YOLOv8n FP32 → `scripts/convert_fp16.py` |
| `face_landmarker.task` | 3.6 MB | [Google AI Edge](https://ai.google.dev/edge/mediapipe/solutions/vision/face_landmarker) |
| `mobilefacenet-fp16.onnx` | ~6.5 MB | InsightFace buffalo_sc (w600k_mbf) → FP16 convert |

### 3. Run on Device

- **On SA8155/SA8295**: Tap START — the app auto-handles ODK removal, camera setup, and permissions.
- **On generic Android**: Grant camera permission when prompted, then tap START.
- **Settings**: Tap the root layout 5 times within 3 seconds to open the hidden settings overlay.

---

## Remote VLM Server (Laptop Setup)

### Why Remote VLM?

The SA8155 (Snapdragon 855 Automotive) is a CPU-only IVI platform — it cannot run Vision Language Models locally. But a laptop with a GPU on the same network can run Qwen3-VL-4B and provide state-of-the-art visual understanding ("is the driver eating while looking at their phone?") that traditional YOLO+MediaPipe models can't match.

The solution splits the work: the **laptop does the seeing and thinking** (webcam capture + VLM inference), and the **device does the reacting** (temporal smoothing, distraction tracking, voice alerts, 5-level escalation, vehicle hardware actions via VHAL). Safety-critical logic — distraction duration counting, escalation decisions, seat vibration, steering heat, window control — always runs on-device. If the laptop disconnects, the device shows "VLM: Lost" and can fall back to local ML models.

### Architecture

```
LAPTOP                                              SA8155 DEVICE
──────                                              ─────────────
Webcam                                              VlmClient (HTTP poll 1-3 Hz)
  → vlm_server.py (bridge)                            → parseDetectResponse()
    → base64 JPEG                                     → TemporalSmoother (3-frame window)
    → vLLM /v1/chat/completions                       → distraction_duration_s (on-device)
    → parse response                                  → AlertOrchestrator
    → apply confidence thresholds                       ├─ AudioAlerter (TTS)
    → /api/detect ──── HTTP GET ────────────────────    ├─ VehicleChannelManager (VHAL)
                                                        └─ Dashboard UI
```

The bridge server (`vlm_server.py`) captures webcam frames, sends base64 JPEG to an external vLLM server's OpenAI-compatible API, parses the VLM response, applies confidence thresholds, and serves detection results to the SA8155 device. Everything is configurable — confidence thresholds, escalation timing, VLM parameters — tunable without rebuilding anything.

### What Runs on the Laptop

| Component | Purpose |
|-----------|---------|
| **vLLM** | Serves the VLM model (Qwen3-VL-4B) with OpenAI-compatible API. Runs separately |
| `vlm_server.py` | Bridge server — captures webcam, queries vLLM, serves results to device |
| `vlm_launcher.py` | Desktop GUI (tkinter) — start/stop server, monitor connection |
| `requirements.txt` | Python dependencies for bridge server (fastapi, uvicorn, opencv-python) |

### Server Setup

```bash
# 1. Start vLLM (in a separate terminal)
vllm serve /home/kpit/code/qwen3_offline_4B \
  --host 0.0.0.0 --port 8080 \
  --trust-remote-code --gpu-memory-utilization 0.8 \
  --max-model-len 16384 --limit-mm-per-prompt '{"video": 1}' \
  --enforce-eager --allowed-local-media-path /

# 2. Install bridge server dependencies
cd scripts
pip install fastapi uvicorn opencv-python

# 3a. Connect bridge to already-running vLLM
python vlm_server.py --vllm-url http://localhost:8080

# 3b. OR start vLLM + bridge together (auto-launches vLLM)
python vlm_server.py --start-vllm --model-path /home/kpit/code/qwen3_offline_4B

# 3c. Mock mode for testing (no GPU needed)
python vlm_server.py --mock
```

### Server Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/detect` | GET | Returns latest detection result as JSON |
| `/api/health` | GET | Returns server + vLLM status (`ready: true/false`) |

### Server Options

Every parameter is configurable via CLI args with sensible defaults:

```
python vlm_server.py [OPTIONS]

  Bridge server:
    --port PORT                    Bridge server port (default: 8000)
    --host HOST                    Bind address (default: 0.0.0.0)

  Camera:
    --camera ID                    Webcam device ID (default: 0)
    --fps FPS                      Inference rate (default: 2.0)
    --jpeg-quality N               JPEG encode quality 1-100 (default: 80)

  Mode:
    --mock                         Mock mode — cycles fake detections, no vLLM needed
    --scenario test-all            Cycles through every detection type for testing

  vLLM connection (one required unless --mock):
    --vllm-url URL                 Connect to already-running vLLM (e.g. http://localhost:8080)
    --start-vllm                   Start vLLM server automatically
    --model-path PATH              Local model path (default: /home/kpit/code/qwen3_offline_4B)
    --vllm-port PORT               Port for auto-started vLLM (default: 8080)

  vLLM launch parameters (used with --start-vllm):
    --gpu-memory-utilization N     GPU memory fraction (default: 0.8)
    --max-model-len N              Max context length (default: 16384)
    --vllm-startup-timeout N       Seconds to wait for model load (default: 300)

  VLM inference:
    --max-tokens N                 Max tokens for VLM response (default: 300)
    --temperature N                Sampling temperature (default: 0.1)
    --request-timeout N            vLLM API request timeout in seconds (default: 30)

  Confidence thresholds (VLM score → boolean):
    --thresh-phone N               driver_using_phone (default: 0.6)
    --thresh-eyes N                driver_eyes_closed (default: 0.5)
    --thresh-yawning N             driver_yawning (default: 0.5)
    --thresh-distracted N          driver_distracted (default: 0.5)
    --thresh-eating N              driver_eating_drinking (default: 0.5)
    --thresh-hands N               hands_off_wheel (default: 0.6)
    --thresh-posture N             dangerous_posture (default: 0.5)
    --thresh-child N               child_present (default: 0.4)
    --thresh-slouching N           child_slouching (default: 0.5)
```

### Using the Launcher GUI

```bash
python vlm_launcher.py
```

The launcher provides a desktop window with:
- **Start/Stop** button for the server
- **Server URL** display with copy-to-clipboard (paste into Android app settings)
- **Target toggle**: Real device (LAN IP) vs Emulator (10.0.2.2)
- **Settings**: Port, camera, FPS, mock mode
- **Test buttons**: Health check and detection query
- **Live log viewer**: Real-time server output
- **Client status**: Shows whether the Android app is connected and polling

### Connecting Android App to VLM Server

1. Start vLLM + bridge server on your laptop (see setup above)
2. On the Android app, open settings (5-tap gesture)
3. Tap **VLM Server URL** and enter `http://<laptop-ip>:8000` — app auto-switches to REMOTE mode
4. Tap START — the app polls the bridge server instead of using local camera/models

The app shows a **VLM** badge (purple pill) during remote inference. Camera status dot shows VLM connection state (Connecting / Active / Lost). Health check shows three states: "VLM Offline" / "VLM Loading" / "VLM Online".

> **Note**: Saving a VLM URL auto-switches to REMOTE mode and restarts monitoring. Clearing the URL auto-switches back to LOCAL.

> **Safety**: `distraction_duration_s` is always computed on-device regardless of inference mode.

---

## What It Detects

| Detection | Description | Voice Alert |
|-----------|-------------|-------------|
| Phone use | Phone near driver's hand area | "Phone detected, please put it down" |
| Eyes closed | Driver's eyes shut for multiple frames | "Eyes closed, please stay alert" |
| Hands off wheel | Driver's hands not on steering wheel | "Hands off wheel, please grip the steering" |
| Distracted | Head turned away from the road | "Distracted, please watch the road" |
| Yawning | Mouth open in yawning pattern | "Yawning detected, consider a break" |
| Eating/drinking | Food or drink near driver | "Eating while driving, please focus" |
| Dangerous posture | Excessive leaning, head drooping | "Dangerous posture detected" |
| Child slouching | Child passenger slouching in seat | "Child is slouching, please check" |
| Person behind vehicle | Person detected while reversing | "Person behind vehicle" (with beep) |
| Animal behind vehicle | Cat/dog detected while reversing | "Animal behind vehicle" |

See `Detection_Alert_Matrix.pdf` for the full alert escalation matrix.

## 5-Level Escalation

Sustained distraction triggers progressively stronger responses:

| Level | After | What Happens |
|-------|-------|-------------|
| L1 Nudge | 0s | Chime + dashboard warning |
| L2 Warning | 5s | + Voice alert + notification |
| L3 Urgent | 10s | + Warning beep + cabin lights + seat vibration |
| L4 Intervention | 20s | + Seat cooling + steering wheel heat |
| L5 Emergency | 30s+ | + Window opens slightly + ADAS engaged |

Vehicle hardware actions require SA8155/SA8295 with VHAL support. On generic Android, only app-level alerts (voice, dashboard, notification) are active.

**Speed-scaled escalation**: At higher vehicle speeds, thresholds compress for faster response:
- **Moderate (31-80 km/h)**: ~2x faster (L2=3s, L3=5s, L4=10s, L5=20s)
- **Fast (>80 km/h)**: ~3x faster (L2=0s, L3=3s, L4=5s, L5=10s)

---

## Project Structure

```
in_cabin_poc-sa8155/
├── app/src/main/
│   ├── assets/              ML models (gitignored)
│   ├── cpp/                 C++ inference (ONNX Runtime, V4L2, JNI)
│   ├── kotlin/com/incabin/  Kotlin source (service, UI, analyzers, alerts)
│   └── res/                 Layouts, strings, styles, drawables
├── app/src/test/kotlin/     Unit tests (644 tests)
├── scripts/
│   ├── vlm_server.py        VLM inference server (laptop)
│   ├── vlm_launcher.py      VLM server GUI launcher (laptop)
│   ├── requirements.txt     Python dependencies for VLM server
│   ├── deploy.sh            Build + install + launch (one command)
│   ├── capture-logs.sh      Capture device logs for analysis
│   ├── parse-perf.sh        Parse logs into performance report
│   └── convert_fp16.py      Convert ONNX models from FP32 to FP16
├── Detection_Alert_Matrix.pdf  Full detection/alert reference
├── CLAUDE.md                Detailed implementation specification
├── SPEC.md                  Algorithm specifications
├── DESIGN.md                Architecture and design decisions
└── DEVICE_SETUP.md          Device-specific setup instructions
```

## Scripts Reference

| Script | Runs On | Purpose |
|--------|---------|---------|
| `deploy.sh` | Dev machine | Build APK, install, grant permissions, launch app |
| `vlm_server.py` | Laptop | VLM bridge server (FastAPI + webcam + external vLLM) |
| `vlm_launcher.py` | Laptop | GUI to manage vlm_server.py |
| `requirements.txt` | Laptop | Python dependencies for VLM server |
| `capture-logs.sh` | Dev machine | Capture device logcat for performance analysis |
| `parse-perf.sh` | Dev machine | Parse captured logs into a summary report |
| `convert_fp16.py` | Dev machine | Convert ONNX models from FP32 to FP16 |
| `generate_alert_matrix_pdf.py` | Dev machine | Generate Detection_Alert_Matrix.pdf |

## Build Requirements

- JDK 17
- Android SDK (compileSdk 34, minSdk 29)
- NDK 26.1.10909125, CMake 3.22.1
- Gradle 8.9 (Kotlin DSL)

## Testing

```bash
./gradlew test     # 644 unit tests
```

## On-Device Performance

Measured on SA8155P (Kryo 485, 8 cores, 7.6 GB RAM):

- Average frame time: ~630ms (Pose ~550ms, Face ~80ms)
- Detection latency: ~2-3s (3-frame smoother window)
- Memory: heap 19MB, native 208MB
- APK size: 84 MB

---

*KPIT Technologies, India*
