#!/usr/bin/env python3
"""
Sample VLM server for In-Cabin AI remote inference mode.

Provides two REST endpoints:
  GET /api/detect  — returns latest detection result from VLM
  GET /api/health  — returns server status

The SA8155 polls /api/detect at ~1-3Hz and feeds the result through its
existing downstream pipeline (smoother, distraction counter, alerts).

Usage:
  pip install fastapi uvicorn opencv-python transformers torch
  python vlm_server.py [--port 8000] [--camera 0] [--model Qwen/Qwen2.5-VL-7B-Instruct]

For quick testing without a real VLM (returns mock detections):
  python vlm_server.py --mock

Requirements:
  - Python 3.10+
  - fastapi, uvicorn, opencv-python
  - For real VLM: transformers, torch (with CUDA recommended)
"""

import argparse
import json
import time
import threading
from datetime import datetime, timezone
from typing import Optional

try:
    from fastapi import FastAPI
    from fastapi.responses import JSONResponse
    import uvicorn
except ImportError:
    print("ERROR: fastapi and uvicorn required. Install with:")
    print("  pip install fastapi uvicorn")
    exit(1)

try:
    import cv2
    HAS_OPENCV = True
except ImportError:
    HAS_OPENCV = False
    print("WARNING: opencv-python not installed. Camera capture disabled.")

# ---------------------------------------------------------------------------
# Global state
# ---------------------------------------------------------------------------
app = FastAPI(title="In-Cabin VLM Server")
latest_result: dict = {}
latest_result_lock = threading.Lock()
server_start_time = time.time()
frame_count = 0
model_name = "mock"
last_client_request_time: float = 0.0
client_request_count: int = 0

# ---------------------------------------------------------------------------
# Detection result template
# ---------------------------------------------------------------------------

def default_result() -> dict:
    """Return a clean (no danger) detection result."""
    return {
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "passenger_count": 1,
        "child_count": 0,
        "adult_count": 0,
        "driver_using_phone": False,
        "driver_eyes_closed": False,
        "driver_yawning": False,
        "driver_distracted": False,
        "driver_eating_drinking": False,
        "hands_off_wheel": False,
        "dangerous_posture": False,
        "child_present": False,
        "child_slouching": False,
        "risk_level": "low",
        "ear_value": None,
        "mar_value": None,
        "head_yaw": None,
        "head_pitch": None,
        "driver_name": None,
        "driver_detected": True,
    }

# ---------------------------------------------------------------------------
# REST endpoints
# ---------------------------------------------------------------------------

@app.get("/api/detect")
def detect():
    global last_client_request_time, client_request_count
    last_client_request_time = time.time()
    client_request_count += 1
    with latest_result_lock:
        if not latest_result:
            return JSONResponse(content=default_result())
        # Update timestamp to now
        result = dict(latest_result)
        result["timestamp"] = datetime.now(timezone.utc).isoformat()
        return JSONResponse(content=result)


@app.get("/api/health")
def health():
    elapsed = time.time() - server_start_time
    fps = frame_count / elapsed if elapsed > 0 else 0
    # Client is "connected" if we got a /api/detect request within the last 5s
    client_age = time.time() - last_client_request_time if last_client_request_time > 0 else -1
    client_connected = 0 < client_age < 5.0
    return JSONResponse(content={
        "status": "ok",
        "model": model_name,
        "fps": round(fps, 2),
        "client_connected": client_connected,
        "client_requests": client_request_count,
        "client_last_seen_s": round(client_age, 1) if client_age >= 0 else None,
    })


# ---------------------------------------------------------------------------
# Mock inference loop (for testing without a real VLM)
# ---------------------------------------------------------------------------

def mock_inference_loop(camera_id: int, fps: float = 2.0):
    """Generate mock detection results, optionally reading camera frames."""
    global latest_result, frame_count

    cap = None
    if HAS_OPENCV:
        cap = cv2.VideoCapture(camera_id)
        if not cap.isOpened():
            print(f"WARNING: Cannot open camera {camera_id}. Running without camera.")
            cap = None

    interval = 1.0 / fps
    print(f"Mock inference loop started (fps={fps}, camera={'ON' if cap else 'OFF'})")

    try:
        while True:
            if cap:
                ret, frame = cap.read()
                if not ret:
                    time.sleep(interval)
                    continue

            result = default_result()

            # Simulate occasional detections for testing
            cycle = int(time.time()) % 30
            if 10 <= cycle < 15:
                result["driver_using_phone"] = True
                result["risk_level"] = "high"
            elif 20 <= cycle < 23:
                result["driver_yawning"] = True
                result["risk_level"] = "medium"

            with latest_result_lock:
                latest_result = result

            frame_count += 1
            time.sleep(interval)
    finally:
        if cap:
            cap.release()


# ---------------------------------------------------------------------------
# Test-all scenario: cycle through every detection for audio alert testing
# ---------------------------------------------------------------------------

# Each entry: (label, field overrides, risk_level, hold_frames, clear_frames)
# hold_frames: how many 1Hz frames to assert the detection (must exceed
#   smoother window=3 + sustained min_frames for each field)
# clear_frames: all-clear gap so AudioAlerter flushes cooldowns before next
TEST_ALL_SEQUENCE = [
    ("Phone",          {"driver_using_phone": True},                              "high",   5, 6),
    ("Eyes closed",    {"driver_eyes_closed": True, "ear_value": 0.15},           "high",   5, 6),
    ("Hands off wheel",{"hands_off_wheel": True},                                 "high",   5, 6),
    ("Yawning",        {"driver_yawning": True, "mar_value": 0.7},               "medium", 5, 6),
    ("Distracted",     {"driver_distracted": True, "head_yaw": 45.0},            "medium", 5, 6),
    ("Eating",         {"driver_eating_drinking": True},                           "medium", 5, 6),
    ("Posture",        {"dangerous_posture": True},                               "medium", 5, 6),
    ("Child slouching",{"child_present": True, "child_slouching": True,
                        "child_count": 1, "passenger_count": 2},                  "medium", 6, 6),
    # Combo: two simultaneous CRITICAL detections
    ("Phone + Eyes",   {"driver_using_phone": True, "driver_eyes_closed": True,
                        "ear_value": 0.12},                                       "high",   5, 6),
]


def test_all_inference_loop(fps: float = 1.0):
    """Cycle through every detection to exercise all audio alerts."""
    global latest_result, frame_count

    interval = 1.0 / fps
    total_frames = sum(hold + clear for _, _, _, hold, clear in TEST_ALL_SEQUENCE)
    total_secs = total_frames * interval
    print(f"=== TEST-ALL scenario: {len(TEST_ALL_SEQUENCE)} detections, "
          f"~{total_secs:.0f}s total at {fps} fps ===")

    while True:
        for label, overrides, risk, hold, clear in TEST_ALL_SEQUENCE:
            # --- Detection phase ---
            print(f"\n>>> [{label}] — asserting for {hold} frames")
            for i in range(hold):
                result = default_result()
                result.update(overrides)
                result["risk_level"] = risk
                with latest_result_lock:
                    latest_result = result
                frame_count += 1
                time.sleep(interval)

            # --- All-clear phase ---
            print(f"    [{label}] — clearing for {clear} frames")
            for i in range(clear):
                result = default_result()
                with latest_result_lock:
                    latest_result = result
                frame_count += 1
                time.sleep(interval)

        print("\n=== Cycle complete — restarting ===\n")


# ---------------------------------------------------------------------------
# Confidence thresholds — VLM confidence must exceed these to flag a detection
# Tuned to reduce false positives: higher = more conservative
# ---------------------------------------------------------------------------
CONFIDENCE_THRESHOLDS = {
    "driver_using_phone": 0.6,
    "driver_eyes_closed": 0.5,
    "driver_yawning": 0.5,
    "driver_distracted": 0.5,
    "driver_eating_drinking": 0.5,
    "hands_off_wheel": 0.6,
    "dangerous_posture": 0.5,
    "child_present": 0.4,
    "child_slouching": 0.5,
}


# ---------------------------------------------------------------------------
# VLM inference loop (real model)
# ---------------------------------------------------------------------------

# Enhanced prompt: asks for per-detection confidence (0.0-1.0) instead of booleans.
# The server applies confidence thresholds before sending booleans to Android.
VLM_PROMPT = """You are a driver monitoring system analyzing an in-cabin camera image.
Analyze the image and return a JSON object with confidence scores (0.0 to 1.0) for each detection.

For each field, 0.0 means definitely not happening, 1.0 means absolutely certain:

{
  "driver_detected": true/false,
  "driver_detected_confidence": 0.0-1.0,
  "passenger_count": <integer>,
  "driver_using_phone": 0.0-1.0,
  "driver_eyes_closed": 0.0-1.0,
  "driver_yawning": 0.0-1.0,
  "driver_distracted": 0.0-1.0,
  "driver_eating_drinking": 0.0-1.0,
  "hands_off_wheel": 0.0-1.0,
  "dangerous_posture": 0.0-1.0,
  "child_present": 0.0-1.0,
  "child_slouching": 0.0-1.0
}

Detection criteria:
- driver_using_phone: Hand holding/touching a phone or device near the face or steering area
- driver_eyes_closed: Both eyelids fully or mostly shut (not just blinking)
- driver_yawning: Mouth wide open in a yawn pattern (not talking/singing)
- driver_distracted: Head turned significantly away from forward direction (>30 degrees)
- driver_eating_drinking: Holding food, drink, bottle, cup near mouth or eating
- hands_off_wheel: BOTH hands clearly not on or near the steering wheel
- dangerous_posture: Leaning heavily sideways, slumped forward, or head drooping
- child_present: A child (small person, child seat) visible in the vehicle
- child_slouching: Child leaning or slouching out of proper seating position

Return ONLY the JSON object, no explanation or markdown."""


def apply_confidence_thresholds(vlm_result: dict) -> dict:
    """Convert VLM confidence scores to booleans using thresholds."""
    result = default_result()

    # Direct fields
    result["driver_detected"] = vlm_result.get("driver_detected", True)
    result["passenger_count"] = max(1, int(vlm_result.get("passenger_count", 1)))

    # Confidence → boolean via thresholds
    for field, threshold in CONFIDENCE_THRESHOLDS.items():
        confidence = vlm_result.get(field, 0.0)
        if isinstance(confidence, (int, float)):
            result[field] = float(confidence) >= threshold
        elif isinstance(confidence, bool):
            # VLM returned a boolean directly — use as-is
            result[field] = confidence

    # Child counting
    child_conf = vlm_result.get("child_present", 0.0)
    if isinstance(child_conf, (int, float)) and float(child_conf) >= CONFIDENCE_THRESHOLDS["child_present"]:
        result["child_count"] = 1
        result["child_present"] = True
    elif isinstance(child_conf, bool) and child_conf:
        result["child_count"] = 1
        result["child_present"] = True

    # Compute risk level from thresholded booleans
    score = 0
    if result.get("driver_using_phone"): score += 3
    if result.get("driver_eyes_closed"): score += 3
    if result.get("hands_off_wheel"): score += 3
    if result.get("driver_distracted"): score += 2
    if result.get("driver_yawning"): score += 2
    if result.get("dangerous_posture"): score += 2
    if result.get("driver_eating_drinking"): score += 1
    if result.get("child_slouching"): score += 1
    result["risk_level"] = "high" if score >= 3 else "medium" if score >= 1 else "low"

    return result


def extract_json_from_text(text: str) -> dict:
    """Extract JSON from VLM output, handling markdown code fences."""
    text = text.strip()
    # Remove markdown code fences if present
    if text.startswith("```"):
        lines = text.split("\n")
        # Remove first and last fence lines
        lines = [l for l in lines if not l.strip().startswith("```")]
        text = "\n".join(lines).strip()
    return json.loads(text)


def vlm_inference_loop(camera_id: int, vlm_model: str, fps: float = 2.0):
    """
    Run actual VLM inference on camera frames.
    Uses enhanced prompt with confidence scoring and adaptive frame pacing.
    """
    global latest_result, frame_count, model_name
    model_name = vlm_model

    try:
        from transformers import Qwen2VLForConditionalGeneration, AutoProcessor
        import torch
    except ImportError:
        print("ERROR: transformers and torch required for real VLM inference.")
        print("  pip install transformers torch")
        print("Falling back to mock mode.")
        mock_inference_loop(camera_id, fps)
        return

    if not HAS_OPENCV:
        print("ERROR: opencv-python required for camera capture.")
        mock_inference_loop(camera_id, fps)
        return

    print(f"Loading VLM model: {vlm_model}")
    processor = AutoProcessor.from_pretrained(vlm_model)
    model = Qwen2VLForConditionalGeneration.from_pretrained(
        vlm_model, torch_dtype=torch.float16, device_map="auto"
    )
    print("VLM model loaded.")

    cap = cv2.VideoCapture(camera_id)
    if not cap.isOpened():
        print(f"ERROR: Cannot open camera {camera_id}")
        return

    target_interval = 1.0 / fps
    print(f"VLM inference loop started (target_fps={fps}, adaptive pacing)")
    inference_times = []

    try:
        from PIL import Image
        import io

        while True:
            frame_start = time.time()

            ret, frame = cap.read()
            if not ret:
                time.sleep(0.1)
                continue

            try:
                # Encode frame as JPEG for VLM input
                _, jpeg = cv2.imencode('.jpg', frame)
                image = Image.open(io.BytesIO(jpeg.tobytes()))

                messages = [
                    {"role": "user", "content": [
                        {"type": "image", "image": image},
                        {"type": "text", "text": VLM_PROMPT},
                    ]}
                ]

                text = processor.apply_chat_template(messages, tokenize=False, add_generation_prompt=True)
                inputs = processor(text=[text], images=[image], return_tensors="pt").to(model.device)

                with torch.no_grad():
                    output_ids = model.generate(**inputs, max_new_tokens=300)

                output_text = processor.batch_decode(
                    output_ids[:, inputs.input_ids.shape[1]:],
                    skip_special_tokens=True
                )[0]

                # Parse VLM output and apply confidence thresholds
                vlm_result = extract_json_from_text(output_text)
                result = apply_confidence_thresholds(vlm_result)

                with latest_result_lock:
                    latest_result = result

                frame_count += 1

                # Track inference time for adaptive pacing stats
                inference_ms = (time.time() - frame_start) * 1000
                inference_times.append(inference_ms)
                if len(inference_times) > 10:
                    inference_times.pop(0)
                avg_ms = sum(inference_times) / len(inference_times)
                actual_fps = 1000.0 / avg_ms if avg_ms > 0 else 0

                if frame_count % 10 == 0:
                    print(f"[VLM Stats] frame={frame_count}, inference={inference_ms:.0f}ms, "
                          f"avg={avg_ms:.0f}ms, actual_fps={actual_fps:.1f}")

            except json.JSONDecodeError as e:
                print(f"VLM JSON parse error: {e}")
            except Exception as e:
                print(f"VLM inference error: {e}")

            # Adaptive pacing: only sleep if inference was faster than target
            elapsed = time.time() - frame_start
            remaining = target_interval - elapsed
            if remaining > 0:
                time.sleep(remaining)
    finally:
        cap.release()


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(description="In-Cabin VLM Server")
    parser.add_argument("--port", type=int, default=8000, help="Server port (default: 8000)")
    parser.add_argument("--camera", type=int, default=0, help="Camera device ID (default: 0)")
    parser.add_argument("--model", type=str, default="Qwen/Qwen2.5-VL-7B-Instruct",
                        help="VLM model name (default: Qwen/Qwen2.5-VL-7B-Instruct)")
    parser.add_argument("--mock", action="store_true", help="Use mock inference (no real VLM)")
    parser.add_argument("--fps", type=float, default=2.0, help="Inference FPS (default: 2.0)")
    parser.add_argument("--host", type=str, default="0.0.0.0", help="Bind host (default: 0.0.0.0)")
    parser.add_argument("--scenario", type=str, choices=["test-all"], default=None,
                        help="Run a test scenario: 'test-all' cycles through every detection")
    args = parser.parse_args()

    global model_name
    if args.scenario == "test-all":
        model_name = "mock (test-all)"
        thread = threading.Thread(target=test_all_inference_loop, args=(args.fps,), daemon=True)
    elif args.mock:
        model_name = "mock"
        thread = threading.Thread(target=mock_inference_loop, args=(args.camera, args.fps), daemon=True)
    else:
        model_name = args.model
        thread = threading.Thread(target=vlm_inference_loop, args=(args.camera, args.model, args.fps), daemon=True)

    thread.start()
    print(f"Starting VLM server on {args.host}:{args.port}")
    print(f"  GET http://{args.host}:{args.port}/api/detect")
    print(f"  GET http://{args.host}:{args.port}/api/health")
    uvicorn.run(app, host=args.host, port=args.port, log_level="info")


if __name__ == "__main__":
    main()
