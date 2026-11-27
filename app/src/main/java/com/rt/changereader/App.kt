package com.rt.changereader

import android.app.Application
import android.os.Build
import java.io.File

/** Saves any crash to a file so the next launch can show the error on screen. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                crashFile(this).writeText(
                    "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), " +
                        "${Build.MANUFACTURER} ${Build.MODEL}\nThread: ${t.name}\n\n" +
                        e.stackTraceToString()
                )
            } catch (_: Exception) { }
            previous?.uncaughtException(t, e)
        }
    }

    companion object {
        fun crashFile(ctx: android.content.Context) = File(ctx.filesDir, "last_crash.txt")
    }
}
