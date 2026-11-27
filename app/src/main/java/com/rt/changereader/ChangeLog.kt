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
    ATTRIBUTES("ATTRIB"),
    INFO("INFO")
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
        if (type == ChangeType.INFO) return "${UI_TIME.format(Date(time))}  ℹ $path"
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
            loadToday()
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

    /** Reload today's CSV so the screen shows the full day, even after the app was closed or killed. */
    private fun loadToday() {
        val f = todayFile() ?: return
        if (!f.exists()) return
        try {
            val parsed = f.readLines().drop(1).takeLast(MAX_IN_MEMORY).mapNotNull { parseCsv(it) }
            synchronized(entries) {
                if (entries.isEmpty()) entries.addAll(parsed)
            }
        } catch (_: Exception) { }
    }

    private val CSV_PARSE_TIME = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    private fun parseCsv(line: String): ChangeEntry? {
        val cols = ArrayList<String>()
        val sb = StringBuilder()
        var inQ = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inQ && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                c == '"' -> inQ = !inQ
                c == ',' && !inQ -> { cols.add(sb.toString()); sb.clear() }
                else -> sb.append(c)
            }
            i++
        }
        cols.add(sb.toString())
        if (cols.size < 6) return null
        val type = ChangeType.values().firstOrNull { it.label == cols[1] } ?: return null
        val time = try { CSV_PARSE_TIME.parse(cols[0])?.time } catch (_: Exception) { null } ?: return null
        return ChangeEntry(time, type, cols[3], cols[2] == "folder", cols[4].toLongOrNull(), cols[5].ifEmpty { null })
    }

    fun snapshot(): List<ChangeEntry> = synchronized(entries) { entries.toList() }

    fun clearMemory() = synchronized(entries) { entries.clear() }

    fun addListener(l: (ChangeEntry) -> Unit) = listeners.add(l)
    fun removeListener(l: (ChangeEntry) -> Unit) = listeners.remove(l)
}
