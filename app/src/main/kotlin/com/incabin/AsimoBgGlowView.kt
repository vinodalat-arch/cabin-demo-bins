package com.incabin

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat

class AsimoBgGlowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var currentColor: Int = ContextCompat.getColor(context, R.color.safe)
    private var glowAlpha: Float = 0.25f

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var colorAnimator: ValueAnimator? = null
    private var pulseAnimator: ValueAnimator? = null

    companion object {
        private const val COLOR_ANIM_DURATION = 500L
        private const val PULSE_DURATION = 800L
        private const val PULSE_ALPHA_MIN = 0.15f
        private const val PULSE_ALPHA_MAX = 0.45f
        private const val STEADY_ALPHA = 0.25f
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun animateColorTo(color: Int) {
        if (color == currentColor) return
        colorAnimator?.cancel()
        colorAnimator = ValueAnimator.ofObject(ArgbEvaluator(), currentColor, color).apply {
            duration = COLOR_ANIM_DURATION
            addUpdateListener {
                currentColor = it.animatedValue as Int
                invalidate()
            }
            start()
        }
    }

    fun setGlowColor(color: Int) {
        currentColor = color
        invalidate()
    }

    fun startPulse() {
        if (pulseAnimator?.isRunning == true) return
        pulseAnimator = ValueAnimator.ofFloat(PULSE_ALPHA_MIN, PULSE_ALPHA_MAX).apply {
            duration = PULSE_DURATION
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                glowAlpha = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun stopPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        glowAlpha = STEADY_ALPHA
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f
        val radius = minOf(cx, cy)

        if (radius <= 0f) return

        val gradient = RadialGradient(
            cx, cy, radius,
            applyAlpha(currentColor, glowAlpha),
            applyAlpha(currentColor, 0f),
            Shader.TileMode.CLAMP
        )
        glowPaint.shader = gradient
        canvas.drawCircle(cx, cy, radius, glowPaint)
    }

    override fun onDetachedFromWindow() {
        colorAnimator?.cancel()
        pulseAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    private fun applyAlpha(color: Int, alpha: Float): Int {
        val a = (alpha * 255).toInt().coerceIn(0, 255)
        return (color and 0x00FFFFFF) or (a shl 24)
    }
}
