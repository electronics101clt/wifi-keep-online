package com.zscreen.wifisettings

import android.app.Activity
import android.content.ActivityNotFoundException
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
 * launch the launcher activity. No UI of its own, and it never opens the Wi-Fi menu
 * itself. Three outcomes:
 *
 *  - **Hotspot up** -- wireless CarPlay has the chip. Get off the screen, touch nothing.
 *  - **Already connected** -- nothing to fix, so dismiss and continue: close up and go
 *    to the home screen, putting ZLauncher in front to get on with the tunnel.
 *  - **Neither** -- hand over to [WifiWatchService], which switches the radio on and
 *    lets the framework join a saved profile if one is visible. Only if that automatic
 *    path runs out of things to try does the service put the menu up.
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

        // Queued before the early returns below, not after. Previously these sat past
        // the already-connected return, so on a healthy unit -- the normal case -- the
        // battery-optimisation grant was never once requested, and the service ran
        // unprotected forever. Measured on an AC8257: whitelist empty, oom_score_adj
        // 200. The grants are the whole reason the ROM leaves us alone, so they have to
        // be asked for on a good boot, not only on a broken one.
        queueOneTimeGrants()

        // CarPlay first, as everywhere else in this app: while the hotspot is up
        // getWifiState() reports DISABLED, so checking Wi-Fi first would read the
        // situation exactly backwards. Nothing to do and nothing to hand off.
        if (NetState.isApActive(this)) {
            dismiss(R.string.toast_hotspot, home = false)
            return
        }


        // Already connected: dismiss and continue -- close up and go to the home
        // screen, so ZLauncher is in front and can get on with the tunnel.
        if (NetState.isWifiConnected(this)) {
            dismiss(R.string.toast_connected, home = true)
            return
        }

        // Otherwise the service takes it from here: switch the radio on and let the
        // framework join a saved profile if one is visible. The menu is not opened
        // here -- it only goes up if that automatic path runs out of things to try.
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
    private fun dismiss(resId: Int, home: Boolean) {
        if (SystemClock.elapsedRealtime() > BOOT_WINDOW_MS) {
            Toast.makeText(applicationContext, resId, Toast.LENGTH_SHORT).show()
        }
        // Outstanding grants still get asked for -- they are what keeps the service
        // alive, and a connected unit is the calmest moment to ask.
        if (prompts.isNotEmpty()) {
            pendingHome = home
            step()
            return
        }
        if (home) Home.go(this)
        finish()
    }

    /** Whether to go home once the grant prompts are done. */
    private var pendingHome = false

    override fun onResume() {
        super.onResume()
        if (isFinishing) return
        // The first resume is our own, before a prompt covers the screen; every one
        // after that is a prompt handing control back.
        if (resumes++ == 0) return
        step()
    }

    /** Fire the next outstanding grant prompt, then get out of the way. */
    private fun step() {
        while (prompts.isNotEmpty()) {
            if (start(prompts.removeFirst())) return
        }
        if (pendingHome) Home.go(this)
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
        // service start activities from the background -- both the handoff home and the
        // Wi-Fi menu, which on those versions is the only way the radio gets switched
        // on at all. Never asked for on 8.0 or 9.
        if (Home.needsOverlayGrant(this) && !prefs.getBoolean(KEY_ASKED_OVERLAY, false)) {
            prefs.edit().putBoolean(KEY_ASKED_OVERLAY, true).apply()
            prompts.add(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                    .setData(Uri.parse("package:$packageName"))
            )
        }
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
        const val TAG = "ZWifiKeep"
        const val KEY_ASKED_BATTERY = "asked_battery_whitelist"
        const val KEY_ASKED_OVERLAY = "asked_overlay"
        /** Launches this soon after boot are the radio's autostart, not a finger. */
        const val BOOT_WINDOW_MS = 3 * 60 * 1000L
    }
}
