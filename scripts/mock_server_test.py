#!/usr/bin/env python3
"""
Controllable mock VLM server for automated adb testing.
Set the current scenario via POST /api/scenario?id=N or GET /api/scenario?id=N.
GET /api/detect returns the active scenario.
GET /api/health returns healthy status.
"""
import json
from http.server import HTTPServer, BaseHTTPRequestHandler
from urllib.parse import urlparse, parse_qs
from datetime import datetime, timezone

# All test scenarios
SCENARIOS = {
    # --- Basic occupancy ---
    0: {"name": "empty_car", "passenger_count": 0, "driver_detected": False,
        "driver_using_phone": False, "driver_eyes_closed": False,
        "driver_yawning": False, "driver_distracted": False,
        "driver_eating_drinking": False, "dangerous_posture": False,
        "hands_off_wheel": False, "child_present": False, "child_slouching": False,
        "seat_map": {
            "driver": {"occupied": False, "state": "Vacant"},
            "front_passenger": {"occupied": False, "state": "Vacant"},
            "rear_left": {"occupied": False, "state": "Vacant"},
            "rear_center": {"occupied": False, "state": "Vacant"},
            "rear_right": {"occupied": False, "state": "Vacant"},
        }},
    1: {"name": "driver_only_safe", "passenger_count": 1, "driver_detected": True,
        "driver_using_phone": False, "driver_eyes_closed": False,
        "driver_yawning": False, "driver_distracted": False,
        "driver_eating_drinking": False, "dangerous_posture": False,
        "hands_off_wheel": False, "child_present": False, "child_slouching": False,
        "seat_map": {
            "driver": {"occupied": True, "state": "Upright"},
            "front_passenger": {"occupied": False, "state": "Vacant"},
            "rear_left": {"occupied": False, "state": "Vacant"},
            "rear_center": {"occupied": False, "state": "Vacant"},
            "rear_right": {"occupied": False, "state": "Vacant"},
        }},
    2: {"name": "driver_front_pax_safe", "passenger_count": 2, "driver_detected": True,
        "driver_using_phone": False, "driver_eyes_closed": False,
        "driver_yawning": False, "driver_distracted": False,
        "driver_eating_drinking": False, "dangerous_posture": False,
        "hands_off_wheel": False, "child_present": False, "child_slouching": False,
        "seat_map": {
            "driver": {"occupied": True, "state": "Upright"},
            "front_passenger": {"occupied": True, "state": "Upright"},
            "rear_left": {"occupied": False, "state": "Vacant"},
            "rear_center": {"occupied": False, "state": "Vacant"},
            "rear_right": {"occupied": False, "state": "Vacant"},
        }},
    # --- Driver danger states ---
    3: {"name": "driver_phone", "passenger_count": 1, "driver_detected": True,
        "driver_using_phone": True, "driver_eyes_closed": False,
        "driver_yawning": False, "driver_distracted": False,
        "driver_eating_drinking": False, "dangerous_posture": False,
        "hands_off_wheel": False, "child_present": False, "child_slouching": False,
        "seat_map": {
            "driver": {"occupied": True, "state": "Phone"},
            "front_passenger": {"occupied": False, "state": "Vacant"},
            "rear_left": {"occupied": False, "state": "Vacant"},
            "rear_center": {"occupied": False, "state": "Vacant"},
            "rear_right": {"occupied": False, "state": "Vacant"},
        }},
    4: {"name": "driver_eyes_closed", "passenger_count": 1, "driver_detected": True,
        "driver_using_phone": False, "driver_eyes_closed": True,
        "driver_yawning": False, "driver_distracted": False,
        "driver_eating_drinking": False, "dangerous_posture": False,
        "hands_off_wheel": False, "child_present": False, "child_slouching": False,
        "seat_map": {
            "driver": {"occupied": True, "state": "Sleeping"},
            "front_passenger": {"occupied": False, "state": "Vacant"},
            "rear_left": {"occupied": False, "state": "Vacant"},
            "rear_center": {"occupied": False, "state": "Vacant"},
            "rear_right": {"occupied": False, "state": "Vacant"},
        }},
    5: {"name": "driver_yawning", "passenger_count": 1, "driver_detected": True,
        "driver_using_phone": False, "driver_eyes_closed": False,
        "driver_yawning": True, "driver_distracted": False,
        "driver_eating_drinking": False, "dangerous_posture": False,
        "hands_off_wheel": False, "child_present": False, "child_slouching": False,
        "seat_map": {
            "driver": {"occupied": True, "state": "Yawning"},
            "front_passenger": {"occupied": False, "state": "Vacant"},
            "rear_left": {"occupied": False, "state": "Vacant"},
            "rear_center": {"occupied": False, "state": "Vacant"},
            "rear_right": {"occupied": False, "state": "Vacant"},
        }},
    6: {"name": "driver_distracted", "passenger_count": 1, "driver_detected": True,
        "driver_using_phone": False, "driver_eyes_closed": False,
        "driver_yawning": False, "driver_distracted": True,
        "driver_eating_drinking": False, "dangerous_posture": False,
        "hands_off_wheel": False, "child_present": False, "child_slouching": False,
        "seat_map": {
            "driver": {"occupied": True, "state": "Distracted"},
            "front_passenger": {"occupied": False, "state": "Vacant"},
            "rear_left": {"occupied": False, "state": "Vacant"},
            "rear_center": {"occupied": False, "state": "Vacant"},
            "rear_right": {"occupied": False, "state": "Vacant"},
        }},
    7: {"name": "driver_eating", "passenger_count": 1, "driver_detected": True,
        "driver_using_phone": False, "driver_eyes_closed": False,
        "driver_yawning": False, "driver_distracted": False,
        "driver_eating_drinking": True, "dangerous_posture": False,
        "hands_off_wheel": False, "child_present": False, "child_slouching": False,
        "seat_map": {
            "driver": {"occupied": True, "state": "Eating"},
            "front_passenger": {"occupied": False, "state": "Vacant"},
            "rear_left": {"occupied": False, "state": "Vacant"},
            "rear_center": {"occupied": False, "state": "Vacant"},
            "rear_right": {"occupied": False, "state": "Vacant"},
        }},
    # --- Multi-occupant combos ---
    8: {"name": "full_car_all_safe", "passenger_count": 5, "driver_detected": True,
        "driver_using_phone": False, "driver_eyes_closed": False,
        "driver_yawning": False, "driver_distracted": False,
        "driver_eating_drinking": False, "dangerous_posture": False,
        "hands_off_wheel": False, "child_present": False, "child_slouching": False,
        "seat_map": {
            "driver": {"occupied": True, "state": "Upright"},
            "front_passenger": {"occupied": True, "state": "Upright"},
            "rear_left": {"occupied": True, "state": "Upright"},
            "rear_center": {"occupied": True, "state": "Upright"},
            "rear_right": {"occupied": True, "state": "Upright"},
        }},
    9: {"name": "full_car_all_danger", "passenger_count": 5, "driver_detected": True,
        "driver_using_phone": True, "driver_eyes_closed": False,
        "driver_yawning": False, "driver_distracted": False,
        "driver_eating_drinking": False, "dangerous_posture": True,
        "hands_off_wheel": False, "child_present": False, "child_slouching": False,
        "seat_map": {
            "driver": {"occupied": True, "state": "Phone"},
            "front_passenger": {"occupied": True, "state": "Sleeping"},
            "rear_left": {"occupied": True, "state": "Distracted"},
            "rear_center": {"occupied": True, "state": "Eating"},
            "rear_right": {"occupied": True, "state": "Yawning"},
        }},
    10: {"name": "driver_phone_rear_sleeping", "passenger_count": 4, "driver_detected": True,
         "driver_using_phone": True, "driver_eyes_closed": False,
         "driver_yawning": False, "driver_distracted": False,
         "driver_eating_drinking": False, "dangerous_posture": True,
         "hands_off_wheel": False, "child_present": False, "child_slouching": False,
         "seat_map": {
             "driver": {"occupied": True, "state": "Phone"},
             "front_passenger": {"occupied": True, "state": "Upright"},
             "rear_left": {"occupied": True, "state": "Sleeping"},
             "rear_center": {"occupied": False, "state": "Vacant"},
             "rear_right": {"occupied": True, "state": "Upright"},
         }},
    11: {"name": "3_rear_mixed", "passenger_count": 5, "driver_detected": True,
         "driver_using_phone": False, "driver_eyes_closed": False,
         "driver_yawning": False, "driver_distracted": False,
         "driver_eating_drinking": False, "dangerous_posture": True,
         "hands_off_wheel": False, "child_present": False, "child_slouching": False,
         "seat_map": {
             "driver": {"occupied": True, "state": "Upright"},
             "front_passenger": {"occupied": True, "state": "Upright"},
             "rear_left": {"occupied": True, "state": "Sleeping"},
             "rear_center": {"occupied": True, "state": "Eating"},
             "rear_right": {"occupied": True, "state": "Upright"},
         }},
    12: {"name": "driver_distracted_pax_yawning", "passenger_count": 3, "driver_detected": True,
         "driver_using_phone": False, "driver_eyes_closed": False,
         "driver_yawning": False, "driver_distracted": True,
         "driver_eating_drinking": False, "dangerous_posture": False,
         "hands_off_wheel": False, "child_present": False, "child_slouching": False,
         "seat_map": {
             "driver": {"occupied": True, "state": "Distracted"},
             "front_passenger": {"occupied": True, "state": "Yawning"},
             "rear_left": {"occupied": True, "state": "Upright"},
             "rear_center": {"occupied": False, "state": "Vacant"},
             "rear_right": {"occupied": False, "state": "Vacant"},
         }},
    # --- Transition scenarios ---
    13: {"name": "back_to_safe", "passenger_count": 2, "driver_detected": True,
         "driver_using_phone": False, "driver_eyes_closed": False,
         "driver_yawning": False, "driver_distracted": False,
         "driver_eating_drinking": False, "dangerous_posture": False,
         "hands_off_wheel": False, "child_present": False, "child_slouching": False,
         "seat_map": {
             "driver": {"occupied": True, "state": "Upright"},
             "front_passenger": {"occupied": True, "state": "Upright"},
             "rear_left": {"occupied": False, "state": "Vacant"},
             "rear_center": {"occupied": False, "state": "Vacant"},
             "rear_right": {"occupied": False, "state": "Vacant"},
         }},
    14: {"name": "rear_only_2", "passenger_count": 3, "driver_detected": True,
         "driver_using_phone": False, "driver_eyes_closed": False,
         "driver_yawning": False, "driver_distracted": False,
         "driver_eating_drinking": False, "dangerous_posture": False,
         "hands_off_wheel": False, "child_present": False, "child_slouching": False,
         "seat_map": {
             "driver": {"occupied": True, "state": "Upright"},
             "front_passenger": {"occupied": False, "state": "Vacant"},
             "rear_left": {"occupied": True, "state": "Upright"},
             "rear_center": {"occupied": False, "state": "Vacant"},
             "rear_right": {"occupied": True, "state": "Upright"},
         }},
}

active_scenario = 1  # start with driver only safe

def build_result(scenario_id):
    s = SCENARIOS.get(scenario_id, SCENARIOS[0])
    result = {
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "passenger_count": s["passenger_count"],
        "child_count": 0, "adult_count": 0,
        "driver_using_phone": s["driver_using_phone"],
        "driver_eyes_closed": s["driver_eyes_closed"],
        "driver_yawning": s["driver_yawning"],
        "driver_distracted": s["driver_distracted"],
        "driver_eating_drinking": s["driver_eating_drinking"],
        "hands_off_wheel": s.get("hands_off_wheel", False),
        "dangerous_posture": s.get("dangerous_posture", False),
        "child_present": s.get("child_present", False),
        "child_slouching": s.get("child_slouching", False),
        "risk_level": "low",
        "ear_value": None, "mar_value": None,
        "head_yaw": None, "head_pitch": None,
        "driver_name": "TestUser",
        "driver_detected": s["driver_detected"],
        "seat_map": s["seat_map"],
    }
    score = 0
    if result["driver_using_phone"]: score += 3
    if result["driver_eyes_closed"]: score += 3
    if result["driver_yawning"]: score += 2
    if result["driver_distracted"]: score += 2
    if result["driver_eating_drinking"]: score += 1
    if result["dangerous_posture"]: score += 2
    result["risk_level"] = "high" if score >= 3 else "medium" if score >= 1 else "low"
    return result

class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        global active_scenario
        parsed = urlparse(self.path)

        if parsed.path == "/api/detect":
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps(build_result(active_scenario)).encode())

        elif parsed.path == "/api/health":
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps({
                "status": "ok", "ready": True,
                "model": "mock-test", "fps": 1.0,
                "active_scenario": active_scenario,
                "scenario_name": SCENARIOS.get(active_scenario, {}).get("name", "unknown")
            }).encode())

        elif parsed.path == "/api/scenario":
            params = parse_qs(parsed.query)
            if "id" in params:
                active_scenario = int(params["id"][0])
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps({
                "active_scenario": active_scenario,
                "name": SCENARIOS.get(active_scenario, {}).get("name", "unknown")
            }).encode())

        elif parsed.path == "/api/scenarios":
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            listing = {k: v["name"] for k, v in SCENARIOS.items()}
            self.wfile.write(json.dumps(listing).encode())

        else:
            self.send_response(404)
            self.end_headers()

    def log_message(self, format, *args):
        pass

if __name__ == "__main__":
    port = 8000
    server = HTTPServer(("0.0.0.0", port), Handler)
    print(f"Mock test server on :{port} — {len(SCENARIOS)} scenarios")
    print(f"  GET /api/detect          — returns active scenario result")
    print(f"  GET /api/scenario?id=N   — switch to scenario N")
    print(f"  GET /api/scenarios       — list all scenarios")
    print(f"  GET /api/health          — health check")
    server.serve_forever()
