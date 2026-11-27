package com.rt.changereader

import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Root mode: watches folders a normal app can't read, such as
 * /data/data/<package>, /data/user/0, Android/data and Android/obb.
 *
 * 1. Live mode: runs an `inotifyd` (toybox, or the busybox bundled with
 *    Magisk / KernelSU / APatch) as root, one watch per sub-folder.
 * 2. Fallback: if no inotifyd exists on the phone, scans the folder as root
 *    every few seconds and logs what changed.
 *
 * Status lines (tool used, folder count, errors) are written to the log as INFO.
 */
class RootWatcher(
    private val context: android.content.Context,
    private val requestedRoot: File,
    private val includeHidden: Boolean,
    private val onEvent: (type: ChangeType, file: File, isDir: Boolean, size: Long?) -> Unit,
    private val onInfo: (String) -> Unit
) : FolderWatcher {

    private val shell = RootShell()
    private val sched = Executors.newSingleThreadScheduledExecutor()
    private var proc: Process? = null
    private var pendingRestart: ScheduledFuture<*>? = null
    private var poller: PollingWatcher? = null
    @Volatile private var running = false
    @Volatile private var folders = 0

    // On Android 6–10, /storage/emulated/0 is a per-app view; the real files live in /data/media/0.
    private val realRoot: String = requestedRoot.absolutePath
        .replaceFirst(Regex("^/(storage/emulated|sdcard|mnt/sdcard)(/0)?(?=/|$)"), "/data/media/0")

    private fun toUserPath(p: String): String =
        if (realRoot != requestedRoot.absolutePath && p.startsWith(realRoot))
            requestedRoot.absolutePath + p.removePrefix(realRoot)
        else p

    override fun start() {
        running = true
        sched.execute { launch() }
    }

    override fun stop() {
        running = false
        pendingRestart?.cancel(false)
        poller?.stop()
        killProc()
        shell.close()
        sched.shutdownNow()
    }

    override fun watchedFolderCount() = folders

    private fun launch() {
        if (!running) return
        killProc()

        val check = shell.run("[ -d ${RootShell.q(realRoot)} ] && echo OK || echo MISSING; id -u")
        if (check.isEmpty()) {
            onInfo("Root shell didn't respond. Open your root manager and allow Change Reader.")
            return
        }
        if (check.firstOrNull() != "OK") {
            onInfo("Folder not found (as root): $realRoot")
            return
        }
        if (check.getOrNull(1) != "0") {
            onInfo("su is not running as root (uid=${check.getOrNull(1)}). Root access denied?")
            return
        }

        val script = writeScript() ?: run {
            onInfo("Couldn't write helper script")
            return
        }
        val p = ProcessBuilder(
            "su", "-c", "sh ${RootShell.q(script.absolutePath)} ${RootShell.q(realRoot)} " +
                    (if (includeHidden) "1" else "0") + " $MAX_DIRS"
        ).redirectErrorStream(true).start()
        proc = p

        Thread {
            var gotInotify = false
            try {
                p.inputStream.bufferedReader().forEachLine { line ->
                    if (line.startsWith("#INFO ")) {
                        val msg = line.removePrefix("#INFO ")
                        when {
                            msg.startsWith("dirs=") ->
                                folders = msg.removePrefix("dirs=").trim().toIntOrNull() ?: 0
                            msg == "tool=none" -> {
                                onInfo("No inotifyd on this phone – using root scan every ${POLL_MS / 1000}s")
                                startRootPolling()
                            }
                            msg.startsWith("tool=") -> gotInotify = true
                            msg.startsWith("ready") ->
                                onInfo("Root live watch active on $realRoot ($folders folders)")
                        }
                    } else {
                        parse(line)
                    }
                }
            } catch (_: Exception) { }
            // inotifyd exited (folder deleted, killed…): restart it
            if (running && gotInotify && proc === p) scheduleRestart(2000)
        }.apply { isDaemon = true; start() }
    }

    private fun startRootPolling() {
        if (poller != null) return
        val hidden = if (includeHidden) "" else " | grep -v '/\\.'"
        poller = PollingWatcher(intervalMs = POLL_MS, scanner = {
            val map = HashMap<String, PollingWatcher.Info>()
            shell.run(
                "find ${RootShell.q(realRoot)} -mindepth 1 2>/dev/null$hidden | head -n 50000 | " +
                        "while IFS= read -r f; do stat -c '%F|%s|%Y|%n' \"\$f\"; done"
            ).forEach { line ->
                val parts = line.split('|', limit = 4)
                if (parts.size == 4) {
                    map[parts[3]] = PollingWatcher.Info(
                        parts[0].contains("directory"),
                        parts[1].toLongOrNull() ?: 0,
                        parts[2].toLongOrNull() ?: 0
                    )
                }
            }
            folders = map.values.count { it.isDir } + 1
            map
        }) { type, file, isDir, size ->
            onEvent(type, File(toUserPath(file.absolutePath)), isDir, size)
        }.also { it.start() }
    }

    /** inotifyd output: "<event>\t<watched dir>\t<name>" */
    private fun parse(line: String) {
        val parts = line.split('\t')
        if (parts.size < 2) {
            if (line.isNotBlank()) onInfo("root: $line")
            return
        }
        val ev = parts[0]
        val dir = parts[1]
        val name = parts.getOrNull(2)?.takeIf { it.isNotEmpty() }

        if (name == null) {
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
        onEvent(type, File(toUserPath(full)), isDir, size)

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
            Runtime.getRuntime()
                .exec(arrayOf("su", "-c", "pkill -f 'inotifyd - ' ; pkill -f changereader_watch"))
                .waitFor()
        } catch (_: Exception) { }
        p.destroy()
    }

    private fun writeScript(): File? = try {
        File(context.filesDir, "changereader_watch.sh").apply {
            writeText(context.assets.open("changereader_watch.sh").bufferedReader().readText())
            setReadable(true, false)
        }
    } catch (_: Exception) {
        null
    }

    companion object {
        private const val MAX_DIRS = 3000
        private const val POLL_MS = 5000L

        // Script lives in app/src/main/assets/changereader_watch.sh

    }
}
