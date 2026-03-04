package com.incabin.channels

import android.util.Log
import com.incabin.Config
import com.incabin.EscalationLevel
import com.incabin.VehicleActionChannel
import com.incabin.VehicleChannelId

/**
 * VHAL window channel. Slide open driver window 10% for 30s at L5 only.
 *
 * Uses WINDOW_MOVE (0x0BC0) — positive values open the window.
 * Only activated at L5_EMERGENCY level.
 */
class WindowChannel(private val propertyManager: Any) : VehicleActionChannel {

    companion object {
        private const val TAG = "WindowChannel"
        const val WINDOW_MOVE = 0x0BC0

        fun isPropertyAvailable(propertyId: Int, availablePropertyIds: Set<Int>): Boolean {
            return propertyId in availablePropertyIds
        }
    }

    override val id = VehicleChannelId.WINDOW
    override val available: Boolean

    init {
        available = probeAvailability()
        Log.d(TAG, "WindowChannel available=$available")
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
            isPropertyAvailable(WINDOW_MOVE, ids)
        } catch (e: Exception) {
            Log.d(TAG, "Property probe failed", e)
            false
        }
    }

    override fun activate(level: EscalationLevel) {
        if (!available) return
        // Only slide window at emergency level
        if (level != EscalationLevel.L5_EMERGENCY) return

        try {
            // Slide open ~10% (small positive move value)
            setIntProperty(WINDOW_MOVE, 1)
            Thread({
                try {
                    Thread.sleep(Config.VEHICLE_WINDOW_SLIDE_MS)
                    restore()
                } catch (_: InterruptedException) {}
            }, "Window-Slide").start()
        } catch (e: Exception) {
            Log.w(TAG, "Activate failed", e)
        }
    }

    override fun restore() {
        try {
            // Stop window movement
            setIntProperty(WINDOW_MOVE, 0)
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
