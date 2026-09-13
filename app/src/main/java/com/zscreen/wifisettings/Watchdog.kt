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

    private const val TAG = "WifiSettings"
    const val ACTION_POKE = "com.zscreen.wifisettings.POKE"
    private const val EVERY_MS = 15 * 60 * 1000L

    private fun pending(context: Context): PendingIntent {
        val intent = Intent(context, BootReceiver::class.java).setAction(ACTION_POKE)
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun arm(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        // Inexact on purpose -- the system batches it with other wakeups and we do not
        // care whether the poke lands at 15:00 or 15:04.
        am.setInexactRepeating(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + EVERY_MS,
            EVERY_MS,
            pending(context)
        )
        Log.d(TAG, "watchdog armed")
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
