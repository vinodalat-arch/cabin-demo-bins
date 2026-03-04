package com.incabin

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat

/**
 * Custom View: premium top-down sedan diagram with per-seat state visualization.
 * Features: metallic gradient body, glass-effect windows, LED headlights/taillights,
 * ground shadow, ambient underglow, multi-layer alloy wheels, 3-spoke steering wheel,
 * panel lines, side mirrors, and ergonomic seat shapes with glow halos.
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

        /** Classify seat state into a color category (3-tier: danger/caution/safe). */
        fun seatColor(state: String): String = when (state) {
            "Upright" -> "safe"
            "Vacant" -> "vacant"
            "Phone", "Sleeping" -> "danger"       // critical (risk score 3)
            else -> "caution"                      // Yawning, Distracted, Eating (warning)
        }

        /**
         * Color category for non-driver (passenger) seats.
         * Passengers aren't driving — only sustained bad posture (Sleeping) warrants red.
         * Everything else caps at caution (yellow).
         */
        fun passengerSeatColor(state: String): String = when (state) {
            "Upright" -> "safe"
            "Vacant" -> "vacant"
            "Sleeping" -> "danger"                // sustained bad posture — safety concern
            else -> "caution"                     // all other states cap at yellow
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

        /** Whether a state is non-safe (danger or caution — triggers pulse animation). */
        fun isDanger(state: String): Boolean = when (state) {
            "Sleeping", "Phone", "Distracted", "Eating", "Yawning" -> true
            else -> false
        }

        /** Number of rear bench zones: 3 if rear center is occupied, else 2. */
        fun benchZoneCount(seatMap: SeatMap): Int =
            if (seatMap.rearCenter.occupied) 3 else 2

        /** Map rear risk level to color category for bumper/ripple rendering. */
        fun rearRiskColor(riskLevel: String): String = when (riskLevel) {
            "danger" -> "danger"
            "caution" -> "caution"
            else -> "clear"
        }
    }

    private var seatMap: SeatMap = SeatMap()
    private var driverSide: String = "left"
    private var driverName: String? = null
    private var dangerPulseAlpha: Float = 1.0f

    // Resolved colors
    private val colorSafe = ContextCompat.getColor(context, R.color.safe)
    private val colorCaution = ContextCompat.getColor(context, R.color.caution)
    private val colorDanger = ContextCompat.getColor(context, R.color.danger)
    private val colorVacant = Color.rgb(0x1E, 0x1F, 0x2A)
    private val colorTextPrimary = ContextCompat.getColor(context, R.color.text_primary)

    // Pre-allocated Paints — gradients set dynamically in onDraw when size is known
    private val bodyFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val bodyStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f; color = Color.rgb(0x3D, 0x3F, 0x4A)
    }
    private val bodyHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1f; color = Color.argb(30, 255, 255, 255)
    }
    private val panelLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 0.5f; color = Color.argb(40, 61, 63, 74)
    }
    private val windowFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val windowChromePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1f; color = Color.argb(80, 255, 255, 255)
    }
    private val seatFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val seatStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1f; color = Color.rgb(0x3D, 0x3F, 0x4A)
    }
    private val seatGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val steeringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2.5f; color = Color.rgb(0x6B, 0x6E, 0x7B)
        strokeCap = Paint.Cap.ROUND
    }
    private val steeringCenterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = Color.rgb(0x6B, 0x6E, 0x7B)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; color = colorTextPrimary; isFakeBoldText = true
    }
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1f; color = Color.rgb(0x2A, 0x2B, 0x35)
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val underglowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val headlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val headlightGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val drlPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f; color = Color.argb(153, 0xF3, 0x9C, 0x12)
        strokeCap = Paint.Cap.ROUND
    }
    private val taillightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val taillightGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val tirePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = Color.rgb(0x1A, 0x1B, 0x22)
    }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val hubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = Color.rgb(0x4A, 0x4C, 0x58)
    }
    private val mirrorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = Color.rgb(0x3D, 0x3F, 0x4A)
    }
    private val mirrorStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1f; color = Color.rgb(0x4A, 0x4C, 0x58)
    }
    private val consolePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = Color.rgb(0x1E, 0x1F, 0x2A)
    }
    private val interiorFloorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = Color.rgb(0x18, 0x19, 0x22)
    }

    // Reusable objects
    private val bodyPath = Path()
    private val bodyHighlightPath = Path()
    private val seatRect = RectF()
    private val steeringRect = RectF()
    private val tempRect = RectF()
    private val windshieldPath = Path()
    private val rearWindowPath = Path()
    private val sideWindowLeftPath = Path()
    private val sideWindowRightPath = Path()

    // Animation state
    private var pulseAnimator: ValueAnimator? = null
    private val seatColorAnimators = mutableMapOf<Seat, ValueAnimator>()
    private val currentColors = IntArray(5) { colorVacant }
    private var hasDanger = false
    private var underglowColor: Int = colorSafe
    private var underglowAnimator: ValueAnimator? = null

    // Rear warning state
    private var rearRiskLevel: String = "clear"
    private var ripplePhase: Float = 0f
    private var rippleAnimator: ValueAnimator? = null

    // Rear warning paints (pre-allocated)
    private val rearBumperPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val rearBumperGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val rearRipplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val rippleRect = RectF()

    // Cache last layout size for gradient invalidation
    private var lastW = 0f
    private var lastH = 0f

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

        // Animate underglow: red if driver critical or passenger sleeping, amber if any caution
        val hasCritical = seatColor(map.driver.state) == "danger" ||
            listOf(map.frontPassenger, map.rearLeft, map.rearCenter, map.rearRight)
                .any { passengerSeatColor(it.state) == "danger" }
        val hasCaution = listOf(map.driver, map.frontPassenger, map.rearLeft, map.rearCenter, map.rearRight)
            .any { seatColor(it.state) == "caution" || seatColor(it.state) == "danger" }
        val targetGlow = when {
            hasCritical -> colorDanger
            hasCaution -> colorCaution
            else -> colorSafe
        }
        if (targetGlow != underglowColor) {
            underglowAnimator?.cancel()
            underglowAnimator = ValueAnimator.ofObject(ArgbEvaluator(), underglowColor, targetGlow).apply {
                duration = COLOR_ANIM_DURATION_MS
                addUpdateListener {
                    underglowColor = it.animatedValue as Int
                    invalidate()
                }
                start()
            }
            underglowColor = targetGlow
        }

        invalidate()
    }

    /** Update rear warning state and start/stop ripple animation. */
    fun setRearWarning(riskLevel: String) {
        val category = rearRiskColor(riskLevel)
        if (category == rearRiskLevel) return
        rearRiskLevel = category

        if (category != "clear") {
            if (rippleAnimator == null) {
                rippleAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 1200L
                    repeatCount = ValueAnimator.INFINITE
                    addUpdateListener {
                        ripplePhase = it.animatedValue as Float
                        invalidate()
                    }
                    start()
                }
            }
        } else {
            rippleAnimator?.cancel()
            rippleAnimator = null
            ripplePhase = 0f
        }
        invalidate()
    }

    private fun resolveColor(state: SeatState, seat: Seat = Seat.DRIVER): Int {
        val category = if (seat == Seat.DRIVER) seatColor(state.state) else passengerSeatColor(state.state)
        return when (category) {
            "safe" -> colorSafe
            "caution" -> colorCaution
            "danger" -> colorDanger
            else -> colorVacant
        }
    }

    private fun animateSeatColor(seat: Seat, old: SeatState, new: SeatState) {
        val oldColor = currentColors[seat.ordinal]
        val newColor = resolveColor(new, seat)
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

        // --- Car bounding box: 55% width, 96% height, centered ---
        val carW = w * 0.55f
        val carH = h * 0.96f
        val carCX = w * 0.40f - 30f * dp  // shifted left ~0.5cm from center
        val carL = carCX - carW / 2f
        val carT = h * 0.02f
        val carR = carL + carW
        val carB = carT + carH

        // Section heights
        val hoodH = carH * 0.20f
        val cabinH = carH * 0.48f
        val trunkH = carH * 0.32f

        val hoodTop = carT
        val cabinTop = carT + hoodH
        val trunkTop = cabinTop + cabinH

        val cr = carW * 0.18f
        val hoodNarrow = carW * 0.10f
        val trunkNarrow = carW * 0.05f

        // Update gradients if size changed
        if (w != lastW || h != lastH) {
            lastW = w; lastH = h
            updateGradients(carL, carR, carT, carB, dp)
        }

        // 1. Ground shadow
        shadowPaint.shader = RadialGradient(
            carCX, carB + 4f * dp, carW * 0.7f,
            Color.argb(77, 0, 0, 0), Color.TRANSPARENT, Shader.TileMode.CLAMP
        )
        canvas.drawOval(
            carCX - carW * 0.6f, carB - 8f * dp,
            carCX + carW * 0.6f, carB + 16f * dp,
            shadowPaint
        )

        // 1.5. Rear warning sonar ripples + glowing bumper edge
        drawRearWarning(canvas, carL, carR, carB, carCX, carH, trunkNarrow, cr, dp)

        // 2. Ambient underglow
        val ugAlpha = 38  // 15%
        val ugColor = Color.argb(ugAlpha,
            Color.red(underglowColor), Color.green(underglowColor), Color.blue(underglowColor))
        underglowPaint.shader = RadialGradient(
            carCX, (carT + carB) / 2f, carW * 0.6f,
            ugColor, Color.TRANSPARENT, Shader.TileMode.CLAMP
        )
        underglowPaint.maskFilter = BlurMaskFilter(12f * dp, BlurMaskFilter.Blur.NORMAL)
        canvas.drawOval(
            carL - 10f * dp, carT - 6f * dp,
            carR + 10f * dp, carB + 6f * dp,
            underglowPaint
        )
        underglowPaint.maskFilter = null

        // 3. Car body
        buildBodyPath(carL, carR, carT, carB, hoodTop, cabinTop, trunkTop,
            hoodNarrow, trunkNarrow, cr, carH)

        canvas.drawPath(bodyPath, bodyFillPaint)
        canvas.drawPath(bodyPath, bodyStrokePaint)

        // 4. Body highlight (rim light along top edge)
        bodyHighlightPath.reset()
        bodyHighlightPath.moveTo(carL + hoodNarrow + cr * 0.5f, hoodTop + 1f)
        bodyHighlightPath.lineTo(carR - hoodNarrow - cr * 0.5f, hoodTop + 1f)
        canvas.drawPath(bodyHighlightPath, bodyHighlightPaint)

        // 5. Panel lines (door seams)
        // Left door seam
        canvas.drawLine(carL + 0.5f * dp, cabinTop + cabinH * 0.45f,
            carL + 0.5f * dp, trunkTop - cabinH * 0.02f, panelLinePaint)
        // Right door seam
        canvas.drawLine(carR - 0.5f * dp, cabinTop + cabinH * 0.45f,
            carR - 0.5f * dp, trunkTop - cabinH * 0.02f, panelLinePaint)
        // Hood seam (horizontal across front)
        canvas.drawLine(carL + hoodNarrow + 2f * dp, cabinTop,
            carR - hoodNarrow - 2f * dp, cabinTop, panelLinePaint)
        // Trunk seam
        canvas.drawLine(carL + trunkNarrow + 2f * dp, trunkTop,
            carR - trunkNarrow - 2f * dp, trunkTop, panelLinePaint)

        // 6. Windows
        drawWindows(canvas, carL, carR, cabinTop, trunkTop, cabinH, trunkH,
            hoodNarrow, trunkNarrow, carW, dp)

        // 7. Side mirrors
        val mirrorY = cabinTop + cabinH * 0.08f
        val mirrorW = carW * 0.04f
        val mirrorH = carW * 0.025f
        // Left mirror
        tempRect.set(carL - mirrorW - 1f * dp, mirrorY, carL - 1f * dp, mirrorY + mirrorH)
        canvas.drawOval(tempRect, mirrorPaint)
        canvas.drawOval(tempRect, mirrorStrokePaint)
        // Right mirror
        tempRect.set(carR + 1f * dp, mirrorY, carR + mirrorW + 1f * dp, mirrorY + mirrorH)
        canvas.drawOval(tempRect, mirrorPaint)
        canvas.drawOval(tempRect, mirrorStrokePaint)

        // 8. Headlights (LED-style)
        val hlW = carW * 0.14f
        val hlH = hoodH * 0.16f
        val hlY = hoodTop + hoodH * 0.12f
        val hlCorner = 3f * dp
        // Left headlight
        headlightPaint.color = Color.rgb(0xFF, 0xF8, 0xE7)
        tempRect.set(carL + hoodNarrow + 2f * dp, hlY,
            carL + hoodNarrow + 2f * dp + hlW, hlY + hlH)
        canvas.drawRoundRect(tempRect, hlCorner, hlCorner, headlightPaint)
        // Headlight glow
        headlightGlowPaint.color = Color.argb(60, 0xFF, 0xF8, 0xE7)
        headlightGlowPaint.maskFilter = BlurMaskFilter(8f * dp, BlurMaskFilter.Blur.NORMAL)
        canvas.drawRoundRect(tempRect, hlCorner, hlCorner, headlightGlowPaint)
        headlightGlowPaint.maskFilter = null
        // Right headlight
        tempRect.set(carR - hoodNarrow - 2f * dp - hlW, hlY,
            carR - hoodNarrow - 2f * dp, hlY + hlH)
        canvas.drawRoundRect(tempRect, hlCorner, hlCorner, headlightPaint)
        headlightGlowPaint.maskFilter = BlurMaskFilter(8f * dp, BlurMaskFilter.Blur.NORMAL)
        canvas.drawRoundRect(tempRect, hlCorner, hlCorner, headlightGlowPaint)
        headlightGlowPaint.maskFilter = null
        // DRL strips (amber, below headlights)
        val drlY = hlY + hlH + 2f * dp
        canvas.drawLine(carL + hoodNarrow + 4f * dp, drlY,
            carL + hoodNarrow + 4f * dp + hlW * 0.8f, drlY, drlPaint)
        canvas.drawLine(carR - hoodNarrow - 4f * dp - hlW * 0.8f, drlY,
            carR - hoodNarrow - 4f * dp, drlY, drlPaint)

        // 9. Taillights (LED-style red)
        val tlW = carW * 0.16f
        val tlH = trunkH * 0.10f
        val tlY = carB - trunkH * 0.18f
        val tlCorner = 2f * dp
        taillightPaint.color = Color.rgb(0xE7, 0x4C, 0x3C)
        // Left taillight
        tempRect.set(carL + trunkNarrow + 2f * dp, tlY,
            carL + trunkNarrow + 2f * dp + tlW, tlY + tlH)
        canvas.drawRoundRect(tempRect, tlCorner, tlCorner, taillightPaint)
        taillightGlowPaint.color = Color.argb(60, 0xE7, 0x4C, 0x3C)
        taillightGlowPaint.maskFilter = BlurMaskFilter(6f * dp, BlurMaskFilter.Blur.NORMAL)
        canvas.drawRoundRect(tempRect, tlCorner, tlCorner, taillightGlowPaint)
        taillightGlowPaint.maskFilter = null
        // Right taillight
        tempRect.set(carR - trunkNarrow - 2f * dp - tlW, tlY,
            carR - trunkNarrow - 2f * dp, tlY + tlH)
        canvas.drawRoundRect(tempRect, tlCorner, tlCorner, taillightPaint)
        taillightGlowPaint.maskFilter = BlurMaskFilter(6f * dp, BlurMaskFilter.Blur.NORMAL)
        canvas.drawRoundRect(tempRect, tlCorner, tlCorner, taillightGlowPaint)
        taillightGlowPaint.maskFilter = null

        // 10. Wheels (multi-layer)
        val wheelW = carW * 0.11f
        val wheelH = carH * 0.08f
        val wheelOffsetX = 3f * dp
        val wheelCorner = 3f * dp
        // Front-left
        drawWheel(canvas, carL - wheelOffsetX, cabinTop + cabinH * 0.04f,
            wheelW, wheelH, wheelCorner, dp)
        // Front-right
        drawWheel(canvas, carR - wheelW + wheelOffsetX, cabinTop + cabinH * 0.04f,
            wheelW, wheelH, wheelCorner, dp)
        // Rear-left
        drawWheel(canvas, carL + trunkNarrow - wheelOffsetX, trunkTop - wheelH * 0.3f,
            wheelW, wheelH, wheelCorner, dp)
        // Rear-right
        drawWheel(canvas, carR - trunkNarrow - wheelW + wheelOffsetX, trunkTop - wheelH * 0.3f,
            wheelW, wheelH, wheelCorner, dp)

        // 11. Interior floor
        val intPadX = carW * 0.14f
        val intL = carL + intPadX
        val intR = carR - intPadX
        val intW = intR - intL
        val intTop = cabinTop + cabinH * 0.04f
        val intBot = trunkTop - cabinH * 0.02f
        tempRect.set(intL, intTop, intR, intBot)
        canvas.drawRoundRect(tempRect, 4f * dp, 4f * dp, interiorFloorPaint)

        // 12. Center console
        val consoleW = intW * 0.06f
        val consoleTop = intTop + cabinH * 0.06f
        val consoleBot = intBot - cabinH * 0.04f
        tempRect.set(carCX - consoleW / 2f, consoleTop, carCX + consoleW / 2f, consoleBot)
        canvas.drawRoundRect(tempRect, 2f * dp, 2f * dp, consolePaint)

        // 13. Seats
        val gap = intW * 0.08f
        val seatW = (intW - gap) / 2f
        val seatH = cabinH * 0.32f
        val seatTop = intTop + cabinH * 0.06f
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

        // 14. Steering wheel (3-spoke)
        val driverSeatL = if (driverSide == "left") intL else intL + seatW + gap
        val swCX = driverSeatL + seatW / 2f
        val swCY = seatTop + seatH * 0.28f
        val swR = seatW * 0.20f
        drawSteeringWheel(canvas, swCX, swCY, swR)

        // Rear row: bench seat
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

    }

    private fun updateGradients(carL: Float, carR: Float, carT: Float, carB: Float, dp: Float) {
        // Body: left-to-right metallic gradient for 3D curvature
        bodyFillPaint.shader = LinearGradient(
            carL, 0f, carR, 0f,
            intArrayOf(
                Color.rgb(0x2A, 0x2C, 0x38),
                Color.rgb(0x38, 0x3B, 0x48),
                Color.rgb(0x2A, 0x2C, 0x38)
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    private fun buildBodyPath(
        carL: Float, carR: Float, carT: Float, carB: Float,
        hoodTop: Float, cabinTop: Float, trunkTop: Float,
        hoodNarrow: Float, trunkNarrow: Float, cr: Float, carH: Float
    ) {
        bodyPath.reset()
        // Smoother bezier sedan silhouette
        bodyPath.moveTo(carL + hoodNarrow + cr * 0.5f, hoodTop)
        bodyPath.lineTo(carR - hoodNarrow - cr * 0.5f, hoodTop)
        bodyPath.quadTo(carR - hoodNarrow, hoodTop, carR - hoodNarrow, hoodTop + cr * 0.5f)
        // Right: hood widens to cabin via smooth bezier
        bodyPath.cubicTo(
            carR - hoodNarrow, cabinTop - cr * 0.5f,
            carR, cabinTop - cr * 0.2f,
            carR, cabinTop + cr * 0.3f
        )
        bodyPath.lineTo(carR, trunkTop - cr * 0.3f)
        // Right: cabin narrows to trunk
        bodyPath.cubicTo(
            carR, trunkTop + cr * 0.2f,
            carR - trunkNarrow, trunkTop + cr * 0.1f,
            carR - trunkNarrow, trunkTop + cr * 0.3f
        )
        bodyPath.lineTo(carR - trunkNarrow, carB - cr * 0.5f)
        bodyPath.quadTo(carR - trunkNarrow, carB, carR - trunkNarrow - cr * 0.5f, carB)
        bodyPath.lineTo(carL + trunkNarrow + cr * 0.5f, carB)
        bodyPath.quadTo(carL + trunkNarrow, carB, carL + trunkNarrow, carB - cr * 0.5f)
        bodyPath.lineTo(carL + trunkNarrow, trunkTop + cr * 0.3f)
        // Left: trunk widens to cabin
        bodyPath.cubicTo(
            carL + trunkNarrow, trunkTop + cr * 0.1f,
            carL, trunkTop + cr * 0.2f,
            carL, trunkTop - cr * 0.3f
        )
        bodyPath.lineTo(carL, cabinTop + cr * 0.3f)
        // Left: cabin narrows to hood
        bodyPath.cubicTo(
            carL, cabinTop - cr * 0.2f,
            carL + hoodNarrow, cabinTop - cr * 0.5f,
            carL + hoodNarrow, hoodTop + cr * 0.5f
        )
        bodyPath.quadTo(carL + hoodNarrow, hoodTop, carL + hoodNarrow + cr * 0.5f, hoodTop)
        bodyPath.close()
    }

    private fun drawRearWarning(
        canvas: Canvas, carL: Float, carR: Float, carB: Float,
        carCX: Float, carH: Float, trunkNarrow: Float, cr: Float, dp: Float
    ) {
        if (rearRiskLevel == "clear") return

        val riskColor = if (rearRiskLevel == "danger") colorDanger else colorCaution

        // Bumper edge endpoints (follows rear curve of body)
        val bumperL = carL + trunkNarrow + cr * 0.5f
        val bumperR = carR - trunkNarrow - cr * 0.5f
        val bumperY = carB

        // 1. Glowing bumper edge (glow layer first, then solid)
        rearBumperGlowPaint.strokeWidth = 8f * dp
        rearBumperGlowPaint.color = Color.argb(100, Color.red(riskColor), Color.green(riskColor), Color.blue(riskColor))
        rearBumperGlowPaint.maskFilter = BlurMaskFilter(8f * dp, BlurMaskFilter.Blur.NORMAL)
        canvas.drawLine(bumperL, bumperY, bumperR, bumperY, rearBumperGlowPaint)
        rearBumperGlowPaint.maskFilter = null

        rearBumperPaint.strokeWidth = 4f * dp
        rearBumperPaint.color = riskColor
        canvas.drawLine(bumperL, bumperY, bumperR, bumperY, rearBumperPaint)

        // 2. Sonar ripple waves (3 arcs expanding from bumper)
        val maxExpand = carH * 0.08f
        val bumperW = bumperR - bumperL
        val arcCX = carCX
        val arcTop = bumperY

        for (i in 0 until 3) {
            // Stagger each arc: phase offset by 0.33 each
            val phase = (ripplePhase + i * 0.33f) % 1f
            val expand = phase * maxExpand
            val alpha = ((1f - phase) * when (i) {
                0 -> 0.80f
                1 -> 0.50f
                else -> 0.25f
            } * 255).toInt().coerceIn(0, 255)

            val strokeW = when (i) {
                0 -> 3f * dp
                1 -> 2f * dp
                else -> 1.5f * dp
            }

            rearRipplePaint.color = Color.argb(alpha, Color.red(riskColor), Color.green(riskColor), Color.blue(riskColor))
            rearRipplePaint.strokeWidth = strokeW

            val halfW = bumperW / 2f + expand * 0.5f
            val arcH = expand + 4f * dp
            rippleRect.set(arcCX - halfW, arcTop, arcCX + halfW, arcTop + arcH * 2f)
            canvas.drawArc(rippleRect, 0f, 180f, false, rearRipplePaint)
        }
    }

    private fun drawWindows(
        canvas: Canvas, carL: Float, carR: Float, cabinTop: Float, trunkTop: Float,
        cabinH: Float, trunkH: Float, hoodNarrow: Float, trunkNarrow: Float,
        carW: Float, dp: Float
    ) {
        // Windshield
        val wsInsetTop = carW * 0.14f
        val wsInsetBot = carW * 0.06f
        val wsTop = cabinTop + cabinH * 0.02f
        val wsBot = cabinTop + cabinH * 0.18f

        // Window gradient (blue tint glass)
        windowFillPaint.shader = LinearGradient(
            0f, wsTop, 0f, wsBot,
            Color.argb(50, 0x5B, 0x8D, 0xEF),
            Color.argb(25, 0x5B, 0x8D, 0xEF),
            Shader.TileMode.CLAMP
        )

        windshieldPath.reset()
        windshieldPath.moveTo(carL + wsInsetTop, wsTop)
        windshieldPath.lineTo(carR - wsInsetTop, wsTop)
        windshieldPath.lineTo(carR - wsInsetBot, wsBot)
        windshieldPath.lineTo(carL + wsInsetBot, wsBot)
        windshieldPath.close()
        canvas.drawPath(windshieldPath, windowFillPaint)
        canvas.drawPath(windshieldPath, windowChromePaint)

        // Rear window
        val rwInsetTop = carW * 0.06f
        val rwInsetBot = carW * 0.12f
        val rwTop = trunkTop - cabinH * 0.01f
        val rwBot = trunkTop + trunkH * 0.22f

        windowFillPaint.shader = LinearGradient(
            0f, rwTop, 0f, rwBot,
            Color.argb(50, 0x5B, 0x8D, 0xEF),
            Color.argb(25, 0x5B, 0x8D, 0xEF),
            Shader.TileMode.CLAMP
        )

        rearWindowPath.reset()
        rearWindowPath.moveTo(carL + rwInsetTop, rwTop)
        rearWindowPath.lineTo(carR - rwInsetTop, rwTop)
        rearWindowPath.lineTo(carR - trunkNarrow - rwInsetBot, rwBot)
        rearWindowPath.lineTo(carL + trunkNarrow + rwInsetBot, rwBot)
        rearWindowPath.close()
        canvas.drawPath(rearWindowPath, windowFillPaint)
        canvas.drawPath(rearWindowPath, windowChromePaint)

        // Side windows
        val swTop = wsBot + cabinH * 0.02f
        val swBot = rwTop - cabinH * 0.02f
        val swThick = carW * 0.06f

        windowFillPaint.shader = null
        windowFillPaint.color = Color.argb(35, 0x5B, 0x8D, 0xEF)

        sideWindowLeftPath.reset()
        sideWindowLeftPath.moveTo(carL + wsInsetBot, swTop)
        sideWindowLeftPath.lineTo(carL + wsInsetBot - swThick * 0.5f, swTop)
        sideWindowLeftPath.lineTo(carL + rwInsetTop - swThick * 0.5f, swBot)
        sideWindowLeftPath.lineTo(carL + rwInsetTop, swBot)
        sideWindowLeftPath.close()
        canvas.drawPath(sideWindowLeftPath, windowFillPaint)

        sideWindowRightPath.reset()
        sideWindowRightPath.moveTo(carR - wsInsetBot, swTop)
        sideWindowRightPath.lineTo(carR - wsInsetBot + swThick * 0.5f, swTop)
        sideWindowRightPath.lineTo(carR - rwInsetTop + swThick * 0.5f, swBot)
        sideWindowRightPath.lineTo(carR - rwInsetTop, swBot)
        sideWindowRightPath.close()
        canvas.drawPath(sideWindowRightPath, windowFillPaint)
    }

    private fun drawWheel(canvas: Canvas, left: Float, top: Float, w: Float, h: Float,
                          corner: Float, dp: Float) {
        // Tire
        tempRect.set(left, top, left + w, top + h)
        canvas.drawRoundRect(tempRect, corner, corner, tirePaint)

        // Alloy rim (inset)
        val rimInset = w * 0.15f
        tempRect.set(left + rimInset, top + rimInset, left + w - rimInset, top + h - rimInset)
        rimPaint.shader = RadialGradient(
            left + w / 2f, top + h / 2f, w * 0.4f,
            Color.rgb(0x8A, 0x8D, 0x9B), Color.rgb(0x5A, 0x5D, 0x6B),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(tempRect, corner * 0.6f, corner * 0.6f, rimPaint)

        // Hub center
        val hubR = w * 0.08f
        canvas.drawCircle(left + w / 2f, top + h / 2f, hubR, hubPaint)
    }

    private fun drawSeat(
        canvas: Canvas, left: Float, top: Float, width: Float, height: Float,
        corner: Float, seat: Seat, state: SeatState, dp: Float
    ) {
        val color = currentColors[seat.ordinal]
        val alpha = if (isDanger(state.state)) dangerPulseAlpha else 1.0f

        seatRect.set(left, top, left + width, top + height)

        // Glow halo for occupied seats
        if (state.occupied) {
            seatGlowPaint.color = color
            seatGlowPaint.alpha = (40 * alpha).toInt()
            seatGlowPaint.maskFilter = BlurMaskFilter(6f * dp, BlurMaskFilter.Blur.NORMAL)
            canvas.drawRoundRect(seatRect, corner, corner, seatGlowPaint)
            seatGlowPaint.maskFilter = null
        }

        // Seat fill with subtle gradient (lighter top -> darker bottom for leather look)
        val topColor = lightenColor(color, 0.15f)
        val botColor = darkenColor(color, 0.1f)
        seatFillPaint.shader = LinearGradient(
            0f, top, 0f, top + height,
            topColor, botColor,
            Shader.TileMode.CLAMP
        )
        seatFillPaint.alpha = (255 * alpha).toInt()
        canvas.drawRoundRect(seatRect, corner, corner, seatFillPaint)
        seatFillPaint.shader = null
        canvas.drawRoundRect(seatRect, corner, corner, seatStrokePaint)

        // State icon label
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
            seatGlowPaint.maskFilter = null
        }

        val topColor = lightenColor(color, 0.15f)
        val botColor = darkenColor(color, 0.1f)
        seatFillPaint.shader = LinearGradient(
            0f, top, 0f, top + height,
            topColor, botColor,
            Shader.TileMode.CLAMP
        )
        seatFillPaint.alpha = (255 * alpha).toInt()
        canvas.drawRoundRect(seatRect, corner, corner, seatFillPaint)
        seatFillPaint.shader = null

        canvas.restore()

        labelPaint.textSize = 10f * dp
        val icon = stateIcon(state.state)
        val textX = left + width / 2f
        val textY = top + height / 2f + labelPaint.textSize * 0.35f
        canvas.drawText(icon, textX, textY, labelPaint)
    }

    private fun drawSteeringWheel(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        // Outer ring (open at bottom)
        steeringRect.set(cx - radius, cy - radius, cx + radius, cy + radius)
        canvas.drawArc(steeringRect, 200f, 280f, false, steeringPaint)
        // 3 spokes at 120-degree intervals
        val spokeLen = radius * 0.65f
        for (angle in intArrayOf(90, 210, 330)) {
            val rad = Math.toRadians(angle.toDouble())
            canvas.drawLine(cx, cy,
                cx + (spokeLen * Math.cos(rad)).toFloat(),
                cy - (spokeLen * Math.sin(rad)).toFloat(),
                steeringPaint)
        }
        // Center hub
        canvas.drawCircle(cx, cy, radius * 0.18f, steeringCenterPaint)
    }

    private fun lightenColor(color: Int, fraction: Float): Int {
        val r = (Color.red(color) + (255 - Color.red(color)) * fraction).toInt().coerceIn(0, 255)
        val g = (Color.green(color) + (255 - Color.green(color)) * fraction).toInt().coerceIn(0, 255)
        val b = (Color.blue(color) + (255 - Color.blue(color)) * fraction).toInt().coerceIn(0, 255)
        return Color.argb(Color.alpha(color), r, g, b)
    }

    private fun darkenColor(color: Int, fraction: Float): Int {
        val r = (Color.red(color) * (1f - fraction)).toInt().coerceIn(0, 255)
        val g = (Color.green(color) * (1f - fraction)).toInt().coerceIn(0, 255)
        val b = (Color.blue(color) * (1f - fraction)).toInt().coerceIn(0, 255)
        return Color.argb(Color.alpha(color), r, g, b)
    }

    override fun onDetachedFromWindow() {
        pulseAnimator?.cancel()
        underglowAnimator?.cancel()
        rippleAnimator?.cancel()
        seatColorAnimators.values.forEach { it.cancel() }
        seatColorAnimators.clear()
        super.onDetachedFromWindow()
    }
}
