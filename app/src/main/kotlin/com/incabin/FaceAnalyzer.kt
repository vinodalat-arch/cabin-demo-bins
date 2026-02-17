package com.incabin

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker.FaceLandmarkerOptions
import org.opencv.calib3d.Calib3d
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfPoint2f
import org.opencv.core.MatOfPoint3f
import org.opencv.core.Point
import org.opencv.core.Point3
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Result of face analysis for a single frame.
 *
 * Null values for [earValue], [marValue], [headYaw], [headPitch] indicate
 * no face was detected in the frame.
 */
data class FaceResult(
    val driverEyesClosed: Boolean = false,
    val earValue: Float? = null,
    val driverYawning: Boolean = false,
    val marValue: Float? = null,
    val driverDistracted: Boolean = false,
    val headYaw: Float? = null,
    val headPitch: Float? = null
) {
    companion object {
        /** Default result when no face is detected. */
        val NO_FACE = FaceResult(
            driverEyesClosed = false,
            earValue = null,
            driverYawning = false,
            marValue = null,
            driverDistracted = false,
            headYaw = null,
            headPitch = null
        )
    }
}

/**
 * Wraps MediaPipe FaceLandmarker to compute EAR (Eye Aspect Ratio),
 * MAR (Mouth Aspect Ratio), and head pose via OpenCV solvePnP.
 *
 * Uses [RunningMode.VIDEO] with a monotonically increasing timestamp
 * (incremented by 33ms per call) to satisfy MediaPipe's expectation of
 * sequential frames, even though the actual capture rate is 1fps.
 */
class FaceAnalyzer(context: Context) {

    companion object {
        private const val TAG = "InCabin-Face"
        private const val MODEL_ASSET = "face_landmarker.task"

        // Timestamp increment per call (simulates ~30fps for MediaPipe VIDEO mode)
        private const val TIMESTAMP_INCREMENT_MS = 33L

        // Minimum denominator to avoid division by zero
        private const val MIN_HORIZONTAL = 1e-6

        // Right eye landmark indices: [lateral, upper1, upper2, medial, lower1, lower2]
        private val RIGHT_EYE_INDICES = intArrayOf(33, 160, 158, 133, 153, 144)

        // Left eye landmark indices: [lateral, upper1, upper2, medial, lower1, lower2]
        private val LEFT_EYE_INDICES = intArrayOf(362, 385, 387, 263, 373, 380)

        // MAR landmark indices: top_lip, bottom_lip, left_corner, right_corner, upper_mid, lower_mid
        private const val MAR_TOP = 13
        private const val MAR_BOTTOM = 14
        private const val MAR_LEFT = 61
        private const val MAR_RIGHT = 291
        private const val MAR_UPPER_MID = 0
        private const val MAR_LOWER_MID = 17

        // Head pose PnP landmark indices
        private val PNP_LANDMARK_INDICES = intArrayOf(1, 152, 33, 263, 61, 291)

        // 3D model points for solvePnP (generic face, arbitrary scale)
        private val MODEL_3D_POINTS = arrayOf(
            Point3(0.0, 0.0, 0.0),         // nose tip
            Point3(0.0, -330.0, -65.0),     // chin
            Point3(-225.0, 170.0, -135.0),  // left eye left corner
            Point3(225.0, 170.0, -135.0),   // right eye right corner
            Point3(-150.0, -150.0, -125.0), // left mouth corner
            Point3(150.0, -150.0, -125.0)   // right mouth corner
        )
    }

    private val landmarker: FaceLandmarker
    private var frameTsMs: Long = 0L

    init {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(MODEL_ASSET)
            .build()

        val options = FaceLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.VIDEO)
            .setNumFaces(1)
            .setMinFaceDetectionConfidence(0.5f)
            .setMinFacePresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .build()

        landmarker = FaceLandmarker.createFromOptions(context, options)
        Log.i(TAG, "FaceLandmarker initialized")
    }

    /**
     * Analyze a single video frame for face landmarks and compute EAR, MAR, and head pose.
     *
     * @param bitmap RGB Bitmap of the frame (the caller is responsible for BGR->RGB conversion).
     * @param frameWidth  Width of the original frame in pixels (used for coordinate conversion).
     * @param frameHeight Height of the original frame in pixels (used for coordinate conversion).
     * @return [FaceResult] with detection flags and metric values, or [FaceResult.NO_FACE] defaults.
     */
    fun analyze(bitmap: Bitmap, frameWidth: Int, frameHeight: Int): FaceResult {
        // Wrap the bitmap for MediaPipe
        val mpImage = BitmapImageBuilder(bitmap).build()

        // Monotonically increasing timestamp for VIDEO mode
        frameTsMs += TIMESTAMP_INCREMENT_MS

        val result = try {
            landmarker.detectForVideo(mpImage, frameTsMs)
        } catch (e: Exception) {
            Log.e(TAG, "FaceLandmarker detection failed", e)
            return FaceResult.NO_FACE
        }

        // No face detected
        if (result.faceLandmarks().isEmpty()) {
            return FaceResult.NO_FACE
        }

        val landmarks = result.faceLandmarks()[0]
        val w = frameWidth.toDouble()
        val h = frameHeight.toDouble()

        // --- EAR ---
        val rightEar = computeEar(landmarks, RIGHT_EYE_INDICES, w, h)
        val leftEar = computeEar(landmarks, LEFT_EYE_INDICES, w, h)
        val avgEar = (leftEar + rightEar) / 2.0
        val eyesClosed = avgEar < Config.EAR_THRESHOLD

        // --- MAR ---
        val mar = computeMar(landmarks, w, h)
        val yawning = mar > Config.MAR_THRESHOLD

        // --- Head Pose ---
        val (yawDeg, pitchDeg) = estimateHeadPose(landmarks, w, h)
        val distracted = abs(yawDeg) > Config.HEAD_YAW_THRESHOLD ||
                abs(pitchDeg) > Config.HEAD_PITCH_THRESHOLD

        return FaceResult(
            driverEyesClosed = eyesClosed,
            earValue = roundTo4(avgEar),
            driverYawning = yawning,
            marValue = roundTo4(mar),
            driverDistracted = distracted,
            headYaw = roundTo1(yawDeg),
            headPitch = roundTo1(pitchDeg)
        )
    }

    /**
     * Release the FaceLandmarker resources.
     */
    fun close() {
        try {
            landmarker.close()
            Log.i(TAG, "FaceLandmarker closed")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing FaceLandmarker", e)
        }
    }

    // -------------------------------------------------------------------------
    // EAR computation
    // -------------------------------------------------------------------------

    /**
     * Compute Eye Aspect Ratio for a single eye.
     *
     * Landmarks order: [lateral(p1), upper1(p2), upper2(p3), medial(p4), lower1(p5), lower2(p6)]
     *
     * EAR = (dist(p2,p6) + dist(p3,p5)) / (2 * dist(p1,p4))
     *
     * Returns 0.0 if the horizontal distance is < 1e-6.
     */
    private fun computeEar(
        landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>,
        indices: IntArray,
        frameWidth: Double,
        frameHeight: Double
    ): Double {
        val p1 = pixelCoords(landmarks[indices[0]], frameWidth, frameHeight) // lateral
        val p2 = pixelCoords(landmarks[indices[1]], frameWidth, frameHeight) // upper1
        val p3 = pixelCoords(landmarks[indices[2]], frameWidth, frameHeight) // upper2
        val p4 = pixelCoords(landmarks[indices[3]], frameWidth, frameHeight) // medial
        val p5 = pixelCoords(landmarks[indices[4]], frameWidth, frameHeight) // lower1
        val p6 = pixelCoords(landmarks[indices[5]], frameWidth, frameHeight) // lower2

        val horizontal = euclideanDist(p1, p4)
        if (horizontal < MIN_HORIZONTAL) return 0.0

        val vertical1 = euclideanDist(p2, p6)
        val vertical2 = euclideanDist(p3, p5)

        return (vertical1 + vertical2) / (2.0 * horizontal)
    }

    // -------------------------------------------------------------------------
    // MAR computation
    // -------------------------------------------------------------------------

    /**
     * Compute Mouth Aspect Ratio.
     *
     * MAR = (dist(top,bottom) + dist(upper_mid,lower_mid)) / (2 * dist(left,right))
     *
     * Returns 0.0 if the horizontal distance is < 1e-6.
     */
    private fun computeMar(
        landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>,
        frameWidth: Double,
        frameHeight: Double
    ): Double {
        val top = pixelCoords(landmarks[MAR_TOP], frameWidth, frameHeight)
        val bottom = pixelCoords(landmarks[MAR_BOTTOM], frameWidth, frameHeight)
        val left = pixelCoords(landmarks[MAR_LEFT], frameWidth, frameHeight)
        val right = pixelCoords(landmarks[MAR_RIGHT], frameWidth, frameHeight)
        val upperMid = pixelCoords(landmarks[MAR_UPPER_MID], frameWidth, frameHeight)
        val lowerMid = pixelCoords(landmarks[MAR_LOWER_MID], frameWidth, frameHeight)

        val horizontal = euclideanDist(left, right)
        if (horizontal < MIN_HORIZONTAL) return 0.0

        val vertical1 = euclideanDist(top, bottom)
        val vertical2 = euclideanDist(upperMid, lowerMid)

        return (vertical1 + vertical2) / (2.0 * horizontal)
    }

    // -------------------------------------------------------------------------
    // Head pose estimation via solvePnP
    // -------------------------------------------------------------------------

    /**
     * Estimate head pose (yaw, pitch) in degrees using OpenCV solvePnP.
     *
     * Uses 6 facial landmarks mapped to a generic 3D face model.
     * Camera intrinsics are approximated from the frame dimensions.
     *
     * @return Pair(yawDegrees, pitchDegrees). Returns (0.0, 0.0) if solvePnP fails.
     */
    private fun estimateHeadPose(
        landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>,
        frameWidth: Double,
        frameHeight: Double
    ): Pair<Double, Double> {
        // Build 2D image points from landmarks
        val imagePoints = MatOfPoint2f()
        val pointsList = PNP_LANDMARK_INDICES.map { idx ->
            val lm = landmarks[idx]
            Point(lm.x().toDouble() * frameWidth, lm.y().toDouble() * frameHeight)
        }
        imagePoints.fromList(pointsList)

        // Build 3D model points
        val modelPoints = MatOfPoint3f()
        modelPoints.fromList(MODEL_3D_POINTS.toList())

        // Camera matrix (approximate)
        val focalLength = frameWidth
        val cx = frameWidth / 2.0
        val cy = frameHeight / 2.0

        val cameraMat = Mat(3, 3, CvType.CV_64F)
        cameraMat.put(0, 0, focalLength, 0.0, cx, 0.0, focalLength, cy, 0.0, 0.0, 1.0)

        // Distortion coefficients (none)
        val distCoeffs = MatOfDouble(0.0, 0.0, 0.0, 0.0)

        // Solve PnP
        val rvec = Mat()
        val tvec = Mat()

        val success = Calib3d.solvePnP(
            modelPoints,
            imagePoints,
            cameraMat,
            distCoeffs,
            rvec,
            tvec,
            false,
            Calib3d.SOLVEPNP_ITERATIVE
        )

        var yawDeg = 0.0
        var pitchDeg = 0.0

        if (success) {
            // Convert rotation vector to rotation matrix via Rodrigues
            val rotationMatrix = Mat(3, 3, CvType.CV_64F)
            Calib3d.Rodrigues(rvec, rotationMatrix)

            // Extract Euler angles
            val r00 = rotationMatrix.get(0, 0)[0]
            val r10 = rotationMatrix.get(1, 0)[0]
            val r20 = rotationMatrix.get(2, 0)[0]

            val sy = sqrt(r00 * r00 + r10 * r10)

            val pitch: Double
            val yaw: Double

            if (sy > 1e-6) {
                pitch = atan2(-r20, sy)
                yaw = atan2(r10, r00)
            } else {
                pitch = atan2(-r20, sy)
                yaw = 0.0
            }

            yawDeg = Math.toDegrees(yaw)
            pitchDeg = Math.toDegrees(pitch)

            // Release intermediate Mats
            rotationMatrix.release()
        }

        // Release Mats
        imagePoints.release()
        modelPoints.release()
        cameraMat.release()
        distCoeffs.release()
        rvec.release()
        tvec.release()

        return Pair(yawDeg, pitchDeg)
    }

    // -------------------------------------------------------------------------
    // Utility functions
    // -------------------------------------------------------------------------

    /** Convert a normalized MediaPipe landmark to pixel coordinates. */
    private fun pixelCoords(
        landmark: com.google.mediapipe.tasks.components.containers.NormalizedLandmark,
        frameWidth: Double,
        frameHeight: Double
    ): DoubleArray {
        return doubleArrayOf(
            landmark.x().toDouble() * frameWidth,
            landmark.y().toDouble() * frameHeight
        )
    }

    /** Euclidean distance between two 2D points represented as [x, y] arrays. */
    private fun euclideanDist(a: DoubleArray, b: DoubleArray): Double {
        val dx = a[0] - b[0]
        val dy = a[1] - b[1]
        return sqrt(dx * dx + dy * dy)
    }

    /** Round a Double to 4 decimal places and return as Float. */
    private fun roundTo4(value: Double): Float {
        return (Math.round(value * 10000.0) / 10000.0).toFloat()
    }

    /** Round a Double to 1 decimal place and return as Float. */
    private fun roundTo1(value: Double): Float {
        return (Math.round(value * 10.0) / 10.0).toFloat()
    }
}
