package com.zscreen.wifisettings

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * The handoff: bring the launcher forward so it can wire the VPN tunnel up.
 *
 * Goes to whatever is set as the home app -- no hard-coded package. On this unit that
 * resolves to ZLauncher (`app.lawnchair`), which carries the tunnel in its own
 * `app.lawnchair.service.KeepAliveService`, but the choice belongs to whoever set the
 * launcher, not to this app. Change the home app and the handoff follows it.
 *
 * We do not reach into KeepAliveService -- it is another app's component and not ours to
 * start. Foregrounding the launcher is the whole handoff; it wires its own tun from there.
 *
 * Not an injected keypress either way: KEYCODE_HOME needs INJECT_EVENTS (a signature
 * permission) and `input keyevent 3` needs root.
 *
 * The catch is Android 10 and 11. Starting an activity from the background is blocked
 * there, and -- despite the common belief -- running a foreground service is *not* on
 * the exemption list. Holding SYSTEM_ALERT_WINDOW is, which is why the app asks for
 * "draw over other apps" on those versions and only those. On 8.0 and 9 the start is
 * unrestricted and the permission is never requested.
 */
object Home {

    private const val TAG = "ZWifiKeep"

    fun go(context: Context): Boolean {
        if (!canStartFromBackground(context)) {
            Log.w(TAG, "skipping handoff: Android ${Build.VERSION.SDK_INT} blocks background " +
                    "activity starts without SYSTEM_ALERT_WINDOW")
            return false
        }
        val intent = homeIntent()
        return try {
            context.startActivity(intent)
            Log.i(TAG, "handoff -> ${resolvedHome(context) ?: "home"}")
            true
        } catch (e: Exception) {
            Log.w(TAG, "handoff failed", e)
            false
        }
    }

    private fun homeIntent() = Intent(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_HOME)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Only for the log line -- which launcher actually took the handoff. */
    private fun resolvedHome(context: Context): String? = try {
        context.packageManager
            .resolveActivity(homeIntent(), PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
    } catch (e: Exception) {
        null
    }

    /** Below API 29 there is no background-start restriction to work around. */
    fun canStartFromBackground(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || Settings.canDrawOverlays(context)

    fun needsOverlayGrant(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !Settings.canDrawOverlays(context)
}
