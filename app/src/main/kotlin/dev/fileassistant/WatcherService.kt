package dev.fileassistant

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentUris
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class WatcherService : Service() {

    companion object {
        const val ACTION_PROPOSAL = "dev.fileassistant.PROPOSAL"
        const val EXTRA_PROPOSAL_JSON = "proposal_json"
        const val ACTION_FILE_EVENT = "dev.fileassistant.FILE_EVENT"
        const val EXTRA_EVENT_LOG = "event_log"
        private const val TAG = "FileAssistant"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "fileassistant_watcher"
        private const val SAF_POLL_INTERVAL_MS = 5_000L
    }

    inner class LocalBinder : Binder() {
        fun getService(): WatcherService = this@WatcherService
    }

    private val binder = LocalBinder()
    private var downloadsObserver: ContentObserver? = null
    private var downloadsStartTime: Long = 0L
    private var safPollHandler: Handler? = null
    private var safPollRunnable: Runnable? = null
    private var isWatching = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_STICKY
    }

    override fun onDestroy() {
        stopWatching()
        super.onDestroy()
    }

    // ── SAF folder polling (replaces FileObserver which silently fails on FUSE) ──

    fun startWatchingUri(treeUri: Uri) {
        stopWatching()
        Log.d(TAG, "startWatchingUri: $treeUri")
        broadcastStatus("Resolving folder URI…")

        val root = DocumentFile.fromTreeUri(this, treeUri)
        if (root == null || !root.exists()) {
            broadcastStatus("ERROR: cannot access folder — $treeUri")
            Log.e(TAG, "DocumentFile.fromTreeUri returned null or non-existent for $treeUri")
            return
        }

        val name = root.name ?: treeUri.toString()
        broadcastStatus("Polling '$name' every ${SAF_POLL_INTERVAL_MS / 1000}s…")
        Log.d(TAG, "SAF poll starting on: $name")

        val seen = root.listFiles().mapTo(mutableSetOf()) { it.uri.toString() }
        Log.d(TAG, "Initial snapshot: ${seen.size} items")

        val handler = Handler(Looper.getMainLooper())
        val runnable = object : Runnable {
            override fun run() {
                val current = DocumentFile.fromTreeUri(this@WatcherService, treeUri)
                    ?.listFiles() ?: emptyArray()
                for (doc in current) {
                    val uriStr = doc.uri.toString()
                    if (uriStr !in seen) {
                        seen.add(uriStr)
                        val fileName = doc.name ?: uriStr
                        Log.d(TAG, "New file detected: $fileName")
                        broadcastFileEvent("FILE_CREATED", fileName)
                        RustBridge.nativeOnFileEvent("FILE_CREATED", uriStr)
                        val proposal = RustBridge.nativeClassify(uriStr)
                        broadcastProposal(proposal)
                    }
                }
                if (isWatching) handler.postDelayed(this, SAF_POLL_INTERVAL_MS)
            }
        }

        safPollHandler = handler
        safPollRunnable = runnable
        handler.postDelayed(runnable, SAF_POLL_INTERVAL_MS)
        isWatching = true
    }

    // ── MediaStore Downloads observer ──

    fun startWatchingDownloads() {
        stopWatching()
        downloadsStartTime = System.currentTimeMillis() / 1000L
        Log.d(TAG, "startWatchingDownloads, epoch baseline=$downloadsStartTime")
        broadcastStatus("Watching Downloads via MediaStore…")

        downloadsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                Log.d(TAG, "MediaStore.Downloads onChange")
                queryNewDownloads()
            }
        }
        contentResolver.registerContentObserver(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            true,
            downloadsObserver!!
        )
        isWatching = true
    }

    private fun queryNewDownloads() {
        val projection = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.DATE_ADDED
        )
        val selection = "${MediaStore.Downloads.DATE_ADDED} >= ?"
        val selectionArgs = arrayOf(downloadsStartTime.toString())
        contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            "${MediaStore.Downloads.DATE_ADDED} DESC"
        )?.use { cursor ->
            Log.d(TAG, "queryNewDownloads: ${cursor.count} rows")
            while (cursor.moveToNext()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID))
                val name = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME))
                val contentUri = ContentUris.withAppendedId(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, id
                )
                val uriStr = contentUri.toString()
                Log.d(TAG, "New download: $name")
                broadcastFileEvent("FILE_CREATED", name ?: uriStr)
                RustBridge.nativeOnFileEvent("FILE_CREATED", uriStr)
                val proposal = RustBridge.nativeClassify(uriStr)
                broadcastProposal(proposal)
            }
        }
    }

    // ── Lifecycle ──

    fun stopWatching() {
        safPollRunnable?.let { safPollHandler?.removeCallbacks(it) }
        safPollHandler = null
        safPollRunnable = null

        downloadsObserver?.let { contentResolver.unregisterContentObserver(it) }
        downloadsObserver = null

        isWatching = false
        Log.d(TAG, "stopWatching")
    }

    fun isWatching(): Boolean = isWatching

    // ── Broadcast helpers ──

    private fun broadcastStatus(msg: String) {
        Log.d(TAG, "STATUS: $msg")
        broadcastFileEvent("STATUS", msg)
    }

    private fun broadcastFileEvent(eventType: String, path: String) {
        val intent = Intent(ACTION_FILE_EVENT).apply {
            putExtra(EXTRA_EVENT_LOG, "[$eventType] $path")
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun broadcastProposal(json: String) {
        val intent = Intent(ACTION_PROPOSAL).apply {
            putExtra(EXTRA_PROPOSAL_JSON, json)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    // ── Notification ──

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle(getString(R.string.notification_title))
        .setContentText(getString(R.string.notification_text))
        .setSmallIcon(android.R.drawable.ic_menu_search)
        .setOngoing(true)
        .build()
}
