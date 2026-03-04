package com.incabin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for DriverProfile data class and DriverProfileStore logic.
 * Tests pure data class behavior — no Android dependencies.
 */
class DriverProfileStoreTest {

    // -------------------------------------------------------------------------
    // DriverProfile data class
    // -------------------------------------------------------------------------

    @Test
    fun defaultProfile_hasCorrectDefaults() {
        val profile = DriverProfile("Alice")
        assertEquals("Alice", profile.name)
        assertEquals(22.0f, profile.preferredTempC, 0.001f)
        assertEquals("#5B8DEF", profile.ambientColorHex)
        assertEquals("comfort", profile.themeId)
    }

    @Test
    fun customProfile_preservesValues() {
        val profile = DriverProfile("Bob", 24.5f, "#FF0000", "relax")
        assertEquals("Bob", profile.name)
        assertEquals(24.5f, profile.preferredTempC, 0.001f)
        assertEquals("#FF0000", profile.ambientColorHex)
        assertEquals("relax", profile.themeId)
    }

    @Test
    fun profile_equality() {
        val a = DriverProfile("Alice", 22.0f, "#5B8DEF", "comfort")
        val b = DriverProfile("Alice", 22.0f, "#5B8DEF", "comfort")
        assertEquals(a, b)
    }

    @Test
    fun profile_copy_modifiesField() {
        val original = DriverProfile("Alice", 22.0f, "#5B8DEF")
        val modified = original.copy(preferredTempC = 25.0f)
        assertEquals(25.0f, modified.preferredTempC, 0.001f)
        assertEquals("Alice", modified.name)
        assertEquals("#5B8DEF", modified.ambientColorHex)
    }

    @Test
    fun profile_differentNames_notEqual() {
        val a = DriverProfile("Alice")
        val b = DriverProfile("Bob")
        assertTrue(a != b)
    }

    @Test
    fun profile_differentTemp_notEqual() {
        val a = DriverProfile("Alice", 22.0f)
        val b = DriverProfile("Alice", 24.0f)
        assertTrue(a != b)
    }

    @Test
    fun profile_differentColor_notEqual() {
        val a = DriverProfile("Alice", 22.0f, "#5B8DEF")
        val b = DriverProfile("Alice", 22.0f, "#FF0000")
        assertTrue(a != b)
    }

    @Test
    fun profile_minTemp_value() {
        val profile = DriverProfile("Cold", 16.0f)
        assertEquals(16.0f, profile.preferredTempC, 0.001f)
    }

    // -------------------------------------------------------------------------
    // themeId (4 tests replacing PRESET_COLORS)
    // -------------------------------------------------------------------------

    @Test
    fun themeId_defaultIsComfort() {
        val profile = DriverProfile("Test")
        assertEquals("comfort", profile.themeId)
    }

    @Test
    fun themeId_customValuePreserved() {
        val profile = DriverProfile("Test", themeId = "night_drive")
        assertEquals("night_drive", profile.themeId)
    }

    @Test
    fun themeId_backwardCompat_defaultsWhenNotSpecified() {
        // Simulates old JSON without themeId field — Gson sets default
        val profile = DriverProfile("Old", 24.0f, "#FF0000")
        assertEquals("comfort", profile.themeId)
    }

    @Test
    fun themeId_differentTheme_notEqual() {
        val a = DriverProfile("Alice", themeId = "comfort")
        val b = DriverProfile("Alice", themeId = "relax")
        assertTrue(a != b)
    }
}
