package com.incabin

import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CameraStatusIndicatorTest {

    @get:Rule val testRule = InCabinTestRule()

    @Test
    fun status_notConnected_showsNoCamera() {
        FrameHolder.postCameraStatus(FrameHolder.CameraStatus.NOT_CONNECTED)
        UiTestUtils.enterMonitoringState(testRule.scenario)
        UiTestUtils.postResultAndWait(TestResultFactory.allClear())
        testRule.onActivity { assertEquals("No camera", it.findViewById<TextView>(R.id.cameraStatusText).text.toString()) }
    }

    @Test
    fun status_active_showsActive() {
        FrameHolder.postCameraStatus(FrameHolder.CameraStatus.ACTIVE)
        UiTestUtils.enterMonitoringState(testRule.scenario)
        UiTestUtils.postResultAndWait(TestResultFactory.allClear())
        testRule.onActivity { assertEquals("Active", it.findViewById<TextView>(R.id.cameraStatusText).text.toString()) }
    }

    @Test
    fun status_lost_showsLost() {
        FrameHolder.postCameraStatus(FrameHolder.CameraStatus.LOST)
        UiTestUtils.enterMonitoringState(testRule.scenario)
        UiTestUtils.postResultAndWait(TestResultFactory.allClear())
        testRule.onActivity { assertEquals("Lost", it.findViewById<TextView>(R.id.cameraStatusText).text.toString()) }
    }

    @Test
    fun status_connecting_showsConnecting() {
        FrameHolder.postCameraStatus(FrameHolder.CameraStatus.CONNECTING)
        UiTestUtils.enterMonitoringState(testRule.scenario)
        UiTestUtils.postResultAndWait(TestResultFactory.allClear())
        testRule.onActivity { assertEquals("Connecting...", it.findViewById<TextView>(R.id.cameraStatusText).text.toString()) }
    }

    @Test
    fun status_freshHeartbeat_notStalled() {
        FrameHolder.postHeartbeat()
        FrameHolder.postCameraStatus(FrameHolder.CameraStatus.ACTIVE)
        UiTestUtils.enterMonitoringState(testRule.scenario)
        UiTestUtils.postResultAndWait(TestResultFactory.allClear())
        testRule.onActivity { assertNotEquals("Stalled", it.findViewById<TextView>(R.id.cameraStatusText).text.toString()) }
    }
}
