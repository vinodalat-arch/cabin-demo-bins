#!/usr/bin/env bash
#
# Capture InCabin device logs for performance analysis.
# Saves logcat output to logs/<timestamp>.log
#
# Usage:
#   ./scripts/capture-logs.sh              # capture until Ctrl+C
#   ./scripts/capture-logs.sh --duration 60  # capture for 60 seconds
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
LOG_DIR="$PROJECT_DIR/logs"

mkdir -p "$LOG_DIR"

TIMESTAMP="$(date +%Y%m%d_%H%M%S)"
LOG_FILE="$LOG_DIR/device_run_${TIMESTAMP}.log"

DURATION=0
if [[ "${1:-}" == "--duration" && -n "${2:-}" ]]; then
    DURATION="$2"
fi

# Verify adb connectivity
if ! adb devices 2>/dev/null | grep -q "device$"; then
    echo "ERROR: No adb device connected."
    echo "Connect the device and retry."
    exit 1
fi

# Collect device info header
{
    echo "=== InCabin Performance Capture ==="
    echo "Date: $(date)"
    echo "Device: $(adb shell getprop ro.product.manufacturer) $(adb shell getprop ro.product.model)"
    echo "Android: $(adb shell getprop ro.build.version.release) (SDK $(adb shell getprop ro.build.version.sdk))"
    echo "CPUs: $(adb shell nproc)"
    echo "RAM: $(adb shell cat /proc/meminfo | head -1)"
    echo "==================================="
    echo ""
} > "$LOG_FILE"

echo "Capturing logs to: $LOG_FILE"
echo "Tags: InCabin, InCabin-V4L2, InCabin-JNI, InCabin-Camera, AudioAlerter"

if [[ "$DURATION" -gt 0 ]]; then
    echo "Duration: ${DURATION}s"
    echo "Press Ctrl+C to stop early."
    echo ""

    # Clear logcat buffer and capture for specified duration
    adb logcat -c
    timeout "$DURATION" adb logcat -s \
        'InCabin:*' 'InCabin-V4L2:*' 'InCabin-JNI:*' \
        'InCabin-Camera:*' 'AudioAlerter:*' >> "$LOG_FILE" 2>&1 || true
else
    echo "Press Ctrl+C to stop."
    echo ""

    # Clear logcat buffer and capture until interrupted
    adb logcat -c
    adb logcat -s \
        'InCabin:*' 'InCabin-V4L2:*' 'InCabin-JNI:*' \
        'InCabin-Camera:*' 'AudioAlerter:*' >> "$LOG_FILE" 2>&1 || true
fi

echo ""
echo "Log saved: $LOG_FILE"
echo "Lines captured: $(wc -l < "$LOG_FILE")"

# Print quick summary if we have stats lines
STATS_COUNT=$(grep -c "\[Stats @" "$LOG_FILE" 2>/dev/null || echo "0")
if [[ "$STATS_COUNT" -gt 0 ]]; then
    echo ""
    echo "=== Quick Summary ==="
    echo "Stats snapshots: $STATS_COUNT"
    grep "\[Stats @" "$LOG_FILE" | tail -3
fi
