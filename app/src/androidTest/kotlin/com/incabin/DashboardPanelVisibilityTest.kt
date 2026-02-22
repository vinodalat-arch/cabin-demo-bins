package com.incabin

import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DashboardPanelVisibilityTest {

    @get:Rule val testRule = InCabinTestRule()

    @Test
    fun rightPanel_hiddenWhenIdle() {
        testRule.onActivity { assertEquals(View.GONE, it.findViewById<View>(R.id.rightPanel).visibility) }
    }

    @Test
    fun rightPanel_visibleDuringMonitoring() {
        UiTestUtils.enterMonitoringState(testRule.scenario)
        testRule.onActivity { assertEquals(View.VISIBLE, it.findViewById<View>(R.id.rightPanel).visibility) }
    }

    @Test
    fun idleOverlay_visibleWhenIdle() {
        testRule.onActivity { assertEquals(View.VISIBLE, it.findViewById<View>(R.id.idleOverlay).visibility) }
    }

    @Test
    fun idleOverlay_hiddenDuringMonitoring() {
        UiTestUtils.enterMonitoringState(testRule.scenario)
        testRule.onActivity {
            val overlay = it.findViewById<View>(R.id.idleOverlay)
            assertTrue(overlay.visibility == View.GONE || overlay.alpha < 0.1f)
        }
    }

    @Test
    fun scoreContainer_hiddenWhenIdle() {
        testRule.onActivity { assertEquals(View.GONE, it.findViewById<View>(R.id.scoreContainer).visibility) }
    }

    @Test
    fun scoreContainer_visibleDuringMonitoring() {
        UiTestUtils.enterMonitoringState(testRule.scenario)
        testRule.onActivity { assertEquals(View.VISIBLE, it.findViewById<View>(R.id.scoreContainer).visibility) }
    }

    @Test
    fun asimoContainer_hiddenWhenIdle() {
        testRule.onActivity { assertEquals(View.GONE, it.findViewById<View>(R.id.asimoContainer).visibility) }
    }

    @Test
    fun asimoContainer_visibleDuringMonitoring() {
        UiTestUtils.enterMonitoringState(testRule.scenario)
        testRule.onActivity { assertEquals(View.VISIBLE, it.findViewById<View>(R.id.asimoContainer).visibility) }
    }
}
