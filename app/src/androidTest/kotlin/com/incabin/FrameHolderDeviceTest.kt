package com.incabin

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device tests for FrameHolder thread safety and state management.
 * These run directly without ActivityScenario since FrameHolder is a singleton.
 */
@RunWith(AndroidJUnit4::class)
class FrameHolderDeviceTest {

    @Before
    fun setup() {
        FrameHolder.clear()
        TestResultFactory.reset()
    }

    @Test
    fun postResult_andGetResult_roundTrip() {
        val result = TestResultFactory.phoneDetected()
        FrameHolder.postResult(result)
        val retrieved = FrameHolder.getLatestResult()
        assertNotNull(retrieved)
        assertEquals(result.timestamp, retrieved!!.timestamp)
        assertTrue(retrieved.driverUsingPhone)
    }

    @Test
    fun clear_resetsAllState() {
        FrameHolder.postResult(TestResultFactory.allClear())
        FrameHolder.postCameraStatus(FrameHolder.CameraStatus.ACTIVE)
        FrameHolder.postHeartbeat()

        FrameHolder.clear()

        assertNull(FrameHolder.getLatestResult())
        assertEquals(FrameHolder.CameraStatus.NOT_CONNECTED, FrameHolder.getCameraStatus())
        assertFalse(FrameHolder.isServiceRunning())
    }

    @Test
    fun heartbeat_ageIsRecent() {
        FrameHolder.postHeartbeat()
        val age = FrameHolder.getHeartbeatAgeMs()
        assertTrue("Heartbeat age should be < 1000ms, was $age", age < 1000)
    }

    @Test
    fun rapidPosting_noExceptions() {
        // Post 50 results rapidly from the test thread
        repeat(50) {
            FrameHolder.postResult(TestResultFactory.allClear())
        }
        val result = FrameHolder.getLatestResult()
        assertNotNull(result)
    }

    @Test
    fun multiThread_noExceptions() {
        // Post from two threads concurrently
        val t1 = Thread {
            repeat(30) {
                FrameHolder.postResult(TestResultFactory.phoneDetected())
            }
        }
        val t2 = Thread {
            repeat(30) {
                FrameHolder.getLatestResult()
                FrameHolder.getCameraStatus()
            }
        }
        t1.start()
        t2.start()
        t1.join(5000)
        t2.join(5000)
        // No exception = pass
        assertNotNull(FrameHolder.getLatestResult())
    }
}
