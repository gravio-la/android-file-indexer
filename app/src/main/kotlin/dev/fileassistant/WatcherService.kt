package dev.fileassistant

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.io.File

class WatcherService : Service() {

    companion object {
        const val ACTION_PROPOSAL   = "dev.fileassistant.PROPOSAL"
        const val EXTRA_PROPOSAL_JSON = "proposal_json"
        const val ACTION_FILE_EVENT = "dev.fileassistant.FILE_EVENT"
        const val EXTRA_EVENT_LOG   = "event_log"

        // Shared prefs (used by service, activity, and boot receiver)
        const val PREFS_NAME        = "dev.fileassistant.prefs"
        const val PREF_WATCH_MODE   = "watch_mode"
        const val PREF_WATCH_URI    = "watch_uri"
        const val PREF_WAS_WATCHING = "was_watching"
        const val WATCH_MODE_SAF       = "SAF"
        const val WATCH_MODE_DOWNLOADS = "DOWNLOADS"

        private const val TAG = "FileAssistant"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "fileassistant_watcher"
        private const val POLL_MS = 5_000L
    }

    inner class LocalBinder : Binder() {
        fun getService(): WatcherService = this@WatcherService
    }

    private val binder = LocalBinder()
    private var pollHandler: Handler? = null
    private var pollRunnable: Runnable? = null
    private var downloadsObserver: ContentObserver? = null
    private var downloadsStartTime = 0L
    var isWatching = false
        private set

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        // START_STICKY restart or boot: resume previously active watch
        if (!isWatching) autoRestartWatch()
        return START_STICKY
    }

    override fun onDestroy() {
        stopWatching()
        super.onDestroy()
    }

    // ── Auto-restart from stored config ────────────────────────────────────────

    private fun autoRestartWatch() {
        val p = prefs()
        if (!p.getBoolean(PREF_WAS_WATCHING, false)) return
        Log.d(TAG, "Auto-restarting watch from stored config")
        when (p.getString(PREF_WATCH_MODE, null)) {
            WATCH_MODE_DOWNLOADS -> startWatchingDownloads()
            WATCH_MODE_SAF -> {
                val uriStr = p.getString(PREF_WATCH_URI, null) ?: return
                startWatchingUri(Uri.parse(uriStr))
            }
        }
    }

    // ── SAF DocumentFile polling (for user-picked folders) ────────────────────

    fun startWatchingUri(treeUri: Uri) {
        stopWatching()
        prefs().edit().putString(PREF_WATCH_MODE, WATCH_MODE_SAF)
            .putString(PREF_WATCH_URI, treeUri.toString())
            .putBoolean(PREF_WAS_WATCHING, true).apply()

        broadcastStatus("Resolving SAF folder…")
        val root = DocumentFile.fromTreeUri(this, treeUri)
        if (root == null || !root.exists()) {
            broadcastStatus("ERROR: cannot access SAF folder — check permissions")
            return
        }
        val name = root.name ?: treeUri.toString()
        broadcastStatus("SAF polling '$name' every ${POLL_MS / 1000}s")

        val seen = root.listFiles().mapTo(mutableSetOf()) { it.uri.toString() }
        Log.d(TAG, "SAF snapshot: ${seen.size} items in $name")

        startPollLoop {
            val current = DocumentFile.fromTreeUri(this, treeUri)?.listFiles() ?: emptyArray()
            for (doc in current) {
                val uriStr = doc.uri.toString()
                if (uriStr !in seen) {
                    seen.add(uriStr)
                    val fileName = doc.name ?: uriStr
                    Log.d(TAG, "SAF new: $fileName")
                    emitEvent("FILE_CREATED", fileName, uriStr)
                }
            }
        }
    }

    // ── Downloads: direct File polling (MANAGE_EXTERNAL_STORAGE) or MediaStore ─

    fun startWatchingDownloads() {
        stopWatching()
        prefs().edit().putString(PREF_WATCH_MODE, WATCH_MODE_DOWNLOADS)
            .putBoolean(PREF_WAS_WATCHING, true).apply()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            startWatchingPath(dir)
        } else {
            startWatchingDownloadsViaMediaStore()
        }
    }

    // Direct File polling — works when MANAGE_EXTERNAL_STORAGE is granted
    private fun startWatchingPath(dir: File) {
        broadcastStatus("Direct polling '${dir.name}' every ${POLL_MS / 1000}s")
        if (!dir.exists() || !dir.isDirectory) {
            broadcastStatus("ERROR: not a directory: ${dir.absolutePath}")
            return
        }
        val seen = dir.listFiles()?.mapTo(mutableSetOf()) { it.absolutePath } ?: mutableSetOf()
        Log.d(TAG, "Path snapshot: ${seen.size} items in ${dir.absolutePath}")

        startPollLoop {
            for (file in dir.listFiles() ?: emptyArray()) {
                if (file.absolutePath !in seen) {
                    seen.add(file.absolutePath)
                    Log.d(TAG, "Path new: ${file.name}")
                    emitEvent("FILE_CREATED", file.name, file.absolutePath)
                }
            }
        }
    }

    // MediaStore fallback — used when MANAGE_EXTERNAL_STORAGE is not granted
    private fun startWatchingDownloadsViaMediaStore() {
        broadcastStatus("Downloads via MediaStore (grant All Files Access for direct polling)")
        downloadsStartTime = System.currentTimeMillis() / 1000L
        downloadsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                Log.d(TAG, "MediaStore onChange")
                queryNewDownloads()
            }
        }
        contentResolver.registerContentObserver(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI, true, downloadsObserver!!
        )
        isWatching = true
    }

    private fun queryNewDownloads() {
        val proj = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.DATE_ADDED
        )
        contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI, proj,
            "${MediaStore.Downloads.DATE_ADDED} >= ?",
            arrayOf(downloadsStartTime.toString()),
            "${MediaStore.Downloads.DATE_ADDED} DESC"
        )?.use { c ->
            Log.d(TAG, "queryNewDownloads: ${c.count} rows")
            while (c.moveToNext()) {
                val id   = c.getLong(c.getColumnIndexOrThrow(MediaStore.Downloads._ID))
                val name = c.getString(c.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME))
                val uri  = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
                Log.d(TAG, "New download: $name")
                emitEvent("FILE_CREATED", name ?: uri.toString(), uri.toString())
            }
        }
    }

    // ── Poll loop ──────────────────────────────────────────────────────────────

    private fun startPollLoop(tick: () -> Unit) {
        val handler = Handler(Looper.getMainLooper())
        val runnable = object : Runnable {
            override fun run() {
                if (!isWatching) return
                try { tick() } catch (e: Exception) {
                    Log.e(TAG, "Poll error", e)
                    broadcastStatus("Poll error: ${e.message}")
                }
                handler.postDelayed(this, POLL_MS)
            }
        }
        pollHandler = handler
        pollRunnable = runnable
        handler.postDelayed(runnable, POLL_MS)
        isWatching = true
    }

    // ── Stop ──────────────────────────────────────────────────────────────────

    fun stopWatching() {
        pollRunnable?.let { pollHandler?.removeCallbacks(it) }
        pollHandler = null
        pollRunnable = null
        downloadsObserver?.let { contentResolver.unregisterContentObserver(it) }
        downloadsObserver = null
        isWatching = false
        prefs().edit().putBoolean(PREF_WAS_WATCHING, false).apply()
        Log.d(TAG, "stopWatching")
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun emitEvent(type: String, displayName: String, uriOrPath: String) {
        broadcastFileEvent(type, displayName)
        RustBridge.nativeOnFileEvent(type, uriOrPath)
        broadcastProposal(RustBridge.nativeClassify(uriOrPath))
    }

    private fun broadcastStatus(msg: String) {
        Log.d(TAG, "STATUS: $msg")
        broadcastFileEvent("STATUS", msg)
    }

    private fun broadcastFileEvent(type: String, msg: String) {
        LocalBroadcastManager.getInstance(this).sendBroadcast(
            Intent(ACTION_FILE_EVENT).putExtra(EXTRA_EVENT_LOG, "[$type] $msg")
        )
    }

    private fun broadcastProposal(json: String) {
        LocalBroadcastManager.getInstance(this).sendBroadcast(
            Intent(ACTION_PROPOSAL).putExtra(EXTRA_PROPOSAL_JSON, json)
        )
    }

    private fun prefs() = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle(getString(R.string.notification_title))
        .setContentText(getString(R.string.notification_text))
        .setSmallIcon(android.R.drawable.ic_menu_search)
        .setOngoing(true)
        .build()
}
