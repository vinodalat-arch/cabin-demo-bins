package com.incabin

import android.os.Build
import android.util.Log

/**
 * Platform detection and tuning profiles for multi-SoC support.
 * Detects SA8155, SA8295, or generic Android at startup and provides
 * platform-appropriate configuration for thread affinity, audio routing,
 * and camera strategy.
 */
enum class Platform {
    SA8155,
    SA8255,
    SA8295,
    GENERIC
}

data class PlatformProfile(
    val platform: Platform,
    val poseThreadCount: Int,
    val poseThreadAffinity: String,
    val faceRecThreadCount: Int,
    val faceRecThreadAffinity: String,
    val audioUsage: Int,
    val cameraStrategy: CameraStrategy,
    /** Head pitch threshold (degrees) — tuned per platform for camera mount angle */
    val headPitchThreshold: Float = 35.0f,
    /** EAR threshold — default 0.21, may need tuning per camera/IR */
    val earThreshold: Float = 0.21f
) {
    enum class CameraStrategy {
        V4L2_FIRST,
        CAMERA2_FIRST
    }

    /** True if running on an automotive BSP that needs full setup (ODK, chmod, etc.) */
    val isAutomotiveBsp: Boolean
        get() = platform == Platform.SA8155 || platform == Platform.SA8255 || platform == Platform.SA8295

    companion object {
        private const val TAG = "PlatformProfile"

        // Audio usage constants (mirrors AudioAttributes values for unit test compatibility)
        const val USAGE_ALARM = 4
        const val USAGE_ASSISTANCE_SONIFICATION = 13

        /** Detect platform from device Build properties and return the appropriate profile. */
        fun detect(): PlatformProfile {
            val hardware = Build.HARDWARE
            val manufacturer = Build.MANUFACTURER
            val model = Build.MODEL
            val fingerprint = Build.FINGERPRINT
            val socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Build.SOC_MODEL
            } else {
                ""
            }

            Log.i(TAG, "Platform detection: MANUFACTURER=$manufacturer, MODEL=$model, " +
                "HARDWARE=$hardware, SOC_MODEL=$socModel")

            if (isEmulator(hardware, fingerprint)) {
                Log.i(TAG, "Running on emulator — using Camera2 with host webcam")
            }

            val profile = fromDeviceInfo(manufacturer, model, hardware, socModel)
            Log.i(TAG, "Detected: ${profile.platform}, camera=${profile.cameraStrategy}, " +
                "poseThreads=${profile.poseThreadCount}, poseAffinity='${profile.poseThreadAffinity}'")
            return profile
        }

        /** Testable: detect platform from explicit device info strings. */
        fun fromDeviceInfo(
            manufacturer: String, model: String, hardware: String, socModel: String
        ): PlatformProfile {
            val platform = detectPlatform(manufacturer, model, hardware, socModel)
            return forPlatform(platform)
        }

        /** Testable: classify platform from device info strings. */
        fun detectPlatform(
            manufacturer: String, model: String, hardware: String, socModel: String
        ): Platform {
            return when {
                isSA8155(manufacturer, hardware, socModel) -> Platform.SA8155
                isSA8255(hardware, socModel) -> Platform.SA8255
                isSA8295(hardware, socModel) -> Platform.SA8295
                else -> Platform.GENERIC
            }
        }

        /** Return the tuning profile for a given platform. */
        fun forPlatform(platform: Platform): PlatformProfile = when (platform) {
            Platform.SA8155 -> PlatformProfile(
                platform = Platform.SA8155,
                poseThreadCount = 4,
                poseThreadAffinity = "4;5;6",
                faceRecThreadCount = 2,
                faceRecThreadAffinity = "5",
                audioUsage = USAGE_ASSISTANCE_SONIFICATION,
                cameraStrategy = CameraStrategy.V4L2_FIRST,
                headPitchThreshold = 35.0f  // Camera mount angle causes ~5-10° pitch baseline
            )
            Platform.SA8255 -> PlatformProfile(
                platform = Platform.SA8255,
                poseThreadCount = 4,
                poseThreadAffinity = "",  // Conservative — no pinning until profiled on hardware
                faceRecThreadCount = 2,
                faceRecThreadAffinity = "",
                audioUsage = USAGE_ALARM,
                cameraStrategy = CameraStrategy.V4L2_FIRST
            )
            Platform.SA8295 -> PlatformProfile(
                platform = Platform.SA8295,
                poseThreadCount = 4,
                poseThreadAffinity = "",  // Conservative — no pinning until profiled on hardware
                faceRecThreadCount = 2,
                faceRecThreadAffinity = "",
                audioUsage = USAGE_ALARM,
                cameraStrategy = CameraStrategy.V4L2_FIRST
            )
            Platform.GENERIC -> PlatformProfile(
                platform = Platform.GENERIC,
                poseThreadCount = 4,
                poseThreadAffinity = "",
                faceRecThreadCount = 2,
                faceRecThreadAffinity = "",
                audioUsage = USAGE_ALARM,
                cameraStrategy = CameraStrategy.CAMERA2_FIRST
            )
        }

        private fun isSA8155(manufacturer: String, hardware: String, socModel: String): Boolean {
            return manufacturer.contains("ALPSALPINE", ignoreCase = true) ||
                socModel.contains("SA8155", ignoreCase = true) ||
                hardware.contains("SA8155", ignoreCase = true)
        }

        private fun isSA8255(hardware: String, socModel: String): Boolean {
            return socModel.contains("SA8255", ignoreCase = true) ||
                hardware.contains("SA8255", ignoreCase = true)
        }

        private fun isSA8295(hardware: String, socModel: String): Boolean {
            return socModel.contains("SA8295", ignoreCase = true) ||
                hardware.contains("SA8295", ignoreCase = true)
        }

        /** Testable: detect if running on an Android emulator. */
        fun isEmulator(hardware: String, fingerprint: String): Boolean {
            return hardware in listOf("ranchu", "goldfish") ||
                fingerprint.contains("generic", ignoreCase = true)
        }
    }
}
