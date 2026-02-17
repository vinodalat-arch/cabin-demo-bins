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
}
