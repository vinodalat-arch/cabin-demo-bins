package com.incabin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Auto-starts InCabinService on device boot for automotive platforms.
 * Only fires startForegroundService on SA8155/SA8255/SA8295; does nothing on generic Android.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"

        /** Pure function: should auto-start on this platform? */
        fun shouldAutoStart(platform: Platform): Boolean {
            return platform == Platform.SA8155 ||
                platform == Platform.SA8255 ||
                platform == Platform.SA8295
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return

        val profile = PlatformProfile.detect()
        Log.i(TAG, "Boot completed on ${profile.platform}")

        if (!shouldAutoStart(profile.platform)) {
            Log.i(TAG, "Auto-start disabled for ${profile.platform}")
            return
        }

        Log.i(TAG, "Auto-starting InCabinService")
        try {
            val serviceIntent = Intent(context, InCabinService::class.java).apply {
                action = InCabinService.ACTION_START
            }
            context.startForegroundService(serviceIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to auto-start service", e)
        }
    }
}
