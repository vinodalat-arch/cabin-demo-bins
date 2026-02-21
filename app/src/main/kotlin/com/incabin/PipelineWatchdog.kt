package com.incabin

import android.os.Handler
import android.os.HandlerThread
import android.util.Log

/**
 * Monitors the inference pipeline for hangs. If no heartbeat is recorded
 * within [timeoutMs], invokes the [onTimeout] callback (typically restarts camera).
 *
 * Uses a HandlerThread to avoid blocking the main or inference threads.
 * Pure [isStalled] companion function is fully unit-testable.
 */
class PipelineWatchdog(
    private val timeoutMs: Long = Config.WATCHDOG_TIMEOUT_MS,
    private val checkIntervalMs: Long = Config.WATCHDOG_CHECK_INTERVAL_MS,
    private val onTimeout: () -> Unit
) {
    companion object {
        private const val TAG = "PipelineWatchdog"

        /**
         * Pure stall check. Returns true if pipeline is stalled.
         * Not stalled if never started (lastHeartbeatMs == 0).
         */
        fun isStalled(lastHeartbeatMs: Long, nowMs: Long, timeoutMs: Long): Boolean {
            if (lastHeartbeatMs == 0L) return false
            return (nowMs - lastHeartbeatMs) > timeoutMs
        }
    }

    @Volatile
    private var lastHeartbeatMs: Long = 0L

    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null

    private val checkRunnable = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            if (isStalled(lastHeartbeatMs, now, timeoutMs)) {
                val staleDurationMs = now - lastHeartbeatMs
                Log.e(TAG, "Pipeline stalled for ${staleDurationMs}ms — triggering restart")
                CrashLog.error(TAG, "Pipeline stalled for ${staleDurationMs}ms")
                lastHeartbeatMs = now  // Reset to avoid repeated triggers
                try {
                    onTimeout()
                } catch (e: Exception) {
                    Log.e(TAG, "Watchdog onTimeout callback failed", e)
                    CrashLog.logException(TAG, "Watchdog callback failed", e)
                }
            }
            handler?.postDelayed(this, checkIntervalMs)
        }
    }

    /** Record a heartbeat from the inference pipeline. Call at end of processFrame(). */
    fun recordHeartbeat() {
        lastHeartbeatMs = System.currentTimeMillis()
    }

    /** Start the watchdog monitor. */
    fun start() {
        val thread = HandlerThread("PipelineWatchdog").also { it.start() }
        handlerThread = thread
        handler = Handler(thread.looper)
        handler?.postDelayed(checkRunnable, checkIntervalMs)
        Log.i(TAG, "Watchdog started (timeout=${timeoutMs}ms, interval=${checkIntervalMs}ms)")
    }

    /** Stop the watchdog and clean up. */
    fun stop() {
        handler?.removeCallbacksAndMessages(null)
        handlerThread?.quitSafely()
        handlerThread = null
        handler = null
        lastHeartbeatMs = 0L
        Log.i(TAG, "Watchdog stopped")
    }
}
