package com.incabin

import org.junit.Assert.*
import org.junit.Test

class PlatformProfileTest {

    // --- Platform Detection ---

    @Test
    fun test_sa8155_detected_by_manufacturer() {
        val platform = PlatformProfile.detectPlatform("ALPSALPINE", "IVI-SYSTEM", "qcom", "")
        assertEquals(Platform.SA8155, platform)
    }

    @Test
    fun test_sa8155_detected_by_soc_model() {
        val platform = PlatformProfile.detectPlatform("Unknown", "Unknown", "qcom", "SA8155P")
        assertEquals(Platform.SA8155, platform)
    }

    @Test
    fun test_sa8155_detected_by_hardware() {
        val platform = PlatformProfile.detectPlatform("Unknown", "Unknown", "sa8155", "")
        assertEquals(Platform.SA8155, platform)
    }

    @Test
    fun test_sa8295_detected_by_soc_model() {
        val platform = PlatformProfile.detectPlatform("Unknown", "Unknown", "qcom", "SA8295P")
        assertEquals(Platform.SA8295, platform)
    }

    @Test
    fun test_sa8295_detected_by_hardware() {
        val platform = PlatformProfile.detectPlatform("Unknown", "Unknown", "sa8295", "")
        assertEquals(Platform.SA8295, platform)
    }

    @Test
    fun test_generic_detected_for_unknown_device() {
        val platform = PlatformProfile.detectPlatform("Google", "Pixel 6", "oriole", "")
        assertEquals(Platform.GENERIC, platform)
    }

    @Test
    fun test_case_insensitive_manufacturer() {
        assertEquals(Platform.SA8155, PlatformProfile.detectPlatform("alpsalpine", "", "", ""))
        assertEquals(Platform.SA8155, PlatformProfile.detectPlatform("AlpsAlpine", "", "", ""))
    }

    @Test
    fun test_case_insensitive_soc_model() {
        assertEquals(Platform.SA8295, PlatformProfile.detectPlatform("", "", "", "sa8295p"))
        assertEquals(Platform.SA8155, PlatformProfile.detectPlatform("", "", "", "Sa8155P"))
    }

    // --- Profile Values ---

    @Test
    fun test_sa8155_profile_thread_affinity() {
        val profile = PlatformProfile.forPlatform(Platform.SA8155)
        assertEquals(4, profile.poseThreadCount)
        assertEquals("4;5;6", profile.poseThreadAffinity)
        assertEquals(2, profile.faceRecThreadCount)
        assertEquals("5", profile.faceRecThreadAffinity)
    }

    @Test
    fun test_sa8155_profile_camera_strategy() {
        val profile = PlatformProfile.forPlatform(Platform.SA8155)
        assertEquals(PlatformProfile.CameraStrategy.V4L2_FIRST, profile.cameraStrategy)
    }

    @Test
    fun test_sa8155_is_automotive_bsp() {
        val profile = PlatformProfile.forPlatform(Platform.SA8155)
        assertTrue(profile.isAutomotiveBsp)
    }

    @Test
    fun test_sa8295_profile_no_thread_pinning() {
        val profile = PlatformProfile.forPlatform(Platform.SA8295)
        assertEquals(4, profile.poseThreadCount)
        assertEquals("", profile.poseThreadAffinity)
        assertEquals(2, profile.faceRecThreadCount)
        assertEquals("", profile.faceRecThreadAffinity)
    }

    @Test
    fun test_sa8295_profile_camera_strategy() {
        val profile = PlatformProfile.forPlatform(Platform.SA8295)
        assertEquals(PlatformProfile.CameraStrategy.V4L2_FIRST, profile.cameraStrategy)
    }

    @Test
    fun test_sa8295_is_automotive_bsp() {
        val profile = PlatformProfile.forPlatform(Platform.SA8295)
        assertTrue(profile.isAutomotiveBsp)
    }

    @Test
    fun test_generic_profile_no_thread_pinning() {
        val profile = PlatformProfile.forPlatform(Platform.GENERIC)
        assertEquals("", profile.poseThreadAffinity)
        assertEquals("", profile.faceRecThreadAffinity)
    }

    @Test
    fun test_generic_profile_camera2_first() {
        val profile = PlatformProfile.forPlatform(Platform.GENERIC)
        assertEquals(PlatformProfile.CameraStrategy.CAMERA2_FIRST, profile.cameraStrategy)
    }

    @Test
    fun test_generic_not_automotive_bsp() {
        val profile = PlatformProfile.forPlatform(Platform.GENERIC)
        assertFalse(profile.isAutomotiveBsp)
    }

    // --- Audio Usage ---

    @Test
    fun test_sa8155_uses_sonification() {
        val profile = PlatformProfile.forPlatform(Platform.SA8155)
        assertEquals(PlatformProfile.USAGE_ASSISTANCE_SONIFICATION, profile.audioUsage)
    }

    @Test
    fun test_sa8295_uses_alarm() {
        val profile = PlatformProfile.forPlatform(Platform.SA8295)
        assertEquals(PlatformProfile.USAGE_ALARM, profile.audioUsage)
    }

    @Test
    fun test_generic_uses_alarm() {
        val profile = PlatformProfile.forPlatform(Platform.GENERIC)
        assertEquals(PlatformProfile.USAGE_ALARM, profile.audioUsage)
    }

    // --- fromDeviceInfo integration ---

    @Test
    fun test_from_device_info_returns_correct_profile() {
        val profile = PlatformProfile.fromDeviceInfo("ALPSALPINE", "IVI-SYSTEM", "qcom", "")
        assertEquals(Platform.SA8155, profile.platform)
        assertEquals("4;5;6", profile.poseThreadAffinity)
    }

    @Test
    fun test_sa8155_takes_priority_over_sa8295() {
        // If device matches both SA8155 and SA8295 criteria, SA8155 wins (checked first)
        val platform = PlatformProfile.detectPlatform("ALPSALPINE", "", "sa8295", "")
        assertEquals(Platform.SA8155, platform)
    }
}
