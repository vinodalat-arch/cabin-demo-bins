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
    }
}
