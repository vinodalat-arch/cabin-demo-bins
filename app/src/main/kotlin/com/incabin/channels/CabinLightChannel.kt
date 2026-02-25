package com.incabin.channels

import android.util.Log
import com.incabin.Config
import com.incabin.EscalationLevel
import com.incabin.VehicleActionChannel
import com.incabin.VehicleChannelId

/**
 * VHAL cabin light channel. Flashes footwell/cabin lights for 3s at L3+.
 *
 * Uses CABIN_LIGHTS_SWITCH (0x0F4004) and SEAT_FOOTWELL_LIGHTS_SWITCH (0x0F4007)
 * via reflection on CarPropertyManager to avoid compile-time Car API dependency.
 */
class CabinLightChannel(private val propertyManager: Any) : VehicleActionChannel {

    companion object {
        private const val TAG = "CabinLightChannel"

        // VehiclePropertyIds for cabin lighting
        const val CABIN_LIGHTS_SWITCH = 0x0F4004
        const val SEAT_FOOTWELL_LIGHTS_SWITCH = 0x0F4007

        /**
         * Pure function: check if a property ID is present in a list of property configs.
         */
        fun isPropertyAvailable(propertyId: Int, availablePropertyIds: Set<Int>): Boolean {
            return propertyId in availablePropertyIds
        }
    }

    override val id = VehicleChannelId.CABIN_LIGHTS
    override val available: Boolean
    private var savedCabinState: Int? = null
    private var savedFootwellState: Int? = null

    init {
        available = probeAvailability()
        Log.d(TAG, "CabinLightChannel available=$available")
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
            isPropertyAvailable(CABIN_LIGHTS_SWITCH, ids) ||
                isPropertyAvailable(SEAT_FOOTWELL_LIGHTS_SWITCH, ids)
        } catch (e: Exception) {
            Log.d(TAG, "Property probe failed", e)
            false
        }
    }

    override fun activate(level: EscalationLevel) {
        if (!available) return
        try {
            // Save current state before first activation
            if (savedCabinState == null) {
                savedCabinState = getIntProperty(CABIN_LIGHTS_SWITCH)
            }

            // Flash: set to ON (value 1), then schedule restore after pulse duration
            setIntProperty(CABIN_LIGHTS_SWITCH, 1)
            Thread({
                try {
                    Thread.sleep(Config.VEHICLE_PULSE_CABIN_LIGHTS_MS)
                    restore()
                } catch (_: InterruptedException) {}
            }, "CabinLight-Pulse").start()
        } catch (e: Exception) {
            Log.w(TAG, "Activate failed", e)
        }
    }

    override fun restore() {
        try {
            savedCabinState?.let { setIntProperty(CABIN_LIGHTS_SWITCH, it) }
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
