# Honda SA8155P — VHAL & Theme Discovery Results

## Discovery Date: 2026-03-04

## Device Info
- ALPSALPINE IVI-SYSTEM, SA8155P, Android 14
- Display: 3510x720 (dual-zone, 1920+1590 split)
- VHAL backend: AIDL
- Car API: v34.0

---

## VHAL Properties Found

### Standard AOSP Properties (confirmed available)
| Property | ID | Type | Access | Current Value |
|---|---|---|---|---|
| PERF_VEHICLE_SPEED | 0x11600207 | Float | R | 0.0 m/s |
| HVAC_TEMPERATURE_SET | 0x15600503 | Float | RW | 18.0°C (zone 0x31 + 0x44) |
| HVAC_POWER_ON | 0x15200510 | Int | RW | 0 (zone 0x75) |
| NIGHT_MODE | 0x11200407 | Int | R | 0 (day) |

### Honda Vendor Properties (0x217xxxxx range)
~90+ vendor properties discovered. All named as raw hex IDs (no symbolic names in VHAL).

**Ambient Light group** (0x21700200-0x21700230):
- 0x21700200: write-only (likely command)
- 0x21700201: read-only, bytes[20] → current value: `[-1, 0, 0, 0, ...]` (likely zone colors as RGB bytes)
- 0x21700204: read-only, bytes[9] → `[-1, 0, 0, ...]` (zone status?)
- 0x21700205: read-only, bytes[9] → same pattern
- Properties alternate read/write in pairs (0x201=R, 0x202=W, 0x204=R, etc.)

**Lighting group** (0x21700300-0x2170030a):
- All read-only, likely cabin/instrument light status

**Vehicle status group** (0x21700400-0x21700422):
- Mix of R and W, likely vehicle settings (cluster brightness, etc.)

**CABIN_LIGHTS (0x0D00)**: NOT supported by this VHAL

### Properties Changed by CarPropertyService (writable, active)
- 0x21202100 — likely vendor command channel
- 0x2120b077 — unknown vendor
- 0x21410800 — unknown vendor
- 0x21414100 — unknown vendor
- 0x21700200 — ambient light command
- 0x21703000-0x21703007 — 8 sequential vendor properties (light zones?)

---

## Honda Theme System

### ThemeManagerStub
- **Package**: `com.honda.ivi.theme.manager`
- **APK**: `/system_ext/priv-app/ThemeManagerStub/ThemeManagerStub.apk` (8KB — STUB only)
- **No activities, services, providers** — just permission declarations
- **Permissions defined**:
  - `com.honda.ivi.theme.permission.ACCESS_THEME_RESOURCES` (signature|privileged)
  - `com.honda.ivi.theme.permission.PROVIDE_THEME` (signature)
- **Uses feature**: `com.honda.software.theme_switching`
- **No content provider** — query attempt fails

**Conclusion**: Theme switching is **not functional** on this BSP. The stub declares the interface but has no implementation. Real theme switching likely requires the full Honda IVI SDK/JAR.

### SystemUI Theme Overlays
Three overlays registered (all enabled):
- `com.android.systemui:neutral`
- `com.android.systemui:accent`
- `com.android.systemui:dynamic`

These are AOSP SystemUI color overlays, not Honda-specific themes.

### Honda Ambient Light App
- **Package**: `com.honda.ivi.ambientlight` (47MB — full app)
- **Activities**: MainActivity, LightingBrightnessActivity, RecommendColorActivity, ThemeColorActivity
- **Uses permission**: `com.honda.ivi.hul.permission.ACCESS_HUL_SERVICE` (Honda Unified Library)
- **Uses**: CAR_VENDOR_CATEGORY_INFO (GET + SET)
- All activities have `distractionOptimized` metadata

This app controls physical cabin ambient lights via vendor VHAL properties through the HUL service.

---

## Honda Wallpaper
- **Default**: `com.honda.ivi.galaxywallpaperservice/GalaxyWallpaperService` (live wallpaper)
- Active for both user 0 and user 10
- Display: 3510x720 (full dual-zone width)

---

## Honda HUL Service (Vehicle Abstraction)
- **Package**: `com.honda.ivi.hul.hulservice`
- **Service**: `com.honda.auto.VendorService` → `HulService`
- **Permission**: `com.honda.ivi.hul.permission.ACCESS_HUL_SERVICE` (required by ambient light, others)
- This is Honda's proprietary vehicle abstraction layer, wrapping VHAL vendor properties

---

## Key Honda IVI Packages (relevant)
| Package | Path | Purpose |
|---|---|---|
| com.honda.ivi.theme.manager | ThemeManagerStub (8KB) | STUB — theme permission declarations only |
| com.honda.ivi.ambientlight | AmbientLight (47MB) | Physical cabin ambient light control |
| com.honda.ivi.galaxywallpaperservice | GalaxyWallpaper | Live wallpaper (default) |
| com.honda.ivi.displaysettings | DisplaySettings | Display brightness/settings |
| com.honda.ivi.massageseat | MassageSeat | Seat massage control |
| com.honda.ivi.hvaccontrol | HvacControl | HVAC temp/fan control |
| com.honda.ivi.hul.hulservice | HulService | Vehicle abstraction (Honda Unified Library) |
| com.honda.ivi.soundsettings | - | Audio settings |
| com.honda.ivi.vehiclesettings | - | Vehicle settings |
| com.honda.personalassistant.personalsettings | - | Personal settings |

---

## What We Can Do Now (without Honda SDK)

### 1. HVAC Temperature — WORKS
- `HVAC_TEMPERATURE_SET (0x15600503)` is standard AOSP, read/write confirmed
- Our existing `ClimateController` already writes this via reflection
- Current value: 18.0°C per zone

### 2. Display Brightness — WORKS
- `settings put system screen_brightness <0-255>` (current: 255)
- Standard Android API, no Honda dependency

### 3. Night Mode — WORKS
- `cmd uimode night yes/no` or `UiModeManager.setNightMode()`
- NIGHT_MODE VHAL property (0x11200407) is read-only (reports vehicle headlight status)
- Android-side night mode is independent and writable

### 4. Ambient Light (Physical) — PARTIALLY POSSIBLE
- Vendor properties exist (0x21700200-0x21700230) with byte array format
- Would need reverse-engineering of the byte protocol (color encoding)
- The Honda AmbientLight app uses HUL service, not direct VHAL writes
- **Risk**: writing wrong values could produce unexpected physical behavior

### 5. Theme Switching — NOT POSSIBLE
- ThemeManagerStub is empty — no theme catalog or switching API
- Need full Honda IVI SDK or the real ThemeManager implementation

### 6. Wallpaper — POSSIBLE BUT LIMITED
- `WallpaperManager.setBitmap()` works for static wallpapers
- Replacing the live GalaxyWallpaper with a static image is possible
- But the live wallpaper is Honda's branded experience — replacing it may not be desirable

---

## Recommendation for Driver Personalization

Since Honda theme switching is non-functional, the **named themes approach** should bundle what we CAN control:

| Theme | Temp | Brightness | Night Mode | Ambient Color | Description |
|---|---|---|---|---|---|
| Comfort | 22.0°C | 200 | Auto | #5B8DEF (Blue) | Balanced defaults |
| Energize | 20.0°C | 255 | Off | #2ECC71 (Green) | Bright, cool, alert |
| Relax | 24.0°C | 150 | On | #9B59B6 (Purple) | Warm, dim, calm |
| Night Drive | 21.0°C | 80 | On | #E74C3C (Red) | Low glare, night visibility |
| Eco | 26.0°C | 180 | Auto | #1ABC9C (Teal) | Energy-saving |

Each theme = one tap in face registration. `DriverProfile` stores `themeId` instead of individual fields. On face recognition, apply all bundled settings.

**What each theme controls**:
- `HVAC_TEMPERATURE_SET` via existing ClimateController ✓
- `screen_brightness` via Settings.System ✓
- Night mode via UiModeManager ✓
- Ambient color → our `AmbientLightController` (software indicator on dashboard) ✓
- Physical ambient lights → deferred until HUL protocol is reverse-engineered

**Implementation status** (2026-03-04): `CabinTheme.kt` + `ThemeApplier.kt` implemented. `DriverProfile.themeId` replaces individual color/temp picker. `FaceRegistrationActivity` shows 5-card theme picker. On face recognition, `ThemeApplier.applyAll()` sets HVAC, brightness, and night mode.

---

## Comprehensive Honda IVI Capabilities

### Rear Camera System
| Interface | Details |
|---|---|
| CarEVS HAL | REARVIEW service type, device at `/dev/video10` |
| `IRearWideCameraHal` | Honda rear wide-angle camera HAL (HIDL service registered) |
| `ILaneWatchCameraHal` | Honda lane watch camera HAL (blind spot view) |
| `IMultiViewCameraHal` | Honda multi-view camera HAL (surround view) |
| **Status** | HAL services registered but no physical rear camera connected (no `/dev/video10` node). Requires physical camera hardware |

### Media & Audio
| Package | Purpose | Access |
|---|---|---|
| `com.honda.ivi.btaudio` | Bluetooth audio streaming | Standard Android media APIs |
| `com.honda.ivi.usbaudiovideo` | USB media playback | Standard Android media APIs |
| `com.honda.ivi.soundsettings` | Audio equalizer, balance, fade | Honda HUL service |
| `com.honda.ivi.soundvolume` | Volume control per source | Honda HUL service |

### Radio & Broadcast
| Package | Purpose | Access |
|---|---|---|
| `com.honda.ivi.radioservice` | AM/FM radio tuner service | Honda radio HAL |
| `com.honda.ivi.dab` | Digital Audio Broadcasting | Honda DAB HAL |
| `com.honda.ivi.fm` | FM radio UI | Intent to radioservice |

### Phone & Bluetooth
| Package | Purpose | Access |
|---|---|---|
| `com.honda.ivi.phone` | Phone dialer, call UI | Android Telecom + BT HFP |
| `com.honda.ivi.btsettings` | Bluetooth pairing, device list | Android BluetoothAdapter |
| `com.honda.ivi.btsettingsservice` | BT settings background service | Bound service |

### Navigation
| Package | Purpose | Access |
|---|---|---|
| `com.honda.ivi.internavi` | Honda InterNavi (built-in navigation) | Honda navigation HAL |
| `com.honda.ivi.mapssettings` | Map display settings | SharedPreferences / content provider |
| `com.honda.ivi.compass` | Digital compass display | Sensor APIs |
| `com.honda.ivi.trip` | Trip computer, fuel economy | VHAL vehicle properties |

### Smart Connectivity
| Package | Purpose | Access |
|---|---|---|
| `com.honda.ivi.carplay` | Apple CarPlay projection | Android projection APIs |
| `com.honda.ivi.androidauto` | Android Auto projection | Android Auto SDK |
| `com.honda.ivi.smartphonecooperation` | Phone mirroring coordinator | Intent-based |
| `com.honda.ivi.hondalink` | Honda connected services | Honda cloud APIs |

### Climate & Comfort
| Package | Purpose | Access |
|---|---|---|
| `com.honda.ivi.hvaccontrol` | HVAC temperature, fan, mode | VHAL `HVAC_TEMPERATURE_SET` (RW), `HVAC_POWER_ON` (RW) |
| `com.honda.ivi.massageseat` | Seat massage control | VHAL vendor properties (0x0B97/0x0B98) |
| `com.honda.ivi.ambientlight` | Physical cabin ambient lights | Honda HUL service + vendor VHAL (0x21700200 range) |

### Voice Assistants
| Package | Purpose | Access |
|---|---|---|
| `com.honda.ivi.alexa` | Amazon Alexa integration | Alexa Auto SDK |
| `com.honda.ivi.voicesetting` | Voice assistant settings | SharedPreferences |
| `com.honda.personalassistant.personalsettings` | Personal assistant preferences | Content provider |

### Vehicle Settings & System
| Package | Purpose | Access |
|---|---|---|
| `com.honda.ivi.vehiclesettings` | Vehicle configuration UI | VHAL properties + Honda HUL |
| `com.honda.ivi.vehicledaemon` | Background vehicle state monitor | VHAL listener |
| `com.honda.ivi.systemsettings` | System-level IVI settings | Android Settings APIs |
| `com.honda.ivi.displaysettings` | Display brightness, theme | Settings.System + UiModeManager |

### Access Requirements Summary
| Capability | Permission / Interface Required |
|---|---|
| HVAC read/write | `android.car.permission.CAR_ENERGY` + Car API |
| Vehicle speed | `android.car.permission.CAR_SPEED` + Car API |
| Display brightness | `android.permission.WRITE_SETTINGS` + Settings.System |
| Night mode | `UiModeManager.setNightMode()` (no special permission) |
| Ambient lights (physical) | `com.honda.ivi.hul.permission.ACCESS_HUL_SERVICE` + HUL binding |
| Seat massage | `android.car.permission.CAR_VENDOR_EXTENSION` + VHAL |
| Camera HALs | CarEVS service binding + camera permissions |
| Radio | Honda radio HAL binding (signature permission) |
| Bluetooth | Standard Android BT permissions |
| Navigation intents | Standard Android intent system |
