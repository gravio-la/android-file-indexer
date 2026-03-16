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
import android.os.IBinder
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
            val binder = service as WatcherService.LocalBinder
            watcherService = binder.getService()
            serviceBound = true
            bindRequested = false
            pendingWatchAction?.invoke()
            pendingWatchAction = null
        }

        override fun onServiceDisconnected(name: ComponentName) {
            watcherService = null
            serviceBound = false
            bindRequested = false
        }
    }

    private val proposalReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val json = intent.getStringExtra(WatcherService.EXTRA_PROPOSAL_JSON) ?: return
            binding.proposalText.text = json
        }
    }

    private val fileEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val log = intent.getStringExtra(WatcherService.EXTRA_EVENT_LOG) ?: return
            if (eventLog.size >= 20) eventLog.removeFirst()
            eventLog.addLast(log)
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
                storeWatchUri(uri)
                storeWatchMode(WatchMode.SAF)
                binding.folderUriText.text = uri.toString()
            }
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* graceful */ }

    private val mediaPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { /* graceful */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestNotificationPermissionIfNeeded()

        val storedUri = loadWatchUri()
        if (storedUri != null) {
            binding.folderUriText.text = storedUri.toString()
        } else if (loadWatchMode() == WatchMode.DOWNLOADS) {
            binding.folderUriText.text = getString(R.string.downloads_folder)
        }

        binding.chooseFolderButton.setOnClickListener {
            folderPickerLauncher.launch(null)
        }

        binding.watchDownloadsButton.setOnClickListener {
            requestMediaPermissionsIfNeeded()
            storeWatchMode(WatchMode.DOWNLOADS)
            binding.folderUriText.text = getString(R.string.downloads_folder)
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
        val serviceIntent = Intent(this, WatcherService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        val mode = loadWatchMode()
        val uri = if (mode == WatchMode.SAF) loadWatchUri() ?: return else null
        val action: () -> Unit = if (mode == WatchMode.DOWNLOADS) {
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
            val intent = Intent(this, WatcherService::class.java)
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    private fun storeWatchUri(uri: Uri) {
        getPreferences(Context.MODE_PRIVATE).edit()
            .putString("watch_uri", uri.toString())
            .apply()
    }

    private fun loadWatchUri(): Uri? {
        val s = getPreferences(Context.MODE_PRIVATE).getString("watch_uri", null)
        return s?.let { Uri.parse(it) }
    }

    private enum class WatchMode { SAF, DOWNLOADS }

    private fun storeWatchMode(mode: WatchMode) {
        getPreferences(Context.MODE_PRIVATE).edit()
            .putString("watch_mode", mode.name)
            .apply()
    }

    private fun loadWatchMode(): WatchMode {
        val name = getPreferences(Context.MODE_PRIVATE).getString("watch_mode", null)
        return if (name == WatchMode.DOWNLOADS.name) WatchMode.DOWNLOADS else WatchMode.SAF
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun requestMediaPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val needed = listOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO
            ).filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (needed.isNotEmpty()) {
                mediaPermissionLauncher.launch(needed.toTypedArray())
            }
        }
    }
}
