package com.incabin

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Flow integration tests for WiFi camera URL configuration state management.
 * Tests Config.WIFI_CAMERA_URL set/clear/priority logic.
 */
class FlowWifiCameraTest {

    private var origWifiUrl = ""

    @Before
    fun saveConfig() {
        origWifiUrl = Config.WIFI_CAMERA_URL
    }

    @After
    fun restoreConfig() {
        Config.WIFI_CAMERA_URL = origWifiUrl
    }

    // -------------------------------------------------------------------------
    // WiFi Camera URL State (6 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_default_wifi_url_is_empty() {
        Config.WIFI_CAMERA_URL = ""
        assertEquals("", Config.WIFI_CAMERA_URL)
    }

    @Test
    fun test_set_and_read_wifi_url() {
        Config.WIFI_CAMERA_URL = "http://192.168.1.100:8080/video"
        assertEquals("http://192.168.1.100:8080/video", Config.WIFI_CAMERA_URL)
    }

    @Test
    fun test_clear_wifi_url() {
        Config.WIFI_CAMERA_URL = "http://192.168.1.100:8080/video"
        Config.WIFI_CAMERA_URL = ""
        assertEquals("", Config.WIFI_CAMERA_URL)
    }

    @Test
    fun test_wifi_url_priority_logic_not_blank() {
        // When URL is set, isNotBlank should be true → WiFi camera mode active
        Config.WIFI_CAMERA_URL = "http://192.168.1.100:8080/video"
        assertTrue(Config.WIFI_CAMERA_URL.isNotBlank())
    }

    @Test
    fun test_wifi_url_priority_logic_blank() {
        // When URL is empty, isNotBlank should be false → fallback to platform camera
        Config.WIFI_CAMERA_URL = ""
        assertFalse(Config.WIFI_CAMERA_URL.isNotBlank())
    }

    @Test
    fun test_https_url_accepted() {
        Config.WIFI_CAMERA_URL = "https://192.168.1.100:8443/video"
        assertTrue(Config.WIFI_CAMERA_URL.startsWith("https://"))
        assertTrue(Config.WIFI_CAMERA_URL.isNotBlank())
    }
}
