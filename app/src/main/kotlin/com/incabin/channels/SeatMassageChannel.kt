package com.incabin.channels

import android.util.Log
import com.incabin.Config
import com.incabin.EscalationLevel
import com.incabin.VehicleActionChannel
import com.incabin.VehicleChannelId

/**
 * VHAL seat massage channel for drowsiness wake-up.
 *
 * Probes in order:
 * 1. SEAT_MASSAGE_ENABLED (0x0B97) + SEAT_MASSAGE_INTENSITY (0x0B98) — proper massage
 * 2. Fallback: SEAT_LUMBAR_FORE_AFT_MOVE (0x0B8E) — quick lumbar pulses
 *
 * Independent of the escalation ladder — triggered by DrowsinessWakeController.
 */
class SeatMassageChannel(private val propertyManager: Any) : VehicleActionChannel {

    companion object {
        private const val TAG = "SeatMassageChannel"

        const val SEAT_MASSAGE_ENABLED = 0x0B97
        const val SEAT_MASSAGE_INTENSITY = 0x0B98
        const val SEAT_LUMBAR_FORE_AFT_MOVE = 0x0B8E

        /**
         * Pure function: determine which massage mode is available.
         * Returns "massage" if proper massage properties exist,
         * "lumbar" if fallback lumbar property exists, null if neither.
         */
        fun detectMode(availablePropertyIds: Set<Int>): String? {
            if (SEAT_MASSAGE_ENABLED in availablePropertyIds &&
                SEAT_MASSAGE_INTENSITY in availablePropertyIds
            ) return "massage"
            if (SEAT_LUMBAR_FORE_AFT_MOVE in availablePropertyIds) return "lumbar"
            return null
        }
    }

    override val id = VehicleChannelId.SEAT_MASSAGE
    override val available: Boolean
    private val mode: String?
    private var lastTriggerMs = 0L

    init {
        val probeResult = probeAvailability()
        mode = probeResult
        available = probeResult != null
        Log.d(TAG, "SeatMassageChannel available=$available, mode=$mode")
    }

    private fun probeAvailability(): String? {
        return try {
            val getListMethod = propertyManager.javaClass.getMethod("getPropertyList")
            val propertyList = getListMethod.invoke(propertyManager) as? List<*> ?: return null
            val ids = propertyList.mapNotNull { config ->
                try {
                    val propIdField = config!!.javaClass.getField("propertyId")
                    propIdField.getInt(config)
                } catch (_: Exception) { null }
            }.toSet()
            detectMode(ids)
        } catch (e: Exception) {
            Log.d(TAG, "Property probe failed", e)
            null
        }
    }

    /**
     * Trigger a drowsiness wake-up burst.
     * Respects cooldown period to avoid constant buzzing.
     * @param bypassCooldown if true, ignore cooldown (used for emergency override)
     */
    fun triggerDrowsinessBurst(bypassCooldown: Boolean = false) {
        if (!available) return

        val now = System.currentTimeMillis()
        if (!bypassCooldown && (now - lastTriggerMs) < Config.SEAT_MASSAGE_COOLDOWN_MS) {
            Log.d(TAG, "Massage cooldown active, skipping")
            return
        }
        lastTriggerMs = now

        when (mode) {
            "massage" -> triggerMassageBurst()
            "lumbar" -> triggerLumbarPulses()
        }
    }

    private fun triggerMassageBurst() {
        try {
            val driverAreaId = driverSeatAreaId()
            setIntProperty(SEAT_MASSAGE_ENABLED, 1, driverAreaId)
            setIntProperty(SEAT_MASSAGE_INTENSITY, 3, driverAreaId) // medium-high intensity
            Thread({
                try {
                    Thread.sleep(Config.SEAT_MASSAGE_DURATION_MS)
                    setIntProperty(SEAT_MASSAGE_ENABLED, 0, driverAreaId)
                } catch (_: InterruptedException) {}
            }, "SeatMassage-Burst").start()
            Log.i(TAG, "Massage burst started (${Config.SEAT_MASSAGE_DURATION_MS}ms)")
        } catch (e: Exception) {
            Log.w(TAG, "Massage burst failed", e)
        }
    }

    private fun triggerLumbarPulses() {
        try {
            val driverAreaId = driverSeatAreaId()
            Thread({
                try {
                    // 2 quick lumbar pulses
                    for (i in 0 until 2) {
                        setIntProperty(SEAT_LUMBAR_FORE_AFT_MOVE, 1, driverAreaId)
                        Thread.sleep(500)
                        setIntProperty(SEAT_LUMBAR_FORE_AFT_MOVE, 0, driverAreaId)
                        Thread.sleep(500)
                    }
                } catch (_: InterruptedException) {}
            }, "SeatLumbar-Pulse").start()
            Log.i(TAG, "Lumbar pulse started")
        } catch (e: Exception) {
            Log.w(TAG, "Lumbar pulse failed", e)
        }
    }

    private fun driverSeatAreaId(): Int {
        return if (Config.DRIVER_SEAT_SIDE == "left") Config.HVAC_AREA_ROW1_LEFT
        else Config.HVAC_AREA_ROW1_RIGHT
    }

    override fun activate(level: EscalationLevel) {
        // Not used in normal escalation ladder — triggered independently
        triggerDrowsinessBurst()
    }

    override fun restore() {
        // No persistent state to restore
    }

    override fun close() {
        // No resources to release
    }

    private fun setIntProperty(propertyId: Int, value: Int, areaId: Int) {
        try {
            val setMethod = propertyManager.javaClass.getMethod(
                "setIntProperty", Int::class.java, Int::class.java, Int::class.java
            )
            setMethod.invoke(propertyManager, propertyId, areaId, value)
        } catch (e: Exception) {
            Log.w(TAG, "setIntProperty($propertyId, $value, area=$areaId) failed", e)
        }
    }
}
