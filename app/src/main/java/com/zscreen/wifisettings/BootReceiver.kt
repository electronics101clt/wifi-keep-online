package com.zscreen.wifisettings

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Every way the watcher can be brought up: cold boot, waking from ACC-off sleep (see
 * [Wake] -- far more common on a head unit than a real boot), a reinstall, and the
 * watchdog alarm.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val known = action == Intent.ACTION_BOOT_COMPLETED ||
                action == Intent.ACTION_MY_PACKAGE_REPLACED ||
                action == Watchdog.ACTION_POKE ||
                action in Wake.ACTIONS
        if (!known) return

        Log.i(TAG, "start from $action")
        WifiWatchService.start(context)
        Watchdog.arm(context)
    }

    private companion object {
        const val TAG = "ZWifiKeep"
    }
}
