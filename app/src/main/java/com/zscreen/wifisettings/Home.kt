package com.zscreen.wifisettings

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * Pressing HOME, so ZLauncher comes forward and wires the tun up.
 *
 * Not an injected keypress: KEYCODE_HOME needs INJECT_EVENTS (a signature permission)
 * and `input keyevent 3` needs root, neither of which we have. Launching the HOME
 * category does the same job with no permission at all.
 *
 * The catch is Android 10 and 11. Starting an activity from the background is blocked
 * there, and -- despite the common belief -- running a foreground service is *not* on
 * the exemption list. Holding SYSTEM_ALERT_WINDOW is, which is why the app asks for
 * "draw over other apps" on those versions and only those. On 8.0 and 9 the start is
 * unrestricted and the permission is never requested.
 */
object Home {

    private const val TAG = "WifiSettings"

    fun go(context: Context): Boolean {
        if (!canStartFromBackground(context)) {
            Log.w(TAG, "skipping HOME: Android ${Build.VERSION.SDK_INT} blocks background " +
                    "activity starts without SYSTEM_ALERT_WINDOW")
            return false
        }
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            Log.i(TAG, "HOME -- handing the screen to the launcher")
            true
        } catch (e: Exception) {
            Log.w(TAG, "HOME failed", e)
            false
        }
    }

    /** Below API 29 there is no background-start restriction to work around. */
    fun canStartFromBackground(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || Settings.canDrawOverlays(context)

    fun needsOverlayGrant(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !Settings.canDrawOverlays(context)
}
