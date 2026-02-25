package com.incabin

import android.content.res.AssetManager
import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.io.Closeable

/** Single keypoint from COCO pose model. */
data class OverlayKeypoint(
    val x: Float = 0f,
    val y: Float = 0f,
    @SerializedName("c")
    val conf: Float = 0f
)

/** Person detection with bounding box and skeleton keypoints for overlay drawing. */
data class OverlayPerson(
    val x1: Float = 0f,
    val y1: Float = 0f,
    val x2: Float = 0f,
    val y2: Float = 0f,
    val confidence: Float = 0f,
    @SerializedName("is_driver")
    val isDriver: Boolean = false,
    val keypoints: List<OverlayKeypoint> = emptyList()
)

/**
 * Pose analysis result from YOLOv8n-pose and YOLOv8n detection (C++ layer).
 * All fields have safe defaults for when analysis fails or no person detected.
 */
data class PoseResult(
    @SerializedName("passenger_count")
    val passengerCount: Int = 0,

    @SerializedName("child_count")
    val childCount: Int = 0,

    @SerializedName("driver_detected")
    val driverDetected: Boolean = true,

    @SerializedName("driver_using_phone")
    val driverUsingPhone: Boolean = false,

    @SerializedName("dangerous_posture")
    val dangerousPosture: Boolean = false,

    @SerializedName("child_present")
    val childPresent: Boolean = false,

    @SerializedName("child_slouching")
    val childSlouching: Boolean = false,

    @SerializedName("driver_eating_drinking")
    val driverEatingDrinking: Boolean = false,

    @SerializedName("hands_off_wheel")
    val handsOffWheel: Boolean = false,

    val persons: List<OverlayPerson> = emptyList()
)

/**
 * Kotlin bridge to the native C++ PoseAnalyzer.
 *
 * Manages lifecycle of the native ONNX Runtime sessions for YOLOv8n-pose
 * and YOLOv8n detection models. Call [close] when done to free native resources.
 */
class PoseAnalyzerBridge(
    assetManager: AssetManager,
    threadCount: Int = 4,
    threadAffinity: String = ""
) : Closeable {

    companion object {
        private const val TAG = "PoseAnalyzerBridge"
        var nativeLoaded = false
            private set

        init {
            try {
                System.loadLibrary("incabin")
                nativeLoaded = true
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load native library 'incabin'", e)
            }
        }

        private val gson = Gson()
    }

    private var nativePtr: Long = 0L

    init {
        if (nativeLoaded) {
            nativePtr = nativeCreatePoseAnalyzer(assetManager, threadCount, threadAffinity)
            if (nativePtr == 0L) {
                Log.e(TAG, "Failed to create native PoseAnalyzer")
            } else {
                Log.i(TAG, "Native PoseAnalyzer created (threads=$threadCount, affinity='$threadAffinity')")
            }
        } else {
            Log.e(TAG, "Skipping native PoseAnalyzer creation — library not loaded")
        }
    }

    /**
     * Run pose analysis on a BGR frame.
     *
     * @param bgrData    BGR pixel data (HWC uint8, width * height * 3 bytes)
     * @param width      Frame width
     * @param height     Frame height
     * @param seatOnLeft true if driver seat is on the left side of the frame
     * @return PoseResult with all pose-derived detection fields
     */
    fun analyze(bgrData: ByteArray, width: Int, height: Int, seatOnLeft: Boolean = true): PoseResult {
        if (nativePtr == 0L) {
            Log.w(TAG, "analyze called on closed PoseAnalyzerBridge")
            return PoseResult()
        }

        val jsonStr = nativeAnalyzePose(nativePtr, bgrData, width, height, seatOnLeft)
        return try {
            gson.fromJson(jsonStr, PoseResult::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse pose result JSON: $jsonStr", e)
            PoseResult()
        }
    }

    val isInitialized: Boolean
        get() = nativePtr != 0L

    override fun close() {
        if (nativePtr != 0L) {
            nativeDestroyPoseAnalyzer(nativePtr)
            nativePtr = 0L
            Log.i(TAG, "Native PoseAnalyzer destroyed")
        }
    }

    private external fun nativeCreatePoseAnalyzer(
        assetManager: AssetManager, threadCount: Int, threadAffinity: String
    ): Long

    private external fun nativeAnalyzePose(
        analyzerPtr: Long,
        bgrData: ByteArray,
        width: Int,
        height: Int,
        seatOnLeft: Boolean
    ): String

    private external fun nativeDestroyPoseAnalyzer(analyzerPtr: Long)
}
