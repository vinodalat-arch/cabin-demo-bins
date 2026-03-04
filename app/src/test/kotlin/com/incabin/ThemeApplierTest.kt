package com.incabin

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for ThemeApplier.applyToConfig (pure function).
 * Android-dependent methods (applyBrightness, applyNightMode) are not tested here.
 */
class ThemeApplierTest {

    private var savedTemp = 0f
    private var savedColor = ""

    @Before
    fun setUp() {
        savedTemp = Config.HVAC_BASE_TEMP_C
        savedColor = Config.CURRENT_DRIVER_AMBIENT_COLOR
    }

    @After
    fun tearDown() {
        Config.HVAC_BASE_TEMP_C = savedTemp
        Config.CURRENT_DRIVER_AMBIENT_COLOR = savedColor
    }

    @Test
    fun applyToConfig_setsTemp() {
        val theme = CabinTheme.findById("relax")!!
        ThemeApplier.applyToConfig(theme)
        assertEquals(24.0f, Config.HVAC_BASE_TEMP_C, 0.001f)
    }

    @Test
    fun applyToConfig_setsColor() {
        val theme = CabinTheme.findById("energize")!!
        ThemeApplier.applyToConfig(theme)
        assertEquals("#2ECC71", Config.CURRENT_DRIVER_AMBIENT_COLOR)
    }

    @Test
    fun applyToConfig_comfort_setsDefaults() {
        val theme = CabinTheme.defaultTheme()
        ThemeApplier.applyToConfig(theme)
        assertEquals(22.0f, Config.HVAC_BASE_TEMP_C, 0.001f)
        assertEquals("#5B8DEF", Config.CURRENT_DRIVER_AMBIENT_COLOR)
    }

    @Test
    fun applyToConfig_nightDrive() {
        val theme = CabinTheme.findById("night_drive")!!
        ThemeApplier.applyToConfig(theme)
        assertEquals(21.0f, Config.HVAC_BASE_TEMP_C, 0.001f)
        assertEquals("#E74C3C", Config.CURRENT_DRIVER_AMBIENT_COLOR)
    }

    @Test
    fun applyToConfig_eco() {
        val theme = CabinTheme.findById("eco")!!
        ThemeApplier.applyToConfig(theme)
        assertEquals(26.0f, Config.HVAC_BASE_TEMP_C, 0.001f)
        assertEquals("#1ABC9C", Config.CURRENT_DRIVER_AMBIENT_COLOR)
    }

    @Test
    fun applyToConfig_allThemes_setCorrectValues() {
        for (theme in CabinTheme.THEMES) {
            ThemeApplier.applyToConfig(theme)
            assertEquals("${theme.id} temp", theme.tempC, Config.HVAC_BASE_TEMP_C, 0.001f)
            assertEquals("${theme.id} color", theme.ambientColorHex, Config.CURRENT_DRIVER_AMBIENT_COLOR)
        }
    }
}
