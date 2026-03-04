package com.incabin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Flow integration tests for driver personalization:
 * theme → Config integration, welcome greeting, profile rename.
 */
class FlowDriverPersonalizationTest {

    @Before
    fun setUp() {
        // Reset Config to defaults before each test
        Config.HVAC_BASE_TEMP_C = 22.0f
        Config.CURRENT_DRIVER_AMBIENT_COLOR = ""
        Config.ENABLE_DRIVER_PROFILES = false
    }

    // -------------------------------------------------------------------------
    // Theme → Config integration
    // -------------------------------------------------------------------------

    @Test
    fun theme_applied_to_config_via_applier() {
        val theme = CabinTheme.findById("relax")!!
        ThemeApplier.applyToConfig(theme)
        assertEquals(24.0f, Config.HVAC_BASE_TEMP_C, 0.001f)
        assertEquals("#9B59B6", Config.CURRENT_DRIVER_AMBIENT_COLOR)
    }

    @Test
    fun theme_resolve_from_profile() {
        val profile = DriverProfile("Alice", themeId = "energize")
        val theme = CabinTheme.findById(profile.themeId)
        assertNotNull(theme)
        assertEquals("Energize", theme!!.displayName)
        assertEquals(20.0f, theme.tempC, 0.001f)
    }

    @Test
    fun default_theme_does_not_change_defaults() {
        val theme = CabinTheme.defaultTheme()
        ThemeApplier.applyToConfig(theme)
        assertEquals(22.0f, Config.HVAC_BASE_TEMP_C, 0.001f)
        assertEquals("#5B8DEF", Config.CURRENT_DRIVER_AMBIENT_COLOR)
    }

    @Test
    fun profile_themeId_backward_compat() {
        // Profile without explicit themeId gets "comfort" default
        val profile = DriverProfile("Old", 24.0f, "#FF0000")
        assertEquals("comfort", profile.themeId)
        val theme = CabinTheme.findById(profile.themeId)
        assertNotNull(theme)
    }

    @Test
    fun profile_temp_range_min() {
        val theme = CabinTheme.findById("energize")!!
        ThemeApplier.applyToConfig(theme)
        assertEquals(20.0f, Config.HVAC_BASE_TEMP_C, 0.001f)
    }

    @Test
    fun profile_temp_range_max() {
        val theme = CabinTheme.findById("eco")!!
        ThemeApplier.applyToConfig(theme)
        assertEquals(26.0f, Config.HVAC_BASE_TEMP_C, 0.001f)
    }

    // -------------------------------------------------------------------------
    // Greeting + theme flow
    // -------------------------------------------------------------------------

    @Test
    fun greeting_en_with_theme_includes_themeName() {
        val msg = AudioAlerter.buildWelcomeGreeting("Alice", 9, themeName = "Relax", isJapanese = false)
        assertTrue(msg.contains("Relax"))
        assertTrue(msg.contains("Alice"))
        assertTrue(msg.contains("Applying"))
    }

    @Test
    fun greeting_en_without_theme_no_suffix() {
        val msg = AudioAlerter.buildWelcomeGreeting("Bob", 14, themeName = null, isJapanese = false)
        assertFalse(msg.contains("Applying"))
        assertTrue(msg.contains("Bob"))
    }

    @Test
    fun greeting_ja_with_theme_includes_suffix() {
        val msg = AudioAlerter.buildWelcomeGreeting("太郎", 10, themeName = "コンフォート", isJapanese = true)
        assertTrue(msg.contains("コンフォート"))
        assertTrue(msg.contains("太郎さん"))
    }

    @Test
    fun greeting_midnight_is_evening() {
        val msg = AudioAlerter.buildWelcomeGreeting("X", 0, themeName = null, isJapanese = false)
        assertTrue(msg.startsWith("Good evening"))
    }

    // -------------------------------------------------------------------------
    // Profile rename preserves theme
    // -------------------------------------------------------------------------

    @Test
    fun profile_copy_with_new_name_preserves_theme() {
        val original = DriverProfile("Alice", 24.5f, "#E91E63", "relax")
        val renamed = original.copy(name = "Alicia")
        assertEquals("Alicia", renamed.name)
        assertEquals("relax", renamed.themeId)
        assertEquals(24.5f, renamed.preferredTempC, 0.001f)
        assertEquals("#E91E63", renamed.ambientColorHex)
    }
}
