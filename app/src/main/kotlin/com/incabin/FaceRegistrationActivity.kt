package com.incabin

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.widget.*

/**
 * Activity for registering face embeddings.
 * Uses programmatic UI (no layout XML).
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

    private var faceStore: FaceStore? = null
    private var capturedEmbedding: FloatArray? = null

    // Pre-allocated pixel buffer for BGR→Bitmap conversion (face crop is small)
    private var pixelBuffer: IntArray? = null

    private val handler = Handler(Looper.getMainLooper())
    private val previewPoller = object : Runnable {
        override fun run() {
            updatePreview()
            handler.postDelayed(this, PREVIEW_POLL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
            setPadding(24, 24, 24, 24)
            setBackgroundColor(Color.BLACK)
        }

        // Title
        root.addView(TextView(this).apply {
            text = "Face Registration"
            textSize = 24f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 16)
        })

        // Preview
        previewImage = ImageView(this).apply {
            setBackgroundColor(Color.rgb(0x11, 0x11, 0x11))
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Face preview"
        }
        root.addView(previewImage, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 300
        ).apply { bottomMargin = 12 })

        // Status
        statusText = TextView(this).apply {
            text = "Start monitoring, then capture a face"
            textSize = 14f
            setTextColor(Color.rgb(0xAA, 0xAA, 0xAA))
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 8)
        }
        root.addView(statusText)

        // Name input
        nameInput = EditText(this).apply {
            hint = "Enter name"
            textSize = 16f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(0x66, 0x66, 0x66))
            setBackgroundColor(Color.rgb(0x1A, 0x1A, 0x1A))
            setPadding(16, 12, 16, 12)
        }
        root.addView(nameInput, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 8; bottomMargin = 8 })

        // Button row
        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        captureButton = Button(this).apply {
            text = "Capture"
            setOnClickListener { onCapture() }
        }
        buttonRow.addView(captureButton, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        ).apply { marginEnd = 8 })

        saveButton = Button(this).apply {
            text = "Save"
            isEnabled = false
            setOnClickListener { onSave() }
        }
        buttonRow.addView(saveButton, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        ).apply { marginStart = 8 })

        root.addView(buttonRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 8; bottomMargin = 16 })

        // Registered faces header
        root.addView(TextView(this).apply {
            text = "Registered Faces"
            textSize = 18f
            setTextColor(Color.WHITE)
            setPadding(0, 8, 0, 8)
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
        root.addView(Button(this).apply {
            text = "Back"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 8 })

        setContentView(root)
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
            statusText.setTextColor(Color.rgb(0xFF, 0x52, 0x52))
            return
        }

        statusText.text = "Computing embedding..."
        statusText.setTextColor(Color.rgb(0x64, 0xB5, 0xF6))
        captureButton.isEnabled = false

        // Compute embedding on background thread
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
                    statusText.setTextColor(Color.rgb(0x4C, 0xAF, 0x50))
                } else {
                    statusText.text = "Capture failed. Try again."
                    statusText.setTextColor(Color.rgb(0xFF, 0x52, 0x52))
                }
            }
        }, "FaceCapture").start()
    }

    private fun onSave() {
        val name = nameInput.text.toString().trim()
        if (name.isEmpty()) {
            statusText.text = "Please enter a name"
            statusText.setTextColor(Color.rgb(0xFF, 0x98, 0x00))
            return
        }

        val embedding = capturedEmbedding
        if (embedding == null) {
            statusText.text = "No face captured. Tap Capture first."
            statusText.setTextColor(Color.rgb(0xFF, 0x52, 0x52))
            return
        }

        try {
            faceStore?.register(name, embedding)
            capturedEmbedding = null
            saveButton.isEnabled = false
            nameInput.text.clear()
            statusText.text = "Saved: $name"
            statusText.setTextColor(Color.rgb(0x4C, 0xAF, 0x50))
            refreshFaceList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save face", e)
            statusText.text = "Save failed"
            statusText.setTextColor(Color.rgb(0xFF, 0x52, 0x52))
        }
    }

    private fun refreshFaceList() {
        faceListLayout.removeAllViews()
        val names = faceStore?.getNames() ?: return

        if (names.isEmpty()) {
            faceListLayout.addView(TextView(this).apply {
                text = "No faces registered"
                textSize = 14f
                setTextColor(Color.rgb(0x88, 0x88, 0x88))
                setPadding(0, 8, 0, 8)
            })
            return
        }

        for (name in names) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 4, 0, 4)
            }

            row.addView(TextView(this).apply {
                text = name
                textSize = 16f
                setTextColor(Color.WHITE)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            row.addView(Button(this).apply {
                text = "Delete"
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
                    statusText.setTextColor(Color.rgb(0xAA, 0xAA, 0xAA))
                    refreshFaceList()
                }
                .setNegativeButton("Cancel", null)
                .show()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to show delete dialog", e)
        }
    }
}
