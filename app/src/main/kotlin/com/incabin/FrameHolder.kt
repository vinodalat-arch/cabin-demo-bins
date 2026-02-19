package com.incabin

import android.graphics.Bitmap
import java.util.concurrent.atomic.AtomicReference

/**
 * Singleton holding the latest camera frame and detection result for UI preview.
 * Thread-safe: service posts frames, activity reads them.
 *
 * Bitmap lifecycle: old bitmaps are NOT recycled here to avoid TOCTOU races
 * where the Activity reads a bitmap that gets recycled mid-use. Instead, old
 * bitmaps are left for GC finalizer. This trades ~3.7 MB of transient memory
 * for crash safety (a recycled-bitmap exception on the UI thread would kill
 * the entire process including the safety-critical InCabinService).
 */
object FrameHolder {

    /** Combines a preview bitmap with the corresponding detection result. */
    data class FrameData(val bitmap: Bitmap, val result: OutputResult)

    private val latest = AtomicReference<FrameData?>(null)

    /** Store a new frame + result. Caller transfers bitmap ownership. */
    fun postFrame(bitmap: Bitmap, result: OutputResult) {
        latest.set(FrameData(bitmap, result))
    }

    /** Get the latest frame data (bitmap + result). Caller must NOT recycle the Bitmap. */
    fun getLatest(): FrameData? = latest.get()

    /** Get the latest frame bitmap only. Caller must NOT recycle the returned Bitmap. */
    fun getLatestFrame(): Bitmap? = latest.get()?.bitmap

    /** Clear the held frame. */
    fun clear() {
        latest.set(null)
    }
}
