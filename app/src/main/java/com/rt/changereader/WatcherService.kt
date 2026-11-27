package com.rt.changereader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.io.File

/**
 * Foreground service – keeps watching the folder while you use other apps.
 */
class WatcherService : Service() {

    private var observer: RecursiveObserver? = null
    private lateinit var tracker: ForegroundAppTracker
    private lateinit var worker: Handler
    private var rootPath = ""
    private var eventCount = 0

    // MODIFY fires many times while a file is being written; collapse them.
    private val lastModify = HashMap<String, Long>()

    override fun onCreate() {
        super.onCreate()
        ChangeLog.init(this)
        tracker = ForegroundAppTracker(this)
        worker = Handler(HandlerThread("watcher").apply { start() }.looper)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopEverything()
            return START_NOT_STICKY
        }

        val prefs = Prefs(this)
        val path = intent?.getStringExtra(EXTRA_PATH) ?: prefs.folder
        if (path.isNullOrBlank() || !File(path).isDirectory) {
            stopSelf()
            return START_NOT_STICKY
        }

        startAsForeground(notification("Starting…"))

        rootPath = path
        isRunning = true
        prefs.running = true
        observer?.stop()
        worker.post {
            observer = RecursiveObserver(File(path), prefs.includeHidden) { type, file, isDir ->
                worker.post { handle(type, file, isDir, prefs) }
            }.also { it.start() }
            updateNotification()
        }
        return START_STICKY // Android restarts the service if it gets killed
    }

    private fun handle(type: ChangeType, file: File, isDir: Boolean, prefs: Prefs) {
        val now = System.currentTimeMillis()
        val key = file.absolutePath

        if (type == ChangeType.MODIFIED) {
            if (!prefs.logModify) return
            val last = lastModify[key] ?: 0
            if (now - last < 3000) return
            lastModify[key] = now
        }
        if (type == ChangeType.WRITE_FINISHED) lastModify.remove(key)
        if (type == ChangeType.ATTRIBUTES && !prefs.logAttrib) return

        val size = if (!isDir && file.exists()) file.length() else null
        ChangeLog.add(ChangeEntry(now, type, key, isDir, size, tracker.current()))
        eventCount++
        if (eventCount % 5 == 1) updateNotification()
    }

    private fun startAsForeground(n: Notification) {
        val type = if (Build.VERSION.SDK_INT >= 34)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIF_ID, n, type)
    }

    private fun updateNotification() {
        val folders = observer?.watchedFolderCount() ?: 0
        val text = "$eventCount changes · $folders folders watched"
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(text))
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, WatcherService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle("Watching ${File(rootPath).name.ifEmpty { "folder" }}")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL, "Folder watcher", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun stopEverything() {
        observer?.stop()
        observer = null
        isRunning = false
        Prefs(this).running = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        observer?.stop()
        isRunning = false
        worker.looper.quitSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val EXTRA_PATH = "path"
        const val ACTION_STOP = "com.rt.changereader.STOP"
        private const val CHANNEL = "watcher"
        private const val NOTIF_ID = 42

        @Volatile var isRunning = false
            private set

        fun start(ctx: Context, path: String) {
            val i = Intent(ctx, WatcherService::class.java).putExtra(EXTRA_PATH, path)
            ctx.startForegroundService(i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, WatcherService::class.java).setAction(ACTION_STOP))
        }
    }
}
