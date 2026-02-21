package com.incabin

import org.junit.Assert.*
import org.junit.Test

class InferenceErrorTest {

    @Test
    fun test_shouldReinitialize_true_at_threshold() {
        assertTrue(InCabinService.shouldReinitialize(10, 10))
    }

    @Test
    fun test_shouldReinitialize_false_below_threshold() {
        assertFalse(InCabinService.shouldReinitialize(9, 10))
    }
}
