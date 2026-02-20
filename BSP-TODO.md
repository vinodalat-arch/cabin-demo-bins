# BSP Build Changes for In-Cabin AI

Changes to make in the Honda SA8155P BSP source tree so the app works out of the box after flashing.

## 1. USB webcam device permissions

**File:** `device/<vendor>/sa8155/ueventd.<platform>.rc` (or `vendor/qcom/sa8155/ueventd.qcom.rc`)

**Add:**
```
/dev/video*               0666   system     camera
```

**Why:** Default `660 system:camera` blocks the app from opening V4L2 devices. Currently requires `chmod 666` via `su` on every webcam reconnect.

## 2. Remove ODK hook module loading

**Find:** `grep -r "odk_hook_module" device/ vendor/`

**File:** Likely `vendor/qcom/sa8155/init.target.rc` or similar

**Do:** Delete or comment out the `insmod /vendor/lib/modules/odk_hook_module.ko` line.

**Optionally:** Remove the `.ko` from `PRODUCT_COPY_FILES` to save image space.

**Why:** This kernel module intercepts USB device binding and prevents the UVC driver from claiming the webcam. Currently requires `rmmod` via `su` on every boot.

## Verification

After flashing the modified build:
```bash
adb shell lsmod | grep odk           # should be empty
adb shell ls -la /dev/video*          # should show 0666 permissions
```

App should detect the webcam immediately on Start without any setup steps.
