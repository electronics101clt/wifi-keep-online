package com.zscreen.wifisettings

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Every way the watcher can be brought up: cold boot, the quick-boot variants these
 * units use instead, a reinstall, and the watchdog alarm.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
            Watchdog.ACTION_POKE -> {
                Log.i(TAG, "start from ${intent.action}")
                WifiWatchService.start(context)
                Watchdog.arm(context)
            }
        }
    }

    private companion object {
        const val TAG = "ZWifiKeep"
    }
}
