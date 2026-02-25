package com.incabin.channels

import android.util.Log
import com.incabin.Config
import com.incabin.EscalationLevel
import com.incabin.VehicleActionChannel
import com.incabin.VehicleChannelId

/**
 * VHAL seat haptic channel. Pulses lumbar fore/aft 3 times for L3+.
 *
 * Uses SEAT_LUMBAR_FORE_AFT_MOVE (0x0B8E) as a crude haptic pulse
 * (full seat vibration motors require OEM vendor extensions).
 */
class SeatHapticChannel(private val propertyManager: Any) : VehicleActionChannel {

    companion object {
        private const val TAG = "SeatHapticChannel"
        const val SEAT_LUMBAR_FORE_AFT_MOVE = 0x0B8E

        fun isPropertyAvailable(propertyId: Int, availablePropertyIds: Set<Int>): Boolean {
            return propertyId in availablePropertyIds
        }
    }

    override val id = VehicleChannelId.SEAT_HAPTIC
    override val available: Boolean

    init {
        available = probeAvailability()
        Log.d(TAG, "SeatHapticChannel available=$available")
    }

    private fun probeAvailability(): Boolean {
        return try {
            val getListMethod = propertyManager.javaClass.getMethod("getPropertyList")
            val propertyList = getListMethod.invoke(propertyManager) as? List<*> ?: return false
            val ids = propertyList.mapNotNull { config ->
                try {
                    val propIdField = config!!.javaClass.getField("propertyId")
                    propIdField.getInt(config)
                } catch (_: Exception) { null }
            }.toSet()
            isPropertyAvailable(SEAT_LUMBAR_FORE_AFT_MOVE, ids)
        } catch (e: Exception) {
            Log.d(TAG, "Property probe failed", e)
            false
        }
    }

    override fun activate(level: EscalationLevel) {
        if (!available) return
        Thread({
            try {
                // 3 fore/aft pulses
                repeat(3) {
                    setIntProperty(SEAT_LUMBAR_FORE_AFT_MOVE, 1)  // fore
                    Thread.sleep(Config.VEHICLE_PULSE_LUMBAR_MS / 3)
                    setIntProperty(SEAT_LUMBAR_FORE_AFT_MOVE, -1) // aft
                    Thread.sleep(Config.VEHICLE_PULSE_LUMBAR_MS / 3)
                }
                setIntProperty(SEAT_LUMBAR_FORE_AFT_MOVE, 0) // stop
            } catch (_: InterruptedException) {
                setIntProperty(SEAT_LUMBAR_FORE_AFT_MOVE, 0)
            } catch (e: Exception) {
                Log.w(TAG, "Haptic pulse failed", e)
            }
        }, "SeatHaptic-Pulse").start()
    }

    override fun restore() {
        try {
            setIntProperty(SEAT_LUMBAR_FORE_AFT_MOVE, 0)
        } catch (e: Exception) {
            Log.w(TAG, "Restore failed", e)
        }
    }

    override fun close() {
        restore()
    }

    private fun setIntProperty(propertyId: Int, value: Int) {
        try {
            val setMethod = propertyManager.javaClass.getMethod(
                "setIntProperty", Int::class.java, Int::class.java, Int::class.java
            )
            setMethod.invoke(propertyManager, propertyId, 0, value)
        } catch (e: Exception) {
            Log.w(TAG, "setIntProperty($propertyId, $value) failed", e)
        }
    }
}
