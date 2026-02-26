package com.incabin

import android.util.Log

/**
 * Manages V4L2 direct camera capture for USB webcams on Honda SA8155P BSP.
 *
 * Scans for a V4L2 capture device, opens it with YUYV format, and runs a
 * background capture loop that delivers BGR frames via the onBgrFrame callback.
 * Used when Camera2 API is unavailable due to disabled camera service.
 *
 * Handles USB webcam disconnect/reconnect automatically:
 * - After MAX_CONSECUTIVE_FAILURES null frames, destroys camera and enters reconnect mode
 * - In reconnect mode, scans for device with exponential backoff (2s → 30s max)
 * - On reconnect success, resumes normal capture immediately
 */
class V4l2CameraManager(
    private val nativeLib: NativeLib,
    private val onBgrFrame: (ByteArray, Int, Int) -> Unit
) {
    companion object {
        private const val TAG = "InCabin-V4L2"
    }

    private var cameraPtr: Long = 0L
    private var captureThread: Thread? = null
    @Volatile private var running = false
    private var firstFrameLogged = false
    private var consecutiveFailures = 0
    private var reconnectDelayMs = Config.V4L2_RECONNECT_INITIAL_DELAY_MS

    // Actual negotiated dimensions (may differ from Config if driver falls back)
    private var actualWidth = Config.CAMERA_WIDTH
    private var actualHeight = Config.CAMERA_HEIGHT

    fun start(): Boolean {
        if (!NativeLib.loaded) {
            Log.e(TAG, "Native library not loaded, cannot use V4L2")
            return false
        }

        val devicePath = nativeLib.nativeFindV4l2Device()
        if (devicePath == null) {
            Log.e(TAG, "No V4L2 capture device found")
            return false
        }
        Log.i(TAG, "Found V4L2 device: $devicePath")

        cameraPtr = nativeLib.nativeCreateV4l2Camera(
            devicePath, Config.CAMERA_WIDTH, Config.CAMERA_HEIGHT
        )
        if (cameraPtr == 0L) {
            Log.e(TAG, "Failed to open V4L2 camera at $devicePath")
            return false
        }

        updateActualDimensions()

        running = true
        captureThread = Thread({
            Log.i(TAG, "V4L2 capture thread started")
            try {
                while (running) {
                    if (cameraPtr == 0L) {
                        if (!attemptReconnect()) {
                            Log.i(TAG, "Reconnect failed, backing off ${reconnectDelayMs}ms")
                            Thread.sleep(reconnectDelayMs)
                            reconnectDelayMs = (reconnectDelayMs * 2)
                                .coerceAtMost(Config.V4L2_RECONNECT_MAX_DELAY_MS)
                            continue
                        }
                    }

                    val grabStartMs = System.currentTimeMillis()
                    val bgrData = nativeLib.nativeGrabBgrFrame(cameraPtr)
                    val grabElapsed = System.currentTimeMillis() - grabStartMs
                    if (bgrData != null) {
                        consecutiveFailures = 0
                        if (!firstFrameLogged) {
                            firstFrameLogged = true
                            Log.i(TAG, "First V4L2 frame: ${actualWidth}x${actualHeight}, ${bgrData.size} bytes BGR, grab=${grabElapsed}ms")
                        }
                        onBgrFrame(bgrData, actualWidth, actualHeight)
                    } else {
                        consecutiveFailures++
                        Log.w(TAG, "V4L2 frame grab returned null ($consecutiveFailures/${Config.V4L2_MAX_CONSECUTIVE_FAILURES})")
                        if (consecutiveFailures >= Config.V4L2_MAX_CONSECUTIVE_FAILURES) {
                            Log.e(TAG, "V4L2 webcam disconnected (${Config.V4L2_MAX_CONSECUTIVE_FAILURES} consecutive failures), entering reconnect mode")
                            destroyCamera()
                            FrameHolder.postCameraStatus(FrameHolder.CameraStatus.LOST)
                            continue
                        }
                    }
                    Thread.sleep(Config.inferenceIntervalMs())
                }
            } catch (_: InterruptedException) {
                Log.i(TAG, "V4L2 capture thread interrupted")
            }
            Log.i(TAG, "V4L2 capture thread stopped")
        }, "V4L2-Capture")
        captureThread!!.isDaemon = true
        captureThread!!.start()
        return true
    }

    private fun destroyCamera() {
        if (cameraPtr != 0L) {
            nativeLib.nativeDestroyV4l2Camera(cameraPtr)
            cameraPtr = 0L
        }
    }

    /** Query actual negotiated dimensions from native camera (may differ from requested). */
    private fun updateActualDimensions() {
        if (cameraPtr == 0L) return
        val w = nativeLib.nativeGetV4l2Width(cameraPtr)
        val h = nativeLib.nativeGetV4l2Height(cameraPtr)
        if (w > 0 && h > 0) {
            actualWidth = w
            actualHeight = h
            if (w != Config.CAMERA_WIDTH || h != Config.CAMERA_HEIGHT) {
                Log.w(TAG, "V4L2 negotiated ${w}x${h} (requested ${Config.CAMERA_WIDTH}x${Config.CAMERA_HEIGHT})")
            }
        }
    }

    private fun attemptReconnect(): Boolean {
        val devicePath = nativeLib.nativeFindV4l2Device() ?: return false

        val ptr = nativeLib.nativeCreateV4l2Camera(
            devicePath, Config.CAMERA_WIDTH, Config.CAMERA_HEIGHT
        )
        if (ptr == 0L) {
            Log.w(TAG, "Found device $devicePath but failed to open")
            return false
        }

        cameraPtr = ptr
        updateActualDimensions()
        consecutiveFailures = 0
        reconnectDelayMs = Config.V4L2_RECONNECT_INITIAL_DELAY_MS
        firstFrameLogged = false
        FrameHolder.postCameraStatus(FrameHolder.CameraStatus.ACTIVE)
        Log.i(TAG, "Reconnected to V4L2 device: $devicePath")
        return true
    }

    fun stop() {
        running = false
        captureThread?.interrupt()
        captureThread?.join(2000)
        captureThread = null
        destroyCamera()
        Log.i(TAG, "V4L2 camera stopped")
    }
}
