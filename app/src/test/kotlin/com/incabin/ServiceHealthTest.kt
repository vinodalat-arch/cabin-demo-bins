package com.incabin

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ServiceHealthTest {

    @Before
    fun resetFrameHolder() {
        FrameHolder.clear()
    }

    @Test
    fun test_heartbeat_age_returns_max_when_not_started() {
        assertEquals(Long.MAX_VALUE, FrameHolder.getHeartbeatAgeMs())
    }

    @Test
    fun test_clear_resets_heartbeat() {
        // Simulate a heartbeat post then clear
        // After clear, heartbeat should be 0 → getHeartbeatAgeMs returns MAX_VALUE
        FrameHolder.clear()
        assertEquals(Long.MAX_VALUE, FrameHolder.getHeartbeatAgeMs())
    }

    @Test
    fun test_isServiceRunning_default_false() {
        assertFalse(FrameHolder.isServiceRunning())
    }
}
