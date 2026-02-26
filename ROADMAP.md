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
- **Test coverage required**: Every new feature must include unit tests. No feature is complete until tests pass alongside all existing tests (currently 583).
- **Track APK size**: Current APK is 84 MB. New assets (models, images) must justify their size. Prefer lightweight solutions — a 4 MB model is acceptable, a 50 MB model needs strong justification.
- **Avoid per-frame allocations**: The pipeline runs at ~1 fps on constrained hardware. Any per-frame allocation (bitmaps, byte arrays, JNI objects) risks GC pressure that degrades detection latency. Pre-allocate buffers, reuse objects, and avoid creating garbage in the hot path.
- **Specs define behavior and constraints, not implementation**: Feature specs should describe what the user sees, how the app behaves, and what constraints apply (performance, safety, BSP quirks). Leave technical design decisions — data structures, concurrency patterns, persistence mechanisms, class names, annotations — to the implementer. Say *"persist across restarts"*, not *"use SharedPreferences with key X"*.
- **Post-implementation critical review**: After every feature implementation, critically assess the entire design — not just the new code, but how it interacts with all existing components. Identify bugs, performance regressions, thread-safety issues, edge cases, and architectural debt introduced by the change. Fix high-priority and safety-critical issues immediately. Document lower-priority findings as recommendations for future work. This review is part of the feature — a feature is not complete until the review is done and critical fixes are shipped.

---

## Completed Features

All features below have been fully implemented, tested, and documented in `SPEC.md` and `CLAUDE.md`:

1. Automated Camera Setup Flow (DeviceSetup)
2. Webcam Preview Toggle
3. Multi-Platform Support (SA8155/SA8295/generic/emulator)
4. WiFi Camera (MJPEG) Support
5. Face Registration (independent camera)
6. Seat-Side Driver Identification
7. Settings/Status UI Separation (5-tap hidden overlay)
8. IVI Deployment Robustness (boot auto-start, watchdog, crash log, memory policy)
9. Multi-Modal Alert Orchestrator (5-level escalation + vehicle channels)
10. Remote VLM Inference (HTTP polling + configurable FPS)

---

<!-- Future features go here -->
