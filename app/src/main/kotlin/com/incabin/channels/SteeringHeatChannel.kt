package com.incabin.channels

import android.util.Log
import com.incabin.EscalationLevel
import com.incabin.VehicleActionChannel
import com.incabin.VehicleChannelId

/**
 * VHAL steering wheel heat channel. Heat pulse for 3s at L4+ (hands-off alert).
 *
 * Uses HVAC_STEERING_WHEEL_HEAT (0x050E).
 */
class SteeringHeatChannel(private val propertyManager: Any) : VehicleActionChannel {

    companion object {
        private const val TAG = "SteeringHeatChannel"
        const val HVAC_STEERING_WHEEL_HEAT = 0x050E

        fun isPropertyAvailable(propertyId: Int, availablePropertyIds: Set<Int>): Boolean {
            return propertyId in availablePropertyIds
        }
    }

    override val id = VehicleChannelId.STEERING_HEAT
    override val available: Boolean
    private var savedHeatLevel: Int? = null

    init {
        available = probeAvailability()
        Log.d(TAG, "SteeringHeatChannel available=$available")
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
            isPropertyAvailable(HVAC_STEERING_WHEEL_HEAT, ids)
        } catch (e: Exception) {
            Log.d(TAG, "Property probe failed", e)
            false
        }
    }

    override fun activate(level: EscalationLevel) {
        if (!available) return
        try {
            if (savedHeatLevel == null) {
                savedHeatLevel = getIntProperty(HVAC_STEERING_WHEEL_HEAT)
            }
            // Max heat pulse
            setIntProperty(HVAC_STEERING_WHEEL_HEAT, 3)
            Thread({
                try {
                    Thread.sleep(3000L)
                    restore()
                } catch (_: InterruptedException) {}
            }, "SteeringHeat-Pulse").start()
        } catch (e: Exception) {
            Log.w(TAG, "Activate failed", e)
        }
    }

    override fun restore() {
        try {
            savedHeatLevel?.let { setIntProperty(HVAC_STEERING_WHEEL_HEAT, it) }
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
