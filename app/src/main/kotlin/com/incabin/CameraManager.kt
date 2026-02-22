package com.incabin

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.nio.ByteBuffer

class CameraManager(
    private val context: Context,
    private val onFrame: (ByteArray, ByteArray, ByteArray, Int, Int, Int, Int, Int) -> Unit
) {
    companion object {
        private const val TAG = "InCabin-Camera"
    }

    private val systemCameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null

    private var lastFrameTimeMs = 0L
    private var firstFrameLogged = false

    fun start() {
        cameraThread = HandlerThread("CameraThread").also { it.start() }
        cameraHandler = Handler(cameraThread!!.looper)

        val cameraId = findCamera()
        if (cameraId == null) {
            Log.e(TAG, "No camera found")
            return
        }

        Log.i(TAG, "Opening camera: $cameraId")
        openCamera(cameraId)
    }

    fun stop() {
        try {
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            imageReader?.close()
            imageReader = null
            cameraThread?.quitSafely()
            cameraThread = null
            cameraHandler = null
            Log.i(TAG, "Camera stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping camera", e)
        }
    }

    private fun findCamera(): String? {
        val cameraIds = try {
            systemCameraManager.cameraIdList
        } catch (e: Exception) {
            Log.w(TAG, "cameraIdList failed: ${e.message}")
            emptyArray()
        }
        Log.i(TAG, "Camera IDs from API: ${cameraIds.joinToString()}")

        // Prefer external (USB) camera
        for (id in cameraIds) {
            val characteristics = systemCameraManager.getCameraCharacteristics(id)
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            if (facing == CameraCharacteristics.LENS_FACING_EXTERNAL) {
                Log.i(TAG, "Found external camera: $id")
                return id
            }
        }

        // Fallback: any available camera (for dev/emulator)
        if (cameraIds.isNotEmpty()) {
            Log.i(TAG, "No external camera found, using fallback: ${cameraIds[0]}")
            return cameraIds[0]
        }

        // AAOS workaround: config.disable_cameraservice=true hides cameras from
        // Camera2 API, but the HAL may still have cameras. Try known IDs.
        val probeIds = listOf("0", "10", "1")
        for (id in probeIds) {
            try {
                systemCameraManager.getCameraCharacteristics(id)
                Log.i(TAG, "Probed camera found: $id (hidden from cameraIdList)")
                return id
            } catch (_: Exception) { }
        }

        return null
    }

    @Suppress("MissingPermission")
    private fun openCamera(cameraId: String) {
        imageReader = ImageReader.newInstance(
            Config.CAMERA_WIDTH,
            Config.CAMERA_HEIGHT,
            ImageFormat.YUV_420_888,
            2
        ).apply {
            setOnImageAvailableListener(imageListener, cameraHandler)
        }

        try {
            systemCameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    Log.i(TAG, "Camera opened")
                    cameraDevice = camera
                    createCaptureSession(camera)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    Log.w(TAG, "Camera disconnected")
                    camera.close()
                    cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Camera error: $error")
                    camera.close()
                    cameraDevice = null
                }
            }, cameraHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to open camera: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error opening camera", e)
        }
    }

    private fun createCaptureSession(camera: CameraDevice) {
        val surface = imageReader?.surface ?: return

        try {
            camera.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        try {
                            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                addTarget(surface)
                            }.build()
                            session.setRepeatingRequest(request, null, cameraHandler)
                            Log.i(TAG, "Capture session started")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to start capture request", e)
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Capture session configuration failed")
                    }
                },
                cameraHandler
            )
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to create capture session: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error creating capture session", e)
        }
    }

    private val imageListener = ImageReader.OnImageAvailableListener { reader ->
        val image = reader.acquireLatestImage() ?: return@OnImageAvailableListener

        try {
            val now = System.currentTimeMillis()
            if (now - lastFrameTimeMs < Config.INFERENCE_INTERVAL_MS) {
                return@OnImageAvailableListener
            }
            lastFrameTimeMs = now

            if (!firstFrameLogged) {
                firstFrameLogged = true
                Log.i(TAG, "First frame: ${image.width}x${image.height}, format=${image.format}, planes=${image.planes.size}")
            }

            val planes = image.planes
            val yPlane = planes[0]
            val uPlane = planes[1]
            val vPlane = planes[2]

            val yBuffer = yPlane.buffer
            val uBuffer = uPlane.buffer
            val vBuffer = vPlane.buffer

            val yBytes = ByteArray(yBuffer.remaining()).also { yBuffer.get(it) }
            val uBytes = ByteArray(uBuffer.remaining()).also { uBuffer.get(it) }
            val vBytes = ByteArray(vBuffer.remaining()).also { vBuffer.get(it) }

            val yRowStride = yPlane.rowStride
            val uvRowStride = uPlane.rowStride
            val uvPixelStride = uPlane.pixelStride

            onFrame(
                yBytes, uBytes, vBytes,
                yRowStride, uvRowStride, uvPixelStride,
                image.width, image.height
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error processing frame", e)
        } finally {
            image.close()
        }
    }
}
