package com.zscreen.wifisettings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * The menu, opened only when the automatic path could not get there on its own.
 *
 * Two reasons it gets used:
 *
 *  - Nothing saved was in range, so the framework had nothing to auto-join.
 *  - We are on Android 10 or 11, where `setWifiEnabled()` silently fails for a
 *    third-party app. There the menu is not a fallback, it is the only path: the app
 *    cannot switch the radio on at all, so it puts the toggle in front of the user.
 *    API 29 added a slide-up panel for exactly this, which is far less intrusive than
 *    the full settings page, so it is preferred where it exists.
 */
object WifiMenu {

    private const val TAG = "WifiSettings"

    fun open(context: Context): Boolean {
        // 1. The slide-up Wi-Fi panel, on the versions that have it (API 29+).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (start(context, Intent(Settings.Panel.ACTION_WIFI))) return true
        }

        // 2. The documented settings page. Works on stock 8.0, no permission needed.
        if (start(context, Intent(Settings.ACTION_WIFI_SETTINGS))) return true

        // 3. Some OEM/head-unit ROMs strip the alias above but keep the activity itself.
        val direct = Intent(Intent.ACTION_MAIN).setComponent(
            ComponentName("com.android.settings", "com.android.settings.wifi.WifiSettings")
        )
        if (start(context, direct)) return true

        // 4. Last resort: the top-level settings list.
        return start(context, Intent(Settings.ACTION_SETTINGS))
    }

    private fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Log.i(TAG, "menu -> ${intent.action ?: intent.component}")
        true
    } catch (e: Exception) {
        Log.w(TAG, "menu ${intent.action ?: intent.component} unavailable", e)
        false
    }
}
