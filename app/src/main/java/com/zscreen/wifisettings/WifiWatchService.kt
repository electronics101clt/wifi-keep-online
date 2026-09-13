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
            update(getString(R.string.state_online, NetState.activeNetworkName(this)))
            schedule(IDLE_POLL_MS)
            return
        }

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
            val sinceKick = now - lastKick
            if (lastKick != 0L && sinceKick < ASSOCIATE_MS) {
                update(getString(R.string.state_connecting))
                schedule(ASSOCIATE_MS - sinceKick)
            } else {
                if (lastKick != 0L) strikes++
                lastKick = 0L
                update(getString(R.string.state_waiting))
                schedule(retryDelay())
            }
            return
        }

        // 5. Offline and the radio is off. This is the whole point of the app.
        val ok = NetState.setWifi(this, true)
        Log.i(TAG, "kick: setWifiEnabled(true) -> $ok (strikes=$strikes)")
        if (ok) {
            weEnabledWifi = true
            lastKick = now
            update(getString(R.string.state_connecting))
            schedule(ASSOCIATE_MS)
        } else {
            strikes++
            update(getString(R.string.state_blocked))
            schedule(retryDelay())
        }
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
        private const val TAG = "WifiSettings"
        private const val CHANNEL = "wifi_watch"
        private const val NOTE_ID = 1

        /** Let the system settle after boot before the first look. */
        private const val FIRST_LOOK_MS = 8_000L
        /** How long association + DHCP is allowed to take before it counts as a miss. */
        private const val ASSOCIATE_MS = 30_000L
        private const val IDLE_POLL_MS = 20_000L
        private const val AP_POLL_MS = 15_000L
        private const val AP_COOLDOWN_MS = 15_000L

        fun start(context: Context) {
            val intent = Intent(context, WifiWatchService::class.java)
            context.startForegroundService(intent)
        }
    }
}
