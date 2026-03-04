package com.incabin

/**
 * Warms rear HVAC zone for children and provides periodic drive-time reminders.
 * All decision logic in pure companion functions — no Android dependencies.
 */
class ChildComfortController {

    private var childDetectedFrames: Int = 0
    private var lastReminderMinute: Int = 0

    /**
     * Called once per frame. Returns CabinEvent for periodic reminders, else null.
     */
    fun update(result: OutputResult, seatMap: SeatMap?, isJapanese: Boolean): CabinEvent? {
        if (!Config.ENABLE_CHILD_COMFORT) return null

        val childInRear = shouldWarmRear(result.childPresent, seatMap)
        if (childInRear) {
            childDetectedFrames++
        } else {
            childDetectedFrames = 0
            lastReminderMinute = 0
            return null
        }

        val minutes = childDriveMinutes(childDetectedFrames, Config.INFERENCE_FPS)
        if (shouldRemind(minutes, lastReminderMinute, Config.CHILD_REMINDER_INTERVAL_MIN)) {
            lastReminderMinute = minutes
            return CabinEvent(CabinEvent.INFO, formatReminder(minutes, isJapanese))
        }

        return null
    }

    fun hasChildInRear(): Boolean = childDetectedFrames > 0

    fun reset() {
        childDetectedFrames = 0
        lastReminderMinute = 0
    }

    companion object {
        fun shouldWarmRear(childPresent: Boolean, seatMap: SeatMap?): Boolean {
            if (!childPresent) return false
            if (seatMap == null) return true // child detected but no seat map — assume rear
            // Child in any rear seat
            return (seatMap.rearLeft.occupied && seatMap.rearLeft.state != "Vacant") ||
                (seatMap.rearCenter.occupied && seatMap.rearCenter.state != "Vacant") ||
                (seatMap.rearRight.occupied && seatMap.rearRight.state != "Vacant")
        }

        fun childDriveMinutes(frames: Int, fps: Int): Int {
            val safeFps = fps.coerceAtLeast(1)
            return frames / (safeFps * 60)
        }

        fun shouldRemind(minutes: Int, lastReminded: Int, interval: Int): Boolean {
            if (minutes < interval) return false
            val nextReminder = if (lastReminded == 0) interval else lastReminded + interval
            return minutes >= nextReminder
        }

        fun formatReminder(minutes: Int, isJapanese: Boolean): String {
            return if (isJapanese) "お子様が${minutes}分間乗車中です。休憩をご検討ください。"
            else "Your child has been in the car for $minutes minutes."
        }
    }
}
