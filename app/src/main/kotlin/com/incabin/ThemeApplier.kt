package com.incabin

import android.app.UiModeManager
import android.content.Context
import android.provider.Settings
import android.util.Log

/**
 * Applies [CabinTheme] settings to the device.
 * Separated from CabinTheme for testability — pure Config writes vs Android API calls.
 */
object ThemeApplier {

    private const val TAG = "ThemeApplier"

    /** Pure: sets Config.HVAC_BASE_TEMP_C and Config.CURRENT_DRIVER_AMBIENT_COLOR. */
    fun applyToConfig(theme: CabinTheme) {
        Config.HVAC_BASE_TEMP_C = theme.tempC
        Config.CURRENT_DRIVER_AMBIENT_COLOR = theme.ambientColorHex
    }

    /** Sets screen brightness (0-255) via Settings.System. */
    fun applyBrightness(brightness: Int, context: Context) {
        try {
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                brightness.coerceIn(0, 255)
            )
            Log.i(TAG, "Brightness set to $brightness")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set brightness", e)
        }
    }

    /** Sets night mode via UiModeManager. nightMode: -1=auto, 1=off, 2=on. */
    fun applyNightMode(nightMode: Int, context: Context) {
        try {
            val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
            if (uiModeManager != null) {
                uiModeManager.nightMode = when (nightMode) {
                    1 -> UiModeManager.MODE_NIGHT_NO
                    2 -> UiModeManager.MODE_NIGHT_YES
                    else -> UiModeManager.MODE_NIGHT_AUTO
                }
                Log.i(TAG, "Night mode set to $nightMode")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set night mode", e)
        }
    }

    /** Applies all theme settings. Each step is independently try-caught. */
    fun applyAll(theme: CabinTheme, context: Context) {
        applyToConfig(theme)
        applyBrightness(theme.brightness, context)
        applyNightMode(theme.nightMode, context)
    }
}
