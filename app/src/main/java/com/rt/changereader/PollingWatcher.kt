package com.rt.changereader

import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Backup scanner: snapshots the folder tree every few seconds and reports what
 * was added, removed or changed. Catches changes Android never delivers as live
 * events (common on Android 6–10 shared storage). The scanner function is
 * swappable so root mode can scan via `su`.
 */
class PollingWatcher(
    private val intervalMs: Long,
    private val scanner: () -> Map<String, Info>,
    private val onEvent: (type: ChangeType, file: File, isDir: Boolean, size: Long?) -> Unit
) : FolderWatcher {

    data class Info(val isDir: Boolean, val size: Long, val mtime: Long)

    private val exec = Executors.newSingleThreadScheduledExecutor()
    private var snapshot: Map<String, Info>? = null
    @Volatile private var folders = 0

    override fun start() {
        exec.execute { snapshot = safeScan() }
        exec.scheduleWithFixedDelay({ tick() }, intervalMs, intervalMs, TimeUnit.MILLISECONDS)
    }

    override fun stop() {
        exec.shutdownNow()
    }

    override fun watchedFolderCount() = folders

    private fun safeScan(): Map<String, Info>? = try {
        scanner().also { m -> folders = m.values.count { it.isDir } + 1 }
    } catch (_: Exception) {
        null
    }

    private fun tick() {
        val now = safeScan() ?: return
        val old = snapshot
        snapshot = now
        if (old == null) return
        for ((path, info) in now) {
            val prev = old[path]
            when {
                prev == null ->
                    onEvent(ChangeType.CREATED, File(path), info.isDir, if (info.isDir) null else info.size)
                !info.isDir && (prev.size != info.size || prev.mtime != info.mtime) ->
                    onEvent(ChangeType.WRITE_FINISHED, File(path), false, info.size)
            }
        }
        for ((path, info) in old) {
            if (path !in now) onEvent(ChangeType.DELETED, File(path), info.isDir, null)
        }
    }

    companion object {
        /** Scanner for normally readable folders (no root). */
        fun fileScanner(root: File, includeHidden: Boolean, maxEntries: Int = 100_000): () -> Map<String, Info> = {
            val out = HashMap<String, Info>(4096)
            val stack = ArrayDeque<File>()
            stack.addLast(root)
            while (stack.isNotEmpty() && out.size < maxEntries) {
                val dir = stack.removeLast()
                val children = dir.listFiles() ?: continue
                for (c in children) {
                    if (!includeHidden && c.name.startsWith(".")) continue
                    val isDir = c.isDirectory
                    out[c.absolutePath] = Info(isDir, if (isDir) 0 else c.length(), c.lastModified())
                    if (isDir) stack.addLast(c)
                }
            }
            out
        }
    }
}
