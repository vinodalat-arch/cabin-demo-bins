#!/usr/bin/env bash
# ============================================================================
# Automated ADB end-to-end test for In-Cabin AI Perception app
# Drives the app through 15 mock VLM scenarios and validates logcat output.
# Requires: adb device/emulator, mock_server_test.py running on host:8000
# ============================================================================
set -euo pipefail

PKG="com.incabin"
ACTIVITY="com.incabin/.MainActivity"
SERVICE="com.incabin/.InCabinService"
MOCK_PORT="8000"
# Host-side URL (for curl from this script)
MOCK_URL="http://localhost:${MOCK_PORT}"
# Device-side URL (what the Android app connects to — 10.0.2.2 maps to host loopback)
DEVICE_MOCK_URL="http://10.0.2.2:${MOCK_PORT}"
SETTLE_TIME=5       # seconds to let app process frames
SMOOTHER_TIME=8     # extra time for temporal smoother (3-frame window + VLM polling)

PASS=0
FAIL=0
ERRORS=()

# --- Utility functions ---
log()  { echo -e "\033[1;34m[TEST]\033[0m $*"; }
pass() { echo -e "  \033[1;32m✓ PASS\033[0m $1"; PASS=$((PASS + 1)); }
fail() { echo -e "  \033[1;31m✗ FAIL\033[0m $1: $2"; FAIL=$((FAIL + 1)); ERRORS+=("$1: $2"); }

set_scenario() {
    curl -s "${MOCK_URL}/api/scenario?id=$1" > /dev/null 2>&1
}

get_scenario() {
    curl -s "${MOCK_URL}/api/health" 2>/dev/null | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('scenario_name','?'))" 2>/dev/null || echo "?"
}

# Get app PID (set after launch)
APP_PID=""

refresh_pid() {
    APP_PID=$(adb shell pidof $PKG 2>/dev/null || echo "")
    if [ -n "$APP_PID" ]; then
        log "  App PID: $APP_PID"
    else
        log "  WARNING: Could not get app PID, falling back to tag filter"
    fi
}

# Capture logcat for N seconds, return output (uses PID if available)
capture_logcat() {
    local seconds=$1
    adb logcat -c 2>/dev/null  # clear
    sleep "$seconds"
    if [ -n "$APP_PID" ]; then
        adb logcat -d --pid="$APP_PID" 2>/dev/null
    else
        adb logcat -d 2>/dev/null | grep -iE "InCabin|AudioAlerter|AlertOrchestrator|VLM|vlm|incabin" 2>/dev/null || true
    fi
}

# Check if a pattern exists in logcat output
assert_logcat_contains() {
    local label="$1"
    local pattern="$2"
    local logcat="$3"
    if echo "$logcat" | grep -qiE "$pattern"; then
        pass "$label"
    else
        fail "$label" "pattern '${pattern}' not found in logcat"
    fi
}

assert_logcat_not_contains() {
    local label="$1"
    local pattern="$2"
    local logcat="$3"
    if echo "$logcat" | grep -qiE "$pattern"; then
        fail "$label" "pattern '${pattern}' unexpectedly found in logcat"
    else
        pass "$label"
    fi
}

# ============================================================================
log "=== ADB E2E Test Suite ==="
log "Checking prerequisites..."

# Check adb device
if ! adb get-state > /dev/null 2>&1; then
    echo "ERROR: No adb device found"; exit 1
fi

# Check mock server
if ! curl -s "${MOCK_URL}/api/health" > /dev/null 2>&1; then
    echo "ERROR: Mock server not running at ${MOCK_URL}"; exit 1
fi

# Ensure adb root for SharedPrefs access
adb root 2>/dev/null || true
sleep 2

log "Device: $(adb shell getprop ro.product.model 2>/dev/null || echo 'unknown')"
log "Mock server: $(get_scenario)"

# ============================================================================
# PHASE 1: App launch & idle state
# ============================================================================
log ""
log "=== Phase 1: App Launch & Idle State ==="

adb shell am force-stop $PKG 2>/dev/null
sleep 1

# Configure VLM mode via SharedPreferences (requires root on emulator)
# Try user 10 first (automotive), then user 0 (standard emulator)
PREFS_DIR_U10="/data/user/10/$PKG/shared_prefs"
PREFS_DIR_U0="/data/data/$PKG/shared_prefs"

write_prefs() {
    local dir=$1
    adb shell "mkdir -p $dir" 2>/dev/null
    adb shell "cat > $dir/incabin_prefs.xml << 'XMLEOF'
<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\" ?>
<map>
    <string name=\"inference_mode\">remote</string>
    <string name=\"vlm_server_url\">${DEVICE_MOCK_URL}</string>
    <string name=\"brand\">honda</string>
    <string name=\"language\">en</string>
    <string name=\"driver_seat_side\">left</string>
    <boolean name=\"audio_enabled\" value=\"true\" />
    <boolean name=\"preview_enabled\" value=\"false\" />
    <string name=\"asimo_size\">m</string>
    <int name=\"inference_fps\" value=\"1\" />
</map>
XMLEOF" 2>/dev/null
}

write_prefs "$PREFS_DIR_U10"
write_prefs "$PREFS_DIR_U0"

# Fix ownership for user 10 (automotive BSP)
adb shell "chown -R u10_a271:u10_a271 $PREFS_DIR_U10 2>/dev/null; chmod 660 $PREFS_DIR_U10/incabin_prefs.xml 2>/dev/null" 2>/dev/null || true

# Verify prefs were written
if adb shell "cat $PREFS_DIR_U10/incabin_prefs.xml 2>/dev/null || cat $PREFS_DIR_U0/incabin_prefs.xml 2>/dev/null" | grep -q "remote"; then
    log "SharedPrefs configured for VLM remote mode (URL: ${DEVICE_MOCK_URL})"
else
    log "WARNING: SharedPrefs may not have been written correctly"
fi

# Launch activity
adb shell am start -n "$ACTIVITY" 2>/dev/null
sleep 3
refresh_pid

# Test 1.1: Activity launched
if adb shell "dumpsys activity activities" 2>/dev/null | grep -q "com.incabin"; then
    pass "1.1 Activity launched"
else
    fail "1.1 Activity launched" "Activity not in foreground"
fi

# Test 1.2: App is in idle state (not monitoring)
if adb shell "dumpsys activity services" 2>/dev/null | grep -q "InCabinService"; then
    fail "1.2 Idle state (service not running)" "Service already running"
else
    pass "1.2 Idle state (service not running)"
fi

# ============================================================================
# PHASE 2: Start monitoring in VLM mode
# ============================================================================
log ""
log "=== Phase 2: Start Monitoring (VLM Remote Mode) ==="

# Set scenario to driver_only_safe before starting
set_scenario 1
sleep 1

# Start the service directly in VLM mode
adb shell am start-foreground-service -a com.incabin.START $SERVICE 2>/dev/null
sleep $SETTLE_TIME
refresh_pid

# Test 2.1: Service is running
if adb shell "dumpsys activity services" 2>/dev/null | grep -q "InCabinService"; then
    pass "2.1 InCabinService running"
else
    fail "2.1 InCabinService running" "Service not found"
fi

# Test 2.2: VLM client polling
LOGCAT=$(capture_logcat 5)
if echo "$LOGCAT" | grep -qiE "VLM|vlm|remote|polling"; then
    pass "2.2 VLM client active (log mentions VLM/remote)"
else
    # Service may still be initializing — wait more
    LOGCAT=$(capture_logcat 5)
    if echo "$LOGCAT" | grep -qiE "VLM|vlm|remote|polling|detect"; then
        pass "2.2 VLM client active (log mentions VLM/remote)"
    else
        fail "2.2 VLM client active" "No VLM-related log output"
    fi
fi

# Test 2.3: Receiving detection results
LOGCAT=$(capture_logcat $SMOOTHER_TIME)
assert_logcat_contains "2.3 Receiving detection JSON" "passenger_count|risk_level|driver_detected|OutputResult" "$LOGCAT"

# ============================================================================
# PHASE 3: Scenario cycling — validate each detection state
# ============================================================================
log ""
log "=== Phase 3: Detection Scenarios ==="

run_scenario() {
    local id=$1
    local name=$2
    local wait=$3
    local check_pattern=$4
    local test_label=$5

    set_scenario "$id"
    LOGCAT=$(capture_logcat "$wait")
    assert_logcat_contains "$test_label" "$check_pattern" "$LOGCAT"
}

# Scenario 0: Empty car
run_scenario 0 "empty_car" $SMOOTHER_TIME \
    "driver_detected.*false|\"driver_detected\":false|passenger_count.*0" \
    "3.1 Empty car — driver_detected=false"

# Scenario 1: Driver only safe
run_scenario 1 "driver_only_safe" $SMOOTHER_TIME \
    "driver_detected.*true|\"driver_detected\":true|risk.*low" \
    "3.2 Driver only safe — detected, low risk"

# Scenario 3: Driver phone
run_scenario 3 "driver_phone" $SMOOTHER_TIME \
    "driver_using_phone.*true|phone.*true|risk.*high" \
    "3.3 Driver phone — phone detected, high risk"

# Scenario 4: Driver eyes closed
run_scenario 4 "driver_eyes_closed" $SMOOTHER_TIME \
    "driver_eyes_closed.*true|eyes.*closed.*true|risk.*high" \
    "3.4 Driver eyes closed — high risk"

# Scenario 5: Driver yawning
run_scenario 5 "driver_yawning" $SMOOTHER_TIME \
    "driver_yawning.*true|yawn.*true|risk.*medium" \
    "3.5 Driver yawning — medium risk"

# Scenario 6: Driver distracted
run_scenario 6 "driver_distracted" $SMOOTHER_TIME \
    "driver_distracted.*true|distracted.*true|risk.*medium" \
    "3.6 Driver distracted — medium risk"

# Scenario 7: Driver eating
run_scenario 7 "driver_eating" $SMOOTHER_TIME \
    "driver_eating_drinking.*true|eating.*true|risk.*medium" \
    "3.7 Driver eating — medium risk"

# Scenario 8: Full car all safe (5 occupants)
run_scenario 8 "full_car_all_safe" $SMOOTHER_TIME \
    "passenger_count.*5|risk.*low" \
    "3.8 Full car all safe — 5 passengers, low risk"

# Scenario 9: Full car all danger
run_scenario 9 "full_car_all_danger" $SMOOTHER_TIME \
    "driver_using_phone.*true|risk.*high|Phone" \
    "3.9 Full car all danger — phone+posture, high risk"

# Scenario 10: Driver phone + rear sleeping
run_scenario 10 "driver_phone_rear_sleeping" $SMOOTHER_TIME \
    "driver_using_phone.*true|dangerous_posture.*true|risk.*high" \
    "3.10 Driver phone + rear sleeping"

# Scenario 11: 3 rear mixed states
run_scenario 11 "3_rear_mixed" $SMOOTHER_TIME \
    "passenger_count.*5|dangerous_posture.*true" \
    "3.11 Three rear mixed — 5 occupants"

# Scenario 12: Driver distracted + pax yawning
run_scenario 12 "driver_distracted_pax_yawning" $SMOOTHER_TIME \
    "driver_distracted.*true|risk.*medium" \
    "3.12 Driver distracted + passenger yawning"

# ============================================================================
# PHASE 4: Transitions
# ============================================================================
log ""
log "=== Phase 4: State Transitions ==="

# Danger → Safe transition
set_scenario 3  # driver phone (danger)
sleep $SMOOTHER_TIME
set_scenario 13  # back to safe
LOGCAT=$(capture_logcat $SMOOTHER_TIME)
assert_logcat_contains "4.1 Danger→Safe transition" "risk.*low|all.clear|driver_using_phone.*false" "$LOGCAT"

# Safe → Danger transition
set_scenario 1  # safe
sleep $SMOOTHER_TIME
set_scenario 4  # eyes closed
LOGCAT=$(capture_logcat $SMOOTHER_TIME)
assert_logcat_contains "4.2 Safe→Danger transition (eyes closed)" "driver_eyes_closed.*true|risk.*high" "$LOGCAT"

# Occupancy change: 1 → 5 → 1
set_scenario 1  # driver only
sleep $SETTLE_TIME
set_scenario 8  # full car
LOGCAT=$(capture_logcat $SMOOTHER_TIME)
assert_logcat_contains "4.3 Occupancy increase (1→5)" "passenger_count.*5" "$LOGCAT"

set_scenario 1  # back to driver only
LOGCAT=$(capture_logcat $SMOOTHER_TIME)
assert_logcat_contains "4.4 Occupancy decrease (5→1)" "passenger_count.*1" "$LOGCAT"

# Danger type change: phone → eyes closed
set_scenario 3  # phone
sleep $SMOOTHER_TIME
set_scenario 4  # eyes closed
LOGCAT=$(capture_logcat $SMOOTHER_TIME)
assert_logcat_contains "4.5 Danger type switch (phone→eyes)" "driver_eyes_closed.*true" "$LOGCAT"

# ============================================================================
# PHASE 5: Service lifecycle
# ============================================================================
log ""
log "=== Phase 5: Service Lifecycle ==="

# Stop monitoring
adb shell am force-stop $PKG 2>/dev/null
sleep 3

# Test 5.1: Service stopped
if adb shell "dumpsys activity services" 2>/dev/null | grep -q "InCabinService"; then
    fail "5.1 Service stopped" "Service still running after force-stop"
else
    pass "5.1 Service stopped"
fi

# Restart: rewrite prefs (force-stop may clear in-memory config)
write_prefs "$PREFS_DIR_U10"
write_prefs "$PREFS_DIR_U0"
adb shell "chown -R u10_a271:u10_a271 $PREFS_DIR_U10 2>/dev/null; chmod 660 $PREFS_DIR_U10/incabin_prefs.xml 2>/dev/null" 2>/dev/null || true

# Relaunch app + service
adb shell am start -n "$ACTIVITY" 2>/dev/null
sleep 2
set_scenario 2  # driver + front pax safe
adb shell am start-foreground-service -a com.incabin.START $SERVICE 2>/dev/null
sleep 3
refresh_pid

LOGCAT=$(capture_logcat $SMOOTHER_TIME)
assert_logcat_contains "5.2 Service restart — receives data" "passenger_count|driver_detected|risk_level" "$LOGCAT"

# ============================================================================
# PHASE 6: Rapid scenario changes (stress test)
# ============================================================================
log ""
log "=== Phase 6: Rapid Scenario Changes ==="

adb logcat -c 2>/dev/null
for s in 0 3 8 4 13 9 1 5 11 7 2 6 14 10 12; do
    set_scenario $s
    sleep 0.5
done
LOGCAT=$(capture_logcat $SMOOTHER_TIME)

# App should still be processing without crashes
if echo "$LOGCAT" | grep -qiE "FATAL|crash|ANR|died"; then
    fail "6.1 No crash during rapid changes" "Crash detected in logcat"
else
    pass "6.1 No crash during rapid changes"
fi

assert_logcat_contains "6.2 Still receiving data after rapid changes" "passenger_count|driver_detected|risk_level" "$LOGCAT"

# Verify service is still running after stress test
if adb shell "dumpsys activity services" 2>/dev/null | grep -q "InCabinService"; then
    pass "6.3 Service survived rapid scenario changes"
else
    fail "6.3 Service survived rapid scenario changes" "Service no longer running"
fi

# ============================================================================
# PHASE 7: Final state verification
# ============================================================================
log ""
log "=== Phase 7: Final Verification ==="

set_scenario 8  # full car all safe
LOGCAT=$(capture_logcat $SMOOTHER_TIME)
assert_logcat_contains "7.1 Final — full car safe, risk=low" "risk.*low|passenger_count.*5" "$LOGCAT"

# Check no ANR
LOGCAT_ALL=$(adb logcat -d 2>/dev/null)
if echo "$LOGCAT_ALL" | grep -qiE "ANR in $PKG"; then
    fail "7.2 No ANR detected" "ANR found"
else
    pass "7.2 No ANR detected"
fi

# Check no uncaught exceptions
if echo "$LOGCAT_ALL" | grep -qiE "FATAL EXCEPTION.*$PKG"; then
    fail "7.3 No fatal exceptions" "Fatal exception found"
else
    pass "7.3 No fatal exceptions"
fi

# Clean up — stop service
adb shell am force-stop $PKG 2>/dev/null

# ============================================================================
# RESULTS
# ============================================================================
log ""
log "============================================"
log "  RESULTS: $PASS passed, $FAIL failed"
log "============================================"

if [ $FAIL -gt 0 ]; then
    log ""
    log "Failed tests:"
    for err in "${ERRORS[@]}"; do
        echo -e "  \033[1;31m✗\033[0m $err"
    done
    exit 1
fi

log "All tests passed!"
exit 0
