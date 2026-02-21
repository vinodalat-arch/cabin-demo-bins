package com.incabin

/**
 * Decides memory-saving actions based on Android onTrimMemory level.
 * Pure function — no Android dependencies, fully unit-testable.
 */
object MemoryPolicy {

    data class Action(
        val disablePreview: Boolean = false,
        val clearBuffers: Boolean = false,
        val requestGc: Boolean = false
    )

    /** Threshold for RUNNING_LOW — disable preview + clear buffers */
    private const val LEVEL_LOW = 10

    /** Threshold for RUNNING_CRITICAL — also request GC */
    private const val LEVEL_CRITICAL = 15

    /** Decide what memory-saving actions to take for the given trim level. */
    fun decideAction(trimLevel: Int): Action {
        return when {
            trimLevel >= LEVEL_CRITICAL -> Action(
                disablePreview = true,
                clearBuffers = true,
                requestGc = true
            )
            trimLevel >= LEVEL_LOW -> Action(
                disablePreview = true,
                clearBuffers = true,
                requestGc = false
            )
            else -> Action()
        }
    }
}
