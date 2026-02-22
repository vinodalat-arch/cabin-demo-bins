package com.incabin

import android.util.Log

class NativeLib {

    companion object {
        private const val TAG = "NativeLib"
        var loaded = false
            private set

        init {
            try {
                System.loadLibrary("incabin")
                loaded = true
                Log.i(TAG, "Native library 'incabin' loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load native library 'incabin'", e)
            }
        }
    }

    /**
     * Convert YUV_420_888 frame to BGR byte array via native code.
     *
     * @param y Y plane bytes
     * @param u U plane bytes
     * @param v V plane bytes
     * @param yRowStride Row stride of Y plane
     * @param uvRowStride Row stride of U/V planes
     * @param uvPixelStride Pixel stride of U/V planes (1=planar, 2=interleaved)
     * @param width Frame width
     * @param height Frame height
     * @return BGR byte array (width * height * 3) or null on error
     */
    external fun nativeYuvToBgr(
        y: ByteArray,
        u: ByteArray,
        v: ByteArray,
        yRowStride: Int,
        uvRowStride: Int,
        uvPixelStride: Int,
        width: Int,
        height: Int
    ): ByteArray?

    /** Convert BGR byte array to ARGB pixel array in native code. Fills pre-allocated IntArray. */
    external fun nativeBgrToArgbPixels(bgr: ByteArray, pixels: IntArray, width: Int, height: Int)

    // --- V4L2 direct camera access ---

    /** Scan /dev/video0-63, return first V4L2 capture device path or null. */
    external fun nativeFindV4l2Device(): String?

    /** Create V4L2 camera: open + configure YUYV + mmap + start streaming. Returns handle or 0. */
    external fun nativeCreateV4l2Camera(devicePath: String, width: Int, height: Int): Long

    /** Grab one YUYV frame, convert to BGR, return byte[]. Null on error. */
    external fun nativeGrabBgrFrame(cameraPtr: Long): ByteArray?

    /** Destroy camera (stop stream + unmap + close). */
    external fun nativeDestroyV4l2Camera(cameraPtr: Long)

    /** Get actual negotiated width from V4L2 camera (may differ from requested). */
    external fun nativeGetV4l2Width(cameraPtr: Long): Int

    /** Get actual negotiated height from V4L2 camera (may differ from requested). */
    external fun nativeGetV4l2Height(cameraPtr: Long): Int
}
