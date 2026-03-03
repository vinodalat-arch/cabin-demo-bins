package com.incabin

import com.incabin.channels.SeatMassageChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for SeatMassageChannel pure companion functions.
 * No Android dependencies — fully unit-testable.
 */
class SeatMassageChannelTest {

    @Test
    fun detectMode_massagePropertiesAvailable_returnsMassage() {
        val ids = setOf(
            SeatMassageChannel.SEAT_MASSAGE_ENABLED,
            SeatMassageChannel.SEAT_MASSAGE_INTENSITY
        )
        assertEquals("massage", SeatMassageChannel.detectMode(ids))
    }

    @Test
    fun detectMode_onlyLumbarAvailable_returnsLumbar() {
        val ids = setOf(SeatMassageChannel.SEAT_LUMBAR_FORE_AFT_MOVE)
        assertEquals("lumbar", SeatMassageChannel.detectMode(ids))
    }

    @Test
    fun detectMode_neitherAvailable_returnsNull() {
        val ids = setOf(0x1234, 0x5678)
        assertNull(SeatMassageChannel.detectMode(ids))
    }

    @Test
    fun detectMode_emptySet_returnsNull() {
        assertNull(SeatMassageChannel.detectMode(emptySet()))
    }

    @Test
    fun detectMode_massageTakesPriority_overLumbar() {
        val ids = setOf(
            SeatMassageChannel.SEAT_MASSAGE_ENABLED,
            SeatMassageChannel.SEAT_MASSAGE_INTENSITY,
            SeatMassageChannel.SEAT_LUMBAR_FORE_AFT_MOVE
        )
        assertEquals("massage", SeatMassageChannel.detectMode(ids))
    }

    @Test
    fun detectMode_onlyOneOfMassagePair_returnsLumbar() {
        // Only MASSAGE_ENABLED without MASSAGE_INTENSITY → falls through to lumbar check
        val ids = setOf(
            SeatMassageChannel.SEAT_MASSAGE_ENABLED,
            SeatMassageChannel.SEAT_LUMBAR_FORE_AFT_MOVE
        )
        assertEquals("lumbar", SeatMassageChannel.detectMode(ids))
    }
}
