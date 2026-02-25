package com.incabin

import android.content.Context
import android.util.Log

/**
 * Registry for vehicle action channels. Connects to Android Car API at init,
 * probes available VHAL properties, and dispatches channel activations.
 *
 * Graceful degradation: if Car API is unavailable (generic Android, ClassNotFoundException),
 * all channels report unavailable and dispatch is a no-op.
 */
class VehicleChannelManager(context: Context) {

    companion object {
        private const val TAG = "VehicleChannelMgr"

        // Driving state constants (mirrors android.car.drivingstate values)
        const val DRIVING_STATE_PARKED = 0
        const val DRIVING_STATE_IDLING = 1
        const val DRIVING_STATE_MOVING = 2

        /**
         * Pure function: should we suppress vehicle channel dispatch for this driving state?
         * Suppresses L1 (nudge) when parked — no need to alert a parked driver with vehicle actions.
         * All other levels and states are allowed.
         */
        fun shouldSuppressForDrivingState(drivingState: Int, level: EscalationLevel): Boolean {
            return drivingState == DRIVING_STATE_PARKED && level == EscalationLevel.L1_NUDGE
        }
    }

    private val channels = mutableListOf<VehicleActionChannel>()
    private var carConnected = false
    private var drivingState = DRIVING_STATE_MOVING // default to moving (safest assumption)

    init {
        connectToCarApi(context)
    }

    /**
     * Attempt to connect to the Android Car API and probe available properties.
     * On generic Android, Car class won't exist — catches ClassNotFoundException.
     */
    private fun connectToCarApi(context: Context) {
        try {
            val carClass = Class.forName("android.car.Car")
            val createMethod = carClass.getMethod("createCar", Context::class.java)
            val car = createMethod.invoke(null, context) ?: run {
                Log.i(TAG, "Car.createCar() returned null — not an automotive device")
                return
            }

            // Get CarPropertyManager
            val getManagerMethod = carClass.getMethod("getCarManager", String::class.java)
            val propertyManagerField = carClass.getField("PROPERTY_SERVICE")
            val propertyServiceName = propertyManagerField.get(null) as String
            val propertyManager = getManagerMethod.invoke(car, propertyServiceName)

            if (propertyManager != null) {
                carConnected = true
                Log.i(TAG, "Connected to Car API")
                registerChannels(propertyManager)
                probeDrivingState(car, getManagerMethod, carClass)
            }
        } catch (e: ClassNotFoundException) {
            Log.i(TAG, "Car API not available (not an automotive build) — vehicle channels disabled")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to connect to Car API — vehicle channels disabled", e)
        }
    }

    /**
     * Register all channel implementations with the property manager.
     * Each channel probes its own property availability.
     */
    private fun registerChannels(propertyManager: Any) {
        try {
            channels.add(com.incabin.channels.CabinLightChannel(propertyManager))
        } catch (e: Exception) { Log.w(TAG, "CabinLightChannel init failed", e) }

        try {
            channels.add(com.incabin.channels.SeatHapticChannel(propertyManager))
        } catch (e: Exception) { Log.w(TAG, "SeatHapticChannel init failed", e) }

        try {
            channels.add(com.incabin.channels.SeatThermalChannel(propertyManager))
        } catch (e: Exception) { Log.w(TAG, "SeatThermalChannel init failed", e) }

        try {
            channels.add(com.incabin.channels.SteeringHeatChannel(propertyManager))
        } catch (e: Exception) { Log.w(TAG, "SteeringHeatChannel init failed", e) }

        try {
            channels.add(com.incabin.channels.WindowChannel(propertyManager))
        } catch (e: Exception) { Log.w(TAG, "WindowChannel init failed", e) }

        try {
            channels.add(com.incabin.channels.AdasChannel(propertyManager))
        } catch (e: Exception) { Log.w(TAG, "AdasChannel init failed", e) }

        val availableCount = channels.count { it.available }
        Log.i(TAG, "Registered ${channels.size} channels, $availableCount available")
    }

    /**
     * Probe current driving state for speed-gating.
     */
    private fun probeDrivingState(car: Any, getManagerMethod: java.lang.reflect.Method, carClass: Class<*>) {
        try {
            val drivingStateField = carClass.getField("CAR_DRIVING_STATE_SERVICE")
            val drivingServiceName = drivingStateField.get(null) as String
            val drivingStateManager = getManagerMethod.invoke(car, drivingServiceName) ?: return

            val getCurrentMethod = drivingStateManager.javaClass.getMethod("getCurrentCarDrivingState")
            val stateEvent = getCurrentMethod.invoke(drivingStateManager) ?: return

            val stateField = stateEvent.javaClass.getField("eventValue")
            drivingState = stateField.getInt(stateEvent)
            Log.i(TAG, "Driving state: $drivingState")
        } catch (e: Exception) {
            Log.w(TAG, "Could not probe driving state, defaulting to MOVING", e)
        }
    }

    /**
     * Dispatch activation to all available channels in the requested set.
     * Each channel activation is wrapped in try-catch for isolation.
     */
    fun dispatch(channelIds: Set<VehicleChannelId>, level: EscalationLevel) {
        if (!carConnected) return

        if (shouldSuppressForDrivingState(drivingState, level)) {
            Log.d(TAG, "Suppressed $level dispatch (parked)")
            return
        }

        for (channel in channels) {
            if (channel.id in channelIds && channel.available) {
                try {
                    channel.activate(level)
                    Log.d(TAG, "Activated ${channel.id} at $level")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to activate ${channel.id}", e)
                }
            }
        }
    }

    /**
     * Restore all channels to their saved state (all-clear).
     */
    fun restoreAll() {
        for (channel in channels) {
            if (channel.available) {
                try {
                    channel.restore()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to restore ${channel.id}", e)
                }
            }
        }
    }

    /**
     * Close all channels and release Car API resources.
     */
    fun close() {
        for (channel in channels) {
            try {
                channel.close()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to close ${channel.id}", e)
            }
        }
        channels.clear()
        carConnected = false
        Log.i(TAG, "VehicleChannelManager closed")
    }
}
