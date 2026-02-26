# In-Cabin AI Perception

Real-time driver monitoring system for Android Automotive IVI platforms. Detects phone use, drowsiness, distraction, eating, dangerous posture, and child safety — then alerts via voice, dashboard, and vehicle hardware actions.

**Target**: Qualcomm SA8155P / SA8295P | Android Automotive 14 | Single APK

## Two Inference Modes

| Mode | How it works | What you need |
|------|-------------|---------------|
| **Local** (default) | USB webcam + on-device ML models (YOLO, MediaPipe, MobileFaceNet) | SA8155/SA8295 or any Android device with camera |
| **Remote VLM** | Polls a laptop-hosted VLM server (Qwen2.5-VL) via HTTP | Laptop with GPU + Android device on same network |

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

When you don't want to run ML models on-device (or want to use a more powerful VLM like Qwen2.5-VL-7B), you can run inference on your laptop and have the Android app poll results over HTTP.

### What Runs on the Laptop

All VLM server components are in the `scripts/` directory:

| File | Purpose |
|------|---------|
| `vlm_server.py` | FastAPI server — captures webcam, runs VLM inference, serves results via REST API |
| `vlm_launcher.py` | Desktop GUI (tkinter) — start/stop server, configure settings, monitor connection |
| `requirements.txt` | Python dependencies for the server |

### Server Setup

```bash
# 1. Create a Python virtual environment
cd scripts
python3 -m venv .venv
source .venv/bin/activate   # macOS/Linux
# .venv\Scripts\activate    # Windows

# 2. Install dependencies
pip install -r requirements.txt

# 3a. Quick test (mock mode — no GPU needed)
python vlm_server.py --mock

# 3b. Real VLM inference (requires GPU + ~16GB VRAM)
pip install transformers torch Pillow
python vlm_server.py --model Qwen/Qwen2.5-VL-7B-Instruct --camera 0
```

### Server Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/detect` | GET | Returns latest detection result as JSON |
| `/api/health` | GET | Returns server status, model info, client connection state |

### Server Options

```
python vlm_server.py [OPTIONS]

  --port PORT        Server port (default: 8000)
  --host HOST        Bind address (default: 0.0.0.0)
  --camera ID        Webcam device ID (default: 0)
  --fps FPS          Inference rate (default: 2.0)
  --model NAME       HuggingFace model (default: Qwen/Qwen2.5-VL-7B-Instruct)
  --mock             Mock mode — cycles fake detections, no GPU needed
  --scenario test-all  Cycles through every detection type for testing alerts
```

### Using the Launcher GUI

```bash
python vlm_launcher.py
```

The launcher provides a desktop window with:
- **Start/Stop** button for the server
- **Server URL** display with copy-to-clipboard (paste into Android app settings)
- **Target toggle**: Real device (LAN IP) vs Emulator (10.0.2.2)
- **Settings**: Port, camera, FPS, mock mode, HuggingFace model name
- **Test buttons**: Health check and detection query
- **Live log viewer**: Real-time server output
- **Client status**: Shows whether the Android app is connected and polling

### Connecting Android App to VLM Server

1. Start the VLM server on your laptop
2. On the Android app, open settings (5-tap gesture)
3. Select **REMOTE** inference mode
4. Tap **VLM Server URL** and enter `http://<laptop-ip>:8000`
5. Tap START — the app polls the server instead of using local camera/models

The app shows a **VLM** badge (purple pill) during remote inference. Camera status dot shows VLM connection state (Connecting / Active / Lost).

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

---

## Project Structure

```
in_cabin_poc-sa8155/
├── app/src/main/
│   ├── assets/              ML models (gitignored)
│   ├── cpp/                 C++ inference (ONNX Runtime, V4L2, JNI)
│   ├── kotlin/com/incabin/  Kotlin source (service, UI, analyzers, alerts)
│   └── res/                 Layouts, strings, styles, drawables
├── app/src/test/kotlin/     Unit tests (625 tests)
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
| `vlm_server.py` | Laptop | VLM inference server (FastAPI + webcam + Qwen2.5-VL) |
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
./gradlew test     # 625 unit tests
```

## On-Device Performance

Measured on SA8155P (Kryo 485, 8 cores, 7.6 GB RAM):

- Average frame time: ~630ms (Pose ~550ms, Face ~80ms)
- Detection latency: ~2-3s (3-frame smoother window)
- Memory: heap 19MB, native 208MB
- APK size: 84 MB

---

*KPIT Technologies, India*
