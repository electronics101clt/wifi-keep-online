package com.zscreen.wifisettings

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.SupplicantState
import android.net.wifi.WifiManager
import android.util.Log
import java.net.NetworkInterface

/**
 * Everything this app needs to know about the radio's current state.
 *
 * The important one is [isApActive]. On these MTK head units a running softAP means
 * wireless CarPlay is holding the radio to talk to a phone, and the chipset cannot do
 * STA and AP at the same time -- switching Wi-Fi client mode on would tear CarPlay's
 * hotspot down. Worse, while the AP is up getWifiState() reports DISABLED, so the
 * naive "Wi-Fi is off, turn it on" read is exactly wrong at that moment. Check AP
 * first, always.
 */
object NetState {

    private const val TAG = "WifiSettings"

    /** Any usable network at all -- Wi-Fi, the built-in modem, a USB dongle. */
    @Suppress("DEPRECATION")
    fun isOnline(context: Context): Boolean {
        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val info = cm.activeNetworkInfo
        return info != null && info.isConnected
    }

    @Suppress("DEPRECATION")
    fun activeNetworkName(context: Context): String {
        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        return cm?.activeNetworkInfo?.typeName ?: "none"
    }

    /**
     * Is the unit running its own hotspot? True means: leave the radio alone.
     *
     * getWifiApState() is @hide, but this targets Oreo, where reflection onto hidden
     * framework methods still works (the greylist clampdown landed in Android 9).
     * If reflection fails on some odd ROM, fall back to looking for a live softAP
     * interface, which no ROM can hide.
     */
    fun isApActive(context: Context): Boolean {
        val wifi = wifiManager(context)
        if (wifi != null) {
            try {
                val m = WifiManager::class.java.getDeclaredMethod("getWifiApState")
                m.isAccessible = true
                val state = m.invoke(wifi) as Int
                // ICS+ uses 10..14 (ENABLING 12, ENABLED 13); pre-ICS used 0..4.
                if (state == 12 || state == 13 || state == 2 || state == 3) {
                    Log.i(TAG, "softAP up (state=$state) -- staying out of the way")
                    return true
                }
                return false
            } catch (e: Exception) {
                Log.w(TAG, "getWifiApState() unavailable, falling back to interfaces", e)
            }
        }
        return hasLiveApInterface()
    }

    /** softAP interfaces on MTK/Qualcomm ROMs: ap0, wlan1, swlan0, softap0. */
    private fun hasLiveApInterface(): Boolean = try {
        NetworkInterface.getNetworkInterfaces().toList().any { nif ->
            val n = nif.name.lowercase()
            val looksLikeAp = n.startsWith("ap") || n.startsWith("swlan") ||
                    n.startsWith("softap") || n == "wlan1"
            looksLikeAp && nif.isUp && nif.inetAddresses.toList().any { !it.isLoopbackAddress }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Could not enumerate interfaces", e)
        false
    }

    /**
     * A genuinely usable Wi-Fi link, not just "the framework says connected".
     *
     * [isOnline] is too loose to hand off on: it also goes true for the built-in modem
     * or a USB dongle, and NetworkInfo can read connected while DHCP is still in flight.
     * So this checks the three things that actually have to be true -- the supplicant
     * has completed association, we are on a real saved network, and we hold an IP.
     *
     * Deliberately does not look at the SSID: on 8.0 `WifiInfo.getSSID()` returns
     * "<unknown ssid>" without location permission, which we do not ask for.
     */
    @Suppress("DEPRECATION")
    fun isWifiConnected(context: Context): Boolean {
        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val net = cm.getNetworkInfo(ConnectivityManager.TYPE_WIFI)
        if (net == null || !net.isConnected) return false

        val info = wifiManager(context)?.connectionInfo ?: return false
        val associated = info.supplicantState == SupplicantState.COMPLETED
        val onNetwork = info.networkId != -1
        val hasIp = info.ipAddress != 0
        Log.d(TAG, "wifi link: associated=$associated network=$onNetwork ip=$hasIp")
        return associated && onNetwork && hasIp
    }

    /**
     * The framework's own verdict on whether the Wi-Fi network actually reaches the
     * internet -- it probes on connect and stamps NET_CAPABILITY_VALIDATED. Free to
     * read; no traffic of our own.
     *
     * Treated as a preference, never a requirement. A phone hotspot with no cell data,
     * or one the probe cannot reach, reads unvalidated while still being a perfectly
     * good link for the tun to sit on. Gating the handoff on this would strand us in
     * exactly the field case this app exists for.
     */
    fun isWifiValidated(context: Context): Boolean {
        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        return try {
            cm.allNetworks.any { net ->
                val caps = cm.getNetworkCapabilities(net)
                caps != null &&
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read network capabilities", e)
            false
        }
    }

    fun wifiManager(context: Context): WifiManager? =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    fun isWifiOn(context: Context): Boolean {
        val s = wifiManager(context)?.wifiState ?: return false
        return s == WifiManager.WIFI_STATE_ENABLED || s == WifiManager.WIFI_STATE_ENABLING
    }

    /**
     * Switch the client radio on. Deprecated from Android 10, where it silently fails
     * for third-party apps -- fine here, this targets API 26 where it works.
     *
     * Nothing else is needed to connect: once the radio is up the framework auto-joins
     * the best saved network in range by itself. That is deliberately the whole
     * "connect" step -- an app cannot join a secured AP it has no password for, and
     * getScanResults() would need location permission plus the location toggle on,
     * which is usually off on a head unit.
     */
    @Suppress("DEPRECATION")
    fun setWifi(context: Context, on: Boolean): Boolean = try {
        wifiManager(context)?.setWifiEnabled(on) ?: false
    } catch (e: SecurityException) {
        Log.w(TAG, "Not allowed to toggle Wi-Fi", e)
        false
    }
}
