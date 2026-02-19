package com.incabin

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Custom View that draws a color-coded arc representing the driving safety score (0-100).
 * Green >= 75, Orange 40-74, Red < 40.
 */
class ScoreArcView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var score: Int = 100
        set(value) {
            field = value.coerceIn(0, 100)
            invalidate()
        }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.rgb(0x33, 0x33, 0x33)
    }

    private val fgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val scorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = Color.WHITE
        isFakeBoldText = true
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = Color.rgb(0x99, 0x99, 0x99)
    }

    private val arcRect = RectF()

    companion object {
        private const val ARC_START = 150f
        private const val ARC_SWEEP = 240f
        private val COLOR_GREEN = Color.rgb(0x4C, 0xAF, 0x50)
        private val COLOR_ORANGE = Color.rgb(0xFF, 0x98, 0x00)
        private val COLOR_RED = Color.rgb(0xF4, 0x43, 0x36)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val strokeW = height * 0.08f
        bgPaint.strokeWidth = strokeW
        fgPaint.strokeWidth = strokeW

        val pad = strokeW + 2f
        arcRect.set(pad, pad, width - pad, height - pad)

        // Background arc
        canvas.drawArc(arcRect, ARC_START, ARC_SWEEP, false, bgPaint)

        // Foreground arc (colored by score)
        fgPaint.color = when {
            score >= 75 -> COLOR_GREEN
            score >= 40 -> COLOR_ORANGE
            else -> COLOR_RED
        }
        val sweep = ARC_SWEEP * score / 100f
        if (sweep > 0f) {
            canvas.drawArc(arcRect, ARC_START, sweep, false, fgPaint)
        }

        // Score number
        scorePaint.textSize = height * 0.32f
        val textY = height / 2f + scorePaint.textSize * 0.3f
        canvas.drawText(score.toString(), width / 2f, textY, scorePaint)

        // "SCORE" label
        labelPaint.textSize = height * 0.14f
        canvas.drawText("SCORE", width / 2f, height * 0.8f, labelPaint)
    }
}
