package com.incabin

import android.graphics.Bitmap
import java.util.concurrent.atomic.AtomicReference

/**
 * Singleton holding the latest camera frame for UI preview.
 * Thread-safe: service posts frames, activity reads them.
 */
object FrameHolder {

    private val latestFrame = AtomicReference<Bitmap?>(null)

    /** Store a new frame, recycling the previous one. Caller transfers ownership. */
    fun postFrame(bitmap: Bitmap) {
        val old = latestFrame.getAndSet(bitmap)
        if (old != null && !old.isRecycled) {
            old.recycle()
        }
    }

    /** Get the latest frame. Caller must NOT recycle the returned Bitmap. */
    fun getLatestFrame(): Bitmap? = latestFrame.get()

    /** Clear and recycle the held frame. */
    fun clear() {
        val old = latestFrame.getAndSet(null)
        if (old != null && !old.isRecycled) {
            old.recycle()
        }
    }
}
