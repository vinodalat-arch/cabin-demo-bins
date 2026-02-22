package com.incabin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for data class default values: PoseResult, FaceResult, OverlayKeypoint,
 * OverlayPerson, and FrameHolder.CameraStatus enum.
 *
 * Verifies that defaults are safe for the pipeline — no detection should fire
 * from default-constructed data classes.
 */
class DataClassDefaultsTest {

    // -------------------------------------------------------------------------
    // PoseResult defaults (6 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_pose_result_default_passenger_count_zero() {
        assertEquals(0, PoseResult().passengerCount)
    }

    @Test
    fun test_pose_result_default_driver_detected_true() {
        assertTrue(PoseResult().driverDetected)
    }

    @Test
    fun test_pose_result_default_no_phone() {
        assertFalse(PoseResult().driverUsingPhone)
    }

    @Test
    fun test_pose_result_default_no_posture() {
        assertFalse(PoseResult().dangerousPosture)
    }

    @Test
    fun test_pose_result_default_no_child() {
        assertFalse(PoseResult().childPresent)
        assertFalse(PoseResult().childSlouching)
    }

    @Test
    fun test_pose_result_default_no_eating() {
        assertFalse(PoseResult().driverEatingDrinking)
    }

    @Test
    fun test_pose_result_default_empty_persons() {
        assertTrue(PoseResult().persons.isEmpty())
    }

    // -------------------------------------------------------------------------
    // FaceResult defaults (5 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_face_result_default_eyes_not_closed() {
        assertFalse(FaceResult().driverEyesClosed)
    }

    @Test
    fun test_face_result_default_not_yawning() {
        assertFalse(FaceResult().driverYawning)
    }

    @Test
    fun test_face_result_default_not_distracted() {
        assertFalse(FaceResult().driverDistracted)
    }

    @Test
    fun test_face_result_default_null_metrics() {
        assertNull(FaceResult().earValue)
        assertNull(FaceResult().marValue)
        assertNull(FaceResult().headYaw)
        assertNull(FaceResult().headPitch)
    }

    @Test
    fun test_face_result_no_face_matches_default() {
        val noFace = FaceResult.NO_FACE
        assertFalse(noFace.driverEyesClosed)
        assertFalse(noFace.driverYawning)
        assertFalse(noFace.driverDistracted)
        assertNull(noFace.earValue)
        assertNull(noFace.marValue)
        assertNull(noFace.headYaw)
        assertNull(noFace.headPitch)
        assertNull(noFace.faceOverlay)
    }

    // -------------------------------------------------------------------------
    // FaceResult.NO_FACE produces low risk when merged (2 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_no_face_merged_with_empty_pose_is_low_risk() {
        val merged = mergeResults(FaceResult.NO_FACE, PoseResult())
        assertEquals("low", merged.riskLevel)
        assertFalse(merged.driverEyesClosed)
        assertFalse(merged.driverYawning)
        assertFalse(merged.driverDistracted)
    }

    @Test
    fun test_no_face_preserves_pose_detections() {
        val pose = PoseResult(driverUsingPhone = true, passengerCount = 3)
        val merged = mergeResults(FaceResult.NO_FACE, pose)
        assertTrue(merged.driverUsingPhone)
        assertEquals(3, merged.passengerCount)
    }

    // -------------------------------------------------------------------------
    // OverlayKeypoint defaults (2 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_overlay_keypoint_defaults() {
        val kp = OverlayKeypoint()
        assertEquals(0f, kp.x, 0.001f)
        assertEquals(0f, kp.y, 0.001f)
        assertEquals(0f, kp.conf, 0.001f)
    }

    @Test
    fun test_overlay_keypoint_with_values() {
        val kp = OverlayKeypoint(100f, 200f, 0.95f)
        assertEquals(100f, kp.x, 0.001f)
        assertEquals(200f, kp.y, 0.001f)
        assertEquals(0.95f, kp.conf, 0.001f)
    }

    // -------------------------------------------------------------------------
    // OverlayPerson defaults (3 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_overlay_person_defaults() {
        val p = OverlayPerson()
        assertEquals(0f, p.x1, 0.001f)
        assertEquals(0f, p.y1, 0.001f)
        assertEquals(0f, p.x2, 0.001f)
        assertEquals(0f, p.y2, 0.001f)
        assertEquals(0f, p.confidence, 0.001f)
        assertFalse(p.isDriver)
        assertTrue(p.keypoints.isEmpty())
    }

    @Test
    fun test_overlay_person_with_bbox() {
        val p = OverlayPerson(
            x1 = 10f, y1 = 20f, x2 = 200f, y2 = 400f,
            confidence = 0.87f, isDriver = true
        )
        assertEquals(10f, p.x1, 0.001f)
        assertEquals(200f, p.x2, 0.001f)
        assertTrue(p.isDriver)
        assertEquals(0.87f, p.confidence, 0.001f)
    }

    @Test
    fun test_overlay_person_with_keypoints() {
        val kps = listOf(
            OverlayKeypoint(50f, 60f, 0.9f),
            OverlayKeypoint(70f, 80f, 0.85f)
        )
        val p = OverlayPerson(keypoints = kps)
        assertEquals(2, p.keypoints.size)
        assertEquals(0.9f, p.keypoints[0].conf, 0.001f)
    }

    // -------------------------------------------------------------------------
    // FrameHolder.CameraStatus enum (2 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_camera_status_enum_values() {
        val statuses = FrameHolder.CameraStatus.values()
        assertEquals(5, statuses.size)
        assertEquals(FrameHolder.CameraStatus.NOT_CONNECTED, statuses[0])
        assertEquals(FrameHolder.CameraStatus.CONNECTING, statuses[1])
        assertEquals(FrameHolder.CameraStatus.READY, statuses[2])
        assertEquals(FrameHolder.CameraStatus.ACTIVE, statuses[3])
        assertEquals(FrameHolder.CameraStatus.LOST, statuses[4])
    }

    @Test
    fun test_camera_status_valueOf() {
        assertEquals(FrameHolder.CameraStatus.ACTIVE, FrameHolder.CameraStatus.valueOf("ACTIVE"))
        assertEquals(FrameHolder.CameraStatus.LOST, FrameHolder.CameraStatus.valueOf("LOST"))
    }

    // -------------------------------------------------------------------------
    // DeviceSetup.Stage enum (1 test)
    // -------------------------------------------------------------------------

    @Test
    fun test_device_setup_stage_enum_values() {
        val stages = DeviceSetup.Stage.values()
        assertEquals(5, stages.size)
        assertEquals(DeviceSetup.Stage.REMOVING_ODK, stages[0])
        assertEquals(DeviceSetup.Stage.WAITING_FOR_CAMERA, stages[1])
        assertEquals(DeviceSetup.Stage.SETTING_PERMISSIONS, stages[2])
        assertEquals(DeviceSetup.Stage.COMPLETE, stages[3])
        assertEquals(DeviceSetup.Stage.FAILED, stages[4])
    }
}
