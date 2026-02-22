package com.incabin

import android.widget.Button
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ToggleButtonStateTest {

    @get:Rule val testRule = InCabinTestRule()

    @Test
    fun idle_buttonShowsStart() {
        testRule.onActivity { assertEquals("Start Monitoring", it.findViewById<Button>(R.id.toggleButton).text.toString()) }
    }

    @Test
    fun monitoring_buttonShowsStop() {
        UiTestUtils.enterMonitoringState(testRule.scenario)
        testRule.onActivity { assertEquals("Stop Monitoring", it.findViewById<Button>(R.id.toggleButton).text.toString()) }
    }

    @Test
    fun idle_statusShowsTapStart() {
        testRule.onActivity { assertEquals("Tap Start to begin monitoring", it.findViewById<TextView>(R.id.statusText).text.toString()) }
    }

    @Test
    fun monitoring_statusShowsActive() {
        UiTestUtils.enterMonitoringState(testRule.scenario)
        testRule.onActivity { assertEquals("Monitoring active", it.findViewById<TextView>(R.id.statusText).text.toString()) }
    }

    @Test
    fun stopMonitoring_buttonReturnsToStart() {
        UiTestUtils.enterMonitoringState(testRule.scenario)
        UiTestUtils.exitMonitoringState(testRule.scenario)
        testRule.onActivity { assertEquals("Start Monitoring", it.findViewById<Button>(R.id.toggleButton).text.toString()) }
    }

    @Test
    fun stopMonitoring_statusReturnsToIdle() {
        UiTestUtils.enterMonitoringState(testRule.scenario)
        UiTestUtils.exitMonitoringState(testRule.scenario)
        testRule.onActivity { assertEquals("Tap Start to begin monitoring", it.findViewById<TextView>(R.id.statusText).text.toString()) }
    }
}
