package com.incabin

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsToggleTest {

    @get:Rule val testRule = InCabinTestRule()

    private fun openSettings() = UiTestUtils.performFiveTapGesture(testRule.scenario)

    @Test
    fun seatSide_defaultIsLeft() {
        assertEquals("left", Config.DRIVER_SEAT_SIDE)
    }

    @Test
    fun seatSide_tapRight_updatesConfig() {
        openSettings()
        UiTestUtils.clickView(testRule.scenario, R.id.seatRightBtn)
        Thread.sleep(200)
        assertEquals("right", Config.DRIVER_SEAT_SIDE)
    }

    @Test
    fun seatSide_tapLeft_updatesConfig() {
        Config.DRIVER_SEAT_SIDE = "right"
        openSettings()
        UiTestUtils.clickView(testRule.scenario, R.id.seatLeftBtn)
        Thread.sleep(200)
        assertEquals("left", Config.DRIVER_SEAT_SIDE)
    }

    @Test
    fun language_defaultIsEn() {
        assertEquals("en", Config.LANGUAGE)
    }

    @Test
    fun language_tapJa_updatesConfig() {
        openSettings()
        UiTestUtils.clickView(testRule.scenario, R.id.langJaBtn)
        Thread.sleep(200)
        assertEquals("ja", Config.LANGUAGE)
    }

    @Test
    fun language_tapEn_updatesConfig() {
        Config.LANGUAGE = "ja"
        openSettings()
        UiTestUtils.clickView(testRule.scenario, R.id.langEnBtn)
        Thread.sleep(200)
        assertEquals("en", Config.LANGUAGE)
    }

    @Test
    fun audio_defaultIsOn() {
        assertTrue(Config.ENABLE_AUDIO_ALERTS)
    }

    @Test
    fun audio_toggle_updatesConfig() {
        openSettings()
        UiTestUtils.clickView(testRule.scenario, R.id.audioToggle)
        Thread.sleep(200)
        assertFalse(Config.ENABLE_AUDIO_ALERTS)
    }

    @Test
    fun preview_defaultIsOff() {
        assertFalse(Config.ENABLE_PREVIEW)
    }

    @Test
    fun preview_toggle_updatesConfig() {
        openSettings()
        UiTestUtils.clickView(testRule.scenario, R.id.previewToggle)
        Thread.sleep(200)
        assertTrue(Config.ENABLE_PREVIEW)
    }
}
