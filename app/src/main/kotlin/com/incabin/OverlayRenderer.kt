package com.incabin

import android.graphics.*

/**
 * Draws detection overlays onto a camera frame bitmap:
 * bounding boxes, COCO skeleton, face landmarks, and metric labels.
 */
class OverlayRenderer {

    // Pre-allocated Paints to avoid GC per frame
    private val driverBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GREEN; style = Paint.Style.STROKE; strokeWidth = 3f
    }
    private val otherBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0x42, 0xA5, 0xF5); style = Paint.Style.STROKE; strokeWidth = 3f
    }
    private val bonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.YELLOW; style = Paint.Style.STROKE; strokeWidth = 2f
    }
    private val keypointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED; style = Paint.Style.FILL
    }
    private val labelBgPaint = Paint().apply {
        color = Color.argb(180, 0, 0, 0)
    }
    private val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 28f; typeface = Typeface.MONOSPACE
    }
    private val eyeOpenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GREEN; style = Paint.Style.STROKE; strokeWidth = 2f
    }
    private val eyeClosedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED; style = Paint.Style.STROKE; strokeWidth = 2f
    }
    private val mouthNormalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GREEN; style = Paint.Style.STROKE; strokeWidth = 2f
    }
    private val mouthYawnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0xFF, 0xA5, 0x00); style = Paint.Style.STROKE; strokeWidth = 2f
    }
    private val nosePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.CYAN; style = Paint.Style.FILL
    }
    private val metricTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 32f; typeface = Typeface.MONOSPACE; isFakeBoldText = true
    }
    private val metricBgPaint = Paint().apply {
        color = Color.argb(180, 0, 0, 0)
    }

    // COCO skeleton connections (17 keypoints, 16 bones)
    private val skeletonBones = arrayOf(
        intArrayOf(0, 1), intArrayOf(0, 2),     // nose -> eyes
        intArrayOf(1, 3), intArrayOf(2, 4),     // eyes -> ears
        intArrayOf(5, 6),                        // shoulder-shoulder
        intArrayOf(5, 7), intArrayOf(7, 9),     // left arm
        intArrayOf(6, 8), intArrayOf(8, 10),    // right arm
        intArrayOf(5, 11), intArrayOf(6, 12),   // shoulders -> hips
        intArrayOf(11, 12),                      // hip-hip
        intArrayOf(11, 13), intArrayOf(13, 15), // left leg
        intArrayOf(12, 14), intArrayOf(14, 16)  // right leg
    )

    /**
     * Render detection overlays onto the source bitmap.
     * Returns a new annotated bitmap (source is not modified).
     */
    /**
     * Render detection overlays directly onto the source bitmap (in-place).
     * Avoids 3.6 MB bitmap copy per frame. Source must be mutable.
     */
    /** Current driver name for overlay label (set before render). */
    var driverName: String? = null

    fun render(
        source: Bitmap,
        poseResult: PoseResult,
        faceResult: FaceResult,
        outputResult: OutputResult
    ): Bitmap {
        driverName = outputResult.driverName
        try {
            val canvas = Canvas(source)
            drawPersons(canvas, poseResult.persons)
            drawFaceLandmarks(canvas, faceResult)
            drawMetricLabels(canvas, outputResult)
        } catch (e: Exception) {
            // Return partially drawn bitmap on error
        }
        return source
    }

    private fun drawPersons(canvas: Canvas, persons: List<OverlayPerson>) {
        for (person in persons) {
            val boxPaint = if (person.isDriver) driverBoxPaint else otherBoxPaint

            // Bounding box
            canvas.drawRect(person.x1, person.y1, person.x2, person.y2, boxPaint)

            // Label
            val label = if (person.isDriver) (driverName ?: "Driver") else "Passenger"
            val conf = (person.confidence * 100).toInt()
            val text = "$label ${conf}%"
            val textWidth = labelTextPaint.measureText(text)
            canvas.drawRect(person.x1, person.y1 - 34f, person.x1 + textWidth + 8f, person.y1, labelBgPaint)
            canvas.drawText(text, person.x1 + 4f, person.y1 - 8f, labelTextPaint)

            // Skeleton bones
            val kps = person.keypoints
            if (kps.size >= 17) {
                for (bone in skeletonBones) {
                    val a = kps[bone[0]]
                    val b = kps[bone[1]]
                    if (a.conf > 0.5f && b.conf > 0.5f) {
                        canvas.drawLine(a.x, a.y, b.x, b.y, bonePaint)
                    }
                }
            }

            // Keypoint dots
            for (kp in kps) {
                if (kp.conf > 0.5f) {
                    canvas.drawCircle(kp.x, kp.y, 4f, keypointPaint)
                }
            }
        }
    }

    private fun drawFaceLandmarks(canvas: Canvas, faceResult: FaceResult) {
        val overlay = faceResult.faceOverlay ?: return

        // Eye contours
        val eyePaint = if (faceResult.driverEyesClosed) eyeClosedPaint else eyeOpenPaint
        drawLandmarkContour(canvas, overlay.rightEye, eyePaint)
        drawLandmarkContour(canvas, overlay.leftEye, eyePaint)

        // Mouth outline
        val mPaint = if (faceResult.driverYawning) mouthYawnPaint else mouthNormalPaint
        drawLandmarkContour(canvas, overlay.mouth, mPaint)

        // Nose tip
        canvas.drawCircle(overlay.noseTip.x, overlay.noseTip.y, 5f, nosePaint)
    }

    private fun drawLandmarkContour(canvas: Canvas, landmarks: List<OverlayLandmark>, paint: Paint) {
        if (landmarks.size < 2) return
        for (i in 0 until landmarks.size - 1) {
            canvas.drawLine(landmarks[i].x, landmarks[i].y, landmarks[i + 1].x, landmarks[i + 1].y, paint)
        }
        // Close the contour
        canvas.drawLine(landmarks.last().x, landmarks.last().y, landmarks.first().x, landmarks.first().y, paint)
    }

    private fun drawMetricLabels(canvas: Canvas, result: OutputResult) {
        val lines = mutableListOf<String>()

        val earStr = result.earValue?.let { ((it * 1000).toInt() / 1000f).toString() } ?: "N/A"
        val marStr = result.marValue?.let { ((it * 1000).toInt() / 1000f).toString() } ?: "N/A"
        val yawStr = result.headYaw?.let { ((it * 10).toInt() / 10f).toString() } ?: "N/A"
        val pitchStr = result.headPitch?.let { ((it * 10).toInt() / 10f).toString() } ?: "N/A"

        lines.add("EAR: $earStr  MAR: $marStr")
        lines.add("Yaw: $yawStr  Pitch: $pitchStr")

        val lineHeight = 38f
        val padX = 12f
        val padY = 8f
        val startY = padY + lineHeight

        // Background
        var maxWidth = 0f
        for (line in lines) {
            maxWidth = maxOf(maxWidth, metricTextPaint.measureText(line))
        }
        val bgHeight = padY * 2 + lineHeight * lines.size
        canvas.drawRect(0f, 0f, maxWidth + padX * 2, bgHeight, metricBgPaint)

        // Text
        for ((i, line) in lines.withIndex()) {
            canvas.drawText(line, padX, startY + i * lineHeight, metricTextPaint)
        }
    }
}
