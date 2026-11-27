package com.rt.changereader

import android.os.FileObserver
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Android's FileObserver only watches ONE directory level.
 * This class attaches one observer per sub-folder and adds/removes
 * observers automatically as folders are created, moved or deleted.
 */
class RecursiveObserver(
    private val root: File,
    private val includeHidden: Boolean,
    private val onEvent: (type: ChangeType, file: File, isDir: Boolean) -> Unit
) : FolderWatcher {
    private val observers = ConcurrentHashMap<String, SingleDirObserver>()

    @Volatile
    private var running = false

    override fun start() {
        running = true
        watchTree(root, reportExisting = false)
    }

    override fun stop() {
        running = false
        observers.values.forEach { it.stopWatching() }
        observers.clear()
    }

    override fun watchedFolderCount(): Int = observers.size

    private fun watchTree(dir: File, reportExisting: Boolean) {
        if (!running || !dir.isDirectory) return
        if (!includeHidden && dir != root && dir.name.startsWith(".")) return
        addObserver(dir)
        dir.listFiles()?.forEach { child ->
            if (!includeHidden && child.name.startsWith(".")) return@forEach
            if (reportExisting) onEvent(ChangeType.CREATED, child, child.isDirectory)
            if (child.isDirectory) watchTree(child, reportExisting)
        }
    }

    private fun addObserver(dir: File) {
        val key = dir.absolutePath
        if (observers.containsKey(key)) return
        val obs = SingleDirObserver(dir)
        observers[key] = obs
        obs.startWatching()
    }

    private fun removeTree(path: String) {
        val prefix = "$path/"
        observers.keys.filter { it == path || it.startsWith(prefix) }.forEach { k ->
            observers.remove(k)?.stopWatching()
        }
    }

    // String-path constructor works on every Android version (the File one needs Android 10+)
    @Suppress("DEPRECATION")
    private inner class SingleDirObserver(private val dir: File) :
        FileObserver(dir.absolutePath, MASK) {

        override fun onEvent(event: Int, path: String?) {
            val ev = event and ALL_EVENTS
            val isDirFlag = (event and IS_DIR_FLAG) != 0

            // Self events (folder itself removed/moved)
            if (ev == DELETE_SELF || ev == MOVE_SELF) {
                removeTree(dir.absolutePath)
                return
            }
            if (path == null) return
            if (!includeHidden && path.startsWith(".")) return

            val file = File(dir, path)
            val isDir = isDirFlag || file.isDirectory

            when (ev) {
                CREATE -> {
                    onEvent(ChangeType.CREATED, file, isDir)
                    // New folder: start watching it and report anything already copied into it
                    if (isDir) watchTree(file, reportExisting = true)
                }
                MOVED_TO -> {
                    onEvent(ChangeType.MOVED_IN, file, isDir)
                    if (isDir) watchTree(file, reportExisting = false)
                }
                MOVED_FROM -> {
                    onEvent(ChangeType.MOVED_OUT, file, isDir)
                    if (isDir) removeTree(file.absolutePath)
                }
                DELETE -> {
                    onEvent(ChangeType.DELETED, file, isDir)
                    if (isDir) removeTree(file.absolutePath)
                }
                MODIFY -> onEvent(ChangeType.MODIFIED, file, isDir)
                CLOSE_WRITE -> onEvent(ChangeType.WRITE_FINISHED, file, isDir)
                ATTRIB -> onEvent(ChangeType.ATTRIBUTES, file, isDir)
            }
        }
    }

    companion object {
        // inotify IN_ISDIR flag (not exposed as a public constant in FileObserver)
        private const val IS_DIR_FLAG = 0x40000000

        private const val MASK = FileObserver.CREATE or FileObserver.DELETE or
                FileObserver.MODIFY or FileObserver.CLOSE_WRITE or
                FileObserver.MOVED_FROM or FileObserver.MOVED_TO or
                FileObserver.DELETE_SELF or FileObserver.MOVE_SELF or
                FileObserver.ATTRIB
    }
}
