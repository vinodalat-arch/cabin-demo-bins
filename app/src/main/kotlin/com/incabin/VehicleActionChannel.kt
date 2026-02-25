package com.incabin

/**
 * Abstract interface for a vehicle action channel (VHAL-backed or software).
 * Each channel probes its availability at init and can be activated/restored.
 *
 * Implementations are expected to:
 * 1. Check property availability in constructor (set [available] accordingly)
 * 2. Save current property state before first activation
 * 3. Pulse (activate → delay → restore) within [activate]
 * 4. Restore saved state in [restore]
 * 5. Release resources in [close]
 */
interface VehicleActionChannel {
    /** Channel identifier. */
    val id: VehicleChannelId

    /** True if the underlying VHAL property is present on this vehicle. */
    val available: Boolean

    /** Activate the channel at the given escalation level. */
    fun activate(level: EscalationLevel)

    /** Restore the channel to its pre-activation (saved) state. */
    fun restore()

    /** Release resources (CarPropertyManager references, etc.). */
    fun close()
}
