package com.incabin

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * MJPEG-over-HTTP camera client. Connects to an MJPEG stream URL,
 * extracts JPEG frames, converts them to BGR byte arrays, and delivers
 * them via the provided callback.
 *
 * Supports standard MJPEG streams (multipart/x-mixed-replace boundary).
 * No external dependencies needed — uses java.net and BitmapFactory.
 */
class MjpegCameraManager(
    nativeLib: NativeLib,  // unused — kept for API compatibility with V4l2CameraManager
    private val onBgrFrame: (ByteArray, Int, Int) -> Unit
) {
    companion object {
        private const val TAG = "InCabin-MJPEG"
        private const val CONNECT_TIMEOUT_MS = 5000
        private const val READ_TIMEOUT_MS = 10000
        private const val BUFFER_SIZE = 65536
        private const val MAX_JPEG_SIZE = 10 * 1024 * 1024  // 10MB max per frame
        private const val MAX_LINE_LENGTH = 4096  // Max header line length to prevent OOM

        /** Compute next backoff delay (doubles, capped at max). Pure function. */
        fun nextBackoffDelay(currentDelayMs: Long, maxDelayMs: Long): Long {
            val next = currentDelayMs * 2
            return if (next > maxDelayMs) maxDelayMs else next
        }
    }

    @Volatile private var running = false
    @Volatile private var wasConnected = false  // Track if stream was ever ACTIVE
    private var streamThread: Thread? = null

    // Pre-allocated buffers for frame processing (avoid per-frame GC pressure)
    private var pixelBuffer: IntArray? = null
    private var bgrBuffer: ByteArray? = null
    private var lastWidth = 0
    private var lastHeight = 0
    private var lastFrameTimeMs = 0L

    /**
     * Start reading from the given MJPEG stream URL.
     * Runs on a background thread. Each decoded frame is delivered via [onBgrFrame].
     */
    fun start(streamUrl: String): Boolean {
        if (running) return true
        running = true
        wasConnected = false

        streamThread = Thread({
            var reconnectDelayMs = Config.V4L2_RECONNECT_INITIAL_DELAY_MS

            // Outer reconnect loop: retries connection with exponential backoff
            while (running) {
                Log.i(TAG, "Connecting to MJPEG stream: $streamUrl")
                var connection: HttpURLConnection? = null

                try {
                    val url = URL(streamUrl)
                    connection = url.openConnection() as HttpURLConnection
                    connection.connectTimeout = CONNECT_TIMEOUT_MS
                    connection.readTimeout = READ_TIMEOUT_MS
                    connection.requestMethod = "GET"
                    connection.connect()

                    val responseCode = connection.responseCode
                    if (responseCode != HttpURLConnection.HTTP_OK) {
                        Log.e(TAG, "HTTP error: $responseCode")
                        handleConnectionFailure(reconnectDelayMs)
                        reconnectDelayMs = nextBackoffDelay(reconnectDelayMs, Config.V4L2_RECONNECT_MAX_DELAY_MS)
                        continue
                    }

                    val contentType = connection.contentType ?: ""
                    Log.i(TAG, "Stream content-type: $contentType")

                    // Extract boundary from content-type
                    val boundary = extractBoundary(contentType)
                    if (boundary == null) {
                        Log.e(TAG, "No MJPEG boundary found in content-type: $contentType")
                        handleConnectionFailure(reconnectDelayMs)
                        reconnectDelayMs = nextBackoffDelay(reconnectDelayMs, Config.V4L2_RECONNECT_MAX_DELAY_MS)
                        continue
                    }

                    Log.i(TAG, "MJPEG stream connected, boundary: $boundary")
                    wasConnected = true
                    reconnectDelayMs = Config.V4L2_RECONNECT_INITIAL_DELAY_MS  // Reset backoff on success
                    FrameHolder.postCameraStatus(FrameHolder.CameraStatus.ACTIVE)
                    val input = BufferedInputStream(connection.inputStream, BUFFER_SIZE)
                    readMjpegStream(input, boundary)

                    // Stream ended (EOF or error in readMjpegStream) — attempt reconnect
                    if (running) {
                        Log.w(TAG, "MJPEG stream ended, will reconnect")
                        FrameHolder.postCameraStatus(FrameHolder.CameraStatus.LOST)
                    }

                } catch (e: Exception) {
                    if (running) {
                        Log.e(TAG, "MJPEG stream error", e)
                    }
                } finally {
                    connection?.disconnect()
                }

                if (!running) break

                // Backoff before reconnect attempt
                handleConnectionFailure(reconnectDelayMs)
                reconnectDelayMs = nextBackoffDelay(reconnectDelayMs, Config.V4L2_RECONNECT_MAX_DELAY_MS)
            }

            // Final status when fully stopped
            if (!wasConnected) {
                FrameHolder.postCameraStatus(FrameHolder.CameraStatus.NOT_CONNECTED)
            }
            Log.i(TAG, "MJPEG stream disconnected")
        }, "MJPEG-Reader")
        streamThread?.isDaemon = true
        streamThread?.start()
        return true
    }

    fun stop() {
        running = false
        streamThread?.interrupt()
        streamThread = null
        Log.i(TAG, "MJPEG camera stopped")
    }

    private fun extractBoundary(contentType: String): String? {
        // Content-Type: multipart/x-mixed-replace; boundary=--myboundary
        val idx = contentType.indexOf("boundary=")
        if (idx < 0) return null
        return contentType.substring(idx + 9).trim()
    }

    private fun handleConnectionFailure(delayMs: Long) {
        if (!wasConnected) {
            FrameHolder.postCameraStatus(FrameHolder.CameraStatus.NOT_CONNECTED)
        } else {
            FrameHolder.postCameraStatus(FrameHolder.CameraStatus.LOST)
        }
        Log.i(TAG, "Reconnecting in ${delayMs}ms...")
        try {
            Thread.sleep(delayMs)
        } catch (_: InterruptedException) {
            // stop() interrupted us
        }
    }

    private fun readMjpegStream(input: BufferedInputStream, boundary: String) {
        var inJpeg = false
        var contentLength = -1

        while (running) {
            if (inJpeg && contentLength > 0) {
                if (contentLength > MAX_JPEG_SIZE) {
                    Log.w(TAG, "JPEG frame too large: $contentLength bytes, skipping")
                    inJpeg = false
                    contentLength = -1
                    continue
                }
                // Read exact content-length bytes
                val jpegData = ByteArray(contentLength)
                var read = 0
                while (read < contentLength && running) {
                    val n = input.read(jpegData, read, contentLength - read)
                    if (n < 0) return
                    read += n
                }
                processJpegFrame(jpegData)
                inJpeg = false
                contentLength = -1
            } else {
                // Read header lines
                val line = readLine(input) ?: return
                when {
                    line.contains(boundary) -> {
                        // New frame boundary
                        contentLength = -1
                    }
                    line.startsWith("Content-Length:", ignoreCase = true) -> {
                        contentLength = line.substringAfter(":").trim().toIntOrNull() ?: -1
                    }
                    line.isEmpty() && contentLength > 0 -> {
                        // Empty line after headers = start of JPEG data
                        inJpeg = true
                    }
                }
            }
        }
    }

    private fun readLine(input: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (running) {
            val b = input.read()
            if (b < 0) return null
            if (b == '\n'.code) {
                val s = sb.toString()
                return if (s.endsWith('\r')) s.dropLast(1) else s
            }
            if (sb.length >= MAX_LINE_LENGTH) {
                Log.w(TAG, "MJPEG header line exceeded $MAX_LINE_LENGTH chars, skipping")
                // Drain until newline
                while (running) {
                    val c = input.read()
                    if (c < 0) return null
                    if (c == '\n'.code) break
                }
                return ""
            }
            sb.append(b.toChar())
        }
        return null
    }

    private fun processJpegFrame(jpegData: ByteArray) {
        // FPS throttle: skip frames that arrive faster than the configured interval
        val now = System.currentTimeMillis()
        if (now - lastFrameTimeMs < Config.inferenceIntervalMs()) return
        lastFrameTimeMs = now

        try {
            // Decode JPEG to Bitmap
            val bitmap = BitmapFactory.decodeByteArray(jpegData, 0, jpegData.size) ?: return
            val width = bitmap.width
            val height = bitmap.height
            val numPixels = width * height

            // Re-allocate buffers only if dimensions changed
            if (width != lastWidth || height != lastHeight) {
                pixelBuffer = IntArray(numPixels)
                bgrBuffer = ByteArray(numPixels * 3)
                lastWidth = width
                lastHeight = height
                Log.i(TAG, "MJPEG frame buffers allocated: ${width}x${height}")
            }

            val pixels = pixelBuffer!!
            val bgr = bgrBuffer!!

            // Extract ARGB pixels
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            bitmap.recycle()

            // Convert ARGB to BGR byte array
            for (i in 0 until numPixels) {
                val argb = pixels[i]
                val idx = i * 3
                bgr[idx] = (argb and 0xFF).toByte()           // B
                bgr[idx + 1] = ((argb shr 8) and 0xFF).toByte()  // G
                bgr[idx + 2] = ((argb shr 16) and 0xFF).toByte() // R
            }

            onBgrFrame(bgr, width, height)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to process MJPEG frame", e)
        }
    }
}
