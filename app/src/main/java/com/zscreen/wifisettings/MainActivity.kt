package com.zscreen.wifisettings

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast

/**
 * The launcher entry. Two jobs, no UI of its own:
 *
 *  1. Make sure the watcher is running (this is also what the head unit's boot-item
 *     list ends up calling, since those lists launch the launcher activity).
 *  2. Open the system Wi-Fi settings screen, because that is what a person tapping
 *     the icon wants.
 *
 * Note the asymmetry with the boot path: [BootReceiver] never opens settings. Throwing
 * the Wi-Fi page over the radio's UI at every startup is the opposite of staying out of
 * the way.
 */
class MainActivity : Activity() {

    /** Set while the battery-optimisation dialog is up, so we know to wait for it. */
    private var awaitingWhitelist = false
    private var resumes = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WifiWatchService.start(this)
        Watchdog.arm(this)

        if (!alreadyHandledWhitelist()) {
            // One time only: get out of the battery optimiser so the ROM has one less
            // excuse to reap us.
            prefs().edit().putBoolean(KEY_ASKED, true).apply()
            awaitingWhitelist = start(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        }

        if (!awaitingWhitelist) {
            openWifiSettings()
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!awaitingWhitelist) return
        // First resume is us, before the dialog covers the screen; the second is the
        // dialog handing control back.
        if (++resumes >= 2) {
            awaitingWhitelist = false
            openWifiSettings()
            finish()
        }
    }

    // --- battery optimisation whitelist -------------------------------------------

    private fun prefs() = getSharedPreferences("wifisettings", Context.MODE_PRIVATE)

    /** True if we are already exempt, or we have asked once and been told no. */
    private fun alreadyHandledWhitelist(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm != null && pm.isIgnoringBatteryOptimizations(packageName)) return true
        return prefs().getBoolean(KEY_ASKED, false)
    }

    // --- settings ------------------------------------------------------------------

    private fun openWifiSettings() {
        // 1. The documented, public way. Works on stock Android 8.0, no permission needed.
        if (start(Intent(Settings.ACTION_WIFI_SETTINGS))) return

        // 2. Some OEM/head-unit ROMs strip the alias above but keep the activity itself.
        val direct = Intent(Intent.ACTION_MAIN).setComponent(
            ComponentName("com.android.settings", "com.android.settings.wifi.WifiSettings")
        )
        if (start(direct)) return

        // 3. Last resort: drop the user in the top-level settings list.
        if (start(Intent(Settings.ACTION_SETTINGS))) return

        Toast.makeText(this, R.string.no_settings_app, Toast.LENGTH_LONG).show()
    }

    private fun start(intent: Intent): Boolean = try {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "No activity for ${intent.action ?: intent.component}", e)
        false
    } catch (e: SecurityException) {
        Log.w(TAG, "Not allowed to start ${intent.component}", e)
        false
    }

    private companion object {
        const val TAG = "WifiSettings"
        const val KEY_ASKED = "asked_battery_whitelist"
    }
}
