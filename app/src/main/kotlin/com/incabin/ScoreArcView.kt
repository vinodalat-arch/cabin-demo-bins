package com.incabin

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat

/**
 * Custom View that draws a color-coded arc representing the driving safety score (0-100).
 * Animated transitions between scores, glow on foreground arc.
 */
class ScoreArcView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var displayScore: Float = 100f
    private var animator: ValueAnimator? = null

    var score: Int = 100
        set(value) {
            val clamped = value.coerceIn(0, 100)
            if (clamped == field) return
            field = clamped
            animateTo(clamped.toFloat())
        }

    private val colorSafe = ContextCompat.getColor(context, R.color.safe)
    private val colorCaution = ContextCompat.getColor(context, R.color.caution)
    private val colorDanger = ContextCompat.getColor(context, R.color.danger)
    private val colorBg = Color.rgb(0x1E, 0x1F, 0x2A)
    private val colorTextPrimary = ContextCompat.getColor(context, R.color.text_primary)

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = colorBg
    }

    private val fgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val scorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = colorTextPrimary
        isFakeBoldText = true
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = Color.rgb(0x6B, 0x6E, 0x7B)
        letterSpacing = 0.15f
    }

    private val arcRect = RectF()
    private val glowRect = RectF()

    companion object {
        private const val ARC_START = 150f
        private const val ARC_SWEEP = 240f
        private const val ANIM_DURATION = 400L
    }

    init {
        // Enable software rendering for BlurMaskFilter
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    private fun animateTo(target: Float) {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(displayScore, target).apply {
            duration = ANIM_DURATION
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                displayScore = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun scoreColor(s: Float): Int = when (AsimoHub.scoreColorCategory(s)) {
        AsimoHub.ScoreColor.SAFE -> colorSafe
        AsimoHub.ScoreColor.CAUTION -> colorCaution
        AsimoHub.ScoreColor.DANGER -> colorDanger
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val strokeW = height * 0.12f
        bgPaint.strokeWidth = strokeW
        fgPaint.strokeWidth = strokeW
        glowPaint.strokeWidth = strokeW * 1.8f

        val pad = strokeW * 1.5f
        arcRect.set(pad, pad, width - pad, height - pad)
        glowRect.set(pad, pad, width - pad, height - pad)

        // Background arc
        canvas.drawArc(arcRect, ARC_START, ARC_SWEEP, false, bgPaint)

        // Foreground arc with glow
        val color = scoreColor(displayScore)
        val sweep = ARC_SWEEP * displayScore / 100f
        if (sweep > 0f) {
            // Glow layer
            glowPaint.color = color
            glowPaint.alpha = 60
            glowPaint.maskFilter = BlurMaskFilter(strokeW * 0.8f, BlurMaskFilter.Blur.NORMAL)
            canvas.drawArc(glowRect, ARC_START, sweep, false, glowPaint)

            // Foreground arc
            fgPaint.color = color
            canvas.drawArc(arcRect, ARC_START, sweep, false, fgPaint)
        }

        // Score number
        scorePaint.textSize = height * 0.28f
        val textY = height / 2f + scorePaint.textSize * 0.2f
        canvas.drawText(displayScore.toInt().toString(), width / 2f, textY, scorePaint)

        // "SAFETY" label below number
        labelPaint.textSize = height * 0.10f
        canvas.drawText("SAFETY", width / 2f, textY + scorePaint.textSize * 0.7f, labelPaint)
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }
}
