package com.incabin

/**
 * Pure seat assignment logic: maps YOLO bbox data to per-seat occupancy.
 * No Android dependencies — fully unit-testable.
 */
object SeatAssigner {

    /**
     * Derive the driver's state string from an OutputResult.
     * Priority order matches alert severity: Sleeping > Phone > Distracted > Eating > Yawning > Upright.
     */
    fun deriveDriverState(result: OutputResult): String {
        return when {
            !result.driverDetected -> "Vacant"
            result.driverEyesClosed -> "Sleeping"
            result.driverUsingPhone -> "Phone"
            result.driverDistracted -> "Distracted"
            result.driverEatingDrinking -> "Eating"
            result.driverYawning -> "Yawning"
            else -> "Upright"
        }
    }

    /**
     * Assign detected persons to seats based on bbox heuristics.
     *
     * @param persons List of detected persons from pose analysis
     * @param driverSeatSide "left" or "right" — which side of the frame the driver sits on
     * @param frameWidth Frame width in pixels (default 1280)
     * @param driverState Pre-derived driver state string
     * @return SeatMap with per-seat occupancy and state
     */
    fun assign(
        persons: List<OverlayPerson>,
        driverSeatSide: String,
        frameWidth: Int = Config.CAMERA_WIDTH,
        driverState: String = "Upright"
    ): SeatMap {
        val midX = frameWidth / 2f

        // 1. Find the driver (tagged by C++ pose analysis)
        val driver = persons.firstOrNull { it.isDriver }
        val driverArea = driver?.let { (it.x2 - it.x1) * (it.y2 - it.y1) } ?: 0f

        val driverSeat = if (driver != null) {
            SeatState(occupied = true, state = driverState)
        } else {
            SeatState(occupied = false, state = "Vacant")
        }

        // 2. Process non-driver persons
        val nonDrivers = persons.filter { !it.isDriver }
        if (nonDrivers.isEmpty()) {
            return SeatMap(
                driver = driverSeat,
                frontPassenger = SeatState(false, "Vacant"),
                rearLeft = SeatState(false, "Vacant"),
                rearRight = SeatState(false, "Vacant")
            )
        }

        // Classify each non-driver by side and row
        data class Candidate(
            val person: OverlayPerson,
            val side: String,   // "left" or "right"
            val row: String,    // "front" or "rear"
            val area: Float
        )

        val candidates = nonDrivers.map { p ->
            val centerX = (p.x1 + p.x2) / 2f
            val area = (p.x2 - p.x1) * (p.y2 - p.y1)
            val side = if (centerX < midX) "left" else "right"
            val row = if (driverArea > 0f && area >= Config.SEAT_FRONT_ROW_AREA_RATIO * driverArea) {
                "front"
            } else {
                "rear"
            }
            Candidate(p, side, row, area)
        }

        val passengerSide = if (driverSeatSide == "left") "right" else "left"

        // 3. Front passenger: largest front-row person on the passenger side
        val frontPassengerCandidate = candidates
            .filter { it.row == "front" && it.side == passengerSide }
            .maxByOrNull { it.area }

        val frontPassengerState = if (frontPassengerCandidate != null) {
            val state = if (frontPassengerCandidate.person.badPosture) "Sleeping" else "Upright"
            SeatState(occupied = true, state = state)
        } else {
            SeatState(occupied = false, state = "Vacant")
        }

        // 4. Rear seats: remaining persons not assigned to front passenger
        val remaining = candidates.filter { it != frontPassengerCandidate }

        // Rear left: largest person on left side in rear row (or any unassigned on left)
        val rearLeftCandidate = remaining
            .filter { it.side == "left" }
            .maxByOrNull { it.area }

        val rearLeftState = if (rearLeftCandidate != null) {
            val state = if (rearLeftCandidate.person.badPosture) "Sleeping" else "Upright"
            SeatState(occupied = true, state = state)
        } else {
            SeatState(occupied = false, state = "Vacant")
        }

        // Rear right: largest person on right side in rear row (or any unassigned on right)
        val rearRightCandidate = remaining
            .filter { it.side == "right" && it != rearLeftCandidate }
            .maxByOrNull { it.area }

        val rearRightState = if (rearRightCandidate != null) {
            val state = if (rearRightCandidate.person.badPosture) "Sleeping" else "Upright"
            SeatState(occupied = true, state = state)
        } else {
            SeatState(occupied = false, state = "Vacant")
        }

        return SeatMap(
            driver = driverSeat,
            frontPassenger = frontPassengerState,
            rearLeft = rearLeftState,
            rearRight = rearRightState
        )
    }
}
