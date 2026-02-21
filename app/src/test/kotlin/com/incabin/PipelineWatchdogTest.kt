package com.incabin

import org.junit.Assert.*
import org.junit.Test

class PipelineWatchdogTest {

    private val timeoutMs = 30_000L

    @Test
    fun test_isStalled_false_when_not_started() {
        // lastHeartbeat=0 means watchdog hasn't received any heartbeat yet
        assertFalse(PipelineWatchdog.isStalled(0L, 100_000L, timeoutMs))
    }

    @Test
    fun test_isStalled_false_within_timeout() {
        val now = 50_000L
        val lastHeartbeat = 40_000L  // 10s ago, within 30s timeout
        assertFalse(PipelineWatchdog.isStalled(lastHeartbeat, now, timeoutMs))
    }

    @Test
    fun test_isStalled_true_beyond_timeout() {
        val now = 80_000L
        val lastHeartbeat = 10_000L  // 70s ago, well beyond 30s timeout
        assertTrue(PipelineWatchdog.isStalled(lastHeartbeat, now, timeoutMs))
    }

    @Test
    fun test_isStalled_false_at_exact_boundary() {
        val now = 60_000L
        val lastHeartbeat = 30_000L  // exactly 30s ago = timeout, not > timeout
        assertFalse(PipelineWatchdog.isStalled(lastHeartbeat, now, timeoutMs))
    }

    @Test
    fun test_isStalled_true_just_past_boundary() {
        val now = 60_001L
        val lastHeartbeat = 30_000L  // 30.001s ago, just past timeout
        assertTrue(PipelineWatchdog.isStalled(lastHeartbeat, now, timeoutMs))
    }

    @Test
    fun test_heartbeat_resets_stale_detection() {
        // Simulate: was stalled, then heartbeat recorded
        val oldHeartbeat = 10_000L
        val newHeartbeat = 75_000L
        val now = 80_000L

        // Before heartbeat: stalled
        assertTrue(PipelineWatchdog.isStalled(oldHeartbeat, now, timeoutMs))
        // After heartbeat: not stalled
        assertFalse(PipelineWatchdog.isStalled(newHeartbeat, now, timeoutMs))
    }
}
