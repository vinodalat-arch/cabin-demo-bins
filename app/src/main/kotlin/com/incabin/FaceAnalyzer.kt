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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Single face landmark in pixel coordinates. */
data class OverlayLandmark(val x: Float, val y: Float)

/** BGR face crop for recognition. */
data class FaceCropResult(val bgrData: ByteArray, val width: Int, val height: Int)

/** Face overlay data for drawing eye contours, mouth outline, and nose. */
data class FaceOverlayData(
    val rightEye: List<OverlayLandmark>,
    val leftEye: List<OverlayLandmark>,
    val mouth: List<OverlayLandmark>,
    val noseTip: OverlayLandmark
)

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
    val headPitch: Float? = null,
    val faceOverlay: FaceOverlayData? = null
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
            headPitch = null,
            faceOverlay = null
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

    /** Last detected face landmarks (normalized coords), or null if no face. */
    var lastLandmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>? = null
        private set

    // Pre-allocated OpenCV Mats for solvePnP (avoids 7 Mat alloc+release per frame)
    private val modelPoints3d = MatOfPoint3f().apply { fromList(MODEL_3D_POINTS.toList()) }
    private val cameraMat = Mat(3, 3, CvType.CV_64F)
    private val distCoeffs = MatOfDouble(0.0, 0.0, 0.0, 0.0)
    private val rvec = Mat()
    private val tvec = Mat()
    private val rotationMatrix = Mat(3, 3, CvType.CV_64F)
    private val imagePoints2d = MatOfPoint2f()
    private var cameraMatInitialized = false

    // Pre-allocated list for solvePnP 2D points (avoids .map{} allocation per frame)
    private val pnpPointsList = ArrayList<Point>(PNP_LANDMARK_INDICES.size)

    // --- Auto-baseline calibration ---
    private val earBaselineSamples = mutableListOf<Double>()
    private val pitchBaselineSamples = mutableListOf<Double>()
    private var earBaseline: Double? = null
    private var pitchBaseline: Double? = null

    // --- Angle smoothing (moving average) ---
    private val yawHistory = ArrayDeque<Double>(Config.ANGLE_SMOOTH_WINDOW)
    private val pitchHistory = ArrayDeque<Double>(Config.ANGLE_SMOOTH_WINDOW)


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
            lastLandmarks = null
            return FaceResult.NO_FACE
        }

        val landmarks = result.faceLandmarks()[0]
        lastLandmarks = landmarks
        val w = frameWidth.toDouble()
        val h = frameHeight.toDouble()

        // --- EAR ---
        val rightEar = computeEar(landmarks, RIGHT_EYE_INDICES, w, h)
        val leftEar = computeEar(landmarks, LEFT_EYE_INDICES, w, h)
        val avgEar = (leftEar + rightEar) / 2.0

        // EAR auto-baseline: accumulate first N frames, then use relative threshold
        val eyesClosed = if (earBaseline != null) {
            avgEar < earBaseline!! * Config.EAR_BASELINE_RATIO
        } else {
            // Still calibrating — collect samples and use fixed threshold as fallback
            // Only accumulate when eyes appear open (EAR >= threshold) to avoid
            // corrupting baseline with closed-eye samples
            if (earBaselineSamples.size < Config.BASELINE_FRAMES && avgEar >= Config.EAR_THRESHOLD) {
                earBaselineSamples.add(avgEar)
                if (earBaselineSamples.size == Config.BASELINE_FRAMES) {
                    earBaseline = earBaselineSamples.average()
                    Log.i(TAG, "EAR baseline calibrated: %.4f (threshold: %.4f)".format(
                        earBaseline, earBaseline!! * Config.EAR_BASELINE_RATIO))
                }
            }
            avgEar < Config.EAR_THRESHOLD
        }

        // --- MAR ---
        val mar = computeMar(landmarks, w, h)
        val yawning = mar > Config.MAR_THRESHOLD

        // --- Head Pose ---
        val (rawYawDeg, rawPitchDeg) = estimateHeadPose(landmarks, w, h)

        // Angle smoothing: 3-frame moving average before thresholding
        yawHistory.addLast(rawYawDeg)
        if (yawHistory.size > Config.ANGLE_SMOOTH_WINDOW) yawHistory.removeFirst()
        pitchHistory.addLast(rawPitchDeg)
        if (pitchHistory.size > Config.ANGLE_SMOOTH_WINDOW) pitchHistory.removeFirst()
        val yawDeg = yawHistory.average()
        val pitchDeg = pitchHistory.average()

        // Pitch auto-baseline: accumulate first N frames, then use deviation-based threshold
        val pitchDistracted = if (pitchBaseline != null) {
            abs(pitchDeg - pitchBaseline!!) > Config.PITCH_BASELINE_DEVIATION
        } else {
            // Still calibrating — collect samples and use fixed threshold as fallback
            // Only accumulate when head is roughly forward (|pitch| < fixed threshold)
            // to avoid corrupting baseline with looked-down/up samples
            if (pitchBaselineSamples.size < Config.BASELINE_FRAMES && abs(pitchDeg) < Config.HEAD_PITCH_THRESHOLD) {
                pitchBaselineSamples.add(pitchDeg)
                if (pitchBaselineSamples.size == Config.BASELINE_FRAMES) {
                    pitchBaseline = pitchBaselineSamples.average()
                    Log.i(TAG, "Pitch baseline calibrated: %.1f° (threshold: ±%.1f°)".format(
                        pitchBaseline, Config.PITCH_BASELINE_DEVIATION))
                }
            }
            abs(pitchDeg) > Config.HEAD_PITCH_THRESHOLD
        }

        val distracted = abs(yawDeg) > Config.HEAD_YAW_THRESHOLD || pitchDistracted

        // --- Build Face Overlay (only when preview is enabled — avoids 4 list allocations per frame) ---
        val faceOverlay = if (!Config.ENABLE_PREVIEW) null else try {
            val rightEyeLandmarks = RIGHT_EYE_INDICES.map { idx ->
                val lm = landmarks[idx]
                OverlayLandmark((lm.x() * w).toFloat(), (lm.y() * h).toFloat())
            }
            val leftEyeLandmarks = LEFT_EYE_INDICES.map { idx ->
                val lm = landmarks[idx]
                OverlayLandmark((lm.x() * w).toFloat(), (lm.y() * h).toFloat())
            }
            val mouthIndices = intArrayOf(MAR_TOP, MAR_BOTTOM, MAR_LEFT, MAR_RIGHT, MAR_UPPER_MID, MAR_LOWER_MID)
            val mouthLandmarks = mouthIndices.map { idx ->
                val lm = landmarks[idx]
                OverlayLandmark((lm.x() * w).toFloat(), (lm.y() * h).toFloat())
            }
            val noseLm = landmarks[1] // nose tip (PnP index 0 = landmark 1)
            val noseTip = OverlayLandmark((noseLm.x() * w).toFloat(), (noseLm.y() * h).toFloat())
            FaceOverlayData(rightEyeLandmarks, leftEyeLandmarks, mouthLandmarks, noseTip)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to build face overlay", e)
            null
        }

        return FaceResult(
            driverEyesClosed = eyesClosed,
            earValue = roundTo4(avgEar),
            driverYawning = yawning,
            marValue = roundTo4(mar),
            driverDistracted = distracted,
            headYaw = roundTo1(yawDeg),
            headPitch = roundTo1(pitchDeg),
            faceOverlay = faceOverlay
        )
    }

    /**
     * Extract a BGR face crop from the source frame using landmark bounding box.
     *
     * @param bgrData      Full frame BGR data
     * @param frameWidth   Frame width
     * @param frameHeight  Frame height
     * @param landmarks    Face landmarks (normalized coordinates)
     * @return FaceCropResult with BGR crop data, or null if crop is invalid
     */
    fun extractFaceCrop(
        bgrData: ByteArray,
        frameWidth: Int,
        frameHeight: Int,
        landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>
    ): FaceCropResult? {
        // Compute bounding box from all landmarks
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var maxY = Float.MIN_VALUE

        for (lm in landmarks) {
            minX = min(minX, lm.x())
            minY = min(minY, lm.y())
            maxX = max(maxX, lm.x())
            maxY = max(maxY, lm.y())
        }

        // Convert to pixel coordinates
        val px1 = (minX * frameWidth).toInt()
        val py1 = (minY * frameHeight).toInt()
        val px2 = (maxX * frameWidth).toInt()
        val py2 = (maxY * frameHeight).toInt()

        // Add 20% padding
        val pw = px2 - px1
        val ph = py2 - py1
        val padX = (pw * 0.2f).toInt()
        val padY = (ph * 0.2f).toInt()

        val cx1 = max(0, px1 - padX)
        val cy1 = max(0, py1 - padY)
        val cx2 = min(frameWidth, px2 + padX)
        val cy2 = min(frameHeight, py2 + padY)

        val cropW = cx2 - cx1
        val cropH = cy2 - cy1
        if (cropW <= 0 || cropH <= 0) return null

        // Row-by-row BGR copy
        val cropData = ByteArray(cropW * cropH * 3)
        for (row in 0 until cropH) {
            val srcOffset = ((cy1 + row) * frameWidth + cx1) * 3
            val dstOffset = row * cropW * 3
            System.arraycopy(bgrData, srcOffset, cropData, dstOffset, cropW * 3)
        }

        return FaceCropResult(cropData, cropW, cropH)
    }

    /**
     * Release the FaceLandmarker and pre-allocated OpenCV Mat resources.
     */
    fun close() {
        try {
            landmarker.close()
            modelPoints3d.release()
            cameraMat.release()
            distCoeffs.release()
            rvec.release()
            tvec.release()
            rotationMatrix.release()
            imagePoints2d.release()
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
     * All Mats are pre-allocated as class members to avoid per-frame allocation.
     *
     * @return Pair(yawDegrees, pitchDegrees). Returns (0.0, 0.0) if solvePnP fails.
     */
    private fun estimateHeadPose(
        landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>,
        frameWidth: Double,
        frameHeight: Double
    ): Pair<Double, Double> {
        // Initialize camera matrix once (frame dimensions are constant)
        if (!cameraMatInitialized) {
            val focalLength = frameWidth
            val cx = frameWidth / 2.0
            val cy = frameHeight / 2.0
            cameraMat.put(0, 0, focalLength, 0.0, cx, 0.0, focalLength, cy, 0.0, 0.0, 1.0)
            cameraMatInitialized = true
        }

        // Build 2D image points from landmarks (reuse pre-allocated list + Mat)
        pnpPointsList.clear()
        for (idx in PNP_LANDMARK_INDICES) {
            val lm = landmarks[idx]
            pnpPointsList.add(Point(lm.x().toDouble() * frameWidth, lm.y().toDouble() * frameHeight))
        }
        imagePoints2d.fromList(pnpPointsList)

        val success = Calib3d.solvePnP(
            modelPoints3d,
            imagePoints2d,
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
            Calib3d.Rodrigues(rvec, rotationMatrix)

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
        }

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
