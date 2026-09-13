package com.zscreen.wifisettings

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.widget.Toast

/**
 * The launcher entry, and also what the head unit's boot-item list calls -- those lists
 * launch the launcher activity. No UI of its own.
 *
 * It starts the watcher, then enters the Wi-Fi dialog only when there is something to
 * fix: **no Wi-Fi connected and no hotspot up**. A hotspot means wireless CarPlay has
 * the chip and we stay off the screen entirely; an existing connection means there is
 * nothing to do. Either way it falls silent with a toast rather than taking the display.
 *
 * When it does enter, the dialog is the "initial screen", and [WifiWatchService] hands
 * off out of it -- to ZLauncher, which finishes the job by wiring the tunnel -- as soon
 * as the connection verifies.
 *
 * First run only, it walks one-time grants that keep the ROM from reaping the service.
 */
class MainActivity : Activity() {

    private val prompts = ArrayDeque<Intent>()
    private var resumes = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WifiWatchService.start(this)
        Watchdog.arm(this)

        // Enter only when there is something to fix. CarPlay first, as everywhere else
        // in this app: while the hotspot is up getWifiState() reports DISABLED, so
        // checking Wi-Fi first would read the situation exactly backwards.
        if (NetState.isApActive(this)) {
            dismiss(R.string.toast_hotspot)
            return
        }
        if (NetState.isWifiConnected(this)) {
            dismiss(R.string.toast_connected)
            return
        }

        queueOneTimeGrants()
        step()
    }

    /**
     * Nothing to do, so get off the screen.
     *
     * Silent at boot: a toast popping over the radio's UI on every startup is exactly
     * the noise this app is supposed to avoid. When a person taps the icon instead, the
     * toast is the difference between "already connected" and a dead icon.
     *
     * The boot-item list and a finger tap arrive as the same MAIN/LAUNCHER intent, so
     * time since boot is the only thing separating them. Getting it wrong costs one
     * stray toast or one missing one, which is a fair price for not guessing on
     * anything that matters.
     */
    private fun dismiss(resId: Int) {
        if (SystemClock.elapsedRealtime() > BOOT_WINDOW_MS) {
            Toast.makeText(applicationContext, resId, Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    override fun onResume() {
        super.onResume()
        if (isFinishing) return
        // The first resume is our own, before a prompt covers the screen; every one
        // after that is a prompt handing control back.
        if (resumes++ == 0) return
        step()
    }

    /** Fire the next outstanding grant prompt, or fall through to Wi-Fi settings. */
    private fun step() {
        while (prompts.isNotEmpty()) {
            if (start(prompts.removeFirst())) return
        }
        openWifiSettings()
        finish()
    }

    // --- one-time grants -----------------------------------------------------------

    private fun prefs() = getSharedPreferences("wifisettings", Context.MODE_PRIVATE)

    private fun queueOneTimeGrants() {
        val prefs = prefs()

        // Out of the battery optimiser, so the ROM has one less excuse to reap us.
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val exempt = pm?.isIgnoringBatteryOptimizations(packageName) ?: false
        if (!exempt && !prefs.getBoolean(KEY_ASKED_BATTERY, false)) {
            prefs.edit().putBoolean(KEY_ASKED_BATTERY, true).apply()
            prompts.add(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        }

        // Only on Android 10/11, and only because it is the exemption that lets the
        // service press HOME from the background. Never asked for on 8.0 or 9.
        if (Home.needsOverlayGrant(this) && !prefs.getBoolean(KEY_ASKED_OVERLAY, false)) {
            prefs.edit().putBoolean(KEY_ASKED_OVERLAY, true).apply()
            prompts.add(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                    .setData(Uri.parse("package:$packageName"))
            )
        }
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
        const val KEY_ASKED_BATTERY = "asked_battery_whitelist"
        const val KEY_ASKED_OVERLAY = "asked_overlay"
        /** Launches this soon after boot are the radio's autostart, not a finger. */
        const val BOOT_WINDOW_MS = 3 * 60 * 1000L
    }
}
