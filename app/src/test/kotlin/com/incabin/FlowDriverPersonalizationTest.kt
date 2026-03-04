package com.incabin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Flow integration tests for driver personalization:
 * profile → Config integration, welcome greeting, profile rename.
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
    // Profile → Config integration
    // -------------------------------------------------------------------------

    @Test
    fun profile_temp_applied_to_config() {
        val profile = DriverProfile("Alice", 25.0f, "#FF0000")
        Config.HVAC_BASE_TEMP_C = profile.preferredTempC
        assertEquals(25.0f, Config.HVAC_BASE_TEMP_C, 0.001f)
    }

    @Test
    fun profile_color_applied_to_config() {
        val profile = DriverProfile("Alice", 22.0f, "#E74C3C")
        Config.CURRENT_DRIVER_AMBIENT_COLOR = profile.ambientColorHex
        assertEquals("#E74C3C", Config.CURRENT_DRIVER_AMBIENT_COLOR)
    }

    @Test
    fun default_profile_does_not_change_defaults() {
        val profile = DriverProfile("Alice")
        Config.HVAC_BASE_TEMP_C = profile.preferredTempC
        Config.CURRENT_DRIVER_AMBIENT_COLOR = profile.ambientColorHex
        assertEquals(22.0f, Config.HVAC_BASE_TEMP_C, 0.001f)
        assertEquals("#5B8DEF", Config.CURRENT_DRIVER_AMBIENT_COLOR)
    }

    @Test
    fun profile_temp_range_min() {
        val profile = DriverProfile("Cold", 16.0f)
        Config.HVAC_BASE_TEMP_C = profile.preferredTempC
        assertEquals(16.0f, Config.HVAC_BASE_TEMP_C, 0.001f)
    }

    @Test
    fun profile_temp_range_max() {
        val profile = DriverProfile("Hot", 28.0f)
        Config.HVAC_BASE_TEMP_C = profile.preferredTempC
        assertEquals(28.0f, Config.HVAC_BASE_TEMP_C, 0.001f)
    }

    // -------------------------------------------------------------------------
    // Greeting + profile flow
    // -------------------------------------------------------------------------

    @Test
    fun greeting_en_with_profile_includes_customizing() {
        val msg = AudioAlerter.buildWelcomeGreeting("Alice", 9, hasProfile = true, isJapanese = false)
        assertTrue(msg.contains("Customizing"))
        assertTrue(msg.contains("Alice"))
    }

    @Test
    fun greeting_en_without_profile_no_customizing() {
        val msg = AudioAlerter.buildWelcomeGreeting("Bob", 14, hasProfile = false, isJapanese = false)
        assertFalse(msg.contains("Customizing"))
        assertTrue(msg.contains("Bob"))
    }

    @Test
    fun greeting_ja_with_profile_includes_suffix() {
        val msg = AudioAlerter.buildWelcomeGreeting("太郎", 10, hasProfile = true, isJapanese = true)
        assertTrue(msg.contains("お好みの設定"))
        assertTrue(msg.contains("太郎さん"))
    }

    @Test
    fun greeting_midnight_is_evening() {
        val msg = AudioAlerter.buildWelcomeGreeting("X", 0, hasProfile = false, isJapanese = false)
        assertTrue(msg.startsWith("Good evening"))
    }

    // -------------------------------------------------------------------------
    // Profile rename preserves preferences
    // -------------------------------------------------------------------------

    @Test
    fun profile_copy_with_new_name_preserves_preferences() {
        val original = DriverProfile("Alice", 24.5f, "#E91E63")
        val renamed = original.copy(name = "Alicia")
        assertEquals("Alicia", renamed.name)
        assertEquals(24.5f, renamed.preferredTempC, 0.001f)
        assertEquals("#E91E63", renamed.ambientColorHex)
    }
}
