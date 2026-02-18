#!/usr/bin/env bash
#
# Parse captured InCabin logs and print a performance summary.
#
# Usage:
#   ./scripts/parse-perf.sh logs/device_run_20260218_143000.log
#

set -euo pipefail

if [[ $# -lt 1 ]]; then
    echo "Usage: $0 <log-file>"
    exit 1
fi

LOG_FILE="$1"

if [[ ! -f "$LOG_FILE" ]]; then
    echo "ERROR: File not found: $LOG_FILE"
    exit 1
fi

echo "=== Performance Report ==="
echo "Log file: $LOG_FILE"
echo ""

# Device info (from header)
if head -10 "$LOG_FILE" | grep -q "Device:"; then
    head -10 "$LOG_FILE" | grep -E "^(Device|Android|CPUs|RAM):"
    echo ""
fi

# Frame timing stats
echo "--- Frame Timing (per-frame) ---"
FRAME_LINES=$(grep -c "Frame timing:" "$LOG_FILE" 2>/dev/null || echo "0")
echo "Total frames processed: $FRAME_LINES"

if [[ "$FRAME_LINES" -gt 0 ]]; then
    # Extract Total=Xms values
    grep "Frame timing:" "$LOG_FILE" \
        | sed -n 's/.*Total=\([0-9]*\)ms.*/\1/p' \
        | awk '
            BEGIN { min=999999; max=0; sum=0; n=0 }
            {
                sum += $1; n++
                if ($1 < min) min = $1
                if ($1 > max) max = $1
            }
            END {
                if (n > 0) {
                    printf "  Avg: %dms, Min: %dms, Max: %dms\n", sum/n, min, max
                    printf "  Effective FPS: %.2f\n", 1000/(sum/n)
                }
            }'

    # Pose vs Face breakdown
    echo ""
    echo "--- Inference Breakdown ---"
    grep "Frame timing:" "$LOG_FILE" \
        | sed -n 's/.*Pose=\([0-9]*\)ms.*Face=\([0-9]*\)ms.*/\1 \2/p' \
        | awk '
            BEGIN { psum=0; fsum=0; n=0 }
            {
                psum += $1; fsum += $2; n++
            }
            END {
                if (n > 0) {
                    printf "  Avg Pose (YOLO): %dms\n", psum/n
                    printf "  Avg Face (MediaPipe+OpenCV): %dms\n", fsum/n
                }
            }'
fi

# Periodic stats snapshots
echo ""
echo "--- Memory Stats (from periodic snapshots) ---"
STATS_LINES=$(grep -c "\[Stats @" "$LOG_FILE" 2>/dev/null || echo "0")
echo "Snapshots: $STATS_LINES"

if [[ "$STATS_LINES" -gt 0 ]]; then
    grep "\[Stats @" "$LOG_FILE" \
        | sed -n 's/.*heap=\([0-9]*\)MB.*native=\([0-9]*\)MB.*/\1 \2/p' \
        | awk '
            BEGIN { hmin=999999; hmax=0; nmin=999999; nmax=0; n=0 }
            {
                n++
                if ($1 < hmin) hmin = $1
                if ($1 > hmax) hmax = $1
                if ($2 < nmin) nmin = $2
                if ($2 > nmax) nmax = $2
            }
            END {
                if (n > 0) {
                    printf "  Java heap: %d-%d MB\n", hmin, hmax
                    printf "  Native heap: %d-%d MB\n", nmin, nmax
                }
            }'
fi

# Alerts summary
echo ""
echo "--- Alerts ---"
ALERT_COUNT=$(grep -c "Speaking:" "$LOG_FILE" 2>/dev/null || echo "0")
echo "Total TTS alerts: $ALERT_COUNT"
if [[ "$ALERT_COUNT" -gt 0 ]]; then
    grep "Speaking:" "$LOG_FILE" | sed 's/.*Speaking: /  /' | sort | uniq -c | sort -rn | head -10
fi

# Distraction duration
echo ""
echo "--- Distraction ---"
MAX_DISTRACTION=$(grep "\[Stats @" "$LOG_FILE" \
    | sed -n 's/.*distraction=\([0-9]*\)s.*/\1/p' \
    | sort -n | tail -1 2>/dev/null || echo "0")
echo "Max distraction duration: ${MAX_DISTRACTION:-0}s"

echo ""
echo "=== End Report ==="
