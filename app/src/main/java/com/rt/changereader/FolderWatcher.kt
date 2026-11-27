package com.rt.changereader

interface FolderWatcher {
    fun start()
    fun stop()
    fun watchedFolderCount(): Int
}
