package com.incabin

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConfigPersistenceTest {

    @get:Rule val testRule = InCabinTestRule()

    private fun openSettings() = UiTestUtils.performFiveTapGesture(testRule.scenario)

    @Test
    fun previewToggle_persistsAcrossRecreation() {
        openSettings()
        UiTestUtils.clickView(testRule.scenario, R.id.previewToggle)
        Thread.sleep(300)
        assertTrue(Config.ENABLE_PREVIEW)

        testRule.scenario.recreate()
        Thread.sleep(500)
        assertTrue("Preview should be restored to ON", Config.ENABLE_PREVIEW)
    }

    @Test
    fun audioToggle_persistsAcrossRecreation() {
        openSettings()
        UiTestUtils.clickView(testRule.scenario, R.id.audioToggle)
        Thread.sleep(300)
        assertFalse(Config.ENABLE_AUDIO_ALERTS)

        testRule.scenario.recreate()
        Thread.sleep(500)
        assertFalse("Audio should be restored to OFF", Config.ENABLE_AUDIO_ALERTS)
    }

    @Test
    fun language_persistsAcrossRecreation() {
        openSettings()
        UiTestUtils.clickView(testRule.scenario, R.id.langJaBtn)
        Thread.sleep(300)
        assertEquals("ja", Config.LANGUAGE)

        testRule.scenario.recreate()
        Thread.sleep(500)
        assertEquals("Language should be restored to ja", "ja", Config.LANGUAGE)
    }

    @Test
    fun seatSide_persistsAcrossRecreation() {
        openSettings()
        UiTestUtils.clickView(testRule.scenario, R.id.seatRightBtn)
        Thread.sleep(300)
        assertEquals("right", Config.DRIVER_SEAT_SIDE)

        testRule.scenario.recreate()
        Thread.sleep(500)
        assertEquals("Seat side should be restored to right", "right", Config.DRIVER_SEAT_SIDE)
    }

    @Test
    fun prefsCleared_restoresDefaults() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        ctx.getSharedPreferences("incabin_prefs", Context.MODE_PRIVATE).edit()
            .putBoolean("preview_enabled", true)
            .putString("language", "ja")
            .commit()

        testRule.scenario.recreate()
        Thread.sleep(500)
        assertTrue(Config.ENABLE_PREVIEW)
        assertEquals("ja", Config.LANGUAGE)

        ctx.getSharedPreferences("incabin_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        Config.ENABLE_PREVIEW = false
        Config.LANGUAGE = "en"

        testRule.scenario.recreate()
        Thread.sleep(500)
        assertFalse(Config.ENABLE_PREVIEW)
        assertEquals("en", Config.LANGUAGE)
    }

    @Test
    fun multipleToggles_persistFinalState() {
        openSettings()
        UiTestUtils.clickView(testRule.scenario, R.id.previewToggle)
        Thread.sleep(100)
        UiTestUtils.clickView(testRule.scenario, R.id.previewToggle)
        Thread.sleep(300)
        assertFalse(Config.ENABLE_PREVIEW)

        testRule.scenario.recreate()
        Thread.sleep(500)
        assertFalse("Final state OFF should persist", Config.ENABLE_PREVIEW)
    }
}
