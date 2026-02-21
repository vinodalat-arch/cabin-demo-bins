# Honda SA8155P Device Setup Guide

## Device Info
- **Platform**: Qualcomm SA8155P (Kryo 485, 8 cores)
- **Manufacturer**: ALPSALPINE IVI-SYSTEM
- **OS**: Android 14, SDK 34
- **RAM**: 7.6 GB
- **Kernel**: 6.1.145
- **SELinux**: Permissive

## Prerequisites
- ADB connection to the device
- Logitech C270 (or compatible UVC webcam) connected via USB
- Built APK at `app/build/outputs/apk/debug/app-debug.apk`

---

## First-Time Setup

### 1. Install APK
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 2. Grant Permissions
The app runs as Android user 10 on this BSP. Use `--user 10`:
```bash
adb shell pm grant --user 10 com.incabin android.permission.CAMERA
```

> **Note**: `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_CAMERA` are install-time permissions declared in the manifest. They are granted automatically and cannot be granted via `pm grant`.

---

## Boot Auto-Start (SA8155/SA8295)

On automotive BSPs, the app auto-starts on boot via `BootReceiver`. After the device boots:
1. `BootReceiver` receives `ACTION_BOOT_COMPLETED`
2. Detects automotive platform → calls `startForegroundService()`
3. Service initializes and begins monitoring automatically

**Note**: Boot auto-start requires the ODK module to already be removed and camera permissions set. If these are not in place (e.g., fresh boot on unmodified BSP), the service will start but camera will fail until manual setup is done. See BSP-TODO.md for permanent BSP-level fixes.

To disable auto-start, uninstall the APK or revoke `RECEIVE_BOOT_COMPLETED` permission.

---

## Per-Boot Setup (Manual)

These steps must be repeated each time the device boots (unless BSP is modified per BSP-TODO.md).

### 1. Remove ODK Hook Module
The `odk_hook_module` kernel module blocks the UVC driver from binding to USB webcams:
```bash
adb shell rmmod odk_hook_module
```

### 2. Reconnect Webcam
Physically **unplug and replug** the USB webcam cable. The kernel's built-in `uvcvideo` driver will bind and create `/dev/videoN` nodes.

### 3. Verify Webcam Detected
```bash
adb shell "ls -la /dev/video*"
adb shell "for d in /sys/class/video4linux/video*; do echo \$(basename \$d): \$(cat \$d/name 2>/dev/null); done"
```

Expected output (device numbers may vary):
```
video0: cam-req-mgr        ← Qualcomm internal, app skips
video1: cam_sync            ← Qualcomm internal, app skips
video2: C270 HD WEBCAM      ← USB webcam
video3: C270 HD WEBCAM      ← USB webcam (metadata node)
```

### 4. Fix Device Node Permissions
The device nodes are created as `660 system:camera`. The app cannot open them without wider permissions:
```bash
adb shell chmod 666 /dev/video2 /dev/video3
```

> **Note**: Adjust `video2`/`video3` to match the actual device numbers from step 3. This resets every time the webcam is reconnected.

---

## Starting the Service

```bash
adb shell am start-foreground-service -a com.incabin.START com.incabin/.InCabinService
```

## Monitoring

```bash
adb logcat -s 'InCabin:*' 'InCabin-V4L2:*' 'InCabin-JNI:*' 'InCabin-Camera:*' 'AudioAlerter:*'
```

### Expected Startup Log
```
I/NativeLib: Native library 'incabin' loaded successfully
I/InCabin:  === Device Info ===
I/InCabin:  Device: ALPSALPINE IVI-SYSTEM, Android 14, SDK 34
I/InCabin:  CPUs: 8, RAM: 7678 MB
I/InCabin:  === Asset Verification ===
I/InCabin:  Asset yolov8n-pose.onnx: 13514570 bytes
I/InCabin:  Asset yolov8n.onnx: 12851047 bytes
I/InCabin:  Asset face_landmarker.task: 3758596 bytes
I/InCabin:  === Component Init ===
I/InCabin:  OpenCV 4.10.0 initialized
I/InCabin:  FaceAnalyzer initialized (~160ms)
I/InCabin:  PoseAnalyzerBridge initialized (~710ms)
I/InCabin:  TemporalSmoother initialized
I/InCabin:  AudioAlerter initialized
I/PipelineWatchdog: Watchdog started (timeout=30000ms, interval=5000ms)
I/InCabin:  === Service Ready ===
I/InCabin-JNI: V4L2: Scanning /dev/video*...
I/InCabin-JNI: V4L2: Found capture device: /dev/video2 (C270 HD WEBCAM)
I/InCabin-JNI: V4L2: Configured YUYV 1280x720
I/InCabin-JNI: V4L2: 4 mmap buffers allocated
I/InCabin-JNI: V4L2: Streaming started
I/InCabin:  Using V4L2 camera
I/InCabin-V4L2: First V4L2 frame: 1280x720, 2764800 bytes BGR
I/InCabin:  {"passenger_count":1,...,"risk_level":"low",...}
```

## Stopping the Service

```bash
adb shell am start-foreground-service -a com.incabin.STOP com.incabin/.InCabinService
```

Or force stop:
```bash
adb shell am force-stop com.incabin
```

---

## Troubleshooting

### V4L2: No capture device found
- ODK hook not removed: `adb shell rmmod odk_hook_module`
- Webcam not reconnected after rmmod: physically replug USB cable
- Verify with `ls /dev/video*` and check names via sysfs

### V4L2: permission denied (need chmod 666)
- Device nodes are `660 system:camera`
- Fix: `adb shell chmod 666 /dev/video2 /dev/video3`
- Must redo after each webcam reconnect

### SecurityException: Starting FGS with type camera
- CAMERA permission not granted for the correct user
- Fix: `adb shell pm grant --user 10 com.incabin android.permission.CAMERA`
- Check which user with: `adb shell am get-current-user`

### V4L2 not available, falling back to Camera2 / No camera found
- Both V4L2 and Camera2 failed. Camera2 will always fail on this BSP (`config.disable_cameraservice=true`)
- Follow the per-boot setup steps above to enable V4L2

### Dashboard shows "Stalled" camera status
- Service heartbeat hasn't been received in 15+ seconds
- Pipeline watchdog will auto-restart camera after 30s stall
- Check crash log: `adb shell cat /data/data/com.incabin/files/crash_log.txt`
- If persistent, check for inference errors or camera hardware issues

### Viewing the persistent crash log
```bash
adb shell cat /data/data/com.incabin/files/crash_log.txt
adb shell cat /data/data/com.incabin/files/crash_log_prev.txt  # rotated log
```

### Frame timing exceeds 1000ms
- Typical: 180-770ms per frame on Kryo 485
- Occasional spikes to ~1000ms are normal during GC or thermal throttling
- If consistently over 1000ms, consider INT8 quantized models

---

## Quick Reference (Copy-Paste)

Full per-boot sequence after device powers on:
```bash
adb shell rmmod odk_hook_module
# physically reconnect webcam USB cable
adb shell "for d in /sys/class/video4linux/video*; do echo \$(basename \$d): \$(cat \$d/name 2>/dev/null); done"
adb shell chmod 666 /dev/video2 /dev/video3
adb shell am start-foreground-service -a com.incabin.START com.incabin/.InCabinService
adb logcat -s 'InCabin:*' 'InCabin-V4L2:*' 'InCabin-JNI:*' 'InCabin-Camera:*' 'AudioAlerter:*'
```
