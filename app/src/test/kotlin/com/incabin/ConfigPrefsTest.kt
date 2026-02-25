package com.incabin

import org.junit.Assert.*
import org.junit.Test

class ConfigPrefsTest {

    @Test
    fun `prefs name is incabin_prefs`() {
        assertEquals("incabin_prefs", ConfigPrefs.PREFS_NAME)
    }

    @Test
    fun `all 8 preference keys are distinct`() {
        val keys = listOf(
            ConfigPrefs.PREF_PREVIEW_ENABLED,
            ConfigPrefs.PREF_AUDIO_ENABLED,
            ConfigPrefs.PREF_LANGUAGE,
            ConfigPrefs.PREF_SEAT_SIDE,
            ConfigPrefs.PREF_WIFI_URL,
            ConfigPrefs.PREF_PASSENGER_DETAIL,
            ConfigPrefs.PREF_ASIMO_SIZE,
            ConfigPrefs.PREF_BOTTOM_WIDGET
        )
        assertEquals(8, keys.toSet().size)
    }

    @Test
    fun `key string values match expected`() {
        assertEquals("preview_enabled", ConfigPrefs.PREF_PREVIEW_ENABLED)
        assertEquals("audio_enabled", ConfigPrefs.PREF_AUDIO_ENABLED)
        assertEquals("language", ConfigPrefs.PREF_LANGUAGE)
        assertEquals("seat_side", ConfigPrefs.PREF_SEAT_SIDE)
        assertEquals("wifi_camera_url", ConfigPrefs.PREF_WIFI_URL)
        assertEquals("passenger_info_detail", ConfigPrefs.PREF_PASSENGER_DETAIL)
        assertEquals("asimo_size", ConfigPrefs.PREF_ASIMO_SIZE)
        assertEquals("bottom_widget", ConfigPrefs.PREF_BOTTOM_WIDGET)
    }
}
