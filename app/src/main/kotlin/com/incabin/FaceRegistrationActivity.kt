package com.incabin

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.widget.*
import androidx.core.content.ContextCompat

/**
 * Activity for registering face embeddings.
 * Uses programmatic UI (no layout XML) with premium palette.
 *
 * Multi-shot capture: captures 3 frames and averages embeddings for robustness.
 * Quality gate: rejects captures where embeddings are too inconsistent (cosine < 0.7).
 *
 * The monitoring service must be running for face capture to work,
 * as it provides BGR face crops via FrameHolder.
 */
class FaceRegistrationActivity : Activity() {

    companion object {
        private const val TAG = "FaceRegistration"
        private const val PREVIEW_POLL_MS = 500L
        private const val MULTI_SHOT_COUNT = 3
        private const val MULTI_SHOT_DELAY_MS = 1500L
        private const val QUALITY_THRESHOLD = 0.7f  // min cosine between any pair of shots
    }

    private lateinit var previewImage: ImageView
    private lateinit var statusText: TextView
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

    private val handler = Handler(Looper.getMainLooper())
    private val previewPoller = object : Runnable {
        override fun run() {
            updatePreview()
            handler.postDelayed(this, PREVIEW_POLL_MS)
        }
    }

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

        refreshFaceList()
    }

    override fun onResume() {
        super.onResume()
        handler.post(previewPoller)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(previewPoller)
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
        ).apply { bottomMargin = dp(12) })

        // Capture progress bar (hidden until multi-shot capture)
        captureProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = MULTI_SHOT_COUNT
            progress = 0
            visibility = android.view.View.GONE
        }
        root.addView(captureProgress, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(4) })

        // Status
        statusText = TextView(this).apply {
            text = "Start monitoring, then capture a face"
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

    private fun updatePreview() {
        try {
            val bitmap = FrameHolder.getLatestFrame()
            if (bitmap != null && !bitmap.isRecycled) {
                previewImage.setImageBitmap(bitmap)
            }
        } catch (e: Exception) {
            // Ignore — preview is best-effort
        }
    }

    /**
     * Multi-shot capture: captures MULTI_SHOT_COUNT frames with delays between them,
     * computes embeddings for each, checks quality (pairwise cosine similarity),
     * and averages the embeddings.
     */
    private fun onCapture() {
        val captureData = FrameHolder.getCaptureData()
        if (captureData == null) {
            statusText.text = "No face detected. Start monitoring first."
            statusText.setTextColor(colorDanger)
            return
        }

        statusText.text = "Capturing 1/$MULTI_SHOT_COUNT..."
        statusText.setTextColor(colorAccent)
        captureButton.isEnabled = false
        captureProgress.visibility = android.view.View.VISIBLE
        captureProgress.progress = 0

        Thread({
            val embeddings = mutableListOf<FloatArray>()
            var recognizer: FaceRecognizerBridge? = null

            try {
                recognizer = FaceRecognizerBridge(assets)

                for (shot in 0 until MULTI_SHOT_COUNT) {
                    // Get latest face crop
                    val data = FrameHolder.getCaptureData()
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
                        captureButton.isEnabled = true
                        captureProgress.visibility = android.view.View.GONE
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
