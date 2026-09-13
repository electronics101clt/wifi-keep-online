package com.zscreen.wifisettings

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log

/**
 * Sits in the background watching for the absence of a connection, and kicks the Wi-Fi
 * radio back on when it finds one. Foreground service so Oreo's background execution
 * limits cannot reap it, START_STICKY so the head unit restarts it if it dies.
 *
 * Standing rule: if the unit is running its own hotspot, that is wireless CarPlay, and
 * this service does nothing at all until the hotspot is gone. See [NetState.isApActive].
 */
class WifiWatchService : Service() {

    private lateinit var worker: HandlerThread
    private lateinit var handler: Handler

    /** Did *we* switch the radio on? Only then will we switch it back off for CarPlay. */
    private var weEnabledWifi = false

    /** Consecutive failed kicks, used to back the retry interval off. */
    private var strikes = 0

    /** Wall-clock of the last enable attempt, so we give association time to happen. */
    private var lastKick = 0L

    /** When the AP last went away -- we hold off briefly so we don't race its teardown. */
    private var apEndedAt = 0L

    private var lastNote: String? = null

    /** Have we seen the unit offline yet this service lifetime? */
    private var sawOffline = false

    /** HOME is pressed once, on the first connection we watched come up. */
    private var homeSent = false

    /** Consecutive passes where the Wi-Fi link checked out, before we hand off. */
    private var confirmations = 0

    /**
     * Once the menu has gone up we stop touching the radio and wait for the user.
     *
     * Fighting for control is the thing that could break Android Auto or CarPlay: a
     * service that keeps flipping Wi-Fi back on every 20s is exactly what you do not
     * want running underneath a projection session. One automatic attempt, one prompt,
     * then hands off the decision. Cleared the moment a connection appears, so a later
     * dropout gets a fresh attempt of its own.
     */
    private var handedToUser = false

    private val events = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.d(TAG, "event ${intent.action}")
            schedule(0)
        }
    }

    override fun onCreate() {
        super.onCreate()
        worker = HandlerThread("wifi-watch").apply { start() }
        handler = Handler(worker.looper)

        startForeground(NOTE_ID, note(getString(R.string.state_starting)))
        Watchdog.arm(this)

        @Suppress("DEPRECATION")
        val filter = IntentFilter().apply {
            addAction(ConnectivityManager.CONNECTIVITY_ACTION)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            // @hide constant, but the broadcast itself is public traffic on every ROM.
            addAction("android.net.wifi.WIFI_AP_STATE_CHANGED")
        }
        registerReceiver(events, filter)

        schedule(FIRST_LOOK_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        schedule(0)
        return START_STICKY
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(events)
        } catch (e: IllegalArgumentException) {
            // never registered; nothing to do
        }
        handler.removeCallbacksAndMessages(null)
        worker.quitSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Some of these launchers kill the task when you clear recents. Come straight back.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Watchdog.pokeIn(this, 3_000L)
        super.onTaskRemoved(rootIntent)
    }

    private fun schedule(delayMs: Long) {
        handler.removeCallbacks(evaluate)
        handler.postDelayed(evaluate, delayMs)
    }

    private val evaluate = Runnable { evaluateNow() }

    private fun evaluateNow() {
        val now = System.currentTimeMillis()

        // 1. CarPlay first. Always. While the hotspot is up, getWifiState() lies and
        //    says DISABLED, so checking Wi-Fi before the AP would read it backwards.
        if (NetState.isApActive(this)) {
            if (weEnabledWifi) {
                Log.i(TAG, "AP came up after our kick -- handing the radio back")
                NetState.setWifi(this, false)
                weEnabledWifi = false
            }
            apEndedAt = 0L
            strikes = 0
            update(getString(R.string.state_carplay))
            schedule(AP_POLL_MS)
            return
        }
        if (apEndedAt == 0L) apEndedAt = now
        val sinceAp = now - apEndedAt

        // 2. Already online -- by Wi-Fi, the built-in modem, whatever. Nothing to do.
        if (NetState.isOnline(this)) {
            strikes = 0
            weEnabledWifi = false

            // The boot-time handoff: the initial screen has its connection, so get off
            // it and let ZLauncher come forward to wire the tun up.
            //
            // sawOffline gates this to a transition we actually watched happen. If the
            // service starts while the unit is already online -- a watchdog poke an hour
            // into a drive -- there was no handoff to make, and pressing HOME would just
            // yank the driver out of whatever they were using. homeSent keeps it to the
            // first connection only, for the same reason: a mid-drive reconnect is not
            // an "initial screen".
            // Connected again -- the user (or the framework) sorted it out. Re-arm, so
            // the next dropout gets its own single attempt and its own single prompt.
            handedToUser = false

            if (sawOffline && !homeSent) {
                if (!NetState.isWifiConnected(this)) {
                    // Online, but through the modem or a dongle rather than Wi-Fi.
                    // Nothing was handed to us, so there is nothing to hand off.
                    confirmations = 0
                } else if (++confirmations < CONFIRMATIONS) {
                    // Leave only on a link we have actually verified: associated, on a
                    // saved network, holding an IP, and still there a few seconds later.
                    // NetworkInfo can read connected while DHCP is still settling, and a
                    // link that survives one poll is not worth giving up the screen for.
                    update(getString(R.string.state_verifying))
                    schedule(CONFIRM_MS)
                    return
                } else {
                    // Deliberately not gated on NET_CAPABILITY_VALIDATED: ZLauncher
                    // strips INTERNET and VALIDATED from its own NetworkRequest because
                    // the framework's opinion is exactly what is unreliable against a
                    // PdaNet hotspot. Waiting on that probe would put a timeout in front
                    // of every handoff. The gateway is the honest signal, and it is the
                    // same one ZLauncher tests before raising the tunnel.
                    Log.i(TAG, "link verified, pdanet=${NetState.isPdaNetLink(this)}")
                    homeSent = true
                    Home.go(this)
                }
            }

            update(
                if (NetState.isPdaNetLink(this)) getString(R.string.state_online_pdanet)
                else getString(R.string.state_online, NetState.activeNetworkName(this))
            )
            schedule(IDLE_POLL_MS)
            return
        }
        sawOffline = true

        // 3. Offline. Give a just-finished AP session a moment to release the chip.
        if (sinceAp < AP_COOLDOWN_MS) {
            update(getString(R.string.state_waiting))
            schedule(AP_COOLDOWN_MS - sinceAp)
            return
        }

        // 4. Offline with the radio already on: the framework is scanning and will
        //    auto-join a saved network on its own. Let it. Just don't call it a
        //    failure until association has had a fair shot.
        if (NetState.isWifiOn(this)) {
            // Progress beats the clock. Measured on an AC8257, association from a cold
            // radio takes ~34s -- so a fixed 30s window declared a miss and put the menu
            // up four seconds before the connection completed on its own. While the
            // supplicant is working, keep waiting however long it takes.
            if (NetState.isAssociating(this)) {
                update(getString(R.string.state_connecting))
                schedule(CONFIRM_MS)
                return
            }

            if (lastKick != 0L && now - lastKick < ASSOCIATE_MS) {
                // Poll, do not sleep the window out. Sleeping meant a connection that
                // landed at 34s went unnoticed until the window expired, so the menu
                // went up over a unit that was already online.
                update(getString(R.string.state_connecting))
                schedule(WAIT_POLL_MS)
                return
            }

            // Radio on, nothing joined. Count the miss every cycle -- this used to be
            // gated on lastKick != 0, which zeroed itself on the first miss, so strikes
            // could never reach the menu threshold and the app sat here doing nothing
            // forever. Found by leaving a unit offline and watching it never recover.
            strikes++
            lastKick = 0L
            // Not on the first miss. Measured association on an AC8257 ranged 34-69s and
            // is not reliably bounded, so one slow attempt must not be enough to put the
            // menu over a connection that is still coming up.
            if (strikes >= STRIKES_BEFORE_MENU) showMenuOnce()
            update(getString(R.string.state_waiting))
            schedule(retryDelay())
            return
        }

        // 5. Offline and the radio is off. This is the whole point of the app -- unless
        // we already asked the user, in which case the radio being off may well be their
        // doing and we leave it alone.
        if (handedToUser) {
            update(getString(R.string.state_waiting_user))
            schedule(retryDelay())
            return
        }

        val ok = NetState.setWifi(this, true)
        Log.i(TAG, "kick: setWifiEnabled(true) -> $ok (strikes=$strikes)")
        if (ok) {
            weEnabledWifi = true
            lastKick = now
            update(getString(R.string.state_connecting))
            schedule(ASSOCIATE_MS)
        } else {
            // Android 10/11: setWifiEnabled() is a no-op for third-party apps, so there
            // is no automatic path at all. The menu is not a fallback here, it is the
            // only way the radio gets switched on.
            // No strike gate here: setWifiEnabled() refusing is not a slow network, it
            // is Android 10/11 telling us there is no automatic path at all.
            strikes++
            showMenuOnce()
            update(getString(R.string.state_blocked))
            schedule(retryDelay())
        }
    }

    private fun showMenuOnce() {
        if (handedToUser) return
        if (!Home.canStartFromBackground(this)) {
            Log.w(TAG, "cannot put the menu up from the background on this version")
            return
        }
        handedToUser = true
        WifiMenu.open(this)
    }

    /** 20s, then 1 min, then 5 min. No point hammering a radio with nothing in range. */
    private fun retryDelay(): Long = when {
        strikes <= 2 -> IDLE_POLL_MS
        strikes <= 5 -> 60_000L
        else -> 300_000L
    }

    // --- notification -------------------------------------------------------------

    private fun update(text: String) {
        if (text == lastNote) return
        lastNote = text
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTE_ID, note(text))
    }

    private fun note(text: String): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // IMPORTANCE_MIN: a foreground service must show something, but this keeps it
        // silent and collapsed at the bottom of the shade instead of in your face.
        val channel = NotificationChannel(CHANNEL, getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_MIN).apply {
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_SECRET
        }
        nm.createNotificationChannel(channel)

        val tap = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT)

        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_wifi)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(tap)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    companion object {
        private const val TAG = "ZWifiKeep"
        private const val CHANNEL = "wifi_watch"
        private const val NOTE_ID = 1

        /** Let the system settle after boot before the first look. */
        private const val FIRST_LOOK_MS = 8_000L
        /**
         * How long a kicked radio gets before the attempt counts as a miss.
         *
         * Measured on an AC8257: association from a cold radio took 34s, 49s, 57s and
         * 69s across four runs. It is not reliably bounded, and the supplicant reports
         * DISCONNECTED the whole way through before jumping straight to CONNECTED, so
         * there is no progress signal to watch on this ROM. Hence a generous window,
         * polled throughout, plus a strike gate before anything interrupts the user.
         */
        private const val ASSOCIATE_MS = 120_000L

        /** How often to look while waiting on association. */
        private const val WAIT_POLL_MS = 5_000L

        /** Misses required before the menu goes up. */
        private const val STRIKES_BEFORE_MENU = 2
        private const val IDLE_POLL_MS = 20_000L
        private const val AP_POLL_MS = 15_000L
        private const val AP_COOLDOWN_MS = 15_000L

        /** Passes the Wi-Fi link must hold before we press HOME, and the gap between. */
        private const val CONFIRMATIONS = 2
        private const val CONFIRM_MS = 4_000L

        fun start(context: Context) {
            val intent = Intent(context, WifiWatchService::class.java)
            context.startForegroundService(intent)
        }
    }
}
