package com.incabin

import android.content.res.AssetManager
import android.util.Log
import java.io.Closeable

/**
 * Kotlin bridge to the native C++ FaceRecognizer (MobileFaceNet).
 *
 * Manages lifecycle of the native ONNX Runtime session for face embedding
 * computation. Call [close] when done to free native resources.
 */
class FaceRecognizerBridge(
    assetManager: AssetManager,
    threadCount: Int = 2,
    threadAffinity: String = ""
) : Closeable {

    companion object {
        private const val TAG = "FaceRecognizerBridge"

        // Uses same libincabin.so as PoseAnalyzerBridge — no separate loadLibrary needed.
        // NativeLib or PoseAnalyzerBridge will have loaded it already.
        val nativeLoaded: Boolean
            get() = NativeLib.loaded
    }

    private var nativePtr: Long = 0L

    init {
        if (nativeLoaded) {
            nativePtr = nativeCreateFaceRecognizer(assetManager, threadCount, threadAffinity)
            if (nativePtr == 0L) {
                Log.e(TAG, "Failed to create native FaceRecognizer")
            } else {
                Log.i(TAG, "Native FaceRecognizer created (threads=$threadCount, affinity='$threadAffinity')")
            }
        } else {
            Log.e(TAG, "Skipping native FaceRecognizer creation — library not loaded")
        }
    }

    /**
     * Compute a 512-dim face embedding from a BGR face crop.
     *
     * @param bgrCrop BGR pixel data (HWC uint8)
     * @param cropW   Crop width
     * @param cropH   Crop height
     * @return FloatArray of 512 dimensions (L2-normalized), or null on failure
     */
    fun computeEmbedding(bgrCrop: ByteArray, cropW: Int, cropH: Int): FloatArray? {
        if (nativePtr == 0L) {
            Log.w(TAG, "computeEmbedding called on closed FaceRecognizerBridge")
            return null
        }
        return nativeComputeEmbedding(nativePtr, bgrCrop, cropW, cropH)
    }

    val isInitialized: Boolean
        get() = nativePtr != 0L

    override fun close() {
        if (nativePtr != 0L) {
            nativeDestroyFaceRecognizer(nativePtr)
            nativePtr = 0L
            Log.i(TAG, "Native FaceRecognizer destroyed")
        }
    }

    private external fun nativeCreateFaceRecognizer(
        assetManager: AssetManager, threadCount: Int, threadAffinity: String
    ): Long
    private external fun nativeComputeEmbedding(
        recognizerPtr: Long, bgrCrop: ByteArray, cropW: Int, cropH: Int
    ): FloatArray?
    private external fun nativeDestroyFaceRecognizer(recognizerPtr: Long)
}
