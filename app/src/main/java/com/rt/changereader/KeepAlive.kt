package com.rt.changereader

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock

/**
 * Keeps the watcher alive:
 *  - starts it after the phone boots or the app is updated
 *  - a watchdog alarm (every 15 min) restarts it if Android or a battery saver killed it
 *  - a one-shot alarm restarts it shortly after the app is swiped away
 */
class KeepAliveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = Prefs(context)
        if (!prefs.running) return              // user pressed Stop – respect that
        val path = prefs.folder ?: return
        if (WatcherService.isRunning && intent.action == ACTION_WATCHDOG) return
        try {
            WatcherService.start(context, path)
        } catch (_: Exception) {
            // Android 12+ may block starting from the background in some states; the next alarm retries.
        }
    }

    companion object {
        const val ACTION_WATCHDOG = "com.rt.changereader.WATCHDOG"
        const val ACTION_RESTART = "com.rt.changereader.RESTART"

        private fun pi(ctx: Context, action: String, code: Int) = PendingIntent.getBroadcast(
            ctx, code, Intent(ctx, KeepAliveReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        fun scheduleWatchdog(ctx: Context) {
            val am = ctx.getSystemService(AlarmManager::class.java)
            am.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + AlarmManager.INTERVAL_FIFTEEN_MINUTES,
                AlarmManager.INTERVAL_FIFTEEN_MINUTES,
                pi(ctx, ACTION_WATCHDOG, 10)
            )
        }

        fun cancelWatchdog(ctx: Context) {
            ctx.getSystemService(AlarmManager::class.java).cancel(pi(ctx, ACTION_WATCHDOG, 10))
        }

        /** Restart soon after the task is swiped away (some phones kill the service with it). */
        fun scheduleQuickRestart(ctx: Context) {
            val am = ctx.getSystemService(AlarmManager::class.java)
            am.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + 5_000,
                pi(ctx, ACTION_RESTART, 11)
            )
        }
    }
}
