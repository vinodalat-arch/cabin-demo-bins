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
 * Custom View: top-down sedan diagram with per-seat state visualization.
 * Elongated sedan silhouette with tapered hood/trunk, wheel arches, side windows.
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
    private val colorVacant = Color.rgb(0x1E, 0x1F, 0x2A)
    private val colorBody = Color.rgb(0x2A, 0x2C, 0x38)
    private val colorBodyStroke = Color.rgb(0x3D, 0x3F, 0x4A)
    private val colorWindow = Color.argb(40, 0x5B, 0x8D, 0xEF)
    private val colorWindowStroke = Color.argb(60, 0x5B, 0x8D, 0xEF)
    private val colorSteering = Color.rgb(0x6B, 0x6E, 0x7B)
    private val colorTextPrimary = ContextCompat.getColor(context, R.color.text_primary)
    private val colorTextMuted = Color.rgb(0x3D, 0x3F, 0x4A)
    private val colorDividerLine = Color.rgb(0x2A, 0x2B, 0x35)
    private val colorWheel = Color.rgb(0x30, 0x32, 0x3E)
    private val colorWheelStroke = Color.rgb(0x4A, 0x4C, 0x58)

    // Pre-allocated Paints
    private val bodyFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = colorBody
    }
    private val bodyStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f; color = colorBodyStroke
    }
    private val windowFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = colorWindow
    }
    private val windowStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.2f; color = colorWindowStroke
    }
    private val seatFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val seatStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1f; color = colorBodyStroke
    }
    private val seatGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val steeringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2.5f; color = colorSteering; strokeCap = Paint.Cap.ROUND
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
        style = Paint.Style.STROKE; strokeWidth = 1f; color = colorDividerLine
    }
    private val wheelFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = colorWheel
    }
    private val wheelStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f; color = colorWheelStroke
    }

    // Reusable objects
    private val bodyPath = Path()
    private val seatRect = RectF()
    private val steeringRect = RectF()
    private val wheelRect = RectF()
    private val windshieldPath = Path()
    private val rearWindowPath = Path()
    private val sideWindowLeftPath = Path()
    private val sideWindowRightPath = Path()

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

        animateSeatColor(Seat.DRIVER, oldMap.driver, map.driver)
        animateSeatColor(Seat.FRONT_PASSENGER, oldMap.frontPassenger, map.frontPassenger)
        animateSeatColor(Seat.REAR_LEFT, oldMap.rearLeft, map.rearLeft)
        animateSeatColor(Seat.REAR_CENTER, oldMap.rearCenter, map.rearCenter)
        animateSeatColor(Seat.REAR_RIGHT, oldMap.rearRight, map.rearRight)

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

        val dp = resources.displayMetrics.density

        // --- Sedan proportions: narrow and long ---
        // Car is centered, ~48% of view width, ~88% of view height
        val carW = w * 0.48f
        val carH = h * 0.88f
        val carL = (w - carW) / 2f
        val carT = h * 0.06f
        val carR = carL + carW
        val carB = carT + carH
        val carCX = (carL + carR) / 2f

        // Section heights (top to bottom): hood, cabin, trunk
        val hoodH = carH * 0.18f      // hood/front
        val cabinH = carH * 0.52f     // cabin (windows + seats)
        val trunkH = carH * 0.30f     // trunk/rear

        val hoodTop = carT
        val cabinTop = carT + hoodH
        val trunkTop = cabinTop + cabinH

        // Body corner radius
        val cr = carW * 0.18f

        // --- Draw car body (sedan silhouette) ---
        // Tapered front (narrower hood), full-width cabin, slightly tapered trunk
        val hoodNarrow = carW * 0.08f  // how much narrower the hood is on each side
        val trunkNarrow = carW * 0.04f

        bodyPath.reset()
        // Start at top-left of hood (tapered)
        bodyPath.moveTo(carL + hoodNarrow + cr * 0.5f, hoodTop)
        // Top edge of hood
        bodyPath.lineTo(carR - hoodNarrow - cr * 0.5f, hoodTop)
        // Top-right corner of hood
        bodyPath.quadTo(carR - hoodNarrow, hoodTop, carR - hoodNarrow, hoodTop + cr * 0.5f)
        // Right side: hood widens to cabin
        bodyPath.lineTo(carR - hoodNarrow, cabinTop - cr * 0.3f)
        bodyPath.quadTo(carR - hoodNarrow, cabinTop, carR, cabinTop + cr * 0.3f)
        // Right side: cabin
        bodyPath.lineTo(carR, trunkTop - cr * 0.3f)
        // Right side: cabin narrows to trunk
        bodyPath.quadTo(carR, trunkTop, carR - trunkNarrow, trunkTop + cr * 0.3f)
        // Right side: trunk
        bodyPath.lineTo(carR - trunkNarrow, carB - cr * 0.5f)
        // Bottom-right corner
        bodyPath.quadTo(carR - trunkNarrow, carB, carR - trunkNarrow - cr * 0.5f, carB)
        // Bottom edge
        bodyPath.lineTo(carL + trunkNarrow + cr * 0.5f, carB)
        // Bottom-left corner
        bodyPath.quadTo(carL + trunkNarrow, carB, carL + trunkNarrow, carB - cr * 0.5f)
        // Left side: trunk
        bodyPath.lineTo(carL + trunkNarrow, trunkTop + cr * 0.3f)
        // Left side: trunk widens to cabin
        bodyPath.quadTo(carL + trunkNarrow, trunkTop, carL, trunkTop - cr * 0.3f)
        // Left side: cabin
        bodyPath.lineTo(carL, cabinTop + cr * 0.3f)
        // Left side: cabin narrows to hood
        bodyPath.quadTo(carL, cabinTop, carL + hoodNarrow, cabinTop - cr * 0.3f)
        // Left side: hood
        bodyPath.lineTo(carL + hoodNarrow, hoodTop + cr * 0.5f)
        // Top-left corner of hood
        bodyPath.quadTo(carL + hoodNarrow, hoodTop, carL + hoodNarrow + cr * 0.5f, hoodTop)
        bodyPath.close()

        canvas.drawPath(bodyPath, bodyFillPaint)
        canvas.drawPath(bodyPath, bodyStrokePaint)

        // --- "FRONT" label ---
        orientPaint.textSize = 9f * dp
        canvas.drawText("FRONT", carCX, hoodTop - 2f * dp, orientPaint)

        // --- Windshield (trapezoid) ---
        val wsInsetTop = carW * 0.14f
        val wsInsetBot = carW * 0.06f
        val wsTop = cabinTop + cabinH * 0.02f
        val wsBot = cabinTop + cabinH * 0.18f
        windshieldPath.reset()
        windshieldPath.moveTo(carL + wsInsetTop, wsTop)
        windshieldPath.lineTo(carR - wsInsetTop, wsTop)
        windshieldPath.lineTo(carR - wsInsetBot, wsBot)
        windshieldPath.lineTo(carL + wsInsetBot, wsBot)
        windshieldPath.close()
        canvas.drawPath(windshieldPath, windowFillPaint)
        canvas.drawPath(windshieldPath, windowStrokePaint)

        // --- Rear window (trapezoid) ---
        val rwInsetTop = carW * 0.06f
        val rwInsetBot = carW * 0.12f
        val rwTop = trunkTop - cabinH * 0.01f
        val rwBot = trunkTop + trunkH * 0.25f
        rearWindowPath.reset()
        rearWindowPath.moveTo(carL + rwInsetTop, rwTop)
        rearWindowPath.lineTo(carR - rwInsetTop, rwTop)
        rearWindowPath.lineTo(carR - trunkNarrow - rwInsetBot, rwBot)
        rearWindowPath.lineTo(carL + trunkNarrow + rwInsetBot, rwBot)
        rearWindowPath.close()
        canvas.drawPath(rearWindowPath, windowFillPaint)
        canvas.drawPath(rearWindowPath, windowStrokePaint)

        // --- Side windows (left and right) ---
        val swTop = wsBot + cabinH * 0.02f
        val swBot = rwTop - cabinH * 0.02f
        val swThick = carW * 0.06f  // window strip thickness

        // Left side window
        sideWindowLeftPath.reset()
        sideWindowLeftPath.moveTo(carL + wsInsetBot, swTop)
        sideWindowLeftPath.lineTo(carL + wsInsetBot - swThick * 0.5f, swTop)
        sideWindowLeftPath.lineTo(carL + rwInsetTop - swThick * 0.5f, swBot)
        sideWindowLeftPath.lineTo(carL + rwInsetTop, swBot)
        sideWindowLeftPath.close()
        canvas.drawPath(sideWindowLeftPath, windowFillPaint)

        // Right side window
        sideWindowRightPath.reset()
        sideWindowRightPath.moveTo(carR - wsInsetBot, swTop)
        sideWindowRightPath.lineTo(carR - wsInsetBot + swThick * 0.5f, swTop)
        sideWindowRightPath.lineTo(carR - rwInsetTop + swThick * 0.5f, swBot)
        sideWindowRightPath.lineTo(carR - rwInsetTop, swBot)
        sideWindowRightPath.close()
        canvas.drawPath(sideWindowRightPath, windowFillPaint)

        // --- Wheels (4 rounded rects at corners) ---
        val wheelW = carW * 0.10f
        val wheelH = carH * 0.08f
        val wheelR = 3f * dp  // corner radius
        val wheelOffsetX = 2f * dp  // how far wheel sticks out from body

        // Front-left wheel
        drawWheel(canvas, carL - wheelOffsetX, cabinTop + cabinH * 0.05f, wheelW, wheelH, wheelR)
        // Front-right wheel
        drawWheel(canvas, carR - wheelW + wheelOffsetX, cabinTop + cabinH * 0.05f, wheelW, wheelH, wheelR)
        // Rear-left wheel
        drawWheel(canvas, carL + trunkNarrow - wheelOffsetX, trunkTop - wheelH * 0.3f, wheelW, wheelH, wheelR)
        // Rear-right wheel
        drawWheel(canvas, carR - trunkNarrow - wheelW + wheelOffsetX, trunkTop - wheelH * 0.3f, wheelW, wheelH, wheelR)

        // --- Interior seat area ---
        val intPad = carW * 0.14f
        val intL = carL + intPad
        val intR = carR - intPad
        val intW = intR - intL
        val gap = intW * 0.08f

        // --- Front row: two bucket seats ---
        val seatW = (intW - gap) / 2f
        val seatH = cabinH * 0.32f
        val seatTop = wsBot + cabinH * 0.06f
        val seatCorner = 6f * dp

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

        drawSeat(canvas, intL, seatTop, seatW, seatH, seatCorner,
            leftSeatEnum, leftSeatState, dp)
        drawSeat(canvas, intL + seatW + gap, seatTop, seatW, seatH, seatCorner,
            rightSeatEnum, rightSeatState, dp)

        // --- Steering wheel ---
        val driverSeatL = if (driverSide == "left") intL else intL + seatW + gap
        val swCX = driverSeatL + seatW / 2f
        val swCY = seatTop + seatH * 0.30f
        val swR = seatW * 0.20f
        drawSteeringWheel(canvas, swCX, swCY, swR)

        // --- Rear row: continuous bench seat ---
        val benchGap = cabinH * 0.08f
        val benchTop = seatTop + seatH + benchGap
        val benchH = cabinH * 0.26f
        val benchCorner = 6f * dp
        val zones = benchZoneCount(seatMap)

        // Bench outline
        seatRect.set(intL, benchTop, intR, benchTop + benchH)
        canvas.drawRoundRect(seatRect, benchCorner, benchCorner, seatStrokePaint)

        if (zones == 3) {
            val zoneW = intW / 3f
            drawBenchZone(canvas, intL, benchTop, zoneW, benchH, benchCorner,
                Seat.REAR_LEFT, seatMap.rearLeft, dp, isFirst = true, isLast = false)
            drawBenchZone(canvas, intL + zoneW, benchTop, zoneW, benchH, benchCorner,
                Seat.REAR_CENTER, seatMap.rearCenter, dp, isFirst = false, isLast = false)
            drawBenchZone(canvas, intL + 2 * zoneW, benchTop, zoneW, benchH, benchCorner,
                Seat.REAR_RIGHT, seatMap.rearRight, dp, isFirst = false, isLast = true)
            canvas.drawLine(intL + zoneW, benchTop + 3f * dp,
                intL + zoneW, benchTop + benchH - 3f * dp, dividerPaint)
            canvas.drawLine(intL + 2 * zoneW, benchTop + 3f * dp,
                intL + 2 * zoneW, benchTop + benchH - 3f * dp, dividerPaint)
        } else {
            val zoneW = intW / 2f
            drawBenchZone(canvas, intL, benchTop, zoneW, benchH, benchCorner,
                Seat.REAR_LEFT, seatMap.rearLeft, dp, isFirst = true, isLast = false)
            drawBenchZone(canvas, intL + zoneW, benchTop, zoneW, benchH, benchCorner,
                Seat.REAR_RIGHT, seatMap.rearRight, dp, isFirst = false, isLast = true)
            canvas.drawLine(intL + zoneW, benchTop + 3f * dp,
                intL + zoneW, benchTop + benchH - 3f * dp, dividerPaint)
        }

        // --- Driver name below car ---
        if (driverName != null) {
            namePaint.textSize = 11f * dp
            canvas.drawText(driverName!!, carCX, carB + 12f * dp, namePaint)
        }
    }

    private fun drawWheel(canvas: Canvas, left: Float, top: Float, w: Float, h: Float, r: Float) {
        wheelRect.set(left, top, left + w, top + h)
        canvas.drawRoundRect(wheelRect, r, r, wheelFillPaint)
        canvas.drawRoundRect(wheelRect, r, r, wheelStrokePaint)
    }

    private fun drawSeat(
        canvas: Canvas, left: Float, top: Float, width: Float, height: Float,
        corner: Float, seat: Seat, state: SeatState, dp: Float
    ) {
        val color = currentColors[seat.ordinal]
        val alpha = if (isDanger(state.state)) dangerPulseAlpha else 1.0f

        seatRect.set(left, top, left + width, top + height)

        if (state.occupied) {
            seatGlowPaint.color = color
            seatGlowPaint.alpha = (40 * alpha).toInt()
            seatGlowPaint.maskFilter = BlurMaskFilter(6f * dp, BlurMaskFilter.Blur.NORMAL)
            canvas.drawRoundRect(seatRect, corner, corner, seatGlowPaint)
        }

        seatFillPaint.color = color
        seatFillPaint.alpha = (255 * alpha).toInt()
        canvas.drawRoundRect(seatRect, corner, corner, seatFillPaint)
        canvas.drawRoundRect(seatRect, corner, corner, seatStrokePaint)

        labelPaint.textSize = 11f * dp
        val icon = stateIcon(state.state)
        val textX = left + width / 2f
        val textY = top + height / 2f + labelPaint.textSize * 0.35f
        canvas.drawText(icon, textX, textY, labelPaint)
    }

    private fun drawBenchZone(
        canvas: Canvas, left: Float, top: Float, width: Float, height: Float,
        corner: Float, seat: Seat, state: SeatState, dp: Float,
        isFirst: Boolean, isLast: Boolean
    ) {
        val color = currentColors[seat.ordinal]
        val alpha = if (isDanger(state.state)) dangerPulseAlpha else 1.0f

        canvas.save()
        canvas.clipRect(left, top, left + width, top + height)

        val fullL = if (isFirst) left else left - corner
        val fullR = if (isLast) left + width else left + width + corner
        seatRect.set(fullL, top, fullR, top + height)

        if (state.occupied) {
            seatGlowPaint.color = color
            seatGlowPaint.alpha = (40 * alpha).toInt()
            seatGlowPaint.maskFilter = BlurMaskFilter(6f * dp, BlurMaskFilter.Blur.NORMAL)
            canvas.drawRoundRect(seatRect, corner, corner, seatGlowPaint)
        }

        seatFillPaint.color = color
        seatFillPaint.alpha = (255 * alpha).toInt()
        canvas.drawRoundRect(seatRect, corner, corner, seatFillPaint)

        canvas.restore()

        labelPaint.textSize = 10f * dp
        val icon = stateIcon(state.state)
        val textX = left + width / 2f
        val textY = top + height / 2f + labelPaint.textSize * 0.35f
        canvas.drawText(icon, textX, textY, labelPaint)
    }

    private fun drawSteeringWheel(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        steeringRect.set(cx - radius, cy - radius, cx + radius, cy + radius)
        canvas.drawArc(steeringRect, 200f, 280f, false, steeringPaint)
        canvas.drawCircle(cx, cy, radius * 0.18f, steeringCenterPaint)
        canvas.drawLine(cx, cy, cx, cy - radius * 0.7f, steeringPaint)
    }

    override fun onDetachedFromWindow() {
        pulseAnimator?.cancel()
        seatColorAnimators.values.forEach { it.cancel() }
        seatColorAnimators.clear()
        super.onDetachedFromWindow()
    }
}
