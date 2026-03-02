package com.incabin

import android.util.Log

/**
 * Occupancy-based HVAC climate controller.
 *
 * Adjusts cabin temperature based on passenger count — more people generate
 * more body heat, so the HVAC compensates by lowering the set point.
 *
 * This is a comfort feature that runs continuously during monitoring,
 * separate from the danger escalation system.
 *
 * Algorithm:
 *   additional = max(0, passengerCount - 1)
 *   adjustment = min(additional * OFFSET_PER_PERSON, MAX_ADJUSTMENT)
 *   target = clamp(base - adjustment, MIN_TEMP, MAX_TEMP)
 *
 * Smoothing:
 *   - Debounce: only adjust after count is stable for DEBOUNCE_FRAMES consecutive frames
 *   - Ramp: step RAMP_STEP_C per update cycle (no instant jumps)
 *   - On count=0: revert to base temperature
 */
class ClimateController(private val propertyManager: Any) {

    companion object {
        private const val TAG = "ClimateController"

        /**
         * Pure function: compute target temperature for a given occupancy.
         * Returns base temp when count <= 1, reduces by 0.5°C per additional occupant up to max.
         */
        fun computeTargetTemp(baseTempC: Float, passengerCount: Int): Float {
            val additional = maxOf(0, passengerCount - 1)
            val adjustment = minOf(
                additional * Config.CLIMATE_OFFSET_PER_PERSON_C,
                Config.CLIMATE_MAX_ADJUSTMENT_C
            )
            return (baseTempC - adjustment).coerceIn(Config.CLIMATE_MIN_TEMP_C, Config.CLIMATE_MAX_TEMP_C)
        }

        /**
         * Pure function: should we adjust temperature?
         * True when count has been stable for debounceThreshold frames and differs from what
         * we're currently targeting.
         */
        fun shouldAdjust(count: Int, previousCount: Int, stableFrames: Int, debounceThreshold: Int): Boolean {
            if (stableFrames < debounceThreshold) return false
            return count != previousCount
        }

        /**
         * Pure function: compute the next temperature step toward the target.
         * Moves by at most stepSize, never overshoots.
         */
        fun rampStep(currentTemp: Float, targetTemp: Float, stepSize: Float): Float {
            val diff = targetTemp - currentTemp
            if (diff == 0f) return currentTemp
            return if (kotlin.math.abs(diff) <= stepSize) {
                targetTemp
            } else {
                currentTemp + if (diff > 0) stepSize else -stepSize
            }
        }
    }

    val available: Boolean
    private var currentTemp: Float = Config.HVAC_BASE_TEMP_C
    private var targetTemp: Float = Config.HVAC_BASE_TEMP_C
    private var lastStableCount: Int = -1
    private var stableFrames: Int = 0
    private var lastCount: Int = -1

    init {
        available = probeAndReadBase()
        if (available) {
            Log.i(TAG, "ClimateController available, base temp=${Config.HVAC_BASE_TEMP_C}°C")
        } else {
            Log.i(TAG, "ClimateController not available (HVAC_TEMPERATURE_SET property missing)")
        }
    }

    /**
     * Probe HVAC_TEMPERATURE_SET availability and read current value as base.
     */
    private fun probeAndReadBase(): Boolean {
        return try {
            val getListMethod = propertyManager.javaClass.getMethod("getPropertyList")
            val propertyList = getListMethod.invoke(propertyManager) as? List<*> ?: return false
            val ids = propertyList.mapNotNull { config ->
                try {
                    val propIdField = config!!.javaClass.getField("propertyId")
                    propIdField.getInt(config)
                } catch (_: Exception) { null }
            }.toSet()

            if (Config.HVAC_TEMPERATURE_SET_PROPERTY_ID !in ids) return false

            // Read current temperature as base
            val getPropertyMethod = propertyManager.javaClass.getMethod(
                "getProperty", Class::class.java, Int::class.java, Int::class.java
            )
            val result = getPropertyMethod.invoke(
                propertyManager, java.lang.Float::class.java, Config.HVAC_TEMPERATURE_SET_PROPERTY_ID, 0
            )
            if (result != null) {
                val getValueMethod = result.javaClass.getMethod("getValue")
                val temp = (getValueMethod.invoke(result) as? Number)?.toFloat()
                if (temp != null) {
                    Config.HVAC_BASE_TEMP_C = temp
                    currentTemp = temp
                    targetTemp = temp
                    Log.i(TAG, "Read HVAC base temperature: ${temp}°C")
                }
            }
            true
        } catch (e: Exception) {
            Log.d(TAG, "HVAC property probe failed", e)
            false
        }
    }

    /**
     * Called once per frame with current passenger count.
     * Handles debounce, target computation, ramping, and VHAL write.
     */
    fun update(passengerCount: Int) {
        if (!available) return
        if (!Config.ENABLE_AUTO_CLIMATE) return

        // Track count stability
        if (passengerCount == lastCount) {
            stableFrames++
        } else {
            stableFrames = 1
            lastCount = passengerCount
        }

        // Debounce: wait for stable count
        if (stableFrames < Config.CLIMATE_DEBOUNCE_FRAMES) return

        // Compute new target (0 occupants → revert to base)
        val newTarget = computeTargetTemp(Config.HVAC_BASE_TEMP_C, passengerCount)

        if (newTarget != targetTemp) {
            targetTemp = newTarget
            lastStableCount = passengerCount
            Log.d(TAG, "Target temp updated: ${targetTemp}°C (occupants=$passengerCount)")
        }

        // Ramp toward target
        val nextTemp = rampStep(currentTemp, targetTemp, Config.CLIMATE_RAMP_STEP_C)
        if (nextTemp != currentTemp) {
            currentTemp = nextTemp
            writeTemperature(currentTemp)
        }
    }

    /**
     * Restore base temperature to VHAL (called on monitoring stop or toggle off).
     */
    fun restore() {
        if (!available) return
        currentTemp = Config.HVAC_BASE_TEMP_C
        targetTemp = Config.HVAC_BASE_TEMP_C
        lastStableCount = -1
        stableFrames = 0
        lastCount = -1
        writeTemperature(Config.HVAC_BASE_TEMP_C)
        Log.i(TAG, "Restored base temperature: ${Config.HVAC_BASE_TEMP_C}°C")
    }

    fun close() {
        restore()
    }

    private fun writeTemperature(tempC: Float) {
        try {
            val setMethod = propertyManager.javaClass.getMethod(
                "setFloatProperty", Int::class.java, Int::class.java, Float::class.java
            )
            setMethod.invoke(propertyManager, Config.HVAC_TEMPERATURE_SET_PROPERTY_ID, 0, tempC)
            Log.d(TAG, "HVAC temperature set to ${tempC}°C")
        } catch (e: Exception) {
            Log.w(TAG, "writeTemperature($tempC) failed", e)
        }
    }
}
