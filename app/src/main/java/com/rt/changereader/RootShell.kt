package com.rt.changereader

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * A long-lived `su` shell for quick root queries (is it a folder? what size?).
 * Requires a rooted phone (Magisk / KernelSU / APatch). The first call shows the
 * root manager's "Grant superuser?" prompt.
 */
class RootShell {
    private var proc: Process? = null
    private var writer: OutputStreamWriter? = null
    private var reader: BufferedReader? = null

    @Synchronized
    fun run(cmd: String): List<String> {
        ensure()
        val marker = "__END_" + UUID.randomUUID().toString().take(8)
        val w = writer ?: return emptyList()
        val r = reader ?: return emptyList()
        return try {
            w.write("$cmd 2>/dev/null; echo $marker\n")
            w.flush()
            val out = ArrayList<String>()
            while (true) {
                val line = r.readLine() ?: break
                if (line == marker) break
                out.add(line)
            }
            out
        } catch (_: Exception) {
            close(); emptyList()
        }
    }

    private fun ensure() {
        if (proc?.isAlive == true) return
        val p = ProcessBuilder("su").redirectErrorStream(true).start()
        proc = p
        writer = OutputStreamWriter(p.outputStream)
        reader = BufferedReader(InputStreamReader(p.inputStream))
    }

    @Synchronized
    fun close() {
        try { writer?.write("exit\n"); writer?.flush() } catch (_: Exception) {}
        proc?.destroy()
        proc = null; writer = null; reader = null
    }

    companion object {
        /** True if `su` exists and the user granted root to this app. */
        fun isAvailable(): Boolean = try {
            val p = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor(15, TimeUnit.SECONDS)
            out.contains("uid=0")
        } catch (_: Exception) {
            false
        }

        /** Shell-quote a path for use inside su commands. */
        fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"
    }
}
