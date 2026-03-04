package com.incabin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for CabinTheme data class and companion functions.
 * Pure Kotlin — no Android dependencies.
 */
class CabinThemeTest {

    @Test
    fun themes_has5Entries() {
        assertEquals(5, CabinTheme.THEMES.size)
    }

    @Test
    fun themes_allHaveUniqueIds() {
        val ids = CabinTheme.THEMES.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun findById_returnsCorrectTheme() {
        val theme = CabinTheme.findById("relax")
        assertNotNull(theme)
        assertEquals("Relax", theme!!.displayName)
        assertEquals("リラックス", theme.displayNameJa)
    }

    @Test
    fun findById_returnsNullForUnknown() {
        assertNull(CabinTheme.findById("nonexistent"))
    }

    @Test
    fun defaultTheme_isComfort() {
        val theme = CabinTheme.defaultTheme()
        assertEquals("comfort", theme.id)
        assertEquals("Comfort", theme.displayName)
    }

    @Test
    fun allBrightness_inValidRange() {
        for (theme in CabinTheme.THEMES) {
            assertTrue("${theme.id} brightness ${theme.brightness} out of range",
                theme.brightness in 0..255)
        }
    }

    @Test
    fun allTemps_inValidRange() {
        for (theme in CabinTheme.THEMES) {
            assertTrue("${theme.id} temp ${theme.tempC} out of range",
                theme.tempC in 16.0f..28.0f)
        }
    }

    @Test
    fun allColors_validHexFormat() {
        val hexPattern = Regex("^#[0-9A-Fa-f]{6}$")
        for (theme in CabinTheme.THEMES) {
            assertTrue("${theme.id} color ${theme.ambientColorHex} invalid hex",
                hexPattern.matches(theme.ambientColorHex))
        }
    }

    @Test
    fun allDisplayNames_nonEmpty() {
        for (theme in CabinTheme.THEMES) {
            assertTrue("${theme.id} displayName empty", theme.displayName.isNotEmpty())
            assertTrue("${theme.id} displayNameJa empty", theme.displayNameJa.isNotEmpty())
        }
    }

    @Test
    fun allDescriptions_nonEmpty() {
        for (theme in CabinTheme.THEMES) {
            assertTrue("${theme.id} description empty", theme.description.isNotEmpty())
            assertTrue("${theme.id} descriptionJa empty", theme.descriptionJa.isNotEmpty())
        }
    }

    @Test
    fun nightMode_validValues() {
        val validModes = setOf(-1, 1, 2)
        for (theme in CabinTheme.THEMES) {
            assertTrue("${theme.id} nightMode ${theme.nightMode} invalid",
                theme.nightMode in validModes)
        }
    }

    @Test
    fun specificThemes_haveExpectedValues() {
        val energize = CabinTheme.findById("energize")!!
        assertEquals(20.0f, energize.tempC, 0.001f)
        assertEquals(255, energize.brightness)
        assertEquals(1, energize.nightMode)  // off
        assertEquals("#2ECC71", energize.ambientColorHex)

        val nightDrive = CabinTheme.findById("night_drive")!!
        assertEquals(21.0f, nightDrive.tempC, 0.001f)
        assertEquals(80, nightDrive.brightness)
        assertEquals(2, nightDrive.nightMode)  // on
        assertEquals("#E74C3C", nightDrive.ambientColorHex)

        val eco = CabinTheme.findById("eco")!!
        assertEquals(26.0f, eco.tempC, 0.001f)
        assertEquals(-1, eco.nightMode)  // auto
    }
}
