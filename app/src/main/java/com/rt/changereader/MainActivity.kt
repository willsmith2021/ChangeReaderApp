package com.rt.changereader

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.rt.changereader.databinding.ActivityMainBinding
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var prefs: Prefs
    private val adapter = LogAdapter()

    private val listener: (ChangeEntry) -> Unit = { e ->
        adapter.add(e, root())
        b.list.scrollToPosition(0)
        b.empty.visibility = android.view.View.GONE
    }

    private val pickFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri ?: return@registerForActivityResult
            val path = treeUriToPath(uri)
            if (path == null) {
                toast("Couldn't map that location to a path. Type the path instead.")
            } else {
                b.pathInput.setText(path)
                prefs.folder = path
            }
        }

    private val notifPerm =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (showLastCrash()) return
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = Prefs(this)
        ChangeLog.init(this)

        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = adapter

        b.pathInput.setText(prefs.folder ?: Environment.getExternalStorageDirectory().absolutePath)

        b.browseBtn.setOnClickListener { pickFolder.launch(null) }
        b.storageRootBtn.setOnClickListener {
            b.pathInput.setText(Environment.getExternalStorageDirectory().absolutePath)
        }

        b.hiddenSwitch.isChecked = prefs.includeHidden
        b.modifySwitch.isChecked = prefs.logModify
        b.attribSwitch.isChecked = prefs.logAttrib
        b.hiddenSwitch.setOnCheckedChangeListener { _, v -> prefs.includeHidden = v }

        b.rootSwitch.isChecked = prefs.useRoot
        b.rootSwitch.setOnCheckedChangeListener { sw, v ->
            if (!v) { prefs.useRoot = false; return@setOnCheckedChangeListener }
            toast("Checking root… approve the superuser prompt")
            Thread {
                val ok = RootShell.isAvailable()
                runOnUiThread {
                    prefs.useRoot = ok
                    sw.isChecked = ok
                    toast(if (ok) "Root granted ✓" else "Root not available or denied")
                }
            }.start()
        }
        b.appDataBtn.setOnClickListener {
            b.pathInput.setText("/data/data")
            if (!prefs.useRoot) toast("Turn on root mode to watch app private data")
        }
        b.modifySwitch.setOnCheckedChangeListener { _, v -> prefs.logModify = v }
        b.attribSwitch.setOnCheckedChangeListener { _, v -> prefs.logAttrib = v }

        b.storagePermBtn.setOnClickListener { requestStorageAccess() }
        b.batteryBtn.setOnClickListener { requestNoBatteryLimits() }
        b.usagePermBtn.setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }

        b.startBtn.setOnClickListener { toggleWatching() }
        b.exportBtn.setOnClickListener { shareLog() }
        b.clearBtn.setOnClickListener {
            ChangeLog.clearMemory()
            adapter.clear()
            b.empty.visibility = android.view.View.VISIBLE
        }

        intent?.getStringExtra("autostart_path")?.let { p ->
            intent.getStringExtra("autostart_root")?.let { prefs.useRoot = it == "1" }
            b.pathInput.setText(p)
            prefs.folder = p
            WatcherService.start(this, p)
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private var crashScreen = false

    /** If the app crashed last time, show the error (copyable) instead of the normal screen. */
    private fun showLastCrash(): Boolean {
        val f = App.crashFile(this)
        if (!f.exists()) return false
        val text = f.readText()
        f.delete()
        crashScreen = true
        val tv = TextView(this).apply {
            setText(text); setTextIsSelectable(true); textSize = 11f; setPadding(32, 32, 32, 32)
        }
        val scroll = android.widget.ScrollView(this).apply { addView(tv) }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Change Reader crashed last time")
            .setView(scroll)
            .setPositiveButton("Copy error") { _, _ ->
                val cm = getSystemService(android.content.ClipboardManager::class.java)
                cm.setPrimaryClip(android.content.ClipData.newPlainText("crash", text))
                toast("Copied – paste it to Claude")
                recreate()
            }
            .setNegativeButton("Retry") { _, _ -> recreate() }
            .setCancelable(false)
            .show()
        return true
    }

    override fun onResume() {
        super.onResume()
        if (crashScreen) return
        adapter.setAll(ChangeLog.snapshot(), root())
        b.empty.visibility =
            if (adapter.itemCount == 0) android.view.View.VISIBLE else android.view.View.GONE
        ChangeLog.addListener(listener)
        refreshStatus()
    }

    override fun onPause() {
        if (!crashScreen) ChangeLog.removeListener(listener)
        super.onPause()
    }

    private fun root() = b.pathInput.text.toString().trim()

    private fun toggleWatching() {
        if (WatcherService.isRunning) {
            WatcherService.stop(this)
            b.root.postDelayed({ refreshStatus() }, 300)
            return
        }
        val path = root()
        if (prefs.useRoot) {
            if (!path.startsWith("/")) { toast("Enter an absolute path"); return }
            prefs.folder = path
            WatcherService.start(this, path)
            b.root.postDelayed({ refreshStatus() }, 300)
            return
        }
        if (!hasStorageAccess()) {
            toast("Grant \"All files access\" first")
            requestStorageAccess()
            return
        }
        val dir = File(path)
        if (!dir.isDirectory) {
            toast("Folder not found: $path")
            return
        }
        if (!dir.canRead()) {
            toast("Android blocks reading this folder. Turn on root mode (rooted phones only).")
            return
        }
        prefs.folder = path
        WatcherService.start(this, path)
        b.root.postDelayed({ refreshStatus() }, 300)
    }

    private fun refreshStatus() {
        val storageOk = hasStorageAccess()
        val usageOk = ForegroundAppTracker.hasUsageAccess(this)
        b.storagePermBtn.text = if (storageOk) "✓ All files access" else "Grant all files access"
        b.storagePermBtn.isEnabled = !storageOk
        b.usagePermBtn.text =
            if (usageOk) "✓ Usage access (app names)" else "Grant usage access (optional)"
        val battOk = isIgnoringBattery()
        b.batteryBtn.text = if (battOk) "✓ No battery limits (keeps running)" else "Allow running in background"
        b.batteryBtn.isEnabled = !battOk
        b.startBtn.text = if (WatcherService.isRunning) "Stop watching" else "Start watching"
        b.status.text = if (WatcherService.isRunning)
            "● Watching ${prefs.folder}${if (prefs.useRoot) " (root)" else ""}\nLog file: ${ChangeLog.todayFile()?.absolutePath}"
        else "○ Not watching"
    }

    private fun isIgnoringBattery(): Boolean =
        getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    @android.annotation.SuppressLint("BatteryLife")
    private fun requestNoBatteryLimits() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun hasStorageAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
        else ContextCompat.checkSelfPermission(
            this, Manifest.permission.READ_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED

    private fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                        .setData(Uri.parse("package:$packageName"))
                )
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 1)
        }
    }

    /** content://…/tree/primary:Download/Foo  ->  /storage/emulated/0/Download/Foo */
    private fun treeUriToPath(uri: Uri): String? {
        val docId = DocumentsContract.getTreeDocumentId(uri) ?: return null
        val parts = docId.split(":", limit = 2)
        val volume = parts[0]
        val rel = parts.getOrElse(1) { "" }
        val base = if (volume.equals("primary", true))
            Environment.getExternalStorageDirectory().absolutePath
        else "/storage/$volume"
        return if (rel.isEmpty()) base else "$base/$rel"
    }

    private fun shareLog() {
        val f = ChangeLog.todayFile()
        if (f == null || !f.exists()) {
            toast("No log for today yet")
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/csv")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "Share change log"))
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    // ---------------- list ----------------

    private class LogAdapter : RecyclerView.Adapter<LogAdapter.VH>() {
        private val rows = ArrayList<Pair<ChangeEntry, String>>()

        class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)

        fun add(e: ChangeEntry, root: String) {
            rows.add(0, e to e.toDisplay(root))
            notifyItemInserted(0)
            if (rows.size > 2000) {
                rows.removeAt(rows.lastIndex)
                notifyItemRemoved(rows.size)
            }
        }

        fun setAll(list: List<ChangeEntry>, root: String) {
            rows.clear()
            list.asReversed().forEach { rows.add(it to it.toDisplay(root)) }
            notifyDataSetChanged()
        }

        fun clear() {
            rows.clear(); notifyDataSetChanged()
        }

        override fun onCreateViewHolder(p: ViewGroup, t: Int) = VH(
            LayoutInflater.from(p.context).inflate(R.layout.item_log, p, false) as TextView
        )

        override fun getItemCount() = rows.size

        override fun onBindViewHolder(h: VH, i: Int) {
            val (e, text) = rows[i]
            h.tv.text = text
            h.tv.setTextColor(
                when (e.type) {
                    ChangeType.CREATED, ChangeType.MOVED_IN -> 0xFF2E7D32.toInt()
                    ChangeType.DELETED, ChangeType.MOVED_OUT -> 0xFFC62828.toInt()
                    ChangeType.MODIFIED, ChangeType.WRITE_FINISHED -> 0xFF1565C0.toInt()
                    ChangeType.ATTRIBUTES -> 0xFF757575.toInt()
                    ChangeType.INFO -> 0xFFEF6C00.toInt()
                }
            )
        }
    }
}
