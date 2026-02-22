package com.incabin

import android.view.View
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AsimoPoseUITest {

    @get:Rule val testRule = InCabinTestRule()

    @Test
    fun asimoContainer_hiddenWhenIdle() {
        testRule.onActivity { assertEquals(View.GONE, it.findViewById<View>(R.id.asimoContainer).visibility) }
    }

    @Test
    fun asimoContainer_visibleDuringMonitoring() {
        UiTestUtils.enterMonitoringState(testRule.scenario)
        testRule.onActivity { assertEquals(View.VISIBLE, it.findViewById<View>(R.id.asimoContainer).visibility) }
    }

    @Test
    fun asimoBubble_showsReadyMessage_onStart() {
        UiTestUtils.enterMonitoringState(testRule.scenario)
        testRule.onActivity { assertEquals("Smart eyes are ready!", it.findViewById<TextView>(R.id.asimoBubbleText).text.toString()) }
    }

    @Test
    fun asimoBubble_showsJapaneseReady_whenJa() {
        Config.LANGUAGE = "ja"
        UiTestUtils.enterMonitoringState(testRule.scenario)
        testRule.onActivity { assertEquals("AIの準備が完了しました！", it.findViewById<TextView>(R.id.asimoBubbleText).text.toString()) }
    }

    @Test
    fun asimoDetectionLabel_hidden_whenAllClear() {
        UiTestUtils.enterMonitoringState(testRule.scenario)
        UiTestUtils.postResultAndWait(TestResultFactory.allClear(), UiTestUtils.ANIM_WAIT_MS)
        testRule.onActivity {
            val label = it.findViewById<View>(R.id.asimoDetectionLabel)
            assertTrue(label.visibility == View.GONE || label.alpha < 0.1f)
        }
    }

    @Test
    fun asimoDetectionLabel_visible_onDetection() {
        UiTestUtils.enterMonitoringState(testRule.scenario)
        UiTestUtils.postResultAndWait(TestResultFactory.phoneDetected(), UiTestUtils.ANIM_WAIT_MS)
        testRule.onActivity { assertEquals(View.VISIBLE, it.findViewById<View>(R.id.asimoDetectionLabel).visibility) }
    }

    @Test
    fun asimoDetectionLabel_showsPhoneText() {
        UiTestUtils.enterMonitoringState(testRule.scenario)
        UiTestUtils.postResultAndWait(TestResultFactory.phoneDetected(), UiTestUtils.ANIM_WAIT_MS)
        testRule.onActivity {
            val text = it.findViewById<TextView>(R.id.asimoDetectionLabel).text.toString()
            assertTrue("Should show PHONE DETECTED, got: $text", text.contains("PHONE DETECTED"))
        }
    }

    @Test
    fun asimoDetectionLabel_showsEyesText() {
        UiTestUtils.enterMonitoringState(testRule.scenario)
        UiTestUtils.postResultAndWait(TestResultFactory.eyesClosed(), UiTestUtils.ANIM_WAIT_MS)
        testRule.onActivity {
            val text = it.findViewById<TextView>(R.id.asimoDetectionLabel).text.toString()
            assertTrue("Should show EYES CLOSED, got: $text", text.contains("EYES CLOSED"))
        }
    }
}
