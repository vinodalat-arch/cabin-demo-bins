package com.incabin

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for FrameHolder singleton: result posting, camera status,
 * heartbeat, and clear functionality.
 *
 * Note: bitmap-related methods can't be tested without Android framework.
 * These tests focus on the result-only channel, camera status, and heartbeat.
 */
class FrameHolderTest {

    @Before
    fun setup() {
        FrameHolder.clear()
    }

    @After
    fun teardown() {
        FrameHolder.clear()
    }

    // -------------------------------------------------------------------------
    // Result channel (4 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_latest_result_null_initially() {
        assertNull(FrameHolder.getLatestResult())
    }

    @Test
    fun test_post_result_then_get() {
        val result = OutputResult.default()
        FrameHolder.postResult(result)
        val retrieved = FrameHolder.getLatestResult()
        assertNotNull(retrieved)
        assertEquals(result.riskLevel, retrieved!!.riskLevel)
    }

    @Test
    fun test_post_result_overwrites_previous() {
        val r1 = OutputResult.default()
        val r2 = OutputResult(
            timestamp = "2026-01-02T00:00:00Z", passengerCount = 3,
            driverUsingPhone = true, driverEyesClosed = false, driverYawning = false,
            driverDistracted = false, driverEatingDrinking = false, dangerousPosture = false,
            childPresent = false, childSlouching = false, riskLevel = "high",
            earValue = null, marValue = null, headYaw = null, headPitch = null,
            distractionDurationS = 5
        )
        FrameHolder.postResult(r1)
        FrameHolder.postResult(r2)
        val retrieved = FrameHolder.getLatestResult()
        assertEquals("high", retrieved!!.riskLevel)
        assertEquals(3, retrieved.passengerCount)
    }

    @Test
    fun test_clear_removes_result() {
        FrameHolder.postResult(OutputResult.default())
        FrameHolder.clear()
        assertNull(FrameHolder.getLatestResult())
    }

    // -------------------------------------------------------------------------
    // Camera status (4 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_camera_status_initially_not_connected() {
        assertEquals(FrameHolder.CameraStatus.NOT_CONNECTED, FrameHolder.getCameraStatus())
    }

    @Test
    fun test_post_camera_status_active() {
        FrameHolder.postCameraStatus(FrameHolder.CameraStatus.ACTIVE)
        assertEquals(FrameHolder.CameraStatus.ACTIVE, FrameHolder.getCameraStatus())
    }

    @Test
    fun test_post_camera_status_lost() {
        FrameHolder.postCameraStatus(FrameHolder.CameraStatus.ACTIVE)
        FrameHolder.postCameraStatus(FrameHolder.CameraStatus.LOST)
        assertEquals(FrameHolder.CameraStatus.LOST, FrameHolder.getCameraStatus())
    }

    @Test
    fun test_clear_resets_camera_status() {
        FrameHolder.postCameraStatus(FrameHolder.CameraStatus.ACTIVE)
        FrameHolder.clear()
        assertEquals(FrameHolder.CameraStatus.NOT_CONNECTED, FrameHolder.getCameraStatus())
    }

    // -------------------------------------------------------------------------
    // Service heartbeat (5 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_service_not_running_initially() {
        assertFalse(FrameHolder.isServiceRunning())
    }

    @Test
    fun test_heartbeat_age_max_when_never_posted() {
        assertEquals(Long.MAX_VALUE, FrameHolder.getHeartbeatAgeMs())
    }

    @Test
    fun test_post_heartbeat_sets_running() {
        FrameHolder.postHeartbeat()
        assertTrue(FrameHolder.isServiceRunning())
    }

    @Test
    fun test_heartbeat_age_small_after_post() {
        FrameHolder.postHeartbeat()
        val age = FrameHolder.getHeartbeatAgeMs()
        // Should be < 1 second since we just posted
        assertTrue("Heartbeat age should be < 1000ms, was $age", age < 1000)
    }

    @Test
    fun test_clear_resets_heartbeat() {
        FrameHolder.postHeartbeat()
        FrameHolder.clear()
        assertFalse(FrameHolder.isServiceRunning())
        assertEquals(Long.MAX_VALUE, FrameHolder.getHeartbeatAgeMs())
    }

    // -------------------------------------------------------------------------
    // Capture data (3 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_capture_data_null_initially() {
        assertNull(FrameHolder.getCaptureData())
    }

    @Test
    fun test_post_capture_data_then_get() {
        val data = FrameHolder.CaptureData(byteArrayOf(1, 2, 3), 10, 10)
        FrameHolder.postCaptureData(data)
        val retrieved = FrameHolder.getCaptureData()
        assertNotNull(retrieved)
        assertEquals(10, retrieved!!.cropWidth)
        assertEquals(10, retrieved.cropHeight)
    }

    @Test
    fun test_clear_removes_capture_data() {
        FrameHolder.postCaptureData(FrameHolder.CaptureData(byteArrayOf(1), 1, 1))
        FrameHolder.clear()
        assertNull(FrameHolder.getCaptureData())
    }
}
