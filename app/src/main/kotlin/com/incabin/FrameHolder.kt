package com.incabin

import android.graphics.Bitmap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
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

    /** Result-only channel: delivers OutputResult to UI without waiting for bitmap. */
    private val latestResult = AtomicReference<OutputResult?>(null)

    /** Per-passenger posture data for UI display (not part of OutputResult schema). */
    data class PassengerPosture(val index: Int, val hasBadPosture: Boolean)
    private val latestPassengerPostures = AtomicReference<List<PassengerPosture>>(emptyList())

    fun postPassengerPostures(postures: List<PassengerPosture>) {
        latestPassengerPostures.set(postures)
    }

    fun getPassengerPostures(): List<PassengerPosture> = latestPassengerPostures.get()

    /** BGR face crop for registration UI. */
    data class CaptureData(val bgrCrop: ByteArray, val cropWidth: Int, val cropHeight: Int)
    private val latestCapture = AtomicReference<CaptureData?>(null)

    /** Store a new frame + result. Caller transfers bitmap ownership. */
    fun postFrame(bitmap: Bitmap, result: OutputResult) {
        latest.set(FrameData(bitmap, result))
    }

    /** Post result only (no bitmap). Dashboard reads this for fast updates. */
    fun postResult(result: OutputResult) {
        latestResult.set(result)
    }

    /** Get the latest frame data (bitmap + result). Caller must NOT recycle the Bitmap. */
    fun getLatest(): FrameData? = latest.get()

    /** Get the latest result (independent of bitmap). */
    fun getLatestResult(): OutputResult? = latestResult.get()

    /** Get the latest frame bitmap only. Caller must NOT recycle the returned Bitmap. */
    fun getLatestFrame(): Bitmap? = latest.get()?.bitmap

    /** Post a face crop for registration UI. */
    fun postCaptureData(data: CaptureData) {
        latestCapture.set(data)
    }

    /** Get the latest face crop (for registration). */
    fun getCaptureData(): CaptureData? = latestCapture.get()

    // --- Camera status indicator ---
    enum class CameraStatus {
        NOT_CONNECTED,
        CONNECTING,
        READY,
        ACTIVE,
        LOST
    }

    private val cameraStatus = AtomicReference(CameraStatus.NOT_CONNECTED)

    fun postCameraStatus(status: CameraStatus) {
        cameraStatus.set(status)
    }

    fun getCameraStatus(): CameraStatus = cameraStatus.get()

    // --- Service heartbeat (for UI stall detection) ---
    private val serviceHeartbeatMs = AtomicLong(0L)
    private val serviceRunning = AtomicBoolean(false)

    /** Record a heartbeat from the service pipeline. */
    fun postHeartbeat() {
        serviceHeartbeatMs.set(System.currentTimeMillis())
        serviceRunning.set(true)
    }

    /** Get age of last heartbeat in ms. Returns Long.MAX_VALUE if never posted. */
    fun getHeartbeatAgeMs(): Long {
        val last = serviceHeartbeatMs.get()
        if (last == 0L) return Long.MAX_VALUE
        return System.currentTimeMillis() - last
    }

    /** Whether the service has ever posted a heartbeat. */
    fun isServiceRunning(): Boolean = serviceRunning.get()

    /** Clear the held frame. */
    fun clear() {
        latest.set(null)
        latestResult.set(null)
        latestCapture.set(null)
        latestPassengerPostures.set(emptyList())
        cameraStatus.set(CameraStatus.NOT_CONNECTED)
        serviceHeartbeatMs.set(0L)
        serviceRunning.set(false)
    }
}
