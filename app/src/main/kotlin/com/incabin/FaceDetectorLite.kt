package com.incabin

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker.FaceLandmarkerOptions
import kotlin.math.max
import kotlin.math.min

/**
 * Lightweight face detector for registration UI.
 *
 * Uses MediaPipe FaceLandmarker in IMAGE mode (no timestamps, no calibration,
 * no EAR/MAR/solvePnP). Returns face bounding box with padding for crop extraction.
 */
class FaceDetectorLite(context: Context) {

    companion object {
        private const val TAG = "FaceDetectorLite"
        private const val MODEL_ASSET = "face_landmarker.task"
        private const val BBOX_PADDING = 0.2f
    }

    /** Result of a single face detection. */
    data class FaceDetection(
        val bboxLeft: Int,
        val bboxTop: Int,
        val bboxRight: Int,
        val bboxBottom: Int,
        val paddedLeft: Int,
        val paddedTop: Int,
        val paddedRight: Int,
        val paddedBottom: Int
    )

    private val landmarker: FaceLandmarker

    init {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(MODEL_ASSET)
            .build()

        val options = FaceLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.IMAGE)
            .setNumFaces(1)
            .setMinFaceDetectionConfidence(0.5f)
            .setMinFacePresenceConfidence(0.5f)
            .build()

        landmarker = FaceLandmarker.createFromOptions(context, options)
        Log.i(TAG, "FaceDetectorLite initialized (IMAGE mode)")
    }

    /**
     * Detect a face in the given bitmap.
     *
     * @param bitmap RGB bitmap to analyze
     * @param frameWidth  Original frame width in pixels
     * @param frameHeight Original frame height in pixels
     * @return FaceDetection with bbox and padded bbox, or null if no face found
     */
    fun detect(bitmap: Bitmap, frameWidth: Int, frameHeight: Int): FaceDetection? {
        val mpImage = BitmapImageBuilder(bitmap).build()

        val result = try {
            landmarker.detect(mpImage)
        } catch (e: Exception) {
            Log.e(TAG, "Face detection failed", e)
            return null
        }

        if (result.faceLandmarks().isEmpty()) return null

        val landmarks = result.faceLandmarks()[0]

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

        // Add padding for face crop
        val pw = px2 - px1
        val ph = py2 - py1
        val padX = (pw * BBOX_PADDING).toInt()
        val padY = (ph * BBOX_PADDING).toInt()

        val cx1 = max(0, px1 - padX)
        val cy1 = max(0, py1 - padY)
        val cx2 = min(frameWidth, px2 + padX)
        val cy2 = min(frameHeight, py2 + padY)

        return FaceDetection(
            bboxLeft = px1, bboxTop = py1, bboxRight = px2, bboxBottom = py2,
            paddedLeft = cx1, paddedTop = cy1, paddedRight = cx2, paddedBottom = cy2
        )
    }

    /** Release MediaPipe resources. */
    fun close() {
        try {
            landmarker.close()
            Log.i(TAG, "FaceDetectorLite closed")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing FaceDetectorLite", e)
        }
    }
}
