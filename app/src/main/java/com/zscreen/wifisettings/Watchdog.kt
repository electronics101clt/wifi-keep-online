package com.zscreen.wifisettings

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log

/**
 * Resurrection. A foreground service survives Oreo's background execution limits, but
 * it does not survive these head-unit ROMs, which ship their own "one-key clean" style
 * process killers that will reap anything they feel like. So a repeating alarm pokes
 * the service back awake; starting a service that is already running is a no-op, so
 * this costs nothing when nothing has gone wrong.
 */
object Watchdog {

    private const val TAG = "ZWifiKeep"
    const val ACTION_POKE = "com.zscreen.wifisettings.POKE"
    /**
     * Five minutes, not fifteen. This is the worst case for how long a unit drives with
     * no internet after the ROM kills us, and it is the *only* revival that works on an
     * ordinary install: measured on an AC8257, vendor wake broadcasts never reach a
     * manifest receiver, while this alarm's PendingIntent targets our own component
     * explicitly and always lands. The app is battery-whitelisted, so Doze will not
     * defer it.
     */
    private const val EVERY_MS = 5 * 60 * 1000L

    private fun pending(context: Context): PendingIntent {
        val intent = Intent(context, BootReceiver::class.java).setAction(ACTION_POKE)
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun arm(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        // Exact, not setInexactRepeating. Measured on an AC8257, a 15-minute inexact
        // repeat was batched into an 11-minute window -- worst case 25 minutes of
        // driving with no internet after a kill. setExactAndAllowWhileIdle also ignores
        // Doze, which matters because the alarm's whole job is to fire when we are dead.
        //
        // Re-armed on every evaluation pass rather than left repeating, so the alarm is
        // always about EVERY_MS in the future. While the service is alive it therefore
        // never fires, which is the point: it is a dead-man's switch, not a timer.
        val at = SystemClock.elapsedRealtime() + EVERY_MS
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pending(context))
        } catch (e: SecurityException) {
            // Some ROMs cap exact alarms; an inexact one still beats nothing.
            Log.w(TAG, "exact alarm refused, falling back", e)
            am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pending(context))
        }
    }

    /** One-shot, for coming back after the task was swiped away. */
    fun pokeIn(context: Context, delayMs: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        am.set(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + delayMs,
            pending(context)
        )
    }
}
