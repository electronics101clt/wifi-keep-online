package com.zscreen.wifisettings

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * The handoff: bring the launcher forward so it can wire the VPN tunnel up.
 *
 * ZLauncher (`app.lawnchair`, a Lawnchair fork) carries the tunnel in its own
 * `app.lawnchair.service.KeepAliveService`. We do not reach into that service -- it is
 * another app's component and not ours to start. Foregrounding the launcher is the whole
 * handoff; it wires its own tun from there.
 *
 * We target ZLauncher explicitly when it is installed, because a plain CATEGORY_HOME
 * only lands on it if it happens to be the *default* home app. If it is not installed,
 * or the explicit start fails, this falls back to whatever home is set.
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

    private const val TAG = "WifiSettings"
    private const val ZLAUNCHER = "app.lawnchair"

    fun go(context: Context): Boolean {
        if (!canStartFromBackground(context)) {
            Log.w(TAG, "skipping handoff: Android ${Build.VERSION.SDK_INT} blocks background " +
                    "activity starts without SYSTEM_ALERT_WINDOW")
            return false
        }

        zlauncherHome(context)?.let { explicit ->
            if (start(context, explicit, "ZLauncher")) return true
        }
        return start(context, homeIntent(), "default home")
    }

    private fun homeIntent() = Intent(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_HOME)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** ZLauncher's own HOME activity, if it is installed on this unit. */
    private fun zlauncherHome(context: Context): Intent? = try {
        context.packageManager
            .queryIntentActivities(homeIntent(), 0)
            .firstOrNull { it.activityInfo?.packageName == ZLAUNCHER }
            ?.activityInfo
            ?.let { info ->
                homeIntent().setClassName(info.packageName, info.name)
            }
    } catch (e: Exception) {
        Log.w(TAG, "Could not look up $ZLAUNCHER", e)
        null
    }

    private fun start(context: Context, intent: Intent, what: String): Boolean = try {
        context.startActivity(intent)
        Log.i(TAG, "handoff -> $what")
        true
    } catch (e: Exception) {
        Log.w(TAG, "handoff to $what failed", e)
        false
    }

    /** Below API 29 there is no background-start restriction to work around. */
    fun canStartFromBackground(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || Settings.canDrawOverlays(context)

    fun needsOverlayGrant(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !Settings.canDrawOverlays(context)
}
