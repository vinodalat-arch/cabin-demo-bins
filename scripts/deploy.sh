#!/bin/bash
# One-command build, install, and launch for In-Cabin AI Perception.
# Usage: ./scripts/deploy.sh
#
# On automotive BSPs (SA8155/SA8295): the service handles DeviceSetup
# (rmmod, camera poll, chmod, pm grant) automatically — zero manual steps.
# On generic Android: grants permissions via adb, then launches the app.

set -euo pipefail

PACKAGE="com.incabin"
APK="app/build/outputs/apk/debug/app-debug.apk"

echo "=== Building APK ==="
./gradlew assembleDebug

echo "=== Installing APK ==="
adb install -r "$APK"

echo "=== Granting permissions ==="
# Try user 10 first (Honda BSP), fall back to default user
if adb shell pm grant --user 10 "$PACKAGE" android.permission.CAMERA 2>/dev/null; then
    adb shell pm grant --user 10 "$PACKAGE" android.permission.POST_NOTIFICATIONS 2>/dev/null || true
    echo "Permissions granted (user 10)"
else
    adb shell pm grant "$PACKAGE" android.permission.CAMERA
    adb shell pm grant "$PACKAGE" android.permission.POST_NOTIFICATIONS 2>/dev/null || true
    echo "Permissions granted (default user)"
fi

echo "=== Launching app ==="
adb shell am start -n "$PACKAGE/.MainActivity"

echo "=== Done! ==="
echo "Monitor logs: adb logcat -s 'InCabin:*' 'DeviceSetup:*' 'AudioAlerter:*' 'BootReceiver:*'"
