package com.incabin

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.core.content.ContextCompat

/**
 * Activity for registering face embeddings.
 * Uses programmatic UI (no layout XML) with premium palette.
 *
 * Self-contained camera ownership: opens its own camera (V4L2, Camera2, or MJPEG)
 * and uses FaceDetectorLite for face detection. Does NOT depend on InCabinService
 * or FrameHolder.
 *
 * Multi-shot capture: captures 3 frames and averages embeddings for robustness.
 * Quality gate: rejects captures where embeddings are too inconsistent (cosine < 0.7).
 */
class FaceRegistrationActivity : Activity() {

    companion object {
        private const val TAG = "FaceRegistration"
        private const val MULTI_SHOT_COUNT = 3
        private const val MULTI_SHOT_DELAY_MS = 1500L
        private const val QUALITY_THRESHOLD = 0.7f
        private const val CAMERA_START_RETRIES = 5
        private const val CAMERA_RETRY_DELAY_MS = 1000L
    }

    private lateinit var previewImage: ImageView
    private lateinit var statusText: TextView
    private lateinit var cameraStatusText: TextView
    private lateinit var nameInput: EditText
    private lateinit var captureButton: Button
    private lateinit var saveButton: Button
    private lateinit var faceListLayout: LinearLayout
    private lateinit var captureProgress: ProgressBar

    // Palette
    private var colorBg = 0
    private var colorSurface = 0
    private var colorSurfaceElevated = 0
    private var colorTextPrimary = 0
    private var colorTextSecondary = 0
    private var colorTextMuted = 0
    private var colorAccent = 0
    private var colorSafe = 0
    private var colorCaution = 0
    private var colorDanger = 0

    private var faceStore: FaceStore? = null
    private var capturedEmbedding: FloatArray? = null

    // Camera (self-owned)
    private val nativeLib = NativeLib()
    private var v4l2Camera: V4l2CameraManager? = null
    private var camera2Manager: CameraManager? = null
    private var mjpegCamera: MjpegCameraManager? = null
    private var cameraStarted = false
    @Volatile private var cameraCancelled = false
    private var cameraStartThread: Thread? = null

    // Face detection
    private var faceDetector: FaceDetectorLite? = null
    private var lastFaceDetection: FaceDetectorLite.FaceDetection? = null

    // Latest frame data (written by camera thread, read by capture thread)
    @Volatile private var latestBgrData: ByteArray? = null
    @Volatile private var latestFrameWidth = 0
    @Volatile private var latestFrameHeight = 0

    // Pre-allocated pixel buffer for BGR→Bitmap conversion
    private var pixelBuffer: IntArray? = null
    private var lastPixelBufferSize = 0

    // Overlay paint for face bbox
    private val bboxPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.GREEN
    }

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Resolve palette
        colorBg = ContextCompat.getColor(this, R.color.background)
        colorSurface = ContextCompat.getColor(this, R.color.surface)
        colorSurfaceElevated = ContextCompat.getColor(this, R.color.surface_elevated)
        colorTextPrimary = ContextCompat.getColor(this, R.color.text_primary)
        colorTextSecondary = ContextCompat.getColor(this, R.color.text_secondary)
        colorTextMuted = ContextCompat.getColor(this, R.color.text_muted)
        colorAccent = ContextCompat.getColor(this, R.color.accent)
        colorSafe = ContextCompat.getColor(this, R.color.safe)
        colorCaution = ContextCompat.getColor(this, R.color.caution)
        colorDanger = ContextCompat.getColor(this, R.color.danger)

        buildUI()

        try {
            faceStore = FaceStore.getInstance(this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init FaceStore", e)
            statusText.text = "Error: Could not load face store"
        }

        try {
            faceDetector = FaceDetectorLite(this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init FaceDetectorLite", e)
            statusText.text = "Error: Face detection unavailable"
        }

        refreshFaceList()
    }

    override fun onResume() {
        super.onResume()
        startCamera()
    }

    override fun onPause() {
        super.onPause()
        stopCamera()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        faceDetector?.close()
        faceDetector = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------------
    // Camera lifecycle
    // ---------------------------------------------------------------------

    private fun startCamera() {
        if (cameraStarted) return

        cameraCancelled = false
        updateCameraStatusUI("Starting camera...")

        val thread = Thread({
            var started = false
            for (attempt in 1..CAMERA_START_RETRIES) {
                if (cameraCancelled || isFinishing || isDestroyed) return@Thread

                started = tryStartCamera()
                if (started) break

                Log.i(TAG, "Camera start attempt $attempt/$CAMERA_START_RETRIES failed, retrying in ${CAMERA_RETRY_DELAY_MS}ms")
                try {
                    Thread.sleep(CAMERA_RETRY_DELAY_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }

            if (cameraCancelled) return@Thread

            handler.post {
                if (started && !cameraCancelled) {
                    cameraStarted = true
                    updateCameraStatusUI("Camera active")
                    statusText.text = "Position face in frame"
                    statusText.setTextColor(colorTextSecondary)
                } else if (!cameraCancelled) {
                    updateCameraStatusUI("Camera unavailable")
                    statusText.text = "Could not open camera. Go back and try again."
                    statusText.setTextColor(colorDanger)
                }
            }
        }, "FaceReg-CameraStart")
        cameraStartThread = thread
        thread.start()
    }

    private fun tryStartCamera(): Boolean {
        val profile = PlatformProfile.detect()

        // WiFi camera takes priority if configured
        if (Config.WIFI_CAMERA_URL.isNotBlank()) {
            Log.i(TAG, "Trying MJPEG camera: ${Config.WIFI_CAMERA_URL}")
            val mjpeg = MjpegCameraManager(nativeLib, ::onBgrFrame)
            if (mjpeg.start(Config.WIFI_CAMERA_URL)) {
                mjpegCamera = mjpeg
                Log.i(TAG, "MJPEG camera started")
                return true
            }
        }

        when (profile.cameraStrategy) {
            PlatformProfile.CameraStrategy.V4L2_FIRST -> {
                Log.i(TAG, "Trying V4L2 camera...")
                val v4l2 = V4l2CameraManager(nativeLib, ::onBgrFrame)
                if (v4l2.start()) {
                    v4l2Camera = v4l2
                    Log.i(TAG, "V4L2 camera started")
                    return true
                }
                // Fallback to Camera2
                Log.i(TAG, "V4L2 failed, trying Camera2...")
                return tryStartCamera2()
            }
            PlatformProfile.CameraStrategy.CAMERA2_FIRST -> {
                return tryStartCamera2()
            }
        }
    }

    private fun tryStartCamera2(): Boolean {
        return try {
            val cam2 = CameraManager(this, ::onYuvFrame)
            cam2.start()
            camera2Manager = cam2
            Log.i(TAG, "Camera2 started")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Camera2 start failed", e)
            false
        }
    }

    private fun stopCamera() {
        cameraCancelled = true
        cameraStartThread?.interrupt()
        cameraStartThread?.join(2000)
        cameraStartThread = null
        cameraStarted = false
        v4l2Camera?.stop()
        v4l2Camera = null
        camera2Manager?.stop()
        camera2Manager = null
        mjpegCamera?.stop()
        mjpegCamera = null
        latestBgrData = null
        Log.i(TAG, "Camera stopped")
    }

    // ---------------------------------------------------------------------
    // Camera frame callbacks
    // ---------------------------------------------------------------------

    /** Called from V4L2 or MJPEG camera thread with BGR data. */
    private fun onBgrFrame(bgrData: ByteArray, width: Int, height: Int) {
        // Defensive copy: MjpegCameraManager reuses its internal buffer across frames,
        // so we must copy to avoid data races when multi-shot capture reads latestBgrData
        val copy = bgrData.copyOf()
        latestBgrData = copy
        latestFrameWidth = width
        latestFrameHeight = height
        processFrame(copy, width, height)
    }

    /** Called from Camera2 with YUV planes. Convert to BGR first. */
    private fun onYuvFrame(
        y: ByteArray, u: ByteArray, v: ByteArray,
        yRowStride: Int, uvRowStride: Int, uvPixelStride: Int,
        width: Int, height: Int
    ) {
        if (!NativeLib.loaded) return
        val bgrData = nativeLib.nativeYuvToBgr(y, u, v, yRowStride, uvRowStride, uvPixelStride, width, height)
            ?: return
        latestBgrData = bgrData
        latestFrameWidth = width
        latestFrameHeight = height
        processFrame(bgrData, width, height)
    }

    /** Process a BGR frame: convert to bitmap, detect face, update preview. */
    private fun processFrame(bgrData: ByteArray, width: Int, height: Int) {
        try {
            val numPixels = width * height

            // Ensure pixel buffer is allocated
            if (pixelBuffer == null || lastPixelBufferSize != numPixels) {
                pixelBuffer = IntArray(numPixels)
                lastPixelBufferSize = numPixels
            }

            val pixels = pixelBuffer ?: return

            // BGR→ARGB via native
            if (NativeLib.loaded) {
                nativeLib.nativeBgrToArgbPixels(bgrData, pixels, width, height)
            } else {
                // Fallback: Kotlin BGR→ARGB conversion
                for (i in 0 until numPixels) {
                    val idx = i * 3
                    val b = bgrData[idx].toInt() and 0xFF
                    val g = bgrData[idx + 1].toInt() and 0xFF
                    val r = bgrData[idx + 2].toInt() and 0xFF
                    pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)

            // Run face detection
            val detection = faceDetector?.detect(bitmap, width, height)
            lastFaceDetection = detection

            // Draw face bbox overlay
            if (detection != null) {
                val canvas = Canvas(bitmap)
                canvas.drawRect(
                    detection.bboxLeft.toFloat(),
                    detection.bboxTop.toFloat(),
                    detection.bboxRight.toFloat(),
                    detection.bboxBottom.toFloat(),
                    bboxPaint
                )
            }

            // Update UI on main thread
            handler.post {
                if (!isFinishing && !isDestroyed) {
                    previewImage.setImageBitmap(bitmap)
                    captureButton.isEnabled = detection != null
                    if (detection != null && statusText.text == "Position face in frame") {
                        statusText.text = "Face detected! Tap Capture."
                        statusText.setTextColor(colorSafe)
                    } else if (detection == null && capturedEmbedding == null) {
                        statusText.text = "Position face in frame"
                        statusText.setTextColor(colorTextSecondary)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Frame processing failed", e)
        }
    }

    // ---------------------------------------------------------------------
    // UI
    // ---------------------------------------------------------------------

    private fun updateCameraStatusUI(text: String) {
        handler.post {
            if (!isFinishing && !isDestroyed) {
                cameraStatusText.text = text
            }
        }
    }

    private fun buildUI() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
            setBackgroundColor(colorBg)
        }

        // Title
        root.addView(TextView(this).apply {
            text = "Face Registration"
            textSize = 20f
            setTextColor(colorTextPrimary)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(16))
        })

        // Preview
        previewImage = ImageView(this).apply {
            setBackgroundColor(colorSurface)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Face preview"
        }
        root.addView(previewImage, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(300)
        ).apply { bottomMargin = dp(4) })

        // Camera status
        cameraStatusText = TextView(this).apply {
            text = "Camera: initializing..."
            textSize = 12f
            setTextColor(colorTextMuted)
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(4))
        }
        root.addView(cameraStatusText)

        // Capture progress bar (hidden until multi-shot capture)
        captureProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = MULTI_SHOT_COUNT
            progress = 0
            visibility = View.GONE
        }
        root.addView(captureProgress, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(4) })

        // Status
        statusText = TextView(this).apply {
            text = "Position face in frame"
            textSize = 14f
            setTextColor(colorTextSecondary)
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
        }
        root.addView(statusText)

        // Name input
        nameInput = EditText(this).apply {
            hint = "Enter name"
            textSize = 16f
            setTextColor(colorTextPrimary)
            setHintTextColor(colorTextMuted)
            setBackgroundColor(colorSurfaceElevated)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        root.addView(nameInput, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8); bottomMargin = dp(8) })

        // Button row
        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        captureButton = makeButton("Capture").apply {
            isEnabled = false
            setOnClickListener { onCapture() }
        }
        buttonRow.addView(captureButton, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        ).apply { marginEnd = dp(8) })

        saveButton = makeButton("Save").apply {
            isEnabled = false
            setOnClickListener { onSave() }
        }
        buttonRow.addView(saveButton, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        ).apply { marginStart = dp(8) })

        root.addView(buttonRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8); bottomMargin = dp(16) })

        // Registered faces header
        root.addView(TextView(this).apply {
            text = "Registered Faces"
            textSize = 16f
            setTextColor(colorTextSecondary)
            setPadding(0, dp(8), 0, dp(8))
        })

        // Scrollable face list
        val scrollView = ScrollView(this)
        faceListLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scrollView.addView(faceListLayout)
        root.addView(scrollView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        // Back button
        root.addView(makeButton("Back").apply {
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })

        setContentView(root)
    }

    private fun makeButton(label: String): Button {
        return Button(this).apply {
            text = label
            textSize = 13f
            setTextColor(colorTextSecondary)
            isAllCaps = false
            stateListAnimator = null
            background = GradientDrawable().apply {
                setColor(colorSurfaceElevated)
                cornerRadius = dp(8).toFloat()
            }
            minimumHeight = dp(44)
        }
    }

    // ---------------------------------------------------------------------
    // Face crop extraction from BGR data
    // ---------------------------------------------------------------------

    /**
     * Extract a BGR face crop from the latest frame using the face detection bbox.
     *
     * @return FaceCropResult or null if no valid crop
     */
    private fun extractFaceCrop(): FrameHolder.CaptureData? {
        val bgr = latestBgrData ?: return null
        val width = latestFrameWidth
        val height = latestFrameHeight
        val detection = lastFaceDetection ?: return null

        val cx1 = detection.paddedLeft
        val cy1 = detection.paddedTop
        val cx2 = detection.paddedRight
        val cy2 = detection.paddedBottom

        val cropW = cx2 - cx1
        val cropH = cy2 - cy1
        if (cropW <= 0 || cropH <= 0) return null

        // Row-by-row BGR copy
        val cropData = ByteArray(cropW * cropH * 3)
        for (row in 0 until cropH) {
            val srcOffset = ((cy1 + row) * width + cx1) * 3
            val dstOffset = row * cropW * 3
            if (srcOffset + cropW * 3 > bgr.size) return null
            System.arraycopy(bgr, srcOffset, cropData, dstOffset, cropW * 3)
        }

        return FrameHolder.CaptureData(cropData, cropW, cropH)
    }

    // ---------------------------------------------------------------------
    // Multi-shot capture
    // ---------------------------------------------------------------------

    /**
     * Multi-shot capture: captures MULTI_SHOT_COUNT frames with delays between them,
     * computes embeddings for each, checks quality (pairwise cosine similarity),
     * and averages the embeddings.
     */
    private fun onCapture() {
        val crop = extractFaceCrop()
        if (crop == null) {
            statusText.text = "No face detected. Position face in frame."
            statusText.setTextColor(colorDanger)
            return
        }

        statusText.text = "Capturing 1/$MULTI_SHOT_COUNT..."
        statusText.setTextColor(colorAccent)
        captureButton.isEnabled = false
        captureProgress.visibility = View.VISIBLE
        captureProgress.progress = 0

        Thread({
            val embeddings = mutableListOf<FloatArray>()
            var recognizer: FaceRecognizerBridge? = null

            try {
                recognizer = FaceRecognizerBridge(assets)

                for (shot in 0 until MULTI_SHOT_COUNT) {
                    // Get latest face crop
                    val data = extractFaceCrop()
                    if (data == null) {
                        handler.post {
                            statusText.text = "Face lost during capture. Try again."
                            statusText.setTextColor(colorDanger)
                        }
                        return@Thread
                    }

                    val embedding = recognizer.computeEmbedding(
                        data.bgrCrop, data.cropWidth, data.cropHeight
                    )

                    if (embedding == null) {
                        handler.post {
                            statusText.text = "Embedding failed on shot ${shot + 1}. Try again."
                            statusText.setTextColor(colorDanger)
                        }
                        return@Thread
                    }

                    embeddings.add(embedding)

                    handler.post {
                        captureProgress.progress = shot + 1
                        statusText.text = "Captured ${shot + 1}/$MULTI_SHOT_COUNT"
                    }

                    // Wait between shots (except after last)
                    if (shot < MULTI_SHOT_COUNT - 1) {
                        Thread.sleep(MULTI_SHOT_DELAY_MS)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Multi-shot capture failed", e)
                handler.post {
                    statusText.text = "Capture failed. Try again."
                    statusText.setTextColor(colorDanger)
                }
                return@Thread
            } finally {
                recognizer?.close()
                if (!isFinishing && !isDestroyed) {
                    handler.post {
                        captureButton.isEnabled = lastFaceDetection != null
                        captureProgress.visibility = View.GONE
                    }
                }
            }

            // Quality gate: check pairwise cosine similarity
            if (!checkQuality(embeddings)) {
                handler.post {
                    statusText.text = "Quality check failed — face was inconsistent. Try again."
                    statusText.setTextColor(colorCaution)
                }
                return@Thread
            }

            // Average embeddings
            val averaged = averageEmbeddings(embeddings)

            handler.post {
                capturedEmbedding = averaged
                saveButton.isEnabled = true
                statusText.text = "Face captured ($MULTI_SHOT_COUNT shots)! Enter a name and tap Save."
                statusText.setTextColor(colorSafe)
            }
        }, "FaceCapture-MultiShot").start()
    }

    /** Check that all pairwise cosine similarities exceed the quality threshold. */
    private fun checkQuality(embeddings: List<FloatArray>): Boolean {
        for (i in embeddings.indices) {
            for (j in i + 1 until embeddings.size) {
                val sim = cosineSimilarity(embeddings[i], embeddings[j])
                Log.d(TAG, "Quality check: shot $i vs $j = %.3f".format(sim))
                if (sim < QUALITY_THRESHOLD) return false
            }
        }
        return true
    }

    /** Average multiple embedding vectors into one, then L2-normalize. */
    private fun averageEmbeddings(embeddings: List<FloatArray>): FloatArray {
        val dim = embeddings[0].size
        val avg = FloatArray(dim)
        for (emb in embeddings) {
            for (i in 0 until dim) {
                avg[i] += emb[i]
            }
        }
        // L2 normalize
        var norm = 0f
        for (v in avg) norm += v * v
        norm = Math.sqrt(norm.toDouble()).toFloat()
        if (norm > 1e-6f) {
            for (i in avg.indices) avg[i] /= norm
        }
        return avg
    }

    /** Cosine similarity between two vectors. */
    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = Math.sqrt(normA.toDouble()) * Math.sqrt(normB.toDouble())
        return if (denom > 1e-6) (dot / denom.toFloat()) else 0f
    }

    private fun onSave() {
        val name = nameInput.text.toString().trim()
        if (name.isEmpty()) {
            statusText.text = "Please enter a name"
            statusText.setTextColor(colorCaution)
            return
        }

        val embedding = capturedEmbedding
        if (embedding == null) {
            statusText.text = "No face captured. Tap Capture first."
            statusText.setTextColor(colorDanger)
            return
        }

        try {
            faceStore?.register(name, embedding)
            capturedEmbedding = null
            saveButton.isEnabled = false
            nameInput.text.clear()
            statusText.text = "Saved: $name"
            statusText.setTextColor(colorSafe)
            refreshFaceList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save face", e)
            statusText.text = "Save failed"
            statusText.setTextColor(colorDanger)
        }
    }

    private fun refreshFaceList() {
        faceListLayout.removeAllViews()
        val names = faceStore?.getNames() ?: return

        if (names.isEmpty()) {
            faceListLayout.addView(TextView(this).apply {
                text = "No faces registered"
                textSize = 14f
                setTextColor(colorTextMuted)
                setPadding(0, dp(8), 0, dp(8))
            })
            return
        }

        for (name in names) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, dp(4))
            }

            row.addView(TextView(this).apply {
                text = name
                textSize = 16f
                setTextColor(colorTextPrimary)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            row.addView(makeButton("Delete").apply {
                textSize = 12f
                setOnClickListener { confirmDelete(name) }
            })

            faceListLayout.addView(row)
        }
    }

    private fun confirmDelete(name: String) {
        try {
            AlertDialog.Builder(this)
                .setTitle("Delete Face")
                .setMessage("Remove \"$name\" from registered faces?")
                .setPositiveButton("Delete") { _, _ ->
                    faceStore?.delete(name)
                    statusText.text = "Deleted: $name"
                    statusText.setTextColor(colorTextSecondary)
                    refreshFaceList()
                }
                .setNegativeButton("Cancel", null)
                .show()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to show delete dialog", e)
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
