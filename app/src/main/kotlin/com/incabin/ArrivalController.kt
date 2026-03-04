package com.incabin

/**
 * Detects deceleration → park transition and announces arrival with trip duration.
 * All decision logic in pure companion functions — no Android dependencies.
 */
class ArrivalController {

    private val speedHistory = ArrayDeque<Float>(Config.ARRIVAL_SPEED_HISTORY_SIZE)
    private var announced: Boolean = false
    private var tripStartMs: Long = 0L

    fun start(nowMs: Long) {
        tripStartMs = nowMs
        announced = false
        speedHistory.clear()
    }

    /**
     * Called once per frame. Returns CabinEvent on arrival detection, else null.
     */
    fun update(speedKmh: Float, isParked: Boolean, nowMs: Long, isJapanese: Boolean): CabinEvent? {
        if (!Config.ENABLE_ARRIVAL_PREP) return null
        if (announced) return null

        // Track speed history
        speedHistory.addLast(speedKmh)
        if (speedHistory.size > Config.ARRIVAL_SPEED_HISTORY_SIZE) {
            speedHistory.removeFirst()
        }

        if (detectArrival(speedHistory.toList(), isParked)) {
            announced = true
            val tripMinutes = if (tripStartMs > 0) ((nowMs - tripStartMs) / 60_000).toInt() else 0
            return CabinEvent(CabinEvent.INFO, formatArrived(tripMinutes, isJapanese))
        }

        return null
    }

    fun reset() {
        speedHistory.clear()
        announced = false
        tripStartMs = 0L
    }

    companion object {
        fun detectArrival(speedHistory: List<Float>, isParked: Boolean): Boolean {
            if (!isParked) return false
            // Must have had at least one speed reading >30 km/h in recent history
            return speedHistory.any { it > 30f }
        }

        fun formatArrivingSoon(isJapanese: Boolean): String {
            return if (isJapanese) "まもなく到着です。" else "Arriving soon."
        }

        fun formatArrived(tripMinutes: Int, isJapanese: Boolean): String {
            return if (isJapanese) "到着しました。走行時間：${tripMinutes}分。"
            else "You've arrived. Trip: $tripMinutes minutes."
        }
    }
}
