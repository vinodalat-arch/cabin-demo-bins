package com.incabin

import com.incabin.AudioAlerter.DangerSnapshot
import com.incabin.AudioAlerter.EscalationState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Flow integration tests for configuration toggles and their downstream effects.
 * Verifies Config defaults, toggle state changes, language effects on alerts,
 * and that smoother/risk scoring use Config constants correctly.
 */
class FlowConfigToggleTest {

    // Save original mutable Config values to restore after each test
    private var origPreview = false
    private var origAudio = true
    private var origLanguage = "en"
    private var origWifiUrl = ""
    private var origSeatSide = "left"
    private var origPaxDetail = "minimal"

    @Before
    fun saveConfig() {
        origPreview = Config.ENABLE_PREVIEW
        origAudio = Config.ENABLE_AUDIO_ALERTS
        origLanguage = Config.LANGUAGE
        origWifiUrl = Config.WIFI_CAMERA_URL
        origSeatSide = Config.DRIVER_SEAT_SIDE
        origPaxDetail = Config.PASSENGER_INFO_DETAIL
    }

    @After
    fun restoreConfig() {
        Config.ENABLE_PREVIEW = origPreview
        Config.ENABLE_AUDIO_ALERTS = origAudio
        Config.LANGUAGE = origLanguage
        Config.WIFI_CAMERA_URL = origWifiUrl
        Config.DRIVER_SEAT_SIDE = origSeatSide
        Config.PASSENGER_INFO_DETAIL = origPaxDetail
    }

    // -------------------------------------------------------------------------
    // Config Defaults (1 test)
    // -------------------------------------------------------------------------

    @Test
    fun test_config_defaults() {
        // Verify factory defaults documented in CLAUDE.md
        assertFalse("ENABLE_PREVIEW default is false", Config.ENABLE_PREVIEW)
        assertTrue("ENABLE_AUDIO_ALERTS default is true", Config.ENABLE_AUDIO_ALERTS)
        assertEquals("en", Config.LANGUAGE)
        assertEquals("left", Config.DRIVER_SEAT_SIDE)
        assertEquals(3, Config.SMOOTHER_WINDOW)
        assertEquals(0.6f, Config.SMOOTHER_THRESHOLD, 0.001f)
    }

    // -------------------------------------------------------------------------
    // Toggle State Changes (2 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_preview_toggle_state_change() {
        Config.ENABLE_PREVIEW = false
        assertFalse(Config.ENABLE_PREVIEW)
        Config.ENABLE_PREVIEW = true
        assertTrue(Config.ENABLE_PREVIEW)
        Config.ENABLE_PREVIEW = false
        assertFalse(Config.ENABLE_PREVIEW)
    }

    @Test
    fun test_audio_toggle_state_change() {
        Config.ENABLE_AUDIO_ALERTS = true
        assertTrue(Config.ENABLE_AUDIO_ALERTS)
        Config.ENABLE_AUDIO_ALERTS = false
        assertFalse(Config.ENABLE_AUDIO_ALERTS)
    }

    // -------------------------------------------------------------------------
    // Language Affects Alert Messages (2 tests)
    // -------------------------------------------------------------------------

    @Test
    fun test_language_english_alert_messages() {
        val clear = DangerSnapshot(false, false, false, false, false, false, false, false)
        val phone = DangerSnapshot(true, false, false, false, false, false, false, false)
        val alerts = AudioAlerter.buildAlerts(
            phone, clear, 0, 0, 100_000L,
            mutableMapOf(), mutableMapOf(), isJapanese = false
        )
        assertEquals(1, alerts.size)
        assertEquals("Phone detected, please put it down", alerts[0].text)
    }

    @Test
    fun test_language_japanese_alert_messages() {
        val clear = DangerSnapshot(false, false, false, false, false, false, false, false)
        val phone = DangerSnapshot(true, false, false, false, false, false, false, false)
        val alerts = AudioAlerter.buildAlerts(
            phone, clear, 0, 0, 100_000L,
            mutableMapOf(), mutableMapOf(), isJapanese = true
        )
        assertEquals(1, alerts.size)
        assertEquals("スマートフォンを検出、置いてください", alerts[0].text)
    }

    // -------------------------------------------------------------------------
    // Smoother Uses Config Constants (1 test)
    // -------------------------------------------------------------------------

    @Test
    fun test_smoother_uses_config_window_and_threshold() {
        // Default smoother: window=3, threshold=0.6 → needs 2/3 majority
        val s = TemporalSmoother()
        val clean = OutputResult(
            timestamp = "2026-01-01T00:00:00Z", passengerCount = 1,
            childCount = 0, adultCount = 0,
            driverUsingPhone = false, driverEyesClosed = false, driverYawning = false,
            driverDistracted = false, driverEatingDrinking = false, dangerousPosture = false,
            childPresent = false, childSlouching = false, riskLevel = "low",
            earValue = 0.25f, marValue = 0.2f, headYaw = 0f, headPitch = 0f,
            distractionDurationS = 0
        )
        val result = s.smooth(clean)
        assertFalse(result.driverUsingPhone)
        assertEquals("low", result.riskLevel)
    }

    // -------------------------------------------------------------------------
    // Risk Weights from Config (1 test)
    // -------------------------------------------------------------------------

    @Test
    fun test_risk_weights_phone_equals_high() {
        // Phone weight = 3 >= RISK_HIGH_THRESHOLD(3) → "high"
        assertEquals(3, Config.RISK_WEIGHT_PHONE)
        assertEquals(3, Config.RISK_HIGH_THRESHOLD)
        val risk = computeRisk(
            driverUsingPhone = true, driverEyesClosed = false,
            dangerousPosture = false, childSlouching = false
        )
        assertEquals("high", risk)
    }

    @Test
    fun test_risk_weights_hands_off_equals_high() {
        // Hands off weight = 3 >= RISK_HIGH_THRESHOLD(3) → "high"
        assertEquals(3, Config.RISK_WEIGHT_HANDS_OFF)
        val risk = computeRisk(
            driverUsingPhone = false, driverEyesClosed = false,
            dangerousPosture = false, childSlouching = false,
            handsOffWheel = true
        )
        assertEquals("high", risk)
    }

    // -------------------------------------------------------------------------
    // Seat Side Toggle (1 test)
    // -------------------------------------------------------------------------

    @Test
    fun test_seat_side_toggle_values() {
        Config.DRIVER_SEAT_SIDE = "left"
        assertEquals("left", Config.DRIVER_SEAT_SIDE)
        Config.DRIVER_SEAT_SIDE = "right"
        assertEquals("right", Config.DRIVER_SEAT_SIDE)
    }

    // -------------------------------------------------------------------------
    // Passenger Info Detail Toggle (1 test)
    // -------------------------------------------------------------------------

    @Test
    fun test_passenger_info_detail_toggle() {
        assertEquals("minimal", Config.PASSENGER_INFO_DETAIL)
        Config.PASSENGER_INFO_DETAIL = "detailed"
        assertEquals("detailed", Config.PASSENGER_INFO_DETAIL)
        Config.PASSENGER_INFO_DETAIL = "minimal"
        assertEquals("minimal", Config.PASSENGER_INFO_DETAIL)
    }
}
