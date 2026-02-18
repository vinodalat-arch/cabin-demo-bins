package com.incabin

import android.graphics.Bitmap
import java.util.concurrent.atomic.AtomicReference

/**
 * Singleton holding the latest camera frame and detection result for UI preview.
 * Thread-safe: service posts frames, activity reads them.
 */
object FrameHolder {

    /** Combines a preview bitmap with the corresponding detection result. */
    data class FrameData(val bitmap: Bitmap, val result: OutputResult)

    private val latest = AtomicReference<FrameData?>(null)

    /** Store a new frame + result, recycling the previous bitmap. Caller transfers bitmap ownership. */
    fun postFrame(bitmap: Bitmap, result: OutputResult) {
        val old = latest.getAndSet(FrameData(bitmap, result))
        if (old != null && !old.bitmap.isRecycled) {
            old.bitmap.recycle()
        }
    }

    /** Get the latest frame data (bitmap + result). Caller must NOT recycle the Bitmap. */
    fun getLatest(): FrameData? = latest.get()

    /** Get the latest frame bitmap only. Caller must NOT recycle the returned Bitmap. */
    fun getLatestFrame(): Bitmap? = latest.get()?.bitmap

    /** Clear and recycle the held frame. */
    fun clear() {
        val old = latest.getAndSet(null)
        if (old != null && !old.bitmap.isRecycled) {
            old.bitmap.recycle()
        }
    }
}
