package com.rt.changereader

/** Runs several watchers together (live events + backup scan). */
class CombinedWatcher(private vararg val parts: FolderWatcher) : FolderWatcher {
    override fun start() = parts.forEach { it.start() }
    override fun stop() = parts.forEach { it.stop() }
    override fun watchedFolderCount() = parts.maxOf { it.watchedFolderCount() }
}
