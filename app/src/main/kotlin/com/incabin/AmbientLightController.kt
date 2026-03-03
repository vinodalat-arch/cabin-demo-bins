package com.incabin

import android.util.Log

/**
 * Per-zone ambient light controller for IVI cabin.
 *
 * Two modes:
 * - **Safety mode** (always active when enabled): Maps risk level to color
 *   (red/amber/green) on occupied seat zones.
 * - **Comfort mode** (when low risk + driver profile loaded): Uses per-driver
 *   preferred ambient color.
 *
 * Probes vendor RGB property first, falls back to footwell light switch per area.
 * Graceful no-op when no light properties are available.
 */
class AmbientLightController(private val propertyManager: Any) {

    companion object {
        private const val TAG = "AmbientLight"

        // Hypothetical vendor RGB ambient light property (probe-first)
        const val VENDOR_AMBIENT_RGB = 0x15900200

        // Standard VHAL footwell light switch (zone on/off fallback)
        const val SEAT_FOOTWELL_LIGHTS_SWITCH = 0x0F4007

        // Standard VHAL area IDs for seat zones
        const val AREA_DRIVER_LEFT = 0x0001      // ROW1_LEFT
        const val AREA_FRONT_PAX_RIGHT = 0x0004  // ROW1_RIGHT
        const val AREA_REAR_LEFT = 0x0010         // ROW2_LEFT
        const val AREA_REAR_RIGHT = 0x0040        // ROW2_RIGHT

        // Safety mode colors (ARGB)
        private const val COLOR_RED = 0xFFE74C3C.toInt()
        private const val COLOR_AMBER = 0xFFF39C12.toInt()
        private const val COLOR_GREEN = 0xFF2ECC71.toInt()

        /**
         * Pure function: map risk level to safety color.
         */
        fun safetyColor(riskLevel: String): Int = when (riskLevel) {
            "high" -> COLOR_RED
            "medium" -> COLOR_AMBER
            else -> COLOR_GREEN
        }

        /**
         * Pure function: determine which seat areas are occupied.
         * Maps SeatMap occupancy to VHAL area IDs, using driver side to
         * assign driver seat (left or right).
         */
        fun occupiedAreaIds(seatMap: SeatMap, driverSide: String): Set<Int> {
            val areas = mutableSetOf<Int>()
            val driverArea = if (driverSide == "left") AREA_DRIVER_LEFT else AREA_FRONT_PAX_RIGHT
            val paxArea = if (driverSide == "left") AREA_FRONT_PAX_RIGHT else AREA_DRIVER_LEFT

            if (seatMap.driver.occupied) areas.add(driverArea)
            if (seatMap.frontPassenger.occupied) areas.add(paxArea)
            if (seatMap.rearLeft.occupied) areas.add(AREA_REAR_LEFT)
            if (seatMap.rearRight.occupied) areas.add(AREA_REAR_RIGHT)
            // REAR_CENTER maps to both rear zones for ambient lighting
            if (seatMap.rearCenter.occupied) {
                areas.add(AREA_REAR_LEFT)
                areas.add(AREA_REAR_RIGHT)
            }
            return areas
        }

        /**
         * Pure function: parse hex color string to ARGB int.
         * Supports "#RRGGBB" and "#AARRGGBB" formats.
         * Returns 0 on invalid input.
         */
        fun parseColorHex(hex: String): Int {
            if (hex.isEmpty() || !hex.startsWith("#")) return 0
            return try {
                val stripped = hex.removePrefix("#")
                when (stripped.length) {
                    6 -> (0xFF000000 or stripped.toLong(16)).toInt()
                    8 -> stripped.toLong(16).toInt()
                    else -> 0
                }
            } catch (_: NumberFormatException) {
                0
            }
        }
    }

    val available: Boolean
    private val hasRgb: Boolean
    private val hasFootwell: Boolean
    private val allZones = setOf(AREA_DRIVER_LEFT, AREA_FRONT_PAX_RIGHT, AREA_REAR_LEFT, AREA_REAR_RIGHT)

    init {
        val probe = probeAvailability()
        hasRgb = probe.first
        hasFootwell = probe.second
        available = hasRgb || hasFootwell
        Log.d(TAG, "AmbientLightController available=$available (rgb=$hasRgb, footwell=$hasFootwell)")
    }

    private fun probeAvailability(): Pair<Boolean, Boolean> {
        return try {
            val getListMethod = propertyManager.javaClass.getMethod("getPropertyList")
            val propertyList = getListMethod.invoke(propertyManager) as? List<*>
                ?: return Pair(false, false)
            val ids = propertyList.mapNotNull { config ->
                try {
                    val propIdField = config!!.javaClass.getField("propertyId")
                    propIdField.getInt(config)
                } catch (_: Exception) { null }
            }.toSet()
            Pair(VENDOR_AMBIENT_RGB in ids, SEAT_FOOTWELL_LIGHTS_SWITCH in ids)
        } catch (e: Exception) {
            Log.d(TAG, "Property probe failed", e)
            Pair(false, false)
        }
    }

    /**
     * Update ambient lights based on current result and seat map.
     * In safety mode: occupied zones get risk-colored light.
     * In comfort mode (low risk + driver profile): driver zone gets preferred color.
     * Unoccupied zones are turned off.
     */
    fun update(result: OutputResult, seatMap: SeatMap) {
        if (!available || !Config.ENABLE_AMBIENT_LIGHT) return

        val occupied = occupiedAreaIds(seatMap, Config.DRIVER_SEAT_SIDE)
        val safety = safetyColor(result.riskLevel)

        // Comfort mode: use driver's preferred color when risk is low
        val comfortColor = if (Config.ENABLE_AMBIENT_COMFORT &&
            result.riskLevel == "low" &&
            Config.CURRENT_DRIVER_AMBIENT_COLOR.isNotEmpty()
        ) {
            parseColorHex(Config.CURRENT_DRIVER_AMBIENT_COLOR)
        } else {
            0
        }

        for (zone in allZones) {
            if (zone in occupied) {
                val color = if (comfortColor != 0) comfortColor else safety
                setZoneColor(zone, color)
            } else {
                setZoneOff(zone)
            }
        }
    }

    /**
     * Flash all zones rapid red for emergency override.
     * Blocks for 5 × flash_ms cycle then restores.
     */
    fun flashEmergency() {
        if (!available) return
        Thread({
            try {
                for (i in 0 until 5) {
                    for (zone in allZones) setZoneColor(zone, COLOR_RED)
                    Thread.sleep(Config.CHILD_LEFT_BEHIND_CABIN_FLASH_MS)
                    for (zone in allZones) setZoneOff(zone)
                    Thread.sleep(Config.CHILD_LEFT_BEHIND_CABIN_FLASH_MS)
                }
            } catch (_: InterruptedException) {}
        }, "AmbientLight-Emergency").start()
    }

    private fun setZoneColor(areaId: Int, color: Int) {
        try {
            if (hasRgb) {
                setIntProperty(VENDOR_AMBIENT_RGB, color, areaId)
            } else if (hasFootwell) {
                setIntProperty(SEAT_FOOTWELL_LIGHTS_SWITCH, 1, areaId)
            }
        } catch (e: Exception) {
            Log.w(TAG, "setZoneColor(area=$areaId) failed", e)
        }
    }

    private fun setZoneOff(areaId: Int) {
        try {
            if (hasRgb) {
                setIntProperty(VENDOR_AMBIENT_RGB, 0, areaId)
            } else if (hasFootwell) {
                setIntProperty(SEAT_FOOTWELL_LIGHTS_SWITCH, 0, areaId)
            }
        } catch (e: Exception) {
            Log.w(TAG, "setZoneOff(area=$areaId) failed", e)
        }
    }

    private fun setIntProperty(propertyId: Int, value: Int, areaId: Int) {
        val setMethod = propertyManager.javaClass.getMethod(
            "setIntProperty", Int::class.java, Int::class.java, Int::class.java
        )
        setMethod.invoke(propertyManager, propertyId, areaId, value)
    }
}
