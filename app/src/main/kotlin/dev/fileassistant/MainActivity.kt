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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import dev.fileassistant.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var watcherService: WatcherService? = null
    private var serviceBound = false
    private var bindRequested = false
    private var isWatching = false
    private var pendingWatchAction: (() -> Unit)? = null

    private val eventLog = ArrayDeque<String>()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            watcherService = (service as WatcherService.LocalBinder).getService()
            serviceBound = true
            bindRequested = false
            pendingWatchAction?.invoke()
            pendingWatchAction = null
            // Sync button state if the service was already watching (e.g. after boot-restart)
            if (watcherService?.isWatching == true && !isWatching) {
                isWatching = true
                binding.toggleWatchButton.text = getString(R.string.stop_watching)
                binding.serviceStatusText.text = getString(R.string.service_running)
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            watcherService = null
            serviceBound = false
            bindRequested = false
        }
    }

    private val proposalReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            binding.proposalText.text =
                intent.getStringExtra(WatcherService.EXTRA_PROPOSAL_JSON) ?: return
        }
    }

    private val fileEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val msg = intent.getStringExtra(WatcherService.EXTRA_EVENT_LOG) ?: return
            if (eventLog.size >= 20) eventLog.removeFirst()
            eventLog.addLast(msg)
            binding.eventLogText.text = eventLog.joinToString("\n")
        }
    }

    private val folderPickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                prefs().edit()
                    .putString(WatcherService.PREF_WATCH_MODE, WatcherService.WATCH_MODE_SAF)
                    .putString(WatcherService.PREF_WATCH_URI, uri.toString())
                    .apply()
                binding.folderUriText.text = uri.toString()
            }
        }

    private val notificationPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private val mediaPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    private val allFilesLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            updateAllFilesAccessStatus()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestNotificationPermIfNeeded()

        // Restore displayed folder label
        val p = prefs()
        when (p.getString(WatcherService.PREF_WATCH_MODE, null)) {
            WatcherService.WATCH_MODE_SAF ->
                binding.folderUriText.text =
                    p.getString(WatcherService.PREF_WATCH_URI, null) ?: getString(R.string.no_folder)
            WatcherService.WATCH_MODE_DOWNLOADS ->
                binding.folderUriText.text = getString(R.string.downloads_folder)
        }

        binding.chooseFolderButton.setOnClickListener {
            folderPickerLauncher.launch(null)
        }

        binding.watchDownloadsButton.setOnClickListener {
            requestMediaPermsIfNeeded()
            prefs().edit()
                .putString(WatcherService.PREF_WATCH_MODE, WatcherService.WATCH_MODE_DOWNLOADS)
                .apply()
            binding.folderUriText.text = getString(R.string.downloads_folder)
        }

        binding.requestAllFilesButton.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                allFilesLauncher.launch(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName"))
                )
            }
        }

        binding.toggleWatchButton.setOnClickListener {
            if (isWatching) stopWatching() else startWatching()
        }
    }

    override fun onResume() {
        super.onResume()
        val lbm = LocalBroadcastManager.getInstance(this)
        lbm.registerReceiver(proposalReceiver, IntentFilter(WatcherService.ACTION_PROPOSAL))
        lbm.registerReceiver(fileEventReceiver, IntentFilter(WatcherService.ACTION_FILE_EVENT))
        bindWatcherService()
        updateAllFilesAccessStatus()
    }

    override fun onPause() {
        val lbm = LocalBroadcastManager.getInstance(this)
        lbm.unregisterReceiver(proposalReceiver)
        lbm.unregisterReceiver(fileEventReceiver)
        super.onPause()
    }

    override fun onDestroy() {
        if (serviceBound || bindRequested) {
            unbindService(serviceConnection)
            serviceBound = false
            bindRequested = false
        }
        super.onDestroy()
    }

    private fun startWatching() {
        val p = prefs()
        val mode = p.getString(WatcherService.PREF_WATCH_MODE, null) ?: return
        val uri  = p.getString(WatcherService.PREF_WATCH_URI, null)?.let { Uri.parse(it) }
        if (mode == WatcherService.WATCH_MODE_SAF && uri == null) return

        val serviceIntent = Intent(this, WatcherService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(serviceIntent)
        else startService(serviceIntent)

        val action: () -> Unit = if (mode == WatcherService.WATCH_MODE_DOWNLOADS) {
            { watcherService?.startWatchingDownloads() }
        } else {
            { watcherService?.startWatchingUri(uri!!) }
        }

        if (serviceBound) {
            action()
        } else {
            pendingWatchAction = action
            if (!bindRequested) {
                bindRequested = true
                bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
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

    private fun bindWatcherService() {
        if (!serviceBound && !bindRequested) {
            bindRequested = true
            bindService(Intent(this, WatcherService::class.java),
                serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    private fun updateAllFilesAccessStatus() {
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

    private fun prefs() = getSharedPreferences(WatcherService.PREFS_NAME, Context.MODE_PRIVATE)

    private fun requestNotificationPermIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun requestMediaPermsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val needed = listOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO
            ).filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
            if (needed.isNotEmpty()) mediaPermLauncher.launch(needed.toTypedArray())
        }
    }
}
