#!/usr/bin/env python3
"""
In-Cabin VLM bridge server for remote inference mode.

Architecture:
  Webcam → this server → external vLLM (OpenAI API) → parse → SA8155 device

Provides two REST endpoints:
  GET /api/detect  — returns latest detection result from VLM
  GET /api/health  — returns server + vLLM status

The SA8155 polls /api/detect at ~1-3Hz and feeds the result through its
existing downstream pipeline (smoother, distraction counter, alerts).

Prerequisites:
  1. vLLM running on the same or another machine:
     vllm serve /path/to/model --host 0.0.0.0 --port 8080 --trust-remote-code

  2. This bridge server:
     pip install fastapi uvicorn opencv-python
     python vlm_server.py --vllm-url http://localhost:8080 --camera 0

For quick testing without a real VLM (returns mock detections):
  python vlm_server.py --mock

Requirements:
  - Python 3.10+
  - fastapi, uvicorn, opencv-python
  - A running vLLM server (external — NOT loaded by this script)
"""

import argparse
import base64
import glob
import json
import os
import subprocess
import sys
import time
import threading
import urllib.request
import urllib.error
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
model_name = "unknown"
vlm_ready = False  # True once vLLM is reachable and inference loop is running
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
        "status": "ok" if vlm_ready else "loading",
        "ready": vlm_ready,
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

    global vlm_ready
    interval = 1.0 / fps
    vlm_ready = True
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
    global vlm_ready
    vlm_ready = True
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
# File-based inference: read pre-computed JSON results from a folder
# ---------------------------------------------------------------------------

def parse_file_result(data: dict) -> dict:
    """
    Map a log_vllm_*.json file to our OutputResult format.
    Only occupancy and driver presence are available — all danger
    detections default to false.
    """
    result = default_result()

    try:
        results = data.get("results", {})

        # Cabin occupancy count
        cabin = results.get("CabinOverview", {})
        count_str = cabin.get("CABIN_OCCUPANCY_COUNT", {}).get("answer", "1")
        try:
            result["passenger_count"] = max(0, int(count_str))
        except (ValueError, TypeError):
            result["passenger_count"] = 1

        # Occupant analysis — per-seat states
        occupants = results.get("OccupantAnalysis", {})

        driver_state = occupants.get("DRIVER_STATE", {}).get("answer", "Vacant")
        result["driver_detected"] = driver_state.lower() != "vacant"

        # Count adults from non-vacant, non-driver seats
        adult_count = 0
        for seat in ("FRONT_PASSENGER_STATE", "REAR_LEFT_STATE", "REAR_RIGHT_STATE"):
            state = occupants.get(seat, {}).get("answer", "Vacant")
            if state.lower() != "vacant":
                adult_count += 1
        result["adult_count"] = adult_count

    except Exception as e:
        print(f"Error parsing file result: {e}")

    return result


def file_inference_loop(file_dir: str, poll_interval: float = 1.0):
    """
    Scan a folder for log_vllm_*.json files, pick the latest by filename,
    parse it, and serve via /api/detect. No VLM or camera needed.
    """
    global latest_result, frame_count, vlm_ready

    vlm_ready = True
    last_file = None
    print(f"File-based inference started (dir={file_dir}, interval={poll_interval}s)")

    while True:
        try:
            # Find all matching JSON files and pick the latest by name
            pattern = os.path.join(file_dir, "log_vllm_*.json")
            files = sorted(glob.glob(pattern))

            if files:
                latest_file = files[-1]  # Sorted alphabetically = latest timestamp

                if latest_file != last_file:
                    with open(latest_file, "r") as f:
                        data = json.load(f)
                    result = parse_file_result(data)
                    with latest_result_lock:
                        latest_result = result
                    frame_count += 1
                    last_file = latest_file

                    if frame_count % 10 == 1:
                        fname = os.path.basename(latest_file)
                        pcount = result["passenger_count"]
                        driver = result["driver_detected"]
                        print(f"[File] frame={frame_count}, file={fname}, "
                              f"passengers={pcount}, driver={driver}")
            else:
                if frame_count == 0:
                    print(f"No log_vllm_*.json files found in {file_dir}, waiting...")

        except json.JSONDecodeError as e:
            print(f"JSON parse error: {e}")
        except Exception as e:
            print(f"File inference error: {e}")

        time.sleep(poll_interval)


# ---------------------------------------------------------------------------
# Confidence thresholds — VLM confidence must exceed these to flag a detection
# Tuned to reduce false positives: higher = more conservative
# Defaults here; overridden by CLI args at startup
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

# VLM request parameters — overridden by CLI args at startup
VLM_MAX_TOKENS = 300
VLM_TEMPERATURE = 0.1
VLM_REQUEST_TIMEOUT = 30
JPEG_QUALITY = 80


# ---------------------------------------------------------------------------
# VLM inference via external vLLM server (OpenAI-compatible API)
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
    # Note: isinstance(bool) must come before isinstance(int/float) since bool is a subclass of int
    for field, threshold in CONFIDENCE_THRESHOLDS.items():
        confidence = vlm_result.get(field, 0.0)
        if isinstance(confidence, bool):
            # VLM returned a boolean directly — use as-is
            result[field] = confidence
        elif isinstance(confidence, (int, float)):
            result[field] = float(confidence) >= threshold
        # else: unexpected type (str, None) — keep default False

    # Child counting
    child_conf = vlm_result.get("child_present", 0.0)
    if isinstance(child_conf, bool):
        if child_conf:
            result["child_count"] = 1
            result["child_present"] = True
    elif isinstance(child_conf, (int, float)) and float(child_conf) >= CONFIDENCE_THRESHOLDS["child_present"]:
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
    # Remove markdown code fences if present (only first and last lines)
    if text.startswith("```"):
        lines = text.split("\n")
        if lines and lines[0].strip().startswith("```"):
            lines = lines[1:]
        if lines and lines[-1].strip().startswith("```"):
            lines = lines[:-1]
        text = "\n".join(lines).strip()
    return json.loads(text)


def query_vllm(vllm_url: str, vllm_model: str, image_base64: str) -> Optional[dict]:
    """
    Send an image to the vLLM OpenAI-compatible API and return parsed JSON.
    Returns None on error.
    """
    url = f"{vllm_url.rstrip('/')}/v1/chat/completions"
    payload = json.dumps({
        "model": vllm_model,
        "messages": [
            {
                "role": "user",
                "content": [
                    {
                        "type": "image_url",
                        "image_url": {"url": f"data:image/jpeg;base64,{image_base64}"}
                    },
                    {
                        "type": "text",
                        "text": VLM_PROMPT
                    }
                ]
            }
        ],
        "max_tokens": VLM_MAX_TOKENS,
        "temperature": VLM_TEMPERATURE,
    }).encode("utf-8")

    req = urllib.request.Request(
        url,
        data=payload,
        headers={"Content-Type": "application/json"},
        method="POST"
    )

    try:
        with urllib.request.urlopen(req, timeout=VLM_REQUEST_TIMEOUT) as resp:
            body = json.loads(resp.read().decode("utf-8"))
            text = body["choices"][0]["message"]["content"]
            return extract_json_from_text(text)
    except urllib.error.URLError as e:
        print(f"vLLM request failed: {e}")
        return None
    except (json.JSONDecodeError, KeyError, IndexError) as e:
        print(f"vLLM response parse error: {e}")
        return None
    except Exception as e:
        print(f"vLLM error: {e}")
        return None


def check_vllm_health(vllm_url: str) -> Optional[str]:
    """
    Check if vLLM is reachable and return the model name.
    Returns model name on success, None on failure.
    """
    url = f"{vllm_url.rstrip('/')}/v1/models"
    req = urllib.request.Request(url, method="GET")
    try:
        with urllib.request.urlopen(req, timeout=5) as resp:
            body = json.loads(resp.read().decode("utf-8"))
            models = body.get("data", [])
            if models:
                return models[0].get("id", "unknown")
            return "unknown"
    except Exception as e:
        print(f"vLLM health check failed: {e}")
        return None


def vlm_inference_loop(vllm_url: str, vllm_model: str, camera_id: int, fps: float = 2.0):
    """
    Capture webcam frames, send to external vLLM server, parse results.
    vLLM must be running separately (e.g., vllm serve ...).
    """
    global latest_result, frame_count, vlm_ready

    if not HAS_OPENCV:
        print("ERROR: opencv-python required for camera capture.")
        print("  pip install opencv-python")
        return

    cap = cv2.VideoCapture(camera_id)
    if not cap.isOpened():
        print(f"ERROR: Cannot open camera {camera_id}")
        return

    vlm_ready = True
    target_interval = 1.0 / fps
    print(f"VLM inference loop started (vllm={vllm_url}, model={vllm_model}, fps={fps})")
    inference_times = []

    try:
        while True:
            frame_start = time.time()

            ret, frame = cap.read()
            if not ret:
                time.sleep(0.1)
                continue

            try:
                # Encode frame as JPEG base64 for vLLM API
                _, jpeg = cv2.imencode('.jpg', frame, [cv2.IMWRITE_JPEG_QUALITY, JPEG_QUALITY])
                image_b64 = base64.b64encode(jpeg.tobytes()).decode("utf-8")

                # Query external vLLM
                vlm_result = query_vllm(vllm_url, vllm_model, image_b64)
                if vlm_result is not None:
                    result = apply_confidence_thresholds(vlm_result)
                    with latest_result_lock:
                        latest_result = result
                    frame_count += 1

                    # Track inference time
                    inference_ms = (time.time() - frame_start) * 1000
                    inference_times.append(inference_ms)
                    if len(inference_times) > 10:
                        inference_times.pop(0)
                    avg_ms = sum(inference_times) / len(inference_times)
                    actual_fps = 1000.0 / avg_ms if avg_ms > 0 else 0

                    if frame_count % 10 == 0:
                        print(f"[VLM Stats] frame={frame_count}, inference={inference_ms:.0f}ms, "
                              f"avg={avg_ms:.0f}ms, actual_fps={actual_fps:.1f}")
                else:
                    print("vLLM returned no result, skipping frame")

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
# vLLM subprocess management
# ---------------------------------------------------------------------------

vllm_process: Optional[subprocess.Popen] = None


def start_vllm_subprocess(model_path: str, vllm_port: int,
                          gpu_mem_util: float = 0.8,
                          max_model_len: int = 16384,
                          startup_timeout: int = 300) -> str:
    """
    Launch vLLM serve as a subprocess. Waits until it's ready.
    Returns the vLLM base URL.
    """
    global vllm_process

    vllm_url = f"http://localhost:{vllm_port}"
    cmd = [
        sys.executable, "-m", "vllm.entrypoints.openai.api_server",
        "--model", model_path,
        "--host", "0.0.0.0",
        "--port", str(vllm_port),
        "--trust-remote-code",
        "--gpu-memory-utilization", str(gpu_mem_util),
        "--max-model-len", str(max_model_len),
        "--limit-mm-per-prompt", '{"video": 1}',
        "--enforce-eager",
        "--allowed-local-media-path", "/",
    ]

    print(f"Starting vLLM server: {model_path} on port {vllm_port}")
    print(f"  Command: {' '.join(cmd)}")
    vllm_process = subprocess.Popen(cmd, stdout=sys.stdout, stderr=sys.stderr)

    # Wait for vLLM to become ready (poll /v1/models)
    print(f"Waiting for vLLM to load model (timeout: {startup_timeout}s) ...")
    max_wait = startup_timeout
    start = time.time()
    while time.time() - start < max_wait:
        detected = check_vllm_health(vllm_url)
        if detected is not None:
            print(f"vLLM ready — model: {detected}")
            return vllm_url

        # Check if process died
        if vllm_process.poll() is not None:
            print("=" * 60)
            print(f"ERROR: vLLM process exited with code {vllm_process.returncode}")
            print("=" * 60)
            exit(1)

        time.sleep(3)

    print("=" * 60)
    print(f"ERROR: vLLM did not become ready within {max_wait}s")
    print("=" * 60)
    vllm_process.terminate()
    exit(1)


def cleanup_vllm():
    """Terminate vLLM subprocess on exit."""
    global vllm_process
    if vllm_process and vllm_process.poll() is None:
        print("Stopping vLLM server ...")
        vllm_process.terminate()
        try:
            vllm_process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            vllm_process.kill()


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(
        description="In-Cabin VLM Bridge Server",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  # Connect to already-running vLLM:
  python vlm_server.py --vllm-url http://localhost:8080

  # Start vLLM + bridge server together:
  python vlm_server.py --start-vllm --model-path /home/kpit/code/qwen3_offline_4B

  # File-based inference (read pre-computed JSON results):
  python vlm_server.py --file-dir /home/kpit/tests/logs/incabin/output

  # Mock mode for testing:
  python vlm_server.py --mock
""")
    # --- Bridge server ---
    server_group = parser.add_argument_group("Bridge server")
    server_group.add_argument("--port", type=int, default=8000, help="This server's port (default: 8000)")
    server_group.add_argument("--host", type=str, default="0.0.0.0", help="Bind host (default: 0.0.0.0)")

    # --- Camera ---
    cam_group = parser.add_argument_group("Camera")
    cam_group.add_argument("--camera", type=int, default=0, help="Camera device ID (default: 0)")
    cam_group.add_argument("--fps", type=float, default=2.0, help="Inference FPS (default: 2.0)")
    cam_group.add_argument("--jpeg-quality", type=int, default=80, help="JPEG encode quality 1-100 (default: 80)")

    # --- Mode ---
    mode_group = parser.add_argument_group("Mode")
    mode_group.add_argument("--mock", action="store_true", help="Use mock inference (no real VLM)")
    mode_group.add_argument("--scenario", type=str, choices=["test-all"], default=None,
                            help="Run a test scenario: 'test-all' cycles through every detection")

    # --- File-based inference ---
    file_group = parser.add_argument_group("File-based inference")
    file_group.add_argument("--file-dir", type=str, default=None,
                            help="Read pre-computed JSON results from this folder (e.g. /home/kpit/tests/logs/incabin/output)")
    file_group.add_argument("--file-poll-interval", type=float, default=1.0,
                            help="Seconds between folder scans (default: 1.0)")

    # --- vLLM connection ---
    vllm_group = parser.add_argument_group("vLLM connection")
    vllm_group.add_argument("--vllm-url", type=str, default=None,
                            help="Connect to already-running vLLM server (e.g. http://localhost:8080)")
    vllm_group.add_argument("--start-vllm", action="store_true",
                            help="Start vLLM server automatically (requires --model-path)")
    vllm_group.add_argument("--model-path", type=str, default="/home/kpit/code/qwen3_offline_4B",
                            help="Local model path for vLLM (default: /home/kpit/code/qwen3_offline_4B)")
    vllm_group.add_argument("--vllm-port", type=int, default=8080,
                            help="Port for vLLM server when using --start-vllm (default: 8080)")

    # --- vLLM launch parameters (used with --start-vllm) ---
    launch_group = parser.add_argument_group("vLLM launch parameters (used with --start-vllm)")
    launch_group.add_argument("--gpu-memory-utilization", type=float, default=0.8,
                              help="vLLM GPU memory fraction (default: 0.8)")
    launch_group.add_argument("--max-model-len", type=int, default=16384,
                              help="vLLM max model context length (default: 16384)")
    launch_group.add_argument("--vllm-startup-timeout", type=int, default=300,
                              help="Seconds to wait for vLLM to load model (default: 300)")

    # --- VLM inference parameters ---
    vlm_group = parser.add_argument_group("VLM inference parameters")
    vlm_group.add_argument("--max-tokens", type=int, default=300,
                           help="Max tokens for VLM response (default: 300)")
    vlm_group.add_argument("--temperature", type=float, default=0.1,
                           help="VLM sampling temperature (default: 0.1)")
    vlm_group.add_argument("--request-timeout", type=int, default=30,
                           help="Timeout in seconds for vLLM API requests (default: 30)")

    # --- Confidence thresholds ---
    thresh_group = parser.add_argument_group("Confidence thresholds (VLM confidence → boolean)")
    thresh_group.add_argument("--thresh-phone", type=float, default=0.6,
                              help="driver_using_phone threshold (default: 0.6)")
    thresh_group.add_argument("--thresh-eyes", type=float, default=0.5,
                              help="driver_eyes_closed threshold (default: 0.5)")
    thresh_group.add_argument("--thresh-yawning", type=float, default=0.5,
                              help="driver_yawning threshold (default: 0.5)")
    thresh_group.add_argument("--thresh-distracted", type=float, default=0.5,
                              help="driver_distracted threshold (default: 0.5)")
    thresh_group.add_argument("--thresh-eating", type=float, default=0.5,
                              help="driver_eating_drinking threshold (default: 0.5)")
    thresh_group.add_argument("--thresh-hands", type=float, default=0.6,
                              help="hands_off_wheel threshold (default: 0.6)")
    thresh_group.add_argument("--thresh-posture", type=float, default=0.5,
                              help="dangerous_posture threshold (default: 0.5)")
    thresh_group.add_argument("--thresh-child", type=float, default=0.4,
                              help="child_present threshold (default: 0.4)")
    thresh_group.add_argument("--thresh-slouching", type=float, default=0.5,
                              help="child_slouching threshold (default: 0.5)")

    args = parser.parse_args()

    # Apply CLI args to global config
    global CONFIDENCE_THRESHOLDS, VLM_MAX_TOKENS, VLM_TEMPERATURE, VLM_REQUEST_TIMEOUT, JPEG_QUALITY
    CONFIDENCE_THRESHOLDS = {
        "driver_using_phone": args.thresh_phone,
        "driver_eyes_closed": args.thresh_eyes,
        "driver_yawning": args.thresh_yawning,
        "driver_distracted": args.thresh_distracted,
        "driver_eating_drinking": args.thresh_eating,
        "hands_off_wheel": args.thresh_hands,
        "dangerous_posture": args.thresh_posture,
        "child_present": args.thresh_child,
        "child_slouching": args.thresh_slouching,
    }
    VLM_MAX_TOKENS = args.max_tokens
    VLM_TEMPERATURE = args.temperature
    VLM_REQUEST_TIMEOUT = args.request_timeout
    JPEG_QUALITY = args.jpeg_quality

    global model_name
    if args.scenario == "test-all":
        model_name = "mock (test-all)"
        thread = threading.Thread(target=test_all_inference_loop, args=(args.fps,), daemon=True)
    elif args.mock:
        model_name = "mock"
        thread = threading.Thread(target=mock_inference_loop, args=(args.camera, args.fps), daemon=True)
    elif args.file_dir:
        model_name = "file-based"
        if not os.path.isdir(args.file_dir):
            print(f"ERROR: File directory does not exist: {args.file_dir}")
            exit(1)
        thread = threading.Thread(
            target=file_inference_loop,
            args=(args.file_dir, args.file_poll_interval),
            daemon=True
        )
    else:
        # Determine vLLM URL — either connect to existing or start new
        vllm_url = args.vllm_url

        if args.start_vllm:
            # Mode 2: Start vLLM ourselves
            vllm_url = start_vllm_subprocess(
                args.model_path, args.vllm_port,
                gpu_mem_util=args.gpu_memory_utilization,
                max_model_len=args.max_model_len,
                startup_timeout=args.vllm_startup_timeout,
            )
            import atexit
            atexit.register(cleanup_vllm)
        elif not vllm_url:
            # Neither --vllm-url nor --start-vllm
            print("=" * 60)
            print("ERROR: Specify how to connect to vLLM:")
            print()
            print("  Option 1 — Connect to already-running vLLM:")
            print("    python vlm_server.py --vllm-url http://localhost:8080")
            print()
            print("  Option 2 — Start vLLM automatically:")
            print("    python vlm_server.py --start-vllm --model-path /path/to/model")
            print("=" * 60)
            exit(1)

        # Pre-flight: check vLLM is reachable
        print(f"Checking vLLM server at {vllm_url} ...")
        detected_model = check_vllm_health(vllm_url)
        if detected_model is None:
            print("=" * 60)
            print(f"ERROR: Cannot reach vLLM server at {vllm_url}")
            print()
            print("Make sure vLLM is running, then retry.")
            print("=" * 60)
            exit(1)

        model_name = detected_model
        print(f"vLLM is running — model: {model_name}")

        if not HAS_OPENCV:
            print("=" * 60)
            print("ERROR: opencv-python not installed (required for camera).")
            print("  pip install opencv-python")
            print("=" * 60)
            exit(1)

        thread = threading.Thread(
            target=vlm_inference_loop,
            args=(vllm_url, model_name, args.camera, args.fps),
            daemon=True
        )

    thread.start()
    print(f"Starting bridge server on {args.host}:{args.port}")
    print(f"  GET http://{args.host}:{args.port}/api/detect")
    print(f"  GET http://{args.host}:{args.port}/api/health")
    uvicorn.run(app, host=args.host, port=args.port, log_level="info")


if __name__ == "__main__":
    main()
