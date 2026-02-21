package com.incabin

import org.junit.Assert.*
import org.junit.Test

class BootReceiverTest {

    @Test
    fun test_shouldAutoStart_true_for_sa8155() {
        assertTrue(BootReceiver.shouldAutoStart(Platform.SA8155))
    }

    @Test
    fun test_shouldAutoStart_true_for_sa8255() {
        assertTrue(BootReceiver.shouldAutoStart(Platform.SA8255))
    }

    @Test
    fun test_shouldAutoStart_true_for_sa8295() {
        assertTrue(BootReceiver.shouldAutoStart(Platform.SA8295))
    }

    @Test
    fun test_shouldAutoStart_false_for_generic() {
        assertFalse(BootReceiver.shouldAutoStart(Platform.GENERIC))
    }
}
