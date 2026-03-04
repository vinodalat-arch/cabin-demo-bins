package com.incabin

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.*
import android.graphics.drawable.GradientDrawable
import androidx.core.content.ContextCompat

/**
 * Activity for registering face embeddings.
 * Premium two-panel landscape layout (XML) matching the main dashboard's dark luxury theme.
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

    // Palette colors (resolved for programmatic face list rows)
    private var colorTextPrimary = 0
    private var colorTextSecondary = 0
    private var colorTextMuted = 0
    private var colorAccent = 0
    private var colorSafe = 0
    private var colorCaution = 0
    private var colorDanger = 0

    private var faceStore: FaceStore? = null
    private var driverProfileStore: DriverProfileStore? = null
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

        // Resolve palette colors for programmatic use (face list rows, status color changes)
        colorTextPrimary = ContextCompat.getColor(this, R.color.text_primary)
        colorTextSecondary = ContextCompat.getColor(this, R.color.text_secondary)
        colorTextMuted = ContextCompat.getColor(this, R.color.text_muted)
        colorAccent = ContextCompat.getColor(this, R.color.accent)
        colorSafe = ContextCompat.getColor(this, R.color.safe)
        colorCaution = ContextCompat.getColor(this, R.color.caution)
        colorDanger = ContextCompat.getColor(this, R.color.danger)

        setContentView(R.layout.activity_face_registration)

        // Bind views
        previewImage = findViewById(R.id.previewImage)
        statusText = findViewById(R.id.statusText)
        cameraStatusText = findViewById(R.id.cameraStatusText)
        nameInput = findViewById(R.id.nameInput)
        captureButton = findViewById(R.id.captureButton)
        saveButton = findViewById(R.id.saveButton)
        faceListLayout = findViewById(R.id.faceListLayout)
        captureProgress = findViewById(R.id.captureProgress)

        // Button listeners
        captureButton.setOnClickListener { onCapture() }
        saveButton.setOnClickListener { onSave() }
        findViewById<Button>(R.id.backButton).setOnClickListener { finish() }

        // Disabled state: reduce alpha
        updateButtonAlpha(captureButton, captureButton.isEnabled)
        updateButtonAlpha(saveButton, saveButton.isEnabled)

        try {
            faceStore = FaceStore.getInstance(this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init FaceStore", e)
            statusText.text = "Error: Could not load face store"
            statusText.setTextColor(colorDanger)
        }

        try {
            driverProfileStore = DriverProfileStore.getInstance(this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init DriverProfileStore", e)
        }

        try {
            faceDetector = FaceDetectorLite(this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init FaceDetectorLite", e)
            statusText.text = "Error: Face detection unavailable"
            statusText.setTextColor(colorDanger)
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
                    val wasEnabled = captureButton.isEnabled
                    captureButton.isEnabled = detection != null
                    if (captureButton.isEnabled != wasEnabled) {
                        updateButtonAlpha(captureButton, captureButton.isEnabled)
                    }
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
    // UI helpers
    // ---------------------------------------------------------------------

    private fun updateCameraStatusUI(text: String) {
        handler.post {
            if (!isFinishing && !isDestroyed) {
                cameraStatusText.text = text
            }
        }
    }

    private fun updateButtonAlpha(button: Button, enabled: Boolean) {
        button.alpha = if (enabled) 1.0f else 0.4f
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
        updateButtonAlpha(captureButton, false)
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
                        val enabled = lastFaceDetection != null
                        captureButton.isEnabled = enabled
                        updateButtonAlpha(captureButton, enabled)
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
                updateButtonAlpha(saveButton, true)
                // Switch save button to accent style
                saveButton.setBackgroundResource(R.drawable.bg_button_accent)
                saveButton.setTextColor(0xFFFFFFFF.toInt())
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

        // Check for duplicate face
        val match = faceStore?.findBestMatch(embedding, Config.FACE_RECOGNITION_THRESHOLD)
        if (match != null && match.first != name) {
            showDuplicateFaceDialog(name, embedding, match.first, match.second)
            return
        }

        doSave(name, embedding)
    }

    private fun showDuplicateFaceDialog(newName: String, embedding: FloatArray, existingName: String, similarity: Float) {
        try {
            val pct = (similarity * 100).toInt()
            AlertDialog.Builder(this)
                .setTitle("Duplicate Face")
                .setMessage("This face looks like \"$existingName\" ($pct% match).")
                .setPositiveButton("Update \"$existingName\"") { _, _ ->
                    doSave(existingName, embedding)
                }
                .setNeutralButton("Save as \"$newName\"") { _, _ ->
                    doSave(newName, embedding)
                }
                .setNegativeButton("Cancel", null)
                .show()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to show duplicate dialog", e)
            doSave(newName, embedding)
        }
    }

    private fun doSave(name: String, embedding: FloatArray) {
        try {
            faceStore?.register(name, embedding)
            capturedEmbedding = null
            saveButton.isEnabled = false
            updateButtonAlpha(saveButton, false)
            saveButton.setBackgroundResource(R.drawable.bg_button)
            saveButton.setTextColor(colorTextSecondary)
            nameInput.text.clear()
            statusText.text = "Saved: $name"
            statusText.setTextColor(colorSafe)
            refreshFaceList()
            showPreferencesDialog(name, driverProfileStore?.get(name), newRegistration = true)
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
                val pad = resources.getDimensionPixelSize(R.dimen.space_sm)
                setPadding(0, pad, 0, pad)
            })
            return
        }

        val rowHeight = (48 * resources.displayMetrics.density).toInt()
        val hPad = resources.getDimensionPixelSize(R.dimen.space_sm)

        val dp = resources.displayMetrics.density
        val btnW = (56 * dp).toInt()
        val btnH = (32 * dp).toInt()
        val dotSize = (14 * dp).toInt()

        for (name in names) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = rowHeight
                setPadding(0, hPad, 0, hPad)
            }

            // Ambient color dot — shows driver's chosen color, or muted if no profile
            val profile = driverProfileStore?.get(name)
            row.addView(View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    if (profile != null) {
                        setColor(AmbientLightController.parseColorHex(profile.ambientColorHex))
                    } else {
                        setColor(colorTextMuted)
                    }
                }
            }, LinearLayout.LayoutParams(dotSize, dotSize).apply {
                marginEnd = (8 * dp).toInt()
            })

            // Name + temp subtitle
            val nameCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }
            nameCol.addView(TextView(this@FaceRegistrationActivity).apply {
                text = name
                textSize = 16f
                setTextColor(colorTextPrimary)
                setSingleLine(true)
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            if (profile != null) {
                nameCol.addView(TextView(this@FaceRegistrationActivity).apply {
                    text = "%.1f°C".format(profile.preferredTempC)
                    textSize = 11f
                    setTextColor(colorTextMuted)
                    setSingleLine(true)
                })
            }
            row.addView(nameCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = (12 * dp).toInt()
            })

            row.addView(Button(this).apply {
                text = "Edit"
                textSize = 11f
                setTextColor(colorAccent)
                isAllCaps = false
                stateListAnimator = null
                setBackgroundResource(R.drawable.bg_button)
                setPadding(0, 0, 0, 0)
                minWidth = 0
                minimumWidth = 0
                minHeight = 0
                minimumHeight = 0
                setOnClickListener { showEditDialog(name) }
            }, LinearLayout.LayoutParams(btnW, btnH).apply {
                marginEnd = (6 * dp).toInt()
            })

            row.addView(Button(this).apply {
                text = "Del"
                textSize = 11f
                setTextColor(colorDanger)
                isAllCaps = false
                stateListAnimator = null
                setBackgroundResource(R.drawable.bg_button)
                setPadding(0, 0, 0, 0)
                minWidth = 0
                minimumWidth = 0
                minHeight = 0
                minimumHeight = 0
                setOnClickListener { confirmDelete(name) }
            }, LinearLayout.LayoutParams(btnW, btnH))

            faceListLayout.addView(row)
        }
    }

    private fun showEditDialog(currentName: String) {
        try {
            val dp = resources.displayMetrics.density
            val pad = (16 * dp).toInt()

            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad, pad, pad)
            }

            val input = EditText(this).apply {
                setText(currentName)
                setSelectAllOnFocus(true)
                setTextColor(colorTextPrimary)
                setPadding(pad, pad / 2, pad, pad / 2)
            }
            container.addView(input)

            val prefsButton = Button(this).apply {
                text = "Edit Preferences..."
                textSize = 13f
                setTextColor(colorAccent)
                isAllCaps = false
                stateListAnimator = null
                setBackgroundResource(R.drawable.bg_button)
            }
            container.addView(prefsButton, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (8 * dp).toInt() })

            val dialog = AlertDialog.Builder(this)
                .setTitle("Edit Face")
                .setView(container)
                .setPositiveButton("Rename") { _, _ ->
                    val newName = input.text.toString().trim()
                    if (newName.isEmpty()) {
                        statusText.text = "Name cannot be empty"
                        statusText.setTextColor(colorCaution)
                        return@setPositiveButton
                    }
                    if (newName == currentName) return@setPositiveButton
                    val success = faceStore?.rename(currentName, newName) ?: false
                    if (success) {
                        // Rename profile too: delete old, save copy with new name
                        val existingProfile = driverProfileStore?.get(currentName)
                        if (existingProfile != null) {
                            driverProfileStore?.delete(currentName)
                            driverProfileStore?.save(existingProfile.copy(name = newName))
                        }
                        statusText.text = "Renamed: $currentName → $newName"
                        statusText.setTextColor(colorSafe)
                        refreshFaceList()
                    } else {
                        statusText.text = "Rename failed: face not found"
                        statusText.setTextColor(colorDanger)
                    }
                }
                .setNegativeButton("Cancel", null)
                .create()

            prefsButton.setOnClickListener {
                dialog.dismiss()
                showPreferencesDialog(currentName, driverProfileStore?.get(currentName), newRegistration = false)
            }

            dialog.show()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to show edit dialog", e)
        }
    }

    /**
     * Show a preferences dialog with color swatch picker and temperature slider.
     * [existingProfile] pre-fills values if editing; null uses defaults.
     * [newRegistration] controls the negative button label (Skip vs Cancel).
     */
    private fun showPreferencesDialog(name: String, existingProfile: DriverProfile?, newRegistration: Boolean) {
        try {
            val dp = resources.displayMetrics.density
            val pad = (16 * dp).toInt()

            val container = ScrollView(this)
            val layout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad, pad, pad)
            }
            container.addView(layout)

            // --- Color swatch section ---
            layout.addView(TextView(this).apply {
                text = "Ambient Light Color"
                textSize = 14f
                setTextColor(colorTextPrimary)
            })

            val colors = DriverProfileStore.PRESET_COLORS
            val swatchSize = (40 * dp).toInt()
            val swatchMargin = (6 * dp).toInt()
            val selectedColor = existingProfile?.ambientColorHex ?: colors[0]
            var currentSelectedIndex = colors.indexOf(selectedColor).coerceAtLeast(0)
            val swatchViews = mutableListOf<View>()

            val grid = GridLayout(this).apply {
                columnCount = 5
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.topMargin = (8 * dp).toInt()
                layoutParams = lp
            }

            for ((index, colorHex) in colors.withIndex()) {
                val swatch = View(this).apply {
                    val drawable = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(AmbientLightController.parseColorHex(colorHex))
                    }
                    background = drawable
                }

                val glp = GridLayout.LayoutParams().apply {
                    width = swatchSize
                    height = swatchSize
                    setMargins(swatchMargin, swatchMargin, swatchMargin, swatchMargin)
                }
                grid.addView(swatch, glp)
                swatchViews.add(swatch)

                swatch.setOnClickListener {
                    currentSelectedIndex = index
                    updateSwatchBorders(swatchViews, currentSelectedIndex)
                }
            }

            // Set initial selection border
            layout.addView(grid)
            updateSwatchBorders(swatchViews, currentSelectedIndex)

            // --- Temperature section ---
            layout.addView(TextView(this).apply {
                text = "Preferred Temperature"
                textSize = 14f
                setTextColor(colorTextPrimary)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.topMargin = (16 * dp).toInt()
                layoutParams = lp
            })

            val tempLabel = TextView(this).apply {
                textSize = 16f
                setTextColor(colorAccent)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.topMargin = (4 * dp).toInt()
                layoutParams = lp
            }

            // SeekBar: 0..24 maps to 16.0-28.0 in 0.5 steps
            val initialTemp = existingProfile?.preferredTempC ?: 22.0f
            val seekBar = SeekBar(this).apply {
                max = 24
                progress = ((initialTemp - 16.0f) / 0.5f).toInt().coerceIn(0, 24)
            }
            tempLabel.text = "%.1f °C".format(initialTemp)

            val minMaxRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                layoutParams = lp
            }
            minMaxRow.addView(TextView(this).apply {
                text = "16°C"
                textSize = 11f
                setTextColor(colorTextMuted)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            minMaxRow.addView(TextView(this).apply {
                text = "28°C"
                textSize = 11f
                setTextColor(colorTextMuted)
                gravity = Gravity.END
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            layout.addView(tempLabel)
            layout.addView(seekBar, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (4 * dp).toInt() })
            layout.addView(minMaxRow)

            seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    val temp = 16.0f + progress * 0.5f
                    tempLabel.text = "%.1f °C".format(temp)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })

            AlertDialog.Builder(this)
                .setTitle("Preferences — $name")
                .setView(container)
                .setPositiveButton("Save") { _, _ ->
                    val tempValue = 16.0f + seekBar.progress * 0.5f
                    val colorValue = colors[currentSelectedIndex]
                    driverProfileStore?.save(DriverProfile(name, tempValue, colorValue))
                    statusText.text = "Preferences saved for $name"
                    statusText.setTextColor(colorSafe)
                }
                .setNegativeButton(if (newRegistration) "Skip" else "Cancel", null)
                .show()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to show preferences dialog", e)
        }
    }

    private fun updateSwatchBorders(swatchViews: List<View>, selectedIndex: Int) {
        val dp = resources.displayMetrics.density
        for ((i, swatch) in swatchViews.withIndex()) {
            val drawable = swatch.background as? GradientDrawable ?: continue
            if (i == selectedIndex) {
                drawable.setStroke((3 * dp).toInt(), colorAccent)
            } else {
                drawable.setStroke((1 * dp).toInt(), colorTextMuted)
            }
        }
    }

    private fun confirmDelete(name: String) {
        try {
            AlertDialog.Builder(this)
                .setTitle("Delete Face")
                .setMessage("Remove \"$name\" from registered faces?")
                .setPositiveButton("Delete") { _, _ ->
                    faceStore?.delete(name)
                    driverProfileStore?.delete(name)
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
}
