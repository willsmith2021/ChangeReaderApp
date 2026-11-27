package com.rt.changereader

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process

/**
 * Best-effort: tells you which app was ON SCREEN when a change happened.
 * Android does not reveal which process actually wrote a file (that needs root),
 * so treat this as "probably caused by" – background apps can also write files.
 */
class ForegroundAppTracker(private val context: Context) {

    private val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    private val labelCache = HashMap<String, String>()
    private var lastPkg: String? = null
    private var lastQuery = 0L

    fun hasPermission(): Boolean = hasUsageAccess(context)

    @Synchronized
    fun current(): String? {
        if (!hasPermission()) return null
        val now = System.currentTimeMillis()
        // Query from slightly before the last check so we never miss a switch
        val from = if (lastQuery == 0L) now - 60 * 60 * 1000 else lastQuery - 2000
        val events = usm.queryEvents(from, now)
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            // MOVE_TO_FOREGROUND (Android 9 and older) has the same value as ACTIVITY_RESUMED
            @Suppress("DEPRECATION")
            if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) lastPkg = e.packageName
        }
        lastQuery = now
        return lastPkg?.let { label(it) }
    }

    private fun label(pkg: String): String = labelCache.getOrPut(pkg) {
        try {
            val pm = context.packageManager
            val ai = pm.getApplicationInfo(pkg, 0)
            "${pm.getApplicationLabel(ai)} ($pkg)"
        } catch (_: PackageManager.NameNotFoundException) {
            pkg
        }
    }

    companion object {
        fun hasUsageAccess(context: Context): Boolean {
            val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = if (android.os.Build.VERSION.SDK_INT >= 29) {
                ops.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                ops.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName
                )
            }
            return mode == AppOpsManager.MODE_ALLOWED
        }
    }
}
