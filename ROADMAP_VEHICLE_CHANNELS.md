# Vehicle Channel Roadmap

Multi-modal alert channels for SA8155/SA8295 IVI deployment.

## Phase 1 — Standard VHAL (current implementation)

Standard Android Automotive VHAL properties, probed at runtime. Graceful no-op if property missing.

| Channel | VHAL Property | Property ID | Status |
|---|---|---|---|
| CabinLightChannel | CABIN_LIGHTS_SWITCH | 0x0F4004 | Implemented |
| CabinLightChannel | SEAT_FOOTWELL_LIGHTS_SWITCH | 0x0F4007 | Implemented |
| SeatHapticChannel | SEAT_LUMBAR_FORE_AFT_MOVE | 0x0B8E | Implemented |
| SeatThermalChannel | HVAC_SEAT_TEMPERATURE | 0x050C | Implemented |
| SteeringHeatChannel | HVAC_STEERING_WHEEL_HEAT | 0x050E | Implemented |
| WindowChannel | WINDOW_MOVE | 0x0BC0 | Implemented |
| AdasChannel | DRIVER_DISTRACTION_STATE | 0x060A | Implemented |
| AdasChannel | DRIVER_DROWSINESS_ATTENTION_STATE | 0x060B | Implemented |

## Phase 2 — SA8155 VHAL Alignment

Requires on-device probing to determine which standard VHAL properties the Honda SA8155P BSP actually exposes.

- [ ] Run `adb shell dumpsys car_service` to list available VHAL properties
- [ ] Map actual Honda SA8155 VHAL property IDs to channel implementations
- [ ] Tune pulse patterns/durations for specific hardware response curves
- [ ] Add car permissions to DeviceSetup automated `pm grant` flow
- [ ] On-device validation of each channel (cabin lights, seat, steering, window)
- [ ] Verify ADAS state writes are accepted (some BSPs make these READ-ONLY)

## Phase 3 — OEM Vendor Extension Properties

Requires Honda cooperation for vendor-specific VHAL extensions.

| Feature | Notes |
|---|---|
| Seat vibration/massage motor | OEM vendor property, not in standard VHAL |
| Steering wheel vibration | OEM vendor property |
| Ambient lighting RGB/patterns | OEM vendor property for addressable LEDs |
| HUD content rendering | OEM InstrumentClusterRenderingService |
| Instrument cluster rendering | Requires system app signing |
| Seatbelt pretensioner tug | READ-ONLY in standard VHAL |
| Brake/speed intervention | ADAS ECU via CAN gateway — safety-critical, OEM-only |

## Escalation Model

| Level | Duration | Audio | Vehicle Actions |
|---|---|---|---|
| L1 Nudge | 0s | Chime + dashboard label | — |
| L2 Warning | 5s | TTS + notification | — |
| L3 Urgent | 10s | Beep + TTS | Footwell lights flash, lumbar pulse |
| L4 Intervention | 20s | Beep loop + TTS | + Seat thermal pulse, steering heat |
| L5 Emergency | 30s+ | Continuous alarm | + Window crack, ADAS DMS state write |

### Per-Detection Caps

| Detection | Max Level | Rationale |
|---|---|---|
| Phone, Eyes, Hands Off, Distracted | L5 | Safety-critical |
| Yawning | L4 | Drowsiness — serious but not emergency |
| Eating, Posture, Child Slouch | L3 | Advisory — should not trigger vehicle intervention |
