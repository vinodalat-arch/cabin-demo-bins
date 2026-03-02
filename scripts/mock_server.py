#!/usr/bin/env python3
"""Minimal mock VLM server for UI testing. Cycles through seat states."""
import json, time, threading
from http.server import HTTPServer, BaseHTTPRequestHandler
from datetime import datetime, timezone

frame = 0

def current_result():
    global frame
    frame += 1
    cycle = (frame // 5) % 6  # change state every 5 frames

    states = [
        # 0: All safe, 2 passengers
        {"passenger_count": 2, "driver_detected": True,
         "driver_using_phone": False, "driver_eyes_closed": False,
         "driver_yawning": False, "driver_distracted": False,
         "driver_eating_drinking": False,
         "seat_map": {
             "driver": {"occupied": True, "state": "Upright"},
             "front_passenger": {"occupied": True, "state": "Upright"},
             "rear_left": {"occupied": False, "state": "Vacant"},
             "rear_center": {"occupied": False, "state": "Vacant"},
             "rear_right": {"occupied": False, "state": "Vacant"},
         }},
        # 1: Driver on phone, 3 passengers
        {"passenger_count": 3, "driver_detected": True,
         "driver_using_phone": True, "driver_eyes_closed": False,
         "driver_yawning": False, "driver_distracted": False,
         "driver_eating_drinking": False,
         "seat_map": {
             "driver": {"occupied": True, "state": "Phone"},
             "front_passenger": {"occupied": True, "state": "Upright"},
             "rear_left": {"occupied": True, "state": "Upright"},
             "rear_center": {"occupied": False, "state": "Vacant"},
             "rear_right": {"occupied": False, "state": "Vacant"},
         }},
        # 2: Driver distracted, 4 passengers, rear sleeping
        {"passenger_count": 4, "driver_detected": True,
         "driver_using_phone": False, "driver_eyes_closed": False,
         "driver_yawning": False, "driver_distracted": True,
         "driver_eating_drinking": False,
         "seat_map": {
             "driver": {"occupied": True, "state": "Distracted"},
             "front_passenger": {"occupied": True, "state": "Upright"},
             "rear_left": {"occupied": True, "state": "Sleeping"},
             "rear_center": {"occupied": False, "state": "Vacant"},
             "rear_right": {"occupied": True, "state": "Upright"},
         }},
        # 3: Full car (5 passengers), all safe
        {"passenger_count": 5, "driver_detected": True,
         "driver_using_phone": False, "driver_eyes_closed": False,
         "driver_yawning": False, "driver_distracted": False,
         "driver_eating_drinking": False,
         "seat_map": {
             "driver": {"occupied": True, "state": "Upright"},
             "front_passenger": {"occupied": True, "state": "Upright"},
             "rear_left": {"occupied": True, "state": "Upright"},
             "rear_center": {"occupied": True, "state": "Upright"},
             "rear_right": {"occupied": True, "state": "Upright"},
         }},
        # 4: Driver yawning, rear center eating
        {"passenger_count": 5, "driver_detected": True,
         "driver_using_phone": False, "driver_eyes_closed": False,
         "driver_yawning": True, "driver_distracted": False,
         "driver_eating_drinking": False,
         "seat_map": {
             "driver": {"occupied": True, "state": "Yawning"},
             "front_passenger": {"occupied": True, "state": "Eating"},
             "rear_left": {"occupied": True, "state": "Upright"},
             "rear_center": {"occupied": True, "state": "Eating"},
             "rear_right": {"occupied": True, "state": "Upright"},
         }},
        # 5: Driver eyes closed
        {"passenger_count": 2, "driver_detected": True,
         "driver_using_phone": False, "driver_eyes_closed": True,
         "driver_yawning": False, "driver_distracted": False,
         "driver_eating_drinking": False,
         "seat_map": {
             "driver": {"occupied": True, "state": "Sleeping"},
             "front_passenger": {"occupied": True, "state": "Upright"},
             "rear_left": {"occupied": False, "state": "Vacant"},
             "rear_center": {"occupied": False, "state": "Vacant"},
             "rear_right": {"occupied": False, "state": "Vacant"},
         }},
    ]

    base = states[cycle]
    result = {
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "passenger_count": base["passenger_count"],
        "child_count": 0, "adult_count": 0,
        "driver_using_phone": base["driver_using_phone"],
        "driver_eyes_closed": base["driver_eyes_closed"],
        "driver_yawning": base["driver_yawning"],
        "driver_distracted": base["driver_distracted"],
        "driver_eating_drinking": base["driver_eating_drinking"],
        "hands_off_wheel": False,
        "dangerous_posture": False,
        "child_present": False, "child_slouching": False,
        "risk_level": "low",
        "ear_value": None, "mar_value": None,
        "head_yaw": None, "head_pitch": None,
        "driver_name": "Demo",
        "driver_detected": base["driver_detected"],
        "seat_map": base["seat_map"],
    }
    # Compute risk
    score = 0
    if result["driver_using_phone"]: score += 3
    if result["driver_eyes_closed"]: score += 3
    if result["driver_yawning"]: score += 2
    if result["driver_distracted"]: score += 2
    if result["driver_eating_drinking"]: score += 1
    result["risk_level"] = "high" if score >= 3 else "medium" if score >= 1 else "low"
    return result

class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/api/detect":
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps(current_result()).encode())
        elif self.path == "/api/health":
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps({"status": "ok", "ready": True, "model": "mock", "fps": 1.0}).encode())
        else:
            self.send_response(404)
            self.end_headers()
    def log_message(self, format, *args):
        pass  # suppress per-request logging

if __name__ == "__main__":
    port = 8000
    server = HTTPServer(("0.0.0.0", port), Handler)
    print(f"Mock VLM server on port {port} — cycling through 6 seat states every 5s")
    server.serve_forever()
