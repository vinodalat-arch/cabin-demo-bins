package com.incabin

import org.junit.Assert.*
import org.junit.Test

class MemoryPolicyTest {

    @Test
    fun test_level_below_threshold_no_action() {
        val action = MemoryPolicy.decideAction(5)
        assertFalse(action.disablePreview)
        assertFalse(action.clearBuffers)
        assertFalse(action.requestGc)
    }

    @Test
    fun test_level_running_low_disables_preview_and_clears_buffers() {
        val action = MemoryPolicy.decideAction(10)
        assertTrue(action.disablePreview)
        assertTrue(action.clearBuffers)
        assertFalse(action.requestGc)
    }

    @Test
    fun test_level_running_critical_full_degradation() {
        val action = MemoryPolicy.decideAction(15)
        assertTrue(action.disablePreview)
        assertTrue(action.clearBuffers)
        assertTrue(action.requestGc)
    }

    @Test
    fun test_level_high_full_degradation() {
        val action = MemoryPolicy.decideAction(80)
        assertTrue(action.disablePreview)
        assertTrue(action.clearBuffers)
        assertTrue(action.requestGc)
    }

    @Test
    fun test_level_zero_no_action() {
        val action = MemoryPolicy.decideAction(0)
        assertFalse(action.disablePreview)
        assertFalse(action.clearBuffers)
        assertFalse(action.requestGc)
    }
}
