package com.rt.changereader

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

enum class ChangeType(val label: String) {
    CREATED("CREATED"),
    MODIFIED("MODIFIED"),
    WRITE_FINISHED("SAVED"),
    DELETED("DELETED"),
    MOVED_IN("MOVED_IN"),
    MOVED_OUT("MOVED_OUT"),
    ATTRIBUTES("ATTRIB")
}

data class ChangeEntry(
    val time: Long,
    val type: ChangeType,
    val path: String,
    val isDir: Boolean,
    val sizeBytes: Long?,
    val foregroundApp: String?
) {
    fun toCsv(): String {
        val ts = CSV_TIME.format(Date(time))
        fun q(s: String?) = "\"" + (s ?: "").replace("\"", "\"\"") + "\""
        return listOf(
            q(ts), q(type.label), q(if (isDir) "folder" else "file"),
            q(path), q(sizeBytes?.toString()), q(foregroundApp)
        ).joinToString(",")
    }

    fun toDisplay(root: String): String {
        val rel = path.removePrefix(root).ifEmpty { "/" }
        val kind = if (isDir) "📁" else "📄"
        val size = sizeBytes?.let { " (${humanSize(it)})" } ?: ""
        val app = foregroundApp?.let { "  ← $it" } ?: ""
        return "${UI_TIME.format(Date(time))}  ${type.label}  $kind $rel$size$app"
    }

    companion object {
        val CSV_HEADER = "timestamp,event,kind,path,size_bytes,foreground_app"
        private val CSV_TIME = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        private val UI_TIME = SimpleDateFormat("HH:mm:ss", Locale.US)

        fun humanSize(b: Long): String = when {
            b < 1024 -> "$b B"
            b < 1024 * 1024 -> "%.1f KB".format(b / 1024.0)
            b < 1024L * 1024 * 1024 -> "%.1f MB".format(b / 1024.0 / 1024.0)
            else -> "%.2f GB".format(b / 1024.0 / 1024.0 / 1024.0)
        }
    }
}

/**
 * Keeps recent entries in memory for the UI and appends every entry
 * to a daily CSV file: Android/data/com.rt.changereader/files/logs/changes_YYYY-MM-DD.csv
 */
object ChangeLog {
    private const val MAX_IN_MEMORY = 2000

    private val entries = ArrayDeque<ChangeEntry>()
    private val listeners = CopyOnWriteArrayList<(ChangeEntry) -> Unit>()
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val DAY = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    @Volatile private var logDir: File? = null

    fun init(context: Context) {
        if (logDir == null) {
            logDir = File(context.getExternalFilesDir(null), "logs").apply { mkdirs() }
        }
    }

    fun logsDir(): File? = logDir

    fun todayFile(): File? = logDir?.let { File(it, "changes_${DAY.format(Date())}.csv") }

    fun add(entry: ChangeEntry) {
        synchronized(entries) {
            entries.addLast(entry)
            while (entries.size > MAX_IN_MEMORY) entries.removeFirst()
        }
        io.execute {
            val f = todayFile() ?: return@execute
            try {
                if (!f.exists()) f.writeText(ChangeEntry.CSV_HEADER + "\n")
                f.appendText(entry.toCsv() + "\n")
            } catch (_: Exception) { }
        }
        main.post { listeners.forEach { it(entry) } }
    }

    fun snapshot(): List<ChangeEntry> = synchronized(entries) { entries.toList() }

    fun clearMemory() = synchronized(entries) { entries.clear() }

    fun addListener(l: (ChangeEntry) -> Unit) = listeners.add(l)
    fun removeListener(l: (ChangeEntry) -> Unit) = listeners.remove(l)
}
