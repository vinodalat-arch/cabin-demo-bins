package com.incabin

/**
 * When a passenger falls asleep, warms their HVAC zone and activates quiet mode.
 * Announces when they wake up.
 * All decision logic in pure companion functions — no Android dependencies.
 */
class NapModeController {

    private var sleepingSeats: Set<Seat> = emptySet()
    private var previousSleepingSeats: Set<Seat> = emptySet()

    /**
     * Called once per frame. Returns list of CabinEvents (wake announcements), may be empty.
     */
    fun update(seatMap: SeatMap?, isJapanese: Boolean): List<CabinEvent> {
        if (!Config.ENABLE_NAP_MODE) return emptyList()
        if (seatMap == null) return emptyList()

        previousSleepingSeats = sleepingSeats
        sleepingSeats = findSleepingPassengers(seatMap)

        val events = mutableListOf<CabinEvent>()

        // Wake-up announcements for passengers who just woke
        val wakers = newWakers(previousSleepingSeats, sleepingSeats)
        for (seat in wakers) {
            events.add(CabinEvent(CabinEvent.INFO, formatWakeMessage(seat, isJapanese)))
        }

        return events
    }

    fun getSleepingSeats(): Set<Seat> = sleepingSeats

    fun hasAnySleeping(): Boolean = sleepingSeats.isNotEmpty()

    fun reset() {
        sleepingSeats = emptySet()
        previousSleepingSeats = emptySet()
    }

    companion object {
        fun findSleepingPassengers(seatMap: SeatMap): Set<Seat> {
            val result = mutableSetOf<Seat>()
            if (seatMap.frontPassenger.occupied && seatMap.frontPassenger.state == "Sleeping")
                result.add(Seat.FRONT_PASSENGER)
            if (seatMap.rearLeft.occupied && seatMap.rearLeft.state == "Sleeping")
                result.add(Seat.REAR_LEFT)
            if (seatMap.rearCenter.occupied && seatMap.rearCenter.state == "Sleeping")
                result.add(Seat.REAR_CENTER)
            if (seatMap.rearRight.occupied && seatMap.rearRight.state == "Sleeping")
                result.add(Seat.REAR_RIGHT)
            return result
        }

        fun newSleepers(previous: Set<Seat>, current: Set<Seat>): Set<Seat> {
            return current - previous
        }

        fun newWakers(previous: Set<Seat>, current: Set<Seat>): Set<Seat> {
            return previous - current
        }

        fun seatDisplayName(seat: Seat, isJapanese: Boolean): String {
            return if (isJapanese) {
                when (seat) {
                    Seat.FRONT_PASSENGER -> "助手席"
                    Seat.REAR_LEFT -> "後部左席"
                    Seat.REAR_CENTER -> "後部中央席"
                    Seat.REAR_RIGHT -> "後部右席"
                    Seat.DRIVER -> "運転席"
                }
            } else {
                when (seat) {
                    Seat.FRONT_PASSENGER -> "Front passenger"
                    Seat.REAR_LEFT -> "Rear left"
                    Seat.REAR_CENTER -> "Rear center"
                    Seat.REAR_RIGHT -> "Rear right"
                    Seat.DRIVER -> "Driver"
                }
            }
        }

        fun formatWakeMessage(seat: Seat, isJapanese: Boolean): String {
            val name = seatDisplayName(seat, isJapanese)
            return if (isJapanese) "おかえりなさい、${name}の方。"
            else "Welcome back, ${name.lowercase()}."
        }
    }
}
