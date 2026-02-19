# Honda SA8155P Device Specification

Queried on 2026-02-19 from live device via ADB.

---

## Platform Identity

| Property | Value |
|---|---|
| SoC | Qualcomm SA8155P (Snapdragon 855 Automotive) |
| SoC ID | 367 |
| SoC Revision | 2.2 |
| SoC Serial | 1129737064 |
| Chip Family | 0x56 |
| Device Tree | `qcom,sa8155p-v2-adp-air qcom,sa8155p qcom,adp-air` |
| Board Platform | `msmnile` |
| Hardware | `qcom` |
| Manufacturer | ALPSALPINE |
| Brand | Honda |
| Model | IVI-SYSTEM |
| Device | `msmnile_au` |
| Product Name | `pf23_s34_g` |
| Serial Number | `fe016620` |

## Android

| Property | Value |
|---|---|
| Android Version | 14 |
| API Level | 34 |
| Build ID | `pf23_s34_s_g-userdebug 14 AEA1416D0.260122.1` |
| Build Type | `userdebug` |
| Build Flavor | `pf23_s34_s_g-userdebug` |
| Security Patch | 2026-01-01 |
| SELinux | Permissive |
| Encryption | Encrypted |
| Debuggable | Yes (`ro.debuggable=1`) |
| ADB Secure | No (`ro.adb.secure=` empty) |

## Kernel

```
Linux 6.1.145-android14-11-g08d2cd39b3b2-ab14503555
SMP PREEMPT Wed Nov 26 21:14:03 UTC 2025
Compiler: Android Clang 17.0.2 (r487747c), LLD 17.0.2
```

## CPU

**8 cores, 3 clusters (Qualcomm Kryo 485 — ARM v8.2-A)**

| Cluster | Cores | CPU Part | Max Freq | Governor | Role |
|---|---|---|---|---|---|
| Silver (efficiency) | 0-3 | 0x805 (Kryo 485 Silver / Cortex-A55) | 1785.6 MHz | `performance` (locked) | Background/efficiency |
| Gold (performance) | 4-6 | 0x804 (Kryo 485 Gold / Cortex-A76) | 2131.2 MHz | `schedutil` | General workloads |
| Prime (high-perf) | 7 | 0x804 (Kryo 485 Prime / Cortex-A76) | 2419.2 MHz | `schedutil` | Peak single-thread |

**Available Frequencies:**
- Silver (cores 0-3): 300 – 1785.6 MHz (18 steps)
- Gold (cores 4-6): 710.4 – 2131.2 MHz (14 steps)
- Prime (core 7): 825.6 – 2419.2 MHz (16 steps)

**CPU Features:** `fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm lrcpc dcpop asimddp`

**BogoMIPS:** 38.40 (all cores)

**Note:** Silver cluster is governor-locked to `performance` (always max). Gold/Prime use `schedutil` (dynamic).

## Memory

| Property | Value |
|---|---|
| Total RAM | 7,862,448 KB (~7.5 GB) |
| Available | ~4.9 GB (typical) |
| Swap | 2,097,148 KB (~2.0 GB) |
| DVM Heap Size | 512 MB (`dalvik.vm.heapsize`) |
| Heap Start Size | 8 MB |
| Heap Target Utilization | 0.75 |

## GPU

| Property | Value |
|---|---|
| GPU | Adreno 640 (SA8155P variant) |
| OpenGL ES Version | 3.2 (`ro.opengles.version=196610`) |
| GPU Memory (total) | ~151 MB allocated |
| GPU Profiler | Supported |
| Driver | System (built-in, no Game Driver) |
| HW Compositing | `debug.sf.hw=0`, `debug.egl.hw=0` (SW path) |
| HDR Support | HLG (2), HDR10 (3), HDR10+ (4) |

## Display

| Property | Value |
|---|---|
| Panel | Built-in (automotive landscape) |
| Resolution | 1920 x 720 |
| Usable App Area | 1744 x 720 (176px navigation bar) |
| Density | 160 dpi |
| Pixel Density | 167.0 x 167.8 dpi (actual) |
| Refresh Rate | 60 Hz |
| Color Mode | sRGB (mode 0) |
| HDR Capable | HLG, HDR10, HDR10+ |
| Max Luminance | 500 nits |
| Orientation | Landscape (fixed, ROTATION_0) |
| HWC Display | QCM panel, port 129 |

## Storage

| Device | Model | Size | Notes |
|---|---|---|---|
| `/dev/block/sda` | Kioxia THGAFEG9T23BAZZA | ~54.4 GB (114M blocks × 512B) | eMMC/UFS, non-rotational. System, data, metadata |
| `/dev/block/sde` | Kioxia THGAFEG9T23BAZZA | ~5.0 GB (10.4M blocks × 512B) | Firmware, DSP, BT firmware |

**Key Partitions:**

| Mount | Device | Size | Used | Notes |
|---|---|---|---|---|
| `/` (root) | dm-1 | 0.9 GB | 100% | Read-only system |
| `/data` | dm-41 (sda19) | — | — | Encrypted user data |
| `/metadata` | sda18 | 62 MB | 56% | |
| `/mnt/scratch` | dm-6 | 14 GB | 3% | Overlays |
| `/vendor/firmware_mnt` | sde4 | 170 MB | 31% | |
| `/mnt/product/persist` | sda3 | 976 MB | ~0% | |
| `/mnt/product/log` | sda7 | 359 MB | 20% | |

## USB

**Webcam (connected):**

| Property | Value |
|---|---|
| Device | Logitech C270 HD WEBCAM |
| USB Vendor ID | 046d (Logitech) |
| USB Product ID | 0825 |
| USB Version | 6.07 |
| Serial | 84882660 |
| USB Bus | 001, Device 003 |
| V4L2 Nodes | `/dev/video2` (capture), `/dev/video3` (metadata) |
| V4L2 Major:Minor | 81:15 (video2), 81:16 (video3) |
| Permissions | `crw-rw-rw-` (after `chmod 666`, resets on reconnect) |

**USB Host Controllers:** 4 xHCI controllers (bus 001-004)

**Internal V4L2 Devices (Qualcomm, skip):**

| Node | Name | Notes |
|---|---|---|
| `/dev/video0` | `cam-req-mgr` | Qualcomm camera request manager |
| `/dev/video1` | `cam_sync` | Qualcomm camera sync |
| `/dev/video32` | (unnamed) | Qualcomm internal |
| `/dev/video33` | (unnamed) | Qualcomm internal |
| `/dev/video51` | `v4l2loopback-video051` | Virtual loopback |

## Networking

| Interface | Status | Details |
|---|---|---|
| `wlan0` | Active (STA) | Connected to WiFi, IP 192.168.1.3 |
| `wlan1` | Down | Secondary WiFi (unused) |
| `wlan2` | Up (AP) | SoftAP on 2462 MHz |
| `p2p0` | Down | WiFi Direct |
| `eth0` | Down (NO-CARRIER) | Ethernet (VLAN-capable: eth0_v11, eth0_v21) |
| `can0` | Down | CAN bus interface |

**WiFi Details:**
- SSID: `Airtel-MyWiFi-AMF-311WW-A180`
- Security: WPA2-PSK
- Frequency: 2462 MHz (Channel 11, 2.4 GHz)
- Link Speed: 72 Mbps (Tx/Rx)
- WiFi Standard: 4 (Wi-Fi 4 / 802.11n)
- RSSI: -36 dBm (excellent)
- Saved networks: `AO_Honda_OTA`, `Airtel-MyWiFi-AMF-311WW-A180`

## Bluetooth

| Property | Value |
|---|---|
| Status | Enabled |
| Name | Honda HFT |
| Address | 30:DF:17:A7:D5:1B |
| A2DP Offload | Enabled |
| Max Audio Devices | 1 |
| Profiles | A2DP Sink, AVRCP Controller, HFP-HF, HID Host, MAP Client, OPP, PBAP Client |
| Scan Mode | SCAN_MODE_NONE |

## Audio

| Stream | Volume | Max | Device | Muted |
|---|---|---|---|---|
| VOICE_CALL | 5 | 5 | bus(1000000) | No |
| SYSTEM (→RING) | 7 | 7 | bus(1000000) | No |
| RING | 7 | 7 | bus(1000000) | No |
| MUSIC | 15 | 15 | bus(1000000) | No |
| ALARM | — | — | bus(1000000) | — |

**Note:** All audio routes through `bus(1000000)` — automotive audio bus. `STREAM_ALARM` may not route to speakers on this BSP (ToneGenerator on ALARM stream was inaudible during testing). `USAGE_ASSISTANCE_SONIFICATION` and `USAGE_MEDIA` (TTS) work.

**Audio HALs:** `honda-hw-audio-hal`, `qti-audiocontrol-hal-aidl`, `vendor.audio-hal`, `vendor.audio_hal_plugin_service`

**Audio Config:** Deep buffer enabled, offload enabled (min 30s), offload buffer 32KB

## Thermal Zones

| Zone | Type | Temp (milli°C) |
|---|---|---|
| 0 | aoss-0 | 43,000 (43°C) |
| 1 | cpu-0-0 | 46,100 (46.1°C) |
| 8 | aoss-1 | 47,300 (47.3°C) |
| 11 | ddr | 42,200 (42.2°C) |

**All thermal zone types:** aoss-0, cpu-0-0, cpu-1-3, cpu-1-4, cpu-1-5, cpu-1-6, cpu-1-7, gpuss-0, aoss-1, cwlan, video, ddr, cpu-0-1, q6-hvx, camera, cmpss, mdm-core, mdm-vec, mdm-scl, gpuss-1

## Dalvik/ART

| Property | Value |
|---|---|
| Heap Size | 512 MB |
| Heap Start | 8 MB |
| Heap Max Free | 8 MB |
| Heap Min Free | 512 KB |
| Target Utilization | 0.75 |
| JIT | Enabled |
| dex2oat threads | 2 (on CPU set 0,1) |
| dex2oat memory | 64m – 512m |
| Image format | lz4 |
| 64-bit | Yes |

## BSP Configuration (Key Properties)

```properties
# Camera service disabled — V4L2 is the only path for USB webcams
config.disable_cameraservice=true
config.disable_networktime=true
config.disable_rtt=true
config.disable_systemtextclassifier=true

# Android Automotive
android.car.user_hal_enabled=true

# Audio
audio.deep_buffer.media=true
audio.offload.gapless.enabled=true
audio.offload.video=true

# Bluetooth
bluetooth.profile.a2dp.sink.enabled=true
bluetooth.profile.hfp.hf.enabled=true
bluetooth.profile.map.client.enabled=true
```

## Honda BSP Services (Running)

Key Honda-specific services running on the device:
- `honda-hw-audio-hal` — Honda audio HAL
- `hondausbserv` — Honda USB service
- `np-honda-hal-lanewatchcamera` — Lane watch camera HAL
- `np-honda-hal-multiviewcamera` — Multi-view camera HAL
- `np-honda-hal-rearwidecamera` — Rear wide camera HAL
- `np-honda-hal-appleic-1.1` — Apple iAP/CarPlay IC
- `np-honda-hal-iap-1.1` — iAP protocol
- `np-honda-hal-keymanager-1.1` — Key management
- `np-honda-hal-logstorage` — Log storage
- `variantcoding-hal-1.1` — Variant coding
- `resetmanagerdaemon` — Reset manager
- `vendor.antitheft-hal-1-1` — Anti-theft
- `vendor.battery-hal-1-1` — Battery HAL
- `vendor.cansetting-hal-1-1` — CAN bus settings

**Qualcomm Services:** `vendor.ais_server` (camera), `qcarcam_hal`, `evs_aidl_driver`, `evs_driver`, `qseecom-service` (secure execution), `thermal-engine`, `time_daemon`, `vendor.cnss-daemon` (WiFi)

## Kernel Modules (Key)

| Module | Size | Notes |
|---|---|---|
| `qcn7605` | 7.4 MB | Qualcomm WiFi driver |
| `cnss_nl` | 28 KB | WiFi netlink |
| `odk_configs` | 69 KB | ODK configuration (related to `odk_hook_module` that blocks UVC) |
| `hdmi_dlkm` | 36 KB | HDMI |
| `glink_pkt` | 45 KB | Qualcomm IPC |

**Note:** `odk_hook_module` is NOT loaded (was removed via `rmmod` per deployment procedure). `odk_configs` remains loaded.

## Honda Packages (Pre-installed)

~120+ Honda IVI packages including:
- **Core:** `com.honda.auto.internal.core`, `com.honda.ivi.systemdaemon`, `com.honda.ivi.vehicledaemon`
- **Media:** `com.honda.ivi.am` (AM radio), `com.honda.ivi.fm`, `com.honda.ivi.dab`, `com.honda.ivi.dtv`, `com.honda.ivi.usbaudiovideo`, `com.honda.ivi.btaudio`
- **Connectivity:** `com.honda.ivi.androidauto`, `com.honda.ivi.carplay`, `com.honda.ivi.hondalink`, `com.honda.ivi.smartphonecooperation`
- **Camera:** `com.honda.ivi.camera`, `com.honda.ivi.camerasettings`
- **Settings:** `com.honda.ivi.settings`, `com.honda.ivi.systemsettings`, `com.honda.ivi.soundsettings`, `com.honda.ivi.displaysettings`, `com.honda.ivi.wifisettings`, `com.honda.ivi.btsettings`
- **Vehicle:** `com.honda.ivi.meter`, `com.honda.ivi.powerflow`, `com.honda.ivi.trip`, `com.honda.ivi.compass`, `com.honda.ivi.hvaccontrol`, `com.honda.ivi.massageseat`
- **Voice:** `com.honda.ivi.alexa`, `com.honda.ivi.voicesetting`
- **Misc:** `com.honda.ivi.bugreport`, `com.honda.ivi.diagservice`, `com.honda.ivi.analytics`, `com.honda.ivi.systemupdate`

## Our App (com.incabin)

| Property | Value |
|---|---|
| Package | `com.incabin` |
| App ID | 10302 |
| Version | 1.0 (versionCode 1) |
| Min SDK | 29 |
| Target SDK | 34 |
| ABI | arm64-v8a |
| Flags | DEBUGGABLE, HAS_CODE, ALLOW_CLEAR_USER_DATA |
| Signing | APK v2 (debug keystore) |
| Install Path | `/data/app/~~l9zbQgEqBX_xS1qj38ZStA==/com.incabin--RXiYtHLFrQUCB_IQVR9Rg==` |
| Data Dir | `/data/user/0/com.incabin` |
| Native Libs | Extracted (`extractNativeLibs=true`) |

## App Runtime Monitoring (com.incabin)

Captured 2026-02-19 15:26–15:28 via `dumpsys meminfo` at 2s intervals + app internal stats logs.

### Memory Startup Profile

| Time | Phase | Java Heap | Native Heap | Notes |
|---|---|---|---|---|
| +0s | Process launch | 0.1 MB | 0.2 MB | Cold start |
| +2s | Early init | 3.2 MB | 1.1 MB | Android components |
| +4s | Loading models | 3.9 MB | 4.5 MB | MediaPipe init |
| +6s | Loading models | 4.0 MB | 4.6 MB | |
| +9s | ONNX loading | 5.5 MB | 79.0 MB | First ONNX model loaded |
| +11s | ONNX + MediaPipe | 11.6 MB | 168.2 MB | Both models loaded |
| +14s | **Steady state** | **19.9 MB** | **170.2 MB** | First inference complete |

### Memory Steady State (30+ seconds)

| Metric | Value |
|---|---|
| Java Heap | 17.2 – 20.1 MB (minor GC dips to ~17MB) |
| Native Heap | 168.5 – 171.2 MB |
| Total App Footprint | ~227 MB (~3% of 7.5 GB RAM) |
| Memory Leak | **None detected** — stable across 180+ frames in longest session |

### CPU / Frame Performance (from app internal stats, all sessions 2026-02-19)

| Session | Frames | Avg | Min | Max | Heap | Native | Notes |
|---|---|---|---|---|---|---|---|
| 13:26 | 30 | 555ms | 188ms | 670ms | 19MB | 229MB | |
| 13:43 | 90 | 397–612ms | 180ms | 692ms | 19MB | 207–208MB | |
| 13:55 | 90 | 585–636ms | 185ms | 708ms | 19MB | 207–208MB | |
| 14:00 | 60 | 587–621ms | 194ms | 697ms | 19MB | 208MB | |
| 14:01 | 90 | 596–666ms | 192ms | 832ms | 19MB | 207–208MB | distraction=21s peak |
| 14:09 | 30 | 643ms | 184ms | 847ms | 19MB | 207MB | |
| 14:45 | 180 | 627–675ms | 191ms | 975ms | 19MB | 208–209MB | longest session |
| 14:49 | 30 | 662ms | 188ms | 839ms | 19MB | 207MB | |
| 14:55 | 60 | 592–624ms | 183ms | 734ms | 19MB | 207–208MB | |
| 14:57 | 30 | 588ms | 199ms | 723ms | 19MB | 207MB | |
| 15:10 | 30 | 597ms | 196ms | 966ms | 19MB | 207MB | |
| 15:27 | 30 | 593ms | 188ms | 700ms | 19MB | 208MB | |

### Performance Summary

| Metric | Value |
|---|---|
| Avg frame time (typical) | **~620 ms** |
| Min frame time | ~180–199 ms (first frames, warm cache) |
| Max frame time | ~700–975 ms (occasional spikes) |
| Frame cadence | ~0.85s (webcam ~1fps + inference) |
| Detection latency | ~2–3s (smoother window=3) |
| Model load time | ~10–14s from cold start to first inference |
| Java Heap (steady) | **19 MB** (rock solid) |
| Native Heap (steady) | **207–209 MB** (stable, no leak) |

### Native Heap Breakdown (estimated)

| Component | Size | Notes |
|---|---|---|
| ONNX Runtime (pose model) | ~80 MB | YOLOv8n-pose FP32 + runtime buffers |
| ONNX Runtime (detect model) | ~80 MB | YOLOv8n FP32 + runtime buffers |
| Pre-allocated buffers | ~6 MB | letterbox_buf, tensor_buf, crop_buf |
| V4L2 mmap buffers | ~3.5 MB | 2 buffers × 1280×720×2 (YUYV) |
| Other (JNI, libs) | ~38 MB | OpenCV, MediaPipe native, misc |

## System Uptime

At time of query: **7528.71 seconds (~2 hours 5 minutes)** since boot.

## Timezone

`Australia/Sydney`
