package com.incabin

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat

/**
 * Custom View: top-down car diagram with per-seat state visualization.
 * Front row: two bucket seats. Rear row: continuous bench (2 or 3 zones).
 * Steering wheel on driver side. Seats glow green (safe) or pulse red (danger).
 */
class CarSeatMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val PULSE_DURATION_MS = 800L
        private const val COLOR_ANIM_DURATION_MS = 500L

        /** Classify seat state into a color category. */
        fun seatColor(state: String): String = when (state) {
            "Upright" -> "safe"
            "Vacant" -> "vacant"
            else -> "danger"  // Sleeping, Phone, Distracted, Eating, Yawning
        }

        /** Short icon label for each seat state. */
        fun stateIcon(state: String): String = when (state) {
            "Upright" -> "OK"
            "Sleeping" -> "Zzz"
            "Phone" -> "TEL"
            "Distracted" -> "!!"
            "Eating" -> "EAT"
            "Yawning" -> "~"
            "Vacant" -> "--"
            else -> "?"
        }

        /** Whether a state is a danger state (red pulse). */
        fun isDanger(state: String): Boolean = when (state) {
            "Sleeping", "Phone", "Distracted", "Eating", "Yawning" -> true
            else -> false
        }

        /** Number of rear bench zones: 3 if rear center is occupied, else 2. */
        fun benchZoneCount(seatMap: SeatMap): Int =
            if (seatMap.rearCenter.occupied) 3 else 2
    }

    private var seatMap: SeatMap = SeatMap()
    private var driverSide: String = "left"
    private var driverName: String? = null
    private var dangerPulseAlpha: Float = 1.0f

    // Resolved colors
    private val colorSafe = ContextCompat.getColor(context, R.color.safe)
    private val colorDanger = ContextCompat.getColor(context, R.color.danger)
    private val colorVacant = Color.rgb(0x1E, 0x1F, 0x2A)  // divider
    private val colorBody = Color.rgb(0x3D, 0x3F, 0x4A)     // text_muted
    private val colorWindow = Color.argb(51, 0x5B, 0x8D, 0xEF)  // accent at 20%
    private val colorSteering = Color.rgb(0x6B, 0x6E, 0x7B)  // text_secondary
    private val colorTextPrimary = ContextCompat.getColor(context, R.color.text_primary)
    private val colorTextMuted = Color.rgb(0x3D, 0x3F, 0x4A)
    private val colorDividerLine = Color.rgb(0x2A, 0x2B, 0x35)

    // Pre-allocated Paints
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2.5f; color = colorBody
    }
    private val windowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = colorWindow
    }
    private val seatFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val seatStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f; color = colorBody
    }
    private val seatGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val steeringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f; color = colorSteering; strokeCap = Paint.Cap.ROUND
    }
    private val steeringCenterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = colorSteering
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; color = colorTextPrimary; isFakeBoldText = true
    }
    private val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; color = colorTextPrimary
    }
    private val orientPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; color = colorTextMuted; isFakeBoldText = true
    }
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f; color = colorDividerLine
    }

    // Reusable objects
    private val bodyRect = RectF()
    private val seatRect = RectF()
    private val benchRect = RectF()
    private val steeringRect = RectF()
    private val windshieldPath = Path()
    private val rearWindowPath = Path()

    // Animation state
    private var pulseAnimator: ValueAnimator? = null
    private val seatColorAnimators = mutableMapOf<Seat, ValueAnimator>()
    private val currentColors = IntArray(5) { colorVacant }
    private var hasDanger = false

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    /** Update seat map data and trigger redraw with animations. */
    fun setSeatMap(map: SeatMap, side: String, name: String?) {
        val oldMap = seatMap
        seatMap = map
        driverSide = side
        driverName = name

        // Animate color transitions per seat
        animateSeatColor(Seat.DRIVER, oldMap.driver, map.driver)
        animateSeatColor(Seat.FRONT_PASSENGER, oldMap.frontPassenger, map.frontPassenger)
        animateSeatColor(Seat.REAR_LEFT, oldMap.rearLeft, map.rearLeft)
        animateSeatColor(Seat.REAR_CENTER, oldMap.rearCenter, map.rearCenter)
        animateSeatColor(Seat.REAR_RIGHT, oldMap.rearRight, map.rearRight)

        // Start/stop pulse animator based on danger presence
        val anyDanger = isDanger(map.driver.state) || isDanger(map.frontPassenger.state) ||
            isDanger(map.rearLeft.state) || isDanger(map.rearCenter.state) ||
            isDanger(map.rearRight.state)

        if (anyDanger && !hasDanger) {
            startPulse()
        } else if (!anyDanger && hasDanger) {
            stopPulse()
        }
        hasDanger = anyDanger

        invalidate()
    }

    private fun resolveColor(state: SeatState): Int = when (seatColor(state.state)) {
        "safe" -> colorSafe
        "danger" -> colorDanger
        else -> colorVacant
    }

    private fun animateSeatColor(seat: Seat, old: SeatState, new: SeatState) {
        val oldColor = currentColors[seat.ordinal]
        val newColor = resolveColor(new)
        if (oldColor == newColor) return

        seatColorAnimators[seat]?.cancel()
        val anim = ValueAnimator.ofObject(ArgbEvaluator(), oldColor, newColor).apply {
            duration = COLOR_ANIM_DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                currentColors[seat.ordinal] = it.animatedValue as Int
                invalidate()
            }
            start()
        }
        seatColorAnimators[seat] = anim
    }

    private fun startPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = ValueAnimator.ofFloat(0.4f, 1.0f).apply {
            duration = PULSE_DURATION_MS
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener {
                dangerPulseAlpha = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        dangerPulseAlpha = 1.0f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val density = resources.displayMetrics.density

        // Car body proportions
        val bodyW = w * 0.78f
        val bodyH = h * 0.72f
        val bodyL = (w - bodyW) / 2f
        val bodyT = h * 0.10f
        val bodyR = bodyL + bodyW
        val bodyB = bodyT + bodyH
        val bodyCorner = 12f * density

        bodyRect.set(bodyL, bodyT, bodyR, bodyB)
        canvas.drawRoundRect(bodyRect, bodyCorner, bodyCorner, bodyPaint)

        // "FRONT" label
        orientPaint.textSize = 10f * density
        canvas.drawText("FRONT", w / 2f, bodyT - 3f * density, orientPaint)

        // Windshield (trapezoid at top)
        val wsInset = bodyW * 0.12f
        val wsTop = bodyT + 3f * density
        val wsBot = bodyT + bodyH * 0.15f
        windshieldPath.reset()
        windshieldPath.moveTo(bodyL + wsInset, wsTop)
        windshieldPath.lineTo(bodyR - wsInset, wsTop)
        windshieldPath.lineTo(bodyR - wsInset * 0.4f, wsBot)
        windshieldPath.lineTo(bodyL + wsInset * 0.4f, wsBot)
        windshieldPath.close()
        canvas.drawPath(windshieldPath, windowPaint)

        // Rear window (trapezoid at bottom)
        val rwTop = bodyB - bodyH * 0.12f
        val rwBot = bodyB - 3f * density
        rearWindowPath.reset()
        rearWindowPath.moveTo(bodyL + wsInset * 0.4f, rwTop)
        rearWindowPath.lineTo(bodyR - wsInset * 0.4f, rwTop)
        rearWindowPath.lineTo(bodyR - wsInset, rwBot)
        rearWindowPath.lineTo(bodyL + wsInset, rwBot)
        rearWindowPath.close()
        canvas.drawPath(rearWindowPath, windowPaint)

        // Interior area
        val intL = bodyL + bodyW * 0.08f
        val intR = bodyR - bodyW * 0.08f
        val intW = intR - intL
        val gap = intW * 0.06f  // gap between front seats

        // --- Front row: two bucket seats ---
        val seatW = (intW - gap) / 2f
        val seatH = bodyH * 0.28f
        val seatTop = bodyT + bodyH * 0.20f
        val seatCorner = 8f * density

        // Determine which seat is on which column
        val leftSeatState: SeatState
        val rightSeatState: SeatState
        val leftSeatEnum: Seat
        val rightSeatEnum: Seat
        if (driverSide == "left") {
            leftSeatState = seatMap.driver; leftSeatEnum = Seat.DRIVER
            rightSeatState = seatMap.frontPassenger; rightSeatEnum = Seat.FRONT_PASSENGER
        } else {
            leftSeatState = seatMap.frontPassenger; leftSeatEnum = Seat.FRONT_PASSENGER
            rightSeatState = seatMap.driver; rightSeatEnum = Seat.DRIVER
        }

        // Left front seat
        drawSeat(canvas, intL, seatTop, seatW, seatH, seatCorner,
            leftSeatEnum, leftSeatState, density)

        // Right front seat
        drawSeat(canvas, intL + seatW + gap, seatTop, seatW, seatH, seatCorner,
            rightSeatEnum, rightSeatState, density)

        // Steering wheel on driver seat
        val driverSeatL = if (driverSide == "left") intL else intL + seatW + gap
        val swCenterX = driverSeatL + seatW / 2f
        val swCenterY = seatTop + seatH * 0.32f
        val swRadius = seatW * 0.22f
        drawSteeringWheel(canvas, swCenterX, swCenterY, swRadius)

        // --- Rear row: continuous bench seat ---
        val benchTop = seatTop + seatH + bodyH * 0.08f
        val benchH = bodyH * 0.24f
        val benchCorner = 8f * density
        val zones = benchZoneCount(seatMap)

        benchRect.set(intL, benchTop, intR, benchTop + benchH)
        // Draw full bench outline
        canvas.drawRoundRect(benchRect, benchCorner, benchCorner, seatStrokePaint)

        if (zones == 3) {
            // 3-zone bench: RL | RC | RR
            val zoneW = intW / 3f
            drawBenchZone(canvas, intL, benchTop, zoneW, benchH, benchCorner,
                Seat.REAR_LEFT, seatMap.rearLeft, density, isFirst = true, isLast = false)
            drawBenchZone(canvas, intL + zoneW, benchTop, zoneW, benchH, benchCorner,
                Seat.REAR_CENTER, seatMap.rearCenter, density, isFirst = false, isLast = false)
            drawBenchZone(canvas, intL + 2 * zoneW, benchTop, zoneW, benchH, benchCorner,
                Seat.REAR_RIGHT, seatMap.rearRight, density, isFirst = false, isLast = true)
            // Divider lines
            canvas.drawLine(intL + zoneW, benchTop + 4f * density,
                intL + zoneW, benchTop + benchH - 4f * density, dividerPaint)
            canvas.drawLine(intL + 2 * zoneW, benchTop + 4f * density,
                intL + 2 * zoneW, benchTop + benchH - 4f * density, dividerPaint)
        } else {
            // 2-zone bench: RL | RR
            val zoneW = intW / 2f
            drawBenchZone(canvas, intL, benchTop, zoneW, benchH, benchCorner,
                Seat.REAR_LEFT, seatMap.rearLeft, density, isFirst = true, isLast = false)
            drawBenchZone(canvas, intL + zoneW, benchTop, zoneW, benchH, benchCorner,
                Seat.REAR_RIGHT, seatMap.rearRight, density, isFirst = false, isLast = true)
            // Center divider line
            canvas.drawLine(intL + zoneW, benchTop + 4f * density,
                intL + zoneW, benchTop + benchH - 4f * density, dividerPaint)
        }

        // Driver name below car body
        if (driverName != null) {
            namePaint.textSize = 12f * density
            canvas.drawText(driverName!!, w / 2f, bodyB + 16f * density, namePaint)
        }
    }

    private fun drawSeat(
        canvas: Canvas, left: Float, top: Float, width: Float, height: Float,
        corner: Float, seat: Seat, state: SeatState, density: Float
    ) {
        val color = currentColors[seat.ordinal]
        val alpha = if (isDanger(state.state)) dangerPulseAlpha else 1.0f

        seatRect.set(left, top, left + width, top + height)

        // Glow halo for occupied seats
        if (state.occupied) {
            seatGlowPaint.color = color
            seatGlowPaint.alpha = (40 * alpha).toInt()
            seatGlowPaint.maskFilter = BlurMaskFilter(8f * density, BlurMaskFilter.Blur.NORMAL)
            canvas.drawRoundRect(seatRect, corner, corner, seatGlowPaint)
        }

        // Seat fill
        seatFillPaint.color = color
        seatFillPaint.alpha = (255 * alpha).toInt()
        canvas.drawRoundRect(seatRect, corner, corner, seatFillPaint)

        // Seat outline
        canvas.drawRoundRect(seatRect, corner, corner, seatStrokePaint)

        // State icon
        labelPaint.textSize = 13f * density
        val icon = stateIcon(state.state)
        val textX = left + width / 2f
        val textY = top + height / 2f + labelPaint.textSize * 0.35f
        canvas.drawText(icon, textX, textY, labelPaint)
    }

    private fun drawBenchZone(
        canvas: Canvas, left: Float, top: Float, width: Float, height: Float,
        corner: Float, seat: Seat, state: SeatState, density: Float,
        isFirst: Boolean, isLast: Boolean
    ) {
        val color = currentColors[seat.ordinal]
        val alpha = if (isDanger(state.state)) dangerPulseAlpha else 1.0f

        canvas.save()
        canvas.clipRect(left, top, left + width, top + height)

        // For rounded corners on outer edges, draw a full bench-sized rounded rect
        // but clipped to this zone's region
        val fullL = if (isFirst) left else left - corner
        val fullR = if (isLast) left + width else left + width + corner
        seatRect.set(fullL, top, fullR, top + height)

        // Glow
        if (state.occupied) {
            seatGlowPaint.color = color
            seatGlowPaint.alpha = (40 * alpha).toInt()
            seatGlowPaint.maskFilter = BlurMaskFilter(8f * density, BlurMaskFilter.Blur.NORMAL)
            canvas.drawRoundRect(seatRect, corner, corner, seatGlowPaint)
        }

        // Fill
        seatFillPaint.color = color
        seatFillPaint.alpha = (255 * alpha).toInt()
        canvas.drawRoundRect(seatRect, corner, corner, seatFillPaint)

        canvas.restore()

        // State icon
        labelPaint.textSize = 12f * density
        val icon = stateIcon(state.state)
        val textX = left + width / 2f
        val textY = top + height / 2f + labelPaint.textSize * 0.35f
        canvas.drawText(icon, textX, textY, labelPaint)
    }

    private fun drawSteeringWheel(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        // Wheel rim (270-degree arc, opening at bottom)
        steeringRect.set(cx - radius, cy - radius, cx + radius, cy + radius)
        canvas.drawArc(steeringRect, 200f, 280f, false, steeringPaint)

        // Center hub
        canvas.drawCircle(cx, cy, radius * 0.18f, steeringCenterPaint)

        // Spoke (vertical line from center to rim top area)
        canvas.drawLine(cx, cy, cx, cy - radius * 0.7f, steeringPaint)
    }

    override fun onDetachedFromWindow() {
        pulseAnimator?.cancel()
        seatColorAnimators.values.forEach { it.cancel() }
        seatColorAnimators.clear()
        super.onDetachedFromWindow()
    }
}
