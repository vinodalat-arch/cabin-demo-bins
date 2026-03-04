package com.incabin

import android.content.Context

/**
 * Centralized SharedPreferences constants and loader for Config volatile fields.
 * Used by both MainActivity (user opens app) and InCabinService (BootReceiver path)
 * to ensure user settings are always applied, even after reboot.
 */
object ConfigPrefs {
    const val PREFS_NAME = "incabin_prefs"
    const val PREF_PREVIEW_ENABLED = "preview_enabled"
    const val PREF_AUDIO_ENABLED = "audio_enabled"
    const val PREF_LANGUAGE = "language"
    const val PREF_SEAT_SIDE = "seat_side"
    const val PREF_WIFI_URL = "wifi_camera_url"
    const val PREF_INFERENCE_MODE = "inference_mode"
    const val PREF_VLM_URL = "vlm_server_url"
    const val PREF_INFERENCE_FPS = "inference_fps"
    const val PREF_PASSENGER_DETAIL = "passenger_info_detail"
    const val PREF_ASIMO_SIZE = "asimo_size"
    const val PREF_BOTTOM_WIDGET = "bottom_widget"
    const val PREF_BRAND = "brand"
    const val PREF_AUTO_CLIMATE = "auto_climate"
    const val PREF_DRIVER_PROFILES = "driver_profiles_enabled"
    const val PREF_CHILD_LEFT_BEHIND = "child_left_behind_enabled"
    const val PREF_SEAT_MASSAGE = "seat_massage_enabled"
    const val PREF_AMBIENT_LIGHT = "ambient_light_enabled"
    const val PREF_AMBIENT_COMFORT = "ambient_comfort_enabled"
    const val PREF_ZONE_HVAC = "zone_hvac_enabled"
    const val PREF_WELLNESS_COACH = "wellness_coach_enabled"
    const val PREF_QUIET_MODE = "quiet_mode_enabled"
    const val PREF_FATIGUE_COMFORT = "fatigue_comfort_enabled"
    const val PREF_NAP_MODE = "nap_mode_enabled"
    const val PREF_CHILD_COMFORT = "child_comfort_enabled"
    const val PREF_ECO_CABIN = "eco_cabin_enabled"
    const val PREF_ARRIVAL_PREP = "arrival_prep_enabled"

    /** Load all persisted preferences into Config volatile fields. */
    fun loadIntoConfig(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        Config.ENABLE_PREVIEW = prefs.getBoolean(PREF_PREVIEW_ENABLED, false)
        Config.ENABLE_AUDIO_ALERTS = prefs.getBoolean(PREF_AUDIO_ENABLED, true)
        Config.LANGUAGE = prefs.getString(PREF_LANGUAGE, "en") ?: "en"
        Config.DRIVER_SEAT_SIDE = prefs.getString(PREF_SEAT_SIDE, "left") ?: "left"
        Config.WIFI_CAMERA_URL = prefs.getString(PREF_WIFI_URL, "") ?: ""
        Config.INFERENCE_MODE = prefs.getString(PREF_INFERENCE_MODE, "local") ?: "local"
        Config.VLM_SERVER_URL = prefs.getString(PREF_VLM_URL, "") ?: ""
        Config.INFERENCE_FPS = prefs.getInt(PREF_INFERENCE_FPS, 1).coerceIn(1, 3)
        Config.PASSENGER_INFO_DETAIL = prefs.getString(PREF_PASSENGER_DETAIL, "minimal") ?: "minimal"
        Config.ASIMO_SIZE = prefs.getString(PREF_ASIMO_SIZE, "m") ?: "m"
        Config.BOTTOM_WIDGET = prefs.getString(PREF_BOTTOM_WIDGET, "none") ?: "none"
        Config.BRAND = prefs.getString(PREF_BRAND, "honda") ?: "honda"
        Config.ENABLE_AUTO_CLIMATE = prefs.getBoolean(PREF_AUTO_CLIMATE, false)
        Config.ENABLE_DRIVER_PROFILES = prefs.getBoolean(PREF_DRIVER_PROFILES, false)
        Config.ENABLE_CHILD_LEFT_BEHIND = prefs.getBoolean(PREF_CHILD_LEFT_BEHIND, true)
        Config.ENABLE_SEAT_MASSAGE = prefs.getBoolean(PREF_SEAT_MASSAGE, true)
        Config.ENABLE_AMBIENT_LIGHT = prefs.getBoolean(PREF_AMBIENT_LIGHT, false)
        Config.ENABLE_AMBIENT_COMFORT = prefs.getBoolean(PREF_AMBIENT_COMFORT, false)
        Config.ENABLE_ZONE_HVAC = prefs.getBoolean(PREF_ZONE_HVAC, false)
        Config.ENABLE_WELLNESS_COACH = prefs.getBoolean(PREF_WELLNESS_COACH, true)
        Config.ENABLE_QUIET_MODE = prefs.getBoolean(PREF_QUIET_MODE, true)
        Config.ENABLE_FATIGUE_COMFORT = prefs.getBoolean(PREF_FATIGUE_COMFORT, true)
        Config.ENABLE_NAP_MODE = prefs.getBoolean(PREF_NAP_MODE, true)
        Config.ENABLE_CHILD_COMFORT = prefs.getBoolean(PREF_CHILD_COMFORT, true)
        Config.ENABLE_ECO_CABIN = prefs.getBoolean(PREF_ECO_CABIN, true)
        Config.ENABLE_ARRIVAL_PREP = prefs.getBoolean(PREF_ARRIVAL_PREP, true)
    }
}
