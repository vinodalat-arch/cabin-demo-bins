package com.incabin

import android.view.View
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DashboardInfoFieldsTest {

    @get:Rule val testRule = InCabinTestRule()

    @Test
    fun passengerCount_singular() {
        UiTestUtils.enterMonitoringState(testRule.scenario)
        UiTestUtils.postResultAndWait(TestResultFactory.allClear(passengerCount = 1))
        testRule.onActivity { assertEquals("1 passenger", it.findViewById<TextView>(R.id.passengerText).text.toString()) }
    }

    @Test
    fun passengerCount_plural() {
        UiTestUtils.enterMonitoringState(testRule.scenario)
        UiTestUtils.postResultAndWait(TestResultFactory.allClear(passengerCount = 3))
        testRule.onActivity { assertEquals("3 passengers", it.findViewById<TextView>(R.id.passengerText).text.toString()) }
    }

    @Test
    fun passengerCount_zero() {
        UiTestUtils.enterMonitoringState(testRule.scenario)
        UiTestUtils.postResultAndWait(TestResultFactory.noOccupants())
        testRule.onActivity { assertEquals("0 passengers", it.findViewById<TextView>(R.id.passengerText).text.toString()) }
    }

    @Test
    fun distractionTimer_showsZero_whenAllClear() {
        UiTestUtils.enterMonitoringState(testRule.scenario)
        UiTestUtils.postResultAndWait(TestResultFactory.allClear())
        testRule.onActivity { assertEquals("Distraction: 0s", it.findViewById<TextView>(R.id.distractionText).text.toString()) }
    }

    @Test
    fun distractionTimer_showsSeconds() {
        UiTestUtils.enterMonitoringState(testRule.scenario)
        UiTestUtils.postResultAndWait(TestResultFactory.withDistraction(7))
        testRule.onActivity { assertEquals("Distraction: 7s", it.findViewById<TextView>(R.id.distractionText).text.toString()) }
    }

    @Test
    fun distractionTimer_highValue_showsDangerColor() {
        UiTestUtils.enterMonitoringState(testRule.scenario)
        UiTestUtils.postResultAndWait(TestResultFactory.withDistraction(12))
        testRule.onActivity {
            val tv = it.findViewById<TextView>(R.id.distractionText)
            val dangerColor = androidx.core.content.ContextCompat.getColor(it, R.color.danger)
            assertEquals(dangerColor, tv.currentTextColor)
        }
    }

    @Test
    fun driverName_shown_whenPresent() {
        UiTestUtils.enterMonitoringState(testRule.scenario)
        UiTestUtils.postResultAndWait(TestResultFactory.withDriverName("Alice"))
        testRule.onActivity {
            val tv = it.findViewById<TextView>(R.id.driverNameText)
            assertEquals(View.VISIBLE, tv.visibility)
            assertEquals("Driver: Alice", tv.text.toString())
        }
    }

    @Test
    fun driverName_hidden_whenNull() {
        UiTestUtils.enterMonitoringState(testRule.scenario)
        UiTestUtils.postResultAndWait(TestResultFactory.allClear())
        testRule.onActivity { assertEquals(View.GONE, it.findViewById<View>(R.id.driverNameText).visibility) }
    }

    @Test
    fun earText_showsValue() {
        UiTestUtils.enterMonitoringState(testRule.scenario)
        UiTestUtils.postResultAndWait(TestResultFactory.allClear())
        testRule.onActivity {
            val text = it.findViewById<TextView>(R.id.earText).text.toString()
            assertTrue("EAR should show value", text.startsWith("EAR:"))
            assertTrue("EAR should contain numeric", text.contains("0.280"))
        }
    }

    @Test
    fun earText_showsDash_whenNull() {
        UiTestUtils.enterMonitoringState(testRule.scenario)
        UiTestUtils.postResultAndWait(TestResultFactory.noOccupants())
        testRule.onActivity { assertEquals("EAR: --", it.findViewById<TextView>(R.id.earText).text.toString()) }
    }
}
