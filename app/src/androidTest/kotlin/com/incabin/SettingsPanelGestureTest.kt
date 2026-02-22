package com.incabin

import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsPanelGestureTest {

    @get:Rule val testRule = InCabinTestRule()

    @Test
    fun fiveTaps_opensSettingsPanel() {
        UiTestUtils.performFiveTapGesture(testRule.scenario)
        testRule.onActivity { assertEquals(View.VISIBLE, it.findViewById<View>(R.id.settingsPanel).visibility) }
    }

    @Test
    fun fourTaps_doesNotOpenPanel() {
        UiTestUtils.performTaps(testRule.scenario, 4)
        Thread.sleep(300)
        testRule.onActivity { assertEquals(View.GONE, it.findViewById<View>(R.id.settingsPanel).visibility) }
    }

    @Test
    fun scrimTap_closesPanel() {
        UiTestUtils.performFiveTapGesture(testRule.scenario)
        testRule.onActivity { assertEquals(View.VISIBLE, it.findViewById<View>(R.id.settingsPanel).visibility) }
        UiTestUtils.clickView(testRule.scenario, R.id.settingsScrim)
        Thread.sleep(400)
        testRule.onActivity { assertEquals(View.GONE, it.findViewById<View>(R.id.settingsPanel).visibility) }
    }

    @Test
    fun closeButton_closesPanel() {
        UiTestUtils.performFiveTapGesture(testRule.scenario)
        UiTestUtils.clickView(testRule.scenario, R.id.settingsCloseBtn)
        Thread.sleep(400)
        testRule.onActivity { assertEquals(View.GONE, it.findViewById<View>(R.id.settingsPanel).visibility) }
    }

    @Test
    fun fiveTapsAgain_togglesClose() {
        UiTestUtils.performFiveTapGesture(testRule.scenario)
        testRule.onActivity { assertEquals(View.VISIBLE, it.findViewById<View>(R.id.settingsPanel).visibility) }
        UiTestUtils.performFiveTapGesture(testRule.scenario)
        testRule.onActivity { assertEquals(View.GONE, it.findViewById<View>(R.id.settingsPanel).visibility) }
    }

    @Test
    fun slowTaps_doNotOpenPanel() {
        UiTestUtils.performTaps(testRule.scenario, 3)
        Thread.sleep(3100) // Wait past the 3s window
        UiTestUtils.performTaps(testRule.scenario, 2)
        Thread.sleep(300)
        testRule.onActivity { assertEquals(View.GONE, it.findViewById<View>(R.id.settingsPanel).visibility) }
    }

    @Test
    fun scrim_visibleWhenPanelOpen() {
        UiTestUtils.performFiveTapGesture(testRule.scenario)
        testRule.onActivity { assertEquals(View.VISIBLE, it.findViewById<View>(R.id.settingsScrim).visibility) }
    }

    @Test
    fun scrim_goneWhenPanelClosed() {
        testRule.onActivity { assertEquals(View.GONE, it.findViewById<View>(R.id.settingsScrim).visibility) }
    }
}
