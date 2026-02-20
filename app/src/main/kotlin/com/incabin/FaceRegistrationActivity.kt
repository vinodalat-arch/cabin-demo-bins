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
 * The monitoring service must be running for face capture to work,
 * as it provides BGR face crops via FrameHolder.
 */
class FaceRegistrationActivity : Activity() {

    companion object {
        private const val TAG = "FaceRegistration"
        private const val PREVIEW_POLL_MS = 500L
    }

    private lateinit var previewImage: ImageView
    private lateinit var statusText: TextView
    private lateinit var nameInput: EditText
    private lateinit var captureButton: Button
    private lateinit var saveButton: Button
    private lateinit var faceListLayout: LinearLayout

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

    private fun onCapture() {
        val captureData = FrameHolder.getCaptureData()
        if (captureData == null) {
            statusText.text = "No face detected. Start monitoring first."
            statusText.setTextColor(colorDanger)
            return
        }

        statusText.text = "Computing embedding..."
        statusText.setTextColor(colorAccent)
        captureButton.isEnabled = false

        Thread({
            var embedding: FloatArray? = null
            var recognizer: FaceRecognizerBridge? = null
            try {
                recognizer = FaceRecognizerBridge(assets)
                embedding = recognizer.computeEmbedding(
                    captureData.bgrCrop, captureData.cropWidth, captureData.cropHeight
                )
            } catch (e: Exception) {
                Log.e(TAG, "Embedding computation failed", e)
            } finally {
                recognizer?.close()
            }

            handler.post {
                captureButton.isEnabled = true
                if (embedding != null) {
                    capturedEmbedding = embedding
                    saveButton.isEnabled = true
                    statusText.text = "Face captured! Enter a name and tap Save."
                    statusText.setTextColor(colorSafe)
                } else {
                    statusText.text = "Capture failed. Try again."
                    statusText.setTextColor(colorDanger)
                }
            }
        }, "FaceCapture").start()
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
