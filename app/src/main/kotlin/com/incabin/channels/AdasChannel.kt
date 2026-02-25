package com.incabin.channels

import android.util.Log
import com.incabin.EscalationLevel
import com.incabin.VehicleActionChannel
import com.incabin.VehicleChannelId

/**
 * VHAL ADAS DMS state channel. Writes driver distraction/drowsiness state
 * to standardized VHAL properties so OEM ADAS systems can react.
 *
 * Uses DRIVER_DISTRACTION_STATE (0x060A) and
 * DRIVER_DROWSINESS_ATTENTION_STATE (0x060B).
 */
class AdasChannel(private val propertyManager: Any) : VehicleActionChannel {

    companion object {
        private const val TAG = "AdasChannel"
        const val DRIVER_DISTRACTION_STATE = 0x060A
        const val DRIVER_DROWSINESS_ATTENTION_STATE = 0x060B

        // ADAS state values (standard VHAL enum)
        const val STATE_NOT_DISTRACTED = 0
        const val STATE_DISTRACTED = 1

        fun isPropertyAvailable(propertyId: Int, availablePropertyIds: Set<Int>): Boolean {
            return propertyId in availablePropertyIds
        }
    }

    override val id = VehicleChannelId.ADAS_STATE
    override val available: Boolean
    private var hasDistractionProp = false
    private var hasDrowsinessProp = false

    init {
        val probeResult = probeAvailability()
        available = probeResult
        Log.d(TAG, "AdasChannel available=$available (distraction=$hasDistractionProp, drowsiness=$hasDrowsinessProp)")
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
            hasDistractionProp = isPropertyAvailable(DRIVER_DISTRACTION_STATE, ids)
            hasDrowsinessProp = isPropertyAvailable(DRIVER_DROWSINESS_ATTENTION_STATE, ids)
            hasDistractionProp || hasDrowsinessProp
        } catch (e: Exception) {
            Log.d(TAG, "Property probe failed", e)
            false
        }
    }

    override fun activate(level: EscalationLevel) {
        if (!available) return
        try {
            if (hasDistractionProp) {
                setIntProperty(DRIVER_DISTRACTION_STATE, STATE_DISTRACTED)
            }
            if (hasDrowsinessProp) {
                // Map escalation level to drowsiness severity (higher level = more severe)
                setIntProperty(DRIVER_DROWSINESS_ATTENTION_STATE, level.ordinalLevel)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Activate failed", e)
        }
    }

    override fun restore() {
        try {
            if (hasDistractionProp) {
                setIntProperty(DRIVER_DISTRACTION_STATE, STATE_NOT_DISTRACTED)
            }
            if (hasDrowsinessProp) {
                setIntProperty(DRIVER_DROWSINESS_ATTENTION_STATE, 0)
            }
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
