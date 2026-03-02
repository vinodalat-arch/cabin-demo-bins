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
                rearCenter = SeatState(false, "Vacant"),
                rearRight = SeatState(false, "Vacant")
            )
        }

        // Classify each non-driver by side and row
        data class Candidate(
            val person: OverlayPerson,
            val side: String,   // "left" or "right"
            val row: String,    // "front" or "rear"
            val area: Float,
            val centerX: Float
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
            Candidate(p, side, row, area, centerX)
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

        // Sort remaining by centerX to handle 3-rear-passenger case
        val rearCandidates = remaining.sortedBy { it.centerX }

        var rearLeftState = SeatState(false, "Vacant")
        var rearCenterState = SeatState(false, "Vacant")
        var rearRightState = SeatState(false, "Vacant")

        if (rearCandidates.size >= 3) {
            // 3+ rear: leftmost → RL, rightmost → RR, middle → RC
            val rl = rearCandidates.first()
            val rr = rearCandidates.last()
            val rc = rearCandidates[1]
            rearLeftState = SeatState(true, if (rl.person.badPosture) "Sleeping" else "Upright")
            rearCenterState = SeatState(true, if (rc.person.badPosture) "Sleeping" else "Upright")
            rearRightState = SeatState(true, if (rr.person.badPosture) "Sleeping" else "Upright")
        } else if (rearCandidates.size == 2) {
            // 2 rear: left half → RL, right half → RR
            val left = rearCandidates.filter { it.side == "left" }.maxByOrNull { it.area }
            val right = rearCandidates.filter { it.side == "right" }.maxByOrNull { it.area }
            if (left != null) {
                rearLeftState = SeatState(true, if (left.person.badPosture) "Sleeping" else "Upright")
            }
            if (right != null) {
                rearRightState = SeatState(true, if (right.person.badPosture) "Sleeping" else "Upright")
            }
            // If both on same side, assign largest to that side, second to opposite
            if (left == null && right != null) {
                // Both on right — second one goes to RL
                val second = rearCandidates.firstOrNull { it != right }
                if (second != null) {
                    rearLeftState = SeatState(true, if (second.person.badPosture) "Sleeping" else "Upright")
                }
            } else if (right == null && left != null) {
                // Both on left — second one goes to RR
                val second = rearCandidates.firstOrNull { it != left }
                if (second != null) {
                    rearRightState = SeatState(true, if (second.person.badPosture) "Sleeping" else "Upright")
                }
            }
        } else if (rearCandidates.size == 1) {
            val c = rearCandidates[0]
            val state = SeatState(true, if (c.person.badPosture) "Sleeping" else "Upright")
            if (c.side == "left") rearLeftState = state else rearRightState = state
        }

        return SeatMap(
            driver = driverSeat,
            frontPassenger = frontPassengerState,
            rearLeft = rearLeftState,
            rearCenter = rearCenterState,
            rearRight = rearRightState
        )
    }
}
