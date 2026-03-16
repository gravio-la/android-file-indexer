package dev.fileassistant

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentUris
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Binder
import android.os.Environment
import android.os.FileObserver
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.io.File

class WatcherService : Service() {

    companion object {
        const val ACTION_PROPOSAL = "dev.fileassistant.PROPOSAL"
        const val EXTRA_PROPOSAL_JSON = "proposal_json"
        const val ACTION_FILE_EVENT = "dev.fileassistant.FILE_EVENT"
        const val EXTRA_EVENT_LOG = "event_log"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "fileassistant_watcher"
    }

    inner class LocalBinder : Binder() {
        fun getService(): WatcherService = this@WatcherService
    }

    private val binder = LocalBinder()
    private var fileObserver: FileObserver? = null
    private var downloadsObserver: ContentObserver? = null
    private var downloadsStartTime: Long = 0L
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

    fun startWatching(path: String) {
        stopWatching()
        val watchDir = File(path)
        if (!watchDir.exists()) return

        fileObserver = object : FileObserver(watchDir, CREATE or CLOSE_WRITE) {
            override fun onEvent(event: Int, relativePath: String?) {
                if (relativePath == null) return
                val fullPath = "$path/$relativePath"
                val eventType = if (event and CREATE != 0) "FILE_CREATED" else "FILE_MODIFIED"
                broadcastFileEvent(eventType, fullPath)
                RustBridge.nativeOnFileEvent(eventType, fullPath)
                val proposal = RustBridge.nativeClassify(fullPath)
                broadcastProposal(proposal)
            }
        }
        fileObserver?.startWatching()
        isWatching = true
    }

    fun startWatchingDownloads() {
        stopWatching()
        downloadsStartTime = System.currentTimeMillis() / 1000L
        downloadsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
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
            while (cursor.moveToNext()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID))
                val name = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME))
                val contentUri = ContentUris.withAppendedId(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, id
                )
                val uriString = contentUri.toString()
                broadcastFileEvent("FILE_CREATED", name ?: uriString)
                RustBridge.nativeOnFileEvent("FILE_CREATED", uriString)
                val proposal = RustBridge.nativeClassify(uriString)
                broadcastProposal(proposal)
            }
        }
    }

    fun stopWatching() {
        fileObserver?.stopWatching()
        fileObserver = null
        downloadsObserver?.let { contentResolver.unregisterContentObserver(it) }
        downloadsObserver = null
        isWatching = false
    }

    fun isWatching(): Boolean = isWatching

    fun startWatchingUri(uri: Uri) {
        val path = uriToPath(uri)
        if (path != null) {
            startWatching(path)
        }
    }

    private fun uriToPath(uri: Uri): String? {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(uri)
            val parts = docId.split(":")
            if (parts[0] == "primary") {
                "${Environment.getExternalStorageDirectory()}/${parts.getOrElse(1) { "" }}"
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
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

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle(getString(R.string.notification_title))
        .setContentText(getString(R.string.notification_text))
        .setSmallIcon(android.R.drawable.ic_menu_search)
        .setOngoing(true)
        .build()
}
