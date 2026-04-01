package dev.fileassistant

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import dev.fileassistant.databinding.ActivityMainBinding
import dev.fileassistant.db.AppDatabase
import dev.fileassistant.db.WatchedDirectory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var watcherService: WatcherService? = null
    private var serviceBound = false
    private var bindRequested = false
    private var isWatching = false
    private var pendingAction: (() -> Unit)? = null

    // Live event log (last 30 lines, shown in the always-visible event section)
    private val liveLog = ArrayDeque<String>()

    private val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    // ── Service connection ────────────────────────────────────────────────────

    private val serviceConn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            watcherService = (binder as WatcherService.LocalBinder).getService()
            serviceBound = true
            bindRequested = false
            pendingAction?.invoke()
            pendingAction = null
            syncWatchButtonState()
            refreshDirsView()
            refreshFilesView()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            watcherService = null
            serviceBound = false
            bindRequested = false
        }
    }

    // ── Broadcast receivers ───────────────────────────────────────────────────

    private val eventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val msg = intent.getStringExtra(WatcherService.EXTRA_EVENT_LOG) ?: return
            val line = "${ts.format(Date())} $msg"
            if (liveLog.size >= 30) liveLog.removeFirst()
            liveLog.addLast(line)
            binding.eventLogText.text = liveLog.joinToString("\n")

            // If debug panel is open, refresh it too
            if (binding.debugContainer.tag == "open") refreshDebugLog()
            // Refresh file list on actual file events
            if (msg.startsWith("[FILE_CREATED]")) refreshFilesView()
        }
    }

    private val proposalReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            binding.proposalText.text =
                intent.getStringExtra(WatcherService.EXTRA_PROPOSAL_JSON) ?: return
        }
    }

    // ── Activity result launchers ─────────────────────────────────────────────

    private val safLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) return@registerForActivityResult
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            val name = uri.lastPathSegment?.substringAfterLast(':')
                ?: uri.toString().takeLast(30)
            val dir = WatchedDirectory(
                uri = uri.toString(),
                displayName = name,
                mode = WatcherService.WATCH_MODE_SAF
            )
            if (serviceBound) {
                watcherService?.addDirectory(dir)
            } else {
                AppDatabase.get(this).dao().insertDir(dir)
            }
            refreshDirsView()
        }

    private val notifPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private val mediaPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    private val allFilesLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            updateAllFilesStatus()
        }

    // ── onCreate ──────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestNotifPermIfNeeded()

        // ── Directories section ──
        binding.toggleDirsButton.setOnClickListener { togglePanel(binding.dirsContainer, "dirs") }

        binding.addSafButton.setOnClickListener { safLauncher.launch(null) }

        binding.addDownloadsButton.setOnClickListener {
            requestMediaPermsIfNeeded()
            val dlPath = Environment
                .getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                .absolutePath
            val dir = WatchedDirectory(
                uri = dlPath,
                displayName = "Downloads",
                mode = WatcherService.WATCH_MODE_DOWNLOADS
            )
            if (serviceBound) {
                watcherService?.addDirectory(dir)
            } else {
                AppDatabase.get(this).dao().insertDir(dir)
            }
            refreshDirsView()
        }

        // ── All-files access ──
        binding.requestAllFilesButton.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                allFilesLauncher.launch(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        }

        // ── Watch control ──
        binding.toggleWatchButton.setOnClickListener {
            if (isWatching) stopWatching() else startWatching()
        }

        binding.scanNowButton.setOnClickListener {
            if (serviceBound) watcherService?.scanNow()
            else liveAppend("[W] Not connected to service yet")
        }

        // ── Debug log ──
        binding.toggleDebugButton.setOnClickListener {
            togglePanel(binding.debugContainer, "debug")
            if (binding.debugContainer.tag == "open") refreshDebugLog()
        }

        binding.clearDebugButton.setOnClickListener {
            // Wipe debug log from DB and clear UI
            AppDatabase.get(this).dao().pruneDebugLog()
            binding.debugLogText.text = "—"
        }
    }

    override fun onResume() {
        super.onResume()
        val lbm = LocalBroadcastManager.getInstance(this)
        lbm.registerReceiver(eventReceiver, IntentFilter(WatcherService.ACTION_FILE_EVENT))
        lbm.registerReceiver(proposalReceiver, IntentFilter(WatcherService.ACTION_PROPOSAL))
        bindWatcherService()
        updateAllFilesStatus()
        refreshDirsView()
        refreshFilesView()
    }

    override fun onPause() {
        val lbm = LocalBroadcastManager.getInstance(this)
        lbm.unregisterReceiver(eventReceiver)
        lbm.unregisterReceiver(proposalReceiver)
        super.onPause()
    }

    override fun onDestroy() {
        if (serviceBound || bindRequested) {
            unbindService(serviceConn)
            serviceBound = false
            bindRequested = false
        }
        super.onDestroy()
    }

    // ── Watch control ─────────────────────────────────────────────────────────

    private fun startWatching() {
        val svcIntent = Intent(this, WatcherService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svcIntent)
        else startService(svcIntent)

        val action = { watcherService?.startWatchAll() }
        if (serviceBound) {
            action()
        } else {
            pendingAction = action
            if (!bindRequested) {
                bindRequested = true
                bindService(svcIntent, serviceConn, Context.BIND_AUTO_CREATE)
            }
        }
        isWatching = true
        binding.toggleWatchButton.text = getString(R.string.stop_watching)
        binding.serviceStatusText.text = getString(R.string.service_running)
    }

    private fun stopWatching() {
        watcherService?.stopWatching()
        isWatching = false
        binding.toggleWatchButton.text = getString(R.string.start_watching)
        binding.serviceStatusText.text = getString(R.string.service_stopped)
    }

    private fun syncWatchButtonState() {
        val watching = watcherService?.isWatching ?: return
        if (watching && !isWatching) {
            isWatching = true
            binding.toggleWatchButton.text = getString(R.string.stop_watching)
            binding.serviceStatusText.text = getString(R.string.service_running)
        }
    }

    private fun bindWatcherService() {
        if (!serviceBound && !bindRequested) {
            bindRequested = true
            bindService(
                Intent(this, WatcherService::class.java),
                serviceConn,
                Context.BIND_AUTO_CREATE
            )
        }
    }

    // ── Directories view ──────────────────────────────────────────────────────

    private fun refreshDirsView() {
        val dirs = AppDatabase.get(this).dao().getAllDirs()
        binding.dirsContainer.removeAllViews()

        if (dirs.isEmpty()) {
            binding.dirsContainer.addView(
                TextView(this).apply { text = getString(R.string.no_dirs_yet); setPadding(0, 4, 0, 4) }
            )
            return
        }

        for (dir in dirs) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = 4.dp }
            }

            val fileCount = AppDatabase.get(this).dao().fileCountForDir(dir.id)
            val label = "${dir.displayName}  [${dir.mode}]  •  $fileCount file(s)"
            val tv = TextView(this).apply {
                text = label
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                textSize = 12f
            }

            val removeBtn = Button(this).apply {
                text = "✕"
                textSize = 11f
                setPadding(16, 4, 16, 4)
                setOnClickListener {
                    if (serviceBound) watcherService?.removeDirectory(dir.id)
                    else AppDatabase.get(this@MainActivity).dao().deleteDir(dir.id)
                    refreshDirsView()
                }
            }

            row.addView(tv)
            row.addView(removeBtn)
            binding.dirsContainer.addView(row)
        }
    }

    // ── Files view ────────────────────────────────────────────────────────────

    private fun refreshFilesView() {
        val files = AppDatabase.get(this).dao().getRecentFiles(20)
        if (files.isEmpty()) {
            binding.recentFilesText.text = getString(R.string.no_files_yet)
            return
        }
        binding.recentFilesText.text = files.joinToString("\n") { f ->
            "${ts.format(Date(f.detectedAt))} ${f.displayName}"
        }
    }

    // ── Debug log view ────────────────────────────────────────────────────────

    private fun refreshDebugLog() {
        val logs = AppDatabase.get(this).dao().getRecentLogs(80)
        if (logs.isEmpty()) { binding.debugLogText.text = "—"; return }
        binding.debugLogText.text = logs.joinToString("\n") { e ->
            "${ts.format(Date(e.timestamp))} [${e.level}] ${e.message}"
        }
    }

    // ── Panel toggle ──────────────────────────────────────────────────────────

    private fun togglePanel(container: LinearLayout, key: String) {
        val isOpen = container.tag == "open"
        container.visibility = if (isOpen) android.view.View.GONE else android.view.View.VISIBLE
        container.tag = if (isOpen) "closed" else "open"
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun updateAllFilesStatus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val granted = Environment.isExternalStorageManager()
            binding.allFilesStatusText.text = getString(
                if (granted) R.string.all_files_granted else R.string.all_files_not_granted
            )
            binding.requestAllFilesButton.isEnabled = !granted
        } else {
            binding.allFilesStatusText.text = getString(R.string.all_files_not_needed)
            binding.requestAllFilesButton.isEnabled = false
        }
    }

    private fun liveAppend(msg: String) {
        val line = "${ts.format(Date())} $msg"
        if (liveLog.size >= 30) liveLog.removeFirst()
        liveLog.addLast(line)
        binding.eventLogText.text = liveLog.joinToString("\n")
    }

    private val Int.dp get() = (this * resources.displayMetrics.density).toInt()

    private fun requestNotifPermIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun requestMediaPermsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val needed = listOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO
            ).filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (needed.isNotEmpty()) mediaPermLauncher.launch(needed.toTypedArray())
        }
    }
}
