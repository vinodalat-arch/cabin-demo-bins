package com.incabin

import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DashboardDetectionLabelsTest {

    @get:Rule val testRule = InCabinTestRule()

    @Test
    fun allClear_showsAllClearLabel() {
        UiTestUtils.enterMonitoringState(testRule.scenario)
        // Must transition from a detection to allClear — posting allClear when
        // currentDetections is already empty short-circuits (no UI change).
        UiTestUtils.postResultAndWait(TestResultFactory.phoneDetected(), UiTestUtils.ANIM_WAIT_MS)
        UiTestUtils.postResultAndWait(TestResultFactory.allClear(), UiTestUtils.ANIM_WAIT_MS)
        testRule.onActivity {
            val text = UiTestUtils.getTaggedViewText(it, R.id.detectionsContainer, "det_allclear")
            assertNotNull("All Clear label should exist", text)
            assertTrue("Should contain 'All Clear'", text!!.contains("All Clear"))
        }
    }

    @Test
    fun phoneDetected_showsPhoneLabel() {
        UiTestUtils.enterMonitoringState(testRule.scenario)
        UiTestUtils.postResultAndWait(TestResultFactory.phoneDetected(), UiTestUtils.ANIM_WAIT_MS)
        testRule.onActivity { assertTrue(UiTestUtils.hasViewWithTag(it, R.id.detectionsContainer, "det_driverUsingPhone")) }
    }

    @Test
    fun eyesClosed_showsEyesLabel() {
        UiTestUtils.enterMonitoringState(testRule.scenario)
        UiTestUtils.postResultAndWait(TestResultFactory.eyesClosed(), UiTestUtils.ANIM_WAIT_MS)
        testRule.onActivity { assertTrue(UiTestUtils.hasViewWithTag(it, R.id.detectionsContainer, "det_driverEyesClosed")) }
    }

    @Test
    fun yawning_showsYawningLabel() {
        UiTestUtils.enterMonitoringState(testRule.scenario)
        UiTestUtils.postResultAndWait(TestResultFactory.yawning(), UiTestUtils.ANIM_WAIT_MS)
        testRule.onActivity { assertTrue(UiTestUtils.hasViewWithTag(it, R.id.detectionsContainer, "det_driverYawning")) }
    }

    @Test
    fun distracted_showsDistractedLabel() {
        UiTestUtils.enterMonitoringState(testRule.scenario)
        UiTestUtils.postResultAndWait(TestResultFactory.distracted(), UiTestUtils.ANIM_WAIT_MS)
        testRule.onActivity { assertTrue(UiTestUtils.hasViewWithTag(it, R.id.detectionsContainer, "det_driverDistracted")) }
    }

    @Test
    fun eating_showsEatingLabel() {
        UiTestUtils.enterMonitoringState(testRule.scenario)
        UiTestUtils.postResultAndWait(TestResultFactory.eating(), UiTestUtils.ANIM_WAIT_MS)
        testRule.onActivity { assertTrue(UiTestUtils.hasViewWithTag(it, R.id.detectionsContainer, "det_driverEatingDrinking")) }
    }

    @Test
    fun posture_showsPostureLabel() {
        UiTestUtils.enterMonitoringState(testRule.scenario)
        UiTestUtils.postResultAndWait(TestResultFactory.posture(), UiTestUtils.ANIM_WAIT_MS)
        testRule.onActivity { assertTrue(UiTestUtils.hasViewWithTag(it, R.id.detectionsContainer, "det_dangerousPosture")) }
    }

    @Test
    fun childSlouching_showsSlouchiLabel() {
        UiTestUtils.enterMonitoringState(testRule.scenario)
        UiTestUtils.postResultAndWait(TestResultFactory.childSlouching(), UiTestUtils.ANIM_WAIT_MS)
        testRule.onActivity { assertTrue(UiTestUtils.hasViewWithTag(it, R.id.detectionsContainer, "det_childSlouching")) }
    }

    @Test
    fun multipleDetections_showsMultipleLabels() {
        UiTestUtils.enterMonitoringState(testRule.scenario)
        UiTestUtils.postResultAndWait(TestResultFactory.highRiskMultiple(), UiTestUtils.ANIM_WAIT_MS)
        testRule.onActivity {
            assertTrue(UiTestUtils.hasViewWithTag(it, R.id.detectionsContainer, "det_driverUsingPhone"))
            assertTrue(UiTestUtils.hasViewWithTag(it, R.id.detectionsContainer, "det_driverEyesClosed"))
            assertTrue(UiTestUtils.hasViewWithTag(it, R.id.detectionsContainer, "det_driverDistracted"))
            assertTrue(UiTestUtils.hasViewWithTag(it, R.id.detectionsContainer, "det_childSlouching"))
        }
    }

    @Test
    fun detectionCleared_removesLabel() {
        UiTestUtils.enterMonitoringState(testRule.scenario)
        UiTestUtils.postResultAndWait(TestResultFactory.phoneDetected(), UiTestUtils.ANIM_WAIT_MS)
        testRule.onActivity { assertTrue(UiTestUtils.hasViewWithTag(it, R.id.detectionsContainer, "det_driverUsingPhone")) }
        UiTestUtils.postResultAndWait(TestResultFactory.allClear(), UiTestUtils.ANIM_WAIT_MS)
        testRule.onActivity { assertFalse(UiTestUtils.hasViewWithTag(it, R.id.detectionsContainer, "det_driverUsingPhone")) }
    }

    @Test
    fun japaneseLabels_showCorrectText() {
        Config.LANGUAGE = "ja"
        UiTestUtils.enterMonitoringState(testRule.scenario)
        // Transition from detection → allClear to trigger the "All Clear" label
        UiTestUtils.postResultAndWait(TestResultFactory.phoneDetected(), UiTestUtils.ANIM_WAIT_MS)
        UiTestUtils.postResultAndWait(TestResultFactory.allClear(), UiTestUtils.ANIM_WAIT_MS)
        testRule.onActivity {
            val text = UiTestUtils.getTaggedViewText(it, R.id.detectionsContainer, "det_allclear")
            assertNotNull(text)
            assertTrue("Should show Japanese all-clear", text!!.contains("安全"))
        }
    }

    @Test
    fun noDriverDetected_showsLabel() {
        UiTestUtils.enterMonitoringState(testRule.scenario)
        UiTestUtils.postResultAndWait(TestResultFactory.noDriver(), UiTestUtils.ANIM_WAIT_MS)
        testRule.onActivity { assertTrue(UiTestUtils.hasViewWithTag(it, R.id.detectionsContainer, "det_noDriverDetected")) }
    }
}
