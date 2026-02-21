package com.incabin

import org.junit.Assert.*
import org.junit.Test

class MjpegReconnectTest {

    @Test
    fun test_nextBackoffDelay_doubles() {
        val next = MjpegCameraManager.nextBackoffDelay(2000L, 30000L)
        assertEquals(4000L, next)
    }

    @Test
    fun test_nextBackoffDelay_capped_at_max() {
        val next = MjpegCameraManager.nextBackoffDelay(20000L, 30000L)
        assertEquals(30000L, next)  // 20000*2=40000 > 30000 → capped
    }

    @Test
    fun test_nextBackoffDelay_at_max_stays_at_max() {
        val next = MjpegCameraManager.nextBackoffDelay(30000L, 30000L)
        assertEquals(30000L, next)  // 30000*2=60000 > 30000 → stays capped
    }
}
