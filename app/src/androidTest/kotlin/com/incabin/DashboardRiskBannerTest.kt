package com.incabin

import android.graphics.Color
import android.view.View
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DashboardRiskBannerTest {

    @get:Rule val testRule = InCabinTestRule()

    @Test
    fun riskBanner_showsLow_onAllClear() {
        UiTestUtils.enterMonitoringState(testRule.scenario)
        UiTestUtils.postResultAndWait(TestResultFactory.allClear())
        testRule.onActivity { assertEquals("LOW", it.findViewById<TextView>(R.id.riskBanner).text.toString()) }
    }

    @Test
    fun riskBanner_showsMedium_onYawning() {
        UiTestUtils.enterMonitoringState(testRule.scenario)
        UiTestUtils.postResultAndWait(TestResultFactory.yawning())
        testRule.onActivity { assertEquals("MEDIUM", it.findViewById<TextView>(R.id.riskBanner).text.toString()) }
    }

    @Test
    fun riskBanner_showsHigh_onPhoneDetected() {
        UiTestUtils.enterMonitoringState(testRule.scenario)
        UiTestUtils.postResultAndWait(TestResultFactory.phoneDetected())
        testRule.onActivity { assertEquals("HIGH", it.findViewById<TextView>(R.id.riskBanner).text.toString()) }
    }

    @Test
    fun riskBanner_showsNoOccupants_whenEmpty() {
        UiTestUtils.enterMonitoringState(testRule.scenario)
        UiTestUtils.postResultAndWait(TestResultFactory.noOccupants())
        testRule.onActivity { assertEquals("NO OCCUPANTS", it.findViewById<TextView>(R.id.riskBanner).text.toString()) }
    }

    @Test
    fun riskBanner_showsNoDriver_whenDriverNotDetected() {
        UiTestUtils.enterMonitoringState(testRule.scenario)
        UiTestUtils.postResultAndWait(TestResultFactory.noDriver())
        testRule.onActivity { assertEquals("NO DRIVER", it.findViewById<TextView>(R.id.riskBanner).text.toString()) }
    }

    @Test
    fun riskBanner_transitionsLowToHigh() {
        UiTestUtils.enterMonitoringState(testRule.scenario)
        UiTestUtils.postResultAndWait(TestResultFactory.allClear())
        testRule.onActivity { assertEquals("LOW", it.findViewById<TextView>(R.id.riskBanner).text.toString()) }
        UiTestUtils.postResultAndWait(TestResultFactory.phoneDetected(), UiTestUtils.ANIM_WAIT_MS)
        testRule.onActivity { assertEquals("HIGH", it.findViewById<TextView>(R.id.riskBanner).text.toString()) }
    }

    @Test
    fun riskBanner_transitionsHighToLow() {
        UiTestUtils.enterMonitoringState(testRule.scenario)
        UiTestUtils.postResultAndWait(TestResultFactory.phoneDetected())
        UiTestUtils.postResultAndWait(TestResultFactory.allClear(), UiTestUtils.ANIM_WAIT_MS)
        testRule.onActivity { assertEquals("LOW", it.findViewById<TextView>(R.id.riskBanner).text.toString()) }
    }

    @Test
    fun riskBanner_isVisible_duringMonitoring() {
        UiTestUtils.enterMonitoringState(testRule.scenario)
        UiTestUtils.postResultAndWait(TestResultFactory.allClear())
        testRule.onActivity { assertEquals(View.VISIBLE, it.findViewById<View>(R.id.riskBanner).visibility) }
    }

    @Test
    fun riskBanner_highTextColor_isWhite() {
        UiTestUtils.enterMonitoringState(testRule.scenario)
        UiTestUtils.postResultAndWait(TestResultFactory.phoneDetected())
        testRule.onActivity { assertEquals(Color.WHITE, it.findViewById<TextView>(R.id.riskBanner).currentTextColor) }
    }

    @Test
    fun riskBanner_lowTextColor_isBlack() {
        UiTestUtils.enterMonitoringState(testRule.scenario)
        UiTestUtils.postResultAndWait(TestResultFactory.allClear())
        testRule.onActivity { assertEquals(Color.BLACK, it.findViewById<TextView>(R.id.riskBanner).currentTextColor) }
    }
}
