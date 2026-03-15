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
    private var isWatching = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val binder = service as WatcherService.LocalBinder
            watcherService = binder.getService()
            serviceBound = true
        }

        override fun onServiceDisconnected(name: ComponentName) {
            watcherService = null
            serviceBound = false
        }
    }

    private val proposalReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val json = intent.getStringExtra(WatcherService.EXTRA_PROPOSAL_JSON) ?: return
            binding.proposalText.text = json
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
                binding.folderUriText.text = uri.toString()
            }
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* graceful — no-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestNotificationPermissionIfNeeded()

        val storedUri = loadWatchUri()
        if (storedUri != null) {
            binding.folderUriText.text = storedUri.toString()
        }

        binding.chooseFolderButton.setOnClickListener {
            folderPickerLauncher.launch(null)
        }

        binding.toggleWatchButton.setOnClickListener {
            if (isWatching) {
                stopWatching()
            } else {
                startWatching()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        LocalBroadcastManager.getInstance(this).registerReceiver(
            proposalReceiver,
            IntentFilter(WatcherService.ACTION_PROPOSAL)
        )
        bindWatcherService()
    }

    override fun onPause() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(proposalReceiver)
        super.onPause()
    }

    override fun onDestroy() {
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        super.onDestroy()
    }

    private fun startWatching() {
        val uri = loadWatchUri() ?: return

        val serviceIntent = Intent(this, WatcherService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        if (!serviceBound) {
            bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
        }

        watcherService?.startWatchingUri(uri)
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
        if (!serviceBound) {
            val intent = Intent(this, WatcherService::class.java)
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    private fun storeWatchUri(uri: Uri) {
        getPreferences(Context.MODE_PRIVATE)
            .edit()
            .putString("watch_uri", uri.toString())
            .apply()
    }

    private fun loadWatchUri(): Uri? {
        val uriString = getPreferences(Context.MODE_PRIVATE).getString("watch_uri", null)
        return uriString?.let { Uri.parse(it) }
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
}
