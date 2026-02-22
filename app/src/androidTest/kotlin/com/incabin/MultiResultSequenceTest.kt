package com.incabin

import android.view.View
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MultiResultSequenceTest {

    @get:Rule val testRule = InCabinTestRule()

    @Test
    fun clearToPhoneToClear_updatesCorrectly() {
        UiTestUtils.enterMonitoringState(testRule.scenario)
        UiTestUtils.postResultAndWait(TestResultFactory.allClear())
        testRule.onActivity { assertEquals("LOW", it.findViewById<TextView>(R.id.riskBanner).text.toString()) }

        UiTestUtils.postResultAndWait(TestResultFactory.phoneDetected())
        testRule.onActivity { assertEquals("HIGH", it.findViewById<TextView>(R.id.riskBanner).text.toString()) }

        UiTestUtils.postResultAndWait(TestResultFactory.allClear(), UiTestUtils.ANIM_WAIT_MS)
        testRule.onActivity { assertEquals("LOW", it.findViewById<TextView>(R.id.riskBanner).text.toString()) }
    }

    @Test
    fun multipleDetectionTypes_inSequence() {
        UiTestUtils.enterMonitoringState(testRule.scenario)

        UiTestUtils.postResultAndWait(TestResultFactory.phoneDetected())
        testRule.onActivity { assertEquals("HIGH", it.findViewById<TextView>(R.id.riskBanner).text.toString()) }

        UiTestUtils.postResultAndWait(TestResultFactory.yawning())
        testRule.onActivity { assertEquals("MEDIUM", it.findViewById<TextView>(R.id.riskBanner).text.toString()) }

        UiTestUtils.postResultAndWait(TestResultFactory.eyesClosed())
        testRule.onActivity { assertEquals("HIGH", it.findViewById<TextView>(R.id.riskBanner).text.toString()) }
    }

    @Test
    fun distractionTimer_accumulates() {
        UiTestUtils.enterMonitoringState(testRule.scenario)

        UiTestUtils.postResultAndWait(TestResultFactory.withDistraction(5))
        testRule.onActivity { assertEquals("Distraction: 5s", it.findViewById<TextView>(R.id.distractionText).text.toString()) }

        UiTestUtils.postResultAndWait(TestResultFactory.withDistraction(10))
        testRule.onActivity { assertEquals("Distraction: 10s", it.findViewById<TextView>(R.id.distractionText).text.toString()) }
    }

    @Test
    fun rapidPosts_noCrashes() {
        UiTestUtils.enterMonitoringState(testRule.scenario)

        repeat(20) { i ->
            val result = if (i % 2 == 0) TestResultFactory.phoneDetected() else TestResultFactory.allClear()
            FrameHolder.postResult(result)
            Thread.sleep(50)
        }

        Thread.sleep(UiTestUtils.POLL_WAIT_MS)
        testRule.onActivity {
            val banner = it.findViewById<TextView>(R.id.riskBanner)
            assertNotNull(banner.text)
            assertTrue(banner.text.toString().isNotEmpty())
        }
    }

    @Test
    fun passengerCountChanges_inSequence() {
        UiTestUtils.enterMonitoringState(testRule.scenario)

        UiTestUtils.postResultAndWait(TestResultFactory.allClear(passengerCount = 1))
        testRule.onActivity { assertEquals("1 passenger", it.findViewById<TextView>(R.id.passengerText).text.toString()) }

        UiTestUtils.postResultAndWait(TestResultFactory.allClear(passengerCount = 3))
        testRule.onActivity { assertEquals("3 passengers", it.findViewById<TextView>(R.id.passengerText).text.toString()) }

        UiTestUtils.postResultAndWait(TestResultFactory.noOccupants())
        testRule.onActivity { assertEquals("0 passengers", it.findViewById<TextView>(R.id.passengerText).text.toString()) }
    }

    @Test
    fun driverName_appearsAndDisappears() {
        UiTestUtils.enterMonitoringState(testRule.scenario)

        UiTestUtils.postResultAndWait(TestResultFactory.withDriverName("Bob"))
        testRule.onActivity {
            val tv = it.findViewById<TextView>(R.id.driverNameText)
            assertEquals(View.VISIBLE, tv.visibility)
            assertEquals("Driver: Bob", tv.text.toString())
        }

        UiTestUtils.postResultAndWait(TestResultFactory.allClear())
        testRule.onActivity { assertEquals(View.GONE, it.findViewById<View>(R.id.driverNameText).visibility) }
    }
}
