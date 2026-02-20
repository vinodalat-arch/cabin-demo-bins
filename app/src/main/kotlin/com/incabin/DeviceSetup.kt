package com.incabin

import android.util.Log

/**
 * Handles automated device setup for automotive BSPs (SA8155, SA8295).
 * Runs root commands (rmmod, chmod, pm grant) via su, polls for camera,
 * and reports progress via callback.
 *
 * All setup runs on a background thread. Callbacks are invoked on that thread —
 * callers must post to UI thread if needed.
 */
class DeviceSetup {

    companion object {
        private const val TAG = "DeviceSetup"
        private const val CAMERA_POLL_INTERVAL_MS = 2000L
        private const val CAMERA_POLL_MAX_ATTEMPTS = 60 // 2 minutes max
    }

    enum class Stage {
        REMOVING_ODK,
        WAITING_FOR_CAMERA,
        SETTING_PERMISSIONS,
        COMPLETE,
        FAILED
    }

    interface Callback {
        fun onStageChanged(stage: Stage, message: String)
        fun onSetupComplete()
        fun onSetupFailed(message: String)
    }

    private var setupThread: Thread? = null
    @Volatile private var cancelled = false

    /**
     * Run the full setup sequence on a background thread.
     * Call [cancel] to abort.
     */
    fun startSetup(packageName: String, callback: Callback) {
        cancelled = false
        setupThread = Thread({
            try {
                // Stage 1: Remove ODK hook module
                callback.onStageChanged(Stage.REMOVING_ODK, "Removing ODK hook module...")
                val odkResult = executeRootCommand("rmmod odk_hook_module")
                if (odkResult) {
                    Log.i(TAG, "ODK hook module removed successfully")
                } else {
                    Log.w(TAG, "ODK rmmod failed (may already be removed or not present)")
                }

                if (cancelled) return@Thread

                // Stage 2: Wait for camera to appear
                callback.onStageChanged(Stage.WAITING_FOR_CAMERA, "Waiting for webcam... Connect USB camera")
                var cameraPath: String? = null
                for (i in 0 until CAMERA_POLL_MAX_ATTEMPTS) {
                    if (cancelled) return@Thread
                    if (NativeLib.loaded) {
                        cameraPath = NativeLib().nativeFindV4l2Device()
                        if (cameraPath != null) {
                            Log.i(TAG, "Camera found: $cameraPath")
                            break
                        }
                    }
                    Thread.sleep(CAMERA_POLL_INTERVAL_MS)
                }

                if (cameraPath == null) {
                    callback.onStageChanged(Stage.FAILED, "No webcam detected")
                    callback.onSetupFailed("No webcam detected. Connect USB webcam and try again.")
                    return@Thread
                }

                if (cancelled) return@Thread

                // Stage 3: Fix permissions
                callback.onStageChanged(Stage.SETTING_PERMISSIONS, "Setting up camera...")

                // chmod all video devices (harmless for Qualcomm internal nodes)
                executeRootCommand("chmod 666 /dev/video*")

                // Grant app permissions
                val userId = detectUserId()
                executeRootCommand("pm grant --user $userId $packageName android.permission.CAMERA")
                executeRootCommand("pm grant --user $userId $packageName android.permission.POST_NOTIFICATIONS")

                Log.i(TAG, "Setup complete")
                callback.onStageChanged(Stage.COMPLETE, "Camera ready!")
                callback.onSetupComplete()

            } catch (e: InterruptedException) {
                Log.i(TAG, "Setup cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Setup failed", e)
                callback.onStageChanged(Stage.FAILED, "Setup failed: ${e.message}")
                callback.onSetupFailed("Setup failed: ${e.message}")
            }
        }, "DeviceSetup")
        setupThread?.isDaemon = true
        setupThread?.start()
    }

    /** Detect current device state without running setup.
     *  Returns true if camera is available and permissions are granted. */
    fun isCameraAvailable(): Boolean {
        if (!NativeLib.loaded) return false
        return NativeLib().nativeFindV4l2Device() != null
    }

    fun cancel() {
        cancelled = true
        setupThread?.interrupt()
    }

    private fun executeRootCommand(command: String): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val exitCode = process.waitFor()
            Log.d(TAG, "Command '$command' exited with code $exitCode")
            exitCode == 0
        } catch (e: Exception) {
            Log.w(TAG, "Root command failed: $command", e)
            false
        }
    }

    private fun detectUserId(): Int {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("id", "-u"))
            val output = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            output.toIntOrNull() ?: 0
        } catch (e: Exception) {
            Log.w(TAG, "Failed to detect user ID, defaulting to 0", e)
            0
        }
    }
}
