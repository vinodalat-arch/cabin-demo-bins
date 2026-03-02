package com.incabin

enum class Seat { DRIVER, FRONT_PASSENGER, REAR_LEFT, REAR_CENTER, REAR_RIGHT }

data class SeatState(val occupied: Boolean, val state: String = "Vacant")

data class SeatMap(
    val driver: SeatState = SeatState(false, "Vacant"),
    val frontPassenger: SeatState = SeatState(false, "Vacant"),
    val rearLeft: SeatState = SeatState(false, "Vacant"),
    val rearCenter: SeatState = SeatState(false, "Vacant"),
    val rearRight: SeatState = SeatState(false, "Vacant")
)
