package com.rt.changereader

import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Root mode: watches folders a normal app can't read, such as
 * /data/data/<package>, /data/user/0, Android/data and Android/obb.
 *
 * It runs Android's built-in `inotifyd` (toybox) as root, one watch per sub-folder.
 * When a new sub-folder appears, the watcher is restarted so the new folder is covered too.
 */
class RootWatcher(
    private val root: File,
    private val includeHidden: Boolean,
    private val onEvent: (type: ChangeType, file: File, isDir: Boolean, size: Long?) -> Unit
) : FolderWatcher {

    private val shell = RootShell()
    private val sched = Executors.newSingleThreadScheduledExecutor()
    private var proc: Process? = null
    private var reader: Thread? = null
    private var pendingRestart: ScheduledFuture<*>? = null
    @Volatile private var running = false
    @Volatile private var folders = 0

    override fun start() {
        running = true
        sched.execute { launch() }
    }

    override fun stop() {
        running = false
        pendingRestart?.cancel(false)
        killProc()
        shell.close()
        sched.shutdownNow()
    }

    override fun watchedFolderCount() = folders

    private fun launch() {
        if (!running) return
        killProc()

        val rootQ = RootShell.q(root.absolutePath)
        val hiddenFilter = if (includeHidden) "" else " -not -path '*/.*'"
        // Build the watch list as root: "<dir>:<mask>" for every sub-folder (capped).
        val dirs = shell.run("find $rootQ -type d$hiddenFilter | head -n $MAX_DIRS")
            .filter { it.startsWith("/") }
        folders = dirs.size
        if (dirs.isEmpty()) return

        val script = buildString {
            append("exec inotifyd -")
            dirs.forEach { append(' ').append(RootShell.q("$it:$MASK")) }
        }
        val p = ProcessBuilder("su", "-c", script).redirectErrorStream(true).start()
        proc = p

        reader = Thread {
            try {
                p.inputStream.bufferedReader().forEachLine { line -> parse(line) }
            } catch (_: Exception) { }
            // If inotifyd exits on its own (e.g. a watched folder was deleted), restart it.
            if (running && proc === p) scheduleRestart(2000)
        }.apply { isDaemon = true; start() }
    }

    /** inotifyd output: "<event>\t<watched dir>\t<name>" */
    private fun parse(line: String) {
        val parts = line.split('\t')
        if (parts.size < 2) return
        val ev = parts[0]
        val dir = parts[1]
        val name = parts.getOrNull(2)?.takeIf { it.isNotEmpty() }

        if (name == null) {
            // Event on the watched folder itself (deleted / moved / unmounted)
            if (ev.any { it in "DMxu" }) scheduleRestart(1500)
            return
        }
        if (!includeHidden && name.startsWith(".")) return

        val full = "$dir/$name"
        val type = when {
            'n' in ev -> ChangeType.CREATED
            'd' in ev -> ChangeType.DELETED
            'm' in ev -> ChangeType.MOVED_IN
            'y' in ev -> ChangeType.MOVED_OUT
            'w' in ev -> ChangeType.WRITE_FINISHED
            'c' in ev -> ChangeType.MODIFIED
            'e' in ev -> ChangeType.ATTRIBUTES
            else -> return
        }

        var isDir = false
        var size: Long? = null
        if (type != ChangeType.DELETED && type != ChangeType.MOVED_OUT) {
            val info = shell.run(
                "if [ -d ${RootShell.q(full)} ]; then echo D; else stat -c %s ${RootShell.q(full)}; fi"
            ).firstOrNull()
            isDir = info == "D"
            size = if (isDir) null else info?.toLongOrNull()
        }
        onEvent(type, File(full), isDir, size)

        // New folder: restart so it is watched too
        if (isDir && (type == ChangeType.CREATED || type == ChangeType.MOVED_IN)) scheduleRestart(1500)
    }

    @Synchronized
    private fun scheduleRestart(delayMs: Long) {
        if (!running) return
        pendingRestart?.cancel(false)
        pendingRestart = sched.schedule({ launch() }, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun killProc() {
        val p = proc ?: return
        proc = null
        try {
            // Kill the root inotifyd too (destroying su alone can leave it running)
            Runtime.getRuntime().exec(arrayOf("su", "-c", "pkill -f 'inotifyd -'")).waitFor()
        } catch (_: Exception) { }
        p.destroy()
    }

    companion object {
        private const val MAX_DIRS = 3000
        // n=created c=modified w=write closed d=deleted m=moved in y=moved out e=metadata D/M=self
        private const val MASK = "ncwdmyeDM"
    }
}
