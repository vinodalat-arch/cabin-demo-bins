package com.incabin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for DrowsinessWakeController pure companion and instance behavior.
 * No Android dependencies — fully unit-testable.
 */
class DrowsinessWakeControllerTest {

    @Before
    fun setUp() {
        Config.ENABLE_SEAT_MASSAGE = true
    }

    // -------------------------------------------------------------------------
    // shouldTrigger (pure companion)
    // -------------------------------------------------------------------------

    @Test
    fun shouldTrigger_risingEdge_returnsTrue() {
        assertTrue(DrowsinessWakeController.shouldTrigger(wasDrowsy = false, isDrowsy = true))
    }

    @Test
    fun shouldTrigger_alreadyDrowsy_returnsFalse() {
        assertFalse(DrowsinessWakeController.shouldTrigger(wasDrowsy = true, isDrowsy = true))
    }

    @Test
    fun shouldTrigger_clearToClear_returnsFalse() {
        assertFalse(DrowsinessWakeController.shouldTrigger(wasDrowsy = false, isDrowsy = false))
    }

    @Test
    fun shouldTrigger_drowsyToClear_returnsFalse() {
        assertFalse(DrowsinessWakeController.shouldTrigger(wasDrowsy = true, isDrowsy = false))
    }

    // -------------------------------------------------------------------------
    // Instance behavior (update)
    // -------------------------------------------------------------------------

    @Test
    fun update_risingEdge_eyesClosed_returnsTrue() {
        val controller = DrowsinessWakeController()
        assertTrue(controller.update(eyesClosed = true, yawning = false))
    }

    @Test
    fun update_risingEdge_yawning_returnsTrue() {
        val controller = DrowsinessWakeController()
        assertTrue(controller.update(eyesClosed = false, yawning = true))
    }

    @Test
    fun update_sustainedDrowsy_returnsFalseAfterFirst() {
        val controller = DrowsinessWakeController()
        assertTrue(controller.update(eyesClosed = true, yawning = false))  // first
        assertFalse(controller.update(eyesClosed = true, yawning = false)) // sustained
    }

    @Test
    fun update_clearThenDrowsyAgain_firesAgain() {
        val controller = DrowsinessWakeController()
        assertTrue(controller.update(eyesClosed = true, yawning = false))
        assertFalse(controller.update(eyesClosed = false, yawning = false)) // clear
        assertTrue(controller.update(eyesClosed = true, yawning = false))   // fires again
    }

    @Test
    fun update_featureDisabled_neverFires() {
        Config.ENABLE_SEAT_MASSAGE = false
        val controller = DrowsinessWakeController()
        assertFalse(controller.update(eyesClosed = true, yawning = true))
    }

    @Test
    fun reset_clearsState() {
        val controller = DrowsinessWakeController()
        assertTrue(controller.update(eyesClosed = true, yawning = false))
        controller.reset()
        assertTrue(controller.update(eyesClosed = true, yawning = false)) // fires again after reset
    }

    @Test
    fun update_noDrowsiness_returnsFalse() {
        val controller = DrowsinessWakeController()
        assertFalse(controller.update(eyesClosed = false, yawning = false))
    }
}
