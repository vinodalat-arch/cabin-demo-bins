package com.incabin.channels

import android.util.Log
import com.incabin.Config
import com.incabin.EscalationLevel
import com.incabin.VehicleActionChannel
import com.incabin.VehicleChannelId

/**
 * VHAL seat thermal channel. Cool blast for 5s at L4+ (drowsiness wake-up).
 *
 * Uses HVAC_SEAT_TEMPERATURE (0x050C) — negative values = cooling, positive = heating.
 */
class SeatThermalChannel(private val propertyManager: Any) : VehicleActionChannel {

    companion object {
        private const val TAG = "SeatThermalChannel"
        const val HVAC_SEAT_TEMPERATURE = 0x050C

        fun isPropertyAvailable(propertyId: Int, availablePropertyIds: Set<Int>): Boolean {
            return propertyId in availablePropertyIds
        }
    }

    override val id = VehicleChannelId.SEAT_THERMAL
    override val available: Boolean
    private var savedTemperature: Int? = null

    init {
        available = probeAvailability()
        Log.d(TAG, "SeatThermalChannel available=$available")
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
            isPropertyAvailable(HVAC_SEAT_TEMPERATURE, ids)
        } catch (e: Exception) {
            Log.d(TAG, "Property probe failed", e)
            false
        }
    }

    override fun activate(level: EscalationLevel) {
        if (!available) return
        try {
            if (savedTemperature == null) {
                savedTemperature = getIntProperty(HVAC_SEAT_TEMPERATURE)
            }
            // Cool blast: set to minimum cooling (-3 is typical VHAL range)
            setIntProperty(HVAC_SEAT_TEMPERATURE, -3)
            Thread({
                try {
                    Thread.sleep(Config.VEHICLE_PULSE_SEAT_THERMAL_MS)
                    restore()
                } catch (_: InterruptedException) {}
            }, "SeatThermal-Pulse").start()
        } catch (e: Exception) {
            Log.w(TAG, "Activate failed", e)
        }
    }

    override fun restore() {
        try {
            savedTemperature?.let { setIntProperty(HVAC_SEAT_TEMPERATURE, it) }
        } catch (e: Exception) {
            Log.w(TAG, "Restore failed", e)
        }
    }

    override fun close() {
        restore()
    }

    private fun getIntProperty(propertyId: Int): Int? {
        return try {
            val getMethod = propertyManager.javaClass.getMethod(
                "getIntProperty", Int::class.java, Int::class.java
            )
            getMethod.invoke(propertyManager, propertyId, 0) as? Int
        } catch (_: Exception) { null }
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
