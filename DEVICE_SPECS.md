# Honda SA8155P — Device Specs & Observed Performance

## Hardware

| Component | Detail |
|-----------|--------|
| **SoC** | Qualcomm SA8155P |
| **CPU** | Kryo 485, 8 cores (4x Gold 2.96 GHz + 4x Silver 1.80 GHz) |
| **GPU** | Adreno 640 (not used — CPU-only inference) |
| **DSP** | Hexagon 690 (not used) |
| **NPU** | None on SA8155P |
| **RAM** | 7,678 MB (~7.6 GB) |
| **Manufacturer** | ALPSALPINE |
| **Model** | IVI-SYSTEM |
| **Kernel** | 6.1.145 |

## Software

| Property | Value |
|----------|-------|
| **OS** | Android 14 (SDK 34) |
| **SELinux** | Permissive |
| **Camera service** | Disabled (`config.disable_cameraservice=true`) |
| **App user** | User 10 (not default user 0) |

## Camera

| Property | Value |
|----------|-------|
| **Device** | Logitech C270 HD WEBCAM |
| **Interface** | USB UVC via V4L2 direct ioctl |
| **Format** | YUYV 1280x720 |
| **V4L2 buffers** | 4 mmap |
| **Device nodes** | `/dev/video2`, `/dev/video3` (numbers vary) |
| **Permissions** | `660 system:camera` — requires `chmod 666` |

## Component Init Timings (observed from startup logs)

| Component | Time |
|-----------|------|
| OpenCV 4.10.0 | <10ms |
| FaceAnalyzer (MediaPipe FaceLandmarker) | ~160ms |
| PoseAnalyzerBridge (ONNX Runtime, 2 models) | ~710ms |
| TemporalSmoother | <1ms |
| AudioAlerter (TTS) | <10ms |
| **Total cold start** | **~900ms** |

## Inference Performance (per frame)

> **Source**: Startup logs from initial device run. Sustained-run stats pending — will update after next `capture-logs.sh` session.

| Metric | Value | Notes |
|--------|-------|-------|
| **Target frame rate** | 1 fps | Governed by `INFERENCE_INTERVAL_MS = 1000` |
| **Typical frame time** | 180–770ms | Within 1fps budget |
| **Pose (YOLOv8n-pose + detect)** | TBD | Need sustained-run data |
| **Face (MediaPipe + solvePnP)** | TBD | Need sustained-run data |
| **Occasional spikes** | ~1000ms | GC pauses or thermal throttling |

## Memory Usage

> Pending sustained-run data from `parse-perf.sh`.

| Metric | Value | Notes |
|--------|-------|-------|
| **Process heap (startup)** | TBD | From periodic stats |
| **Java heap (steady state)** | TBD | From periodic stats |
| **Native heap (steady state)** | TBD | ONNX Runtime + V4L2 buffers |

## Model Sizes

| Model | Size | Format |
|-------|------|--------|
| yolov8n-pose.onnx | 12.9 MB (13,514,570 bytes) | ONNX FP32 |
| yolov8n.onnx | 12.3 MB (12,851,047 bytes) | ONNX FP32 |
| face_landmarker.task | 3.5 MB (3,758,596 bytes) | TFLite float16 |
| **APK total** | ~65 MB | Debug build |

## BSP Quirks

| Quirk | Impact | Workaround |
|-------|--------|------------|
| `odk_hook_module` | Blocks UVC driver binding | `rmmod odk_hook_module` each boot |
| Camera service disabled | Camera2 API sees no cameras | V4L2 direct access |
| User 10 | `pm grant` needs `--user 10` | Documented in setup |
| Device node permissions | V4L2 open fails | `chmod 666` after each webcam reconnect |
| SELinux permissive | No SELinux blocks | No action needed |

## Thermal & Sustained Performance

> Not yet characterized. Will update after extended runs (5+ minutes).

| Metric | Value | Notes |
|--------|-------|-------|
| Thermal throttling observed? | TBD | Monitor via frame time trends |
| Sustained FPS after 5 min | TBD | |
| CPU frequency scaling | TBD | Check `/sys/devices/system/cpu/cpu*/cpufreq/` |

---

## How to Collect Data

```bash
# Capture logs during a device run
./scripts/capture-logs.sh --duration 120

# Parse into performance report
./scripts/parse-perf.sh logs/device_run_<timestamp>.log
```

Update this file with the parsed results after each significant device run.
