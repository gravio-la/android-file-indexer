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
import dev.fileassistant.db.AppDatabase
import dev.fileassistant.db.DebugLogEntry
import dev.fileassistant.db.DetectedFile
import dev.fileassistant.db.WatchedDirectory

class WatcherService : Service() {

    companion object {
        const val ACTION_FILE_EVENT   = "dev.fileassistant.FILE_EVENT"
        const val EXTRA_EVENT_LOG     = "event_log"
        const val ACTION_PROPOSAL     = "dev.fileassistant.PROPOSAL"
        const val EXTRA_PROPOSAL_JSON = "proposal_json"

        const val PREFS_NAME        = "dev.fileassistant.prefs"
        const val PREF_WAS_WATCHING = "was_watching"

        const val WATCH_MODE_SAF       = "SAF"
        const val WATCH_MODE_DOWNLOADS = "DOWNLOADS"

        private const val TAG = "FileAssistant"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "fileassistant_watcher"
        private const val POLL_MS = 5_000L
        private const val PRUNE_EVERY_N_LOGS = 200
    }

    inner class LocalBinder : Binder() {
        fun getService(): WatcherService = this@WatcherService
    }

    private val binder = LocalBinder()

    /** dirId → set of already-seen URIs / paths for that directory */
    private val seenFiles = mutableMapOf<Long, MutableSet<String>>()

    private var pollHandler: Handler? = null
    private var pollRunnable: Runnable? = null
    private var mediaStoreObserver: ContentObserver? = null
    private var mediaStoreDirId: Long = -1L
    private var mediaStoreStartEpoch: Long = 0L
    private var logInsertCount = 0

    var isWatching = false
        private set

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        if (!isWatching) autoRestartWatch()
        return START_STICKY
    }

    override fun onDestroy() {
        stopWatching()
        super.onDestroy()
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** (Re-)start watching all active directories from the DB. */
    fun startWatchAll() {
        stopWatching()
        val db = AppDatabase.get(this)
        val dirs = db.dao().getActiveDirs()

        if (dirs.isEmpty()) {
            dbLog("W", "startWatchAll: no active directories in DB")
            return
        }

        dbLog("I", "=== startWatchAll: ${dirs.size} dir(s) ===")
        seenFiles.clear()

        val pollable = mutableListOf<WatchedDirectory>()

        for (dir in dirs) {
            dbLog("D", "Init dir id=${dir.id} '${dir.displayName}' mode=${dir.mode} uri=${dir.uri}")
            try {
                when (dir.mode) {
                    WATCH_MODE_SAF -> {
                        val uri = Uri.parse(dir.uri)
                        val root = DocumentFile.fromTreeUri(this, uri)
                        when {
                            root == null ->
                                dbLog("E", "SAF fromTreeUri=null '${dir.displayName}' — URI may have expired, re-pick the folder")
                            !root.canRead() ->
                                dbLog("E", "SAF canRead=false '${dir.displayName}' — permission revoked?")
                            !root.isDirectory ->
                                dbLog("E", "SAF isDirectory=false '${dir.displayName}'")
                            else -> {
                                val snap = root.listFiles()
                                seenFiles[dir.id] = snap.mapTo(mutableSetOf()) { it.uri.toString() }
                                dbLog("I", "SAF '${dir.displayName}': initial snapshot = ${snap.size} item(s)")
                                pollable.add(dir)
                            }
                        }
                    }
                    WATCH_MODE_DOWNLOADS -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                            Environment.isExternalStorageManager()
                        ) {
                            val dlDir = Environment.getExternalStoragePublicDirectory(
                                Environment.DIRECTORY_DOWNLOADS
                            )
                            val snap = dlDir.listFiles() ?: emptyArray()
                            seenFiles[dir.id] = snap.mapTo(mutableSetOf()) { it.absolutePath }
                            dbLog("I", "Downloads direct: initial snapshot = ${snap.size} item(s) in ${dlDir.absolutePath}")
                            pollable.add(dir)
                        } else {
                            dbLog("I", "Downloads: MANAGE_EXTERNAL_STORAGE not granted → MediaStore observer")
                            startMediaStoreObserver(dir.id)
                        }
                    }
                    else -> dbLog("W", "Unknown mode '${dir.mode}' for '${dir.displayName}'")
                }
            } catch (e: Exception) {
                dbLog("E", "Init '${dir.displayName}': ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        startPollLoop(pollable)

        // Even if there are no pollable dirs, the MediaStore observer counts
        if (!isWatching && mediaStoreObserver != null) {
            isWatching = true
        }

        if (isWatching) {
            prefs().edit().putBoolean(PREF_WAS_WATCHING, true).apply()
            dbLog("I", "Watching: ${pollable.size} polled + ${if (mediaStoreObserver != null) 1 else 0} MediaStore")
            broadcast("[STATUS] Watching ${dirs.size} directory(s)")
        } else {
            dbLog("W", "startWatchAll: nothing started (no accessible dirs)")
        }
    }

    /** Adds a directory to the DB and restarts the watch. */
    fun addDirectory(dir: WatchedDirectory) {
        val id = AppDatabase.get(this).dao().insertDir(dir)
        if (id == -1L) {
            dbLog("W", "addDirectory: '${dir.displayName}' already in DB (duplicate URI)")
        } else {
            dbLog("I", "Directory added: '${dir.displayName}' id=$id")
        }
        if (isWatching) startWatchAll()
    }

    /** Removes a directory from the DB and restarts the watch. */
    fun removeDirectory(dirId: Long) {
        AppDatabase.get(this).dao().deleteDir(dirId)
        dbLog("I", "Directory removed id=$dirId")
        seenFiles.remove(dirId)
        if (isWatching) startWatchAll()
    }

    /** Immediately runs one poll cycle on all active directories. */
    fun scanNow() {
        dbLog("I", "=== Manual scan triggered ===")
        val db = AppDatabase.get(this)
        val dirs = db.dao().getActiveDirs()
        if (dirs.isEmpty()) { dbLog("W", "scanNow: no active dirs"); return }

        var totalNew = 0
        for (dir in dirs) {
            if (dir.id !in seenFiles && dir.mode != WATCH_MODE_DOWNLOADS) {
                dbLog("W", "scanNow: '${dir.displayName}' not initialised yet — run Start Watching first")
                continue
            }
            totalNew += pollDir(db, dir)
        }
        dbLog("I", "Manual scan complete: $totalNew new file(s)")
        broadcast("[STATUS] Scan done: $totalNew new file(s)")
    }

    fun stopWatching() {
        pollRunnable?.let { pollHandler?.removeCallbacks(it) }
        pollHandler = null
        pollRunnable = null
        mediaStoreObserver?.let { contentResolver.unregisterContentObserver(it) }
        mediaStoreObserver = null
        mediaStoreDirId = -1L
        seenFiles.clear()
        isWatching = false
        prefs().edit().putBoolean(PREF_WAS_WATCHING, false).apply()
        dbLog("I", "Stopped watching")
    }

    // ── Convenience accessors for the Activity ────────────────────────────────

    fun getAllDirs(): List<WatchedDirectory> = AppDatabase.get(this).dao().getAllDirs()
    fun getRecentFiles(n: Int = 30): List<DetectedFile> =
        AppDatabase.get(this).dao().getRecentFiles(n)
    fun getRecentLogs(n: Int = 60): List<DebugLogEntry> =
        AppDatabase.get(this).dao().getRecentLogs(n)
    fun fileCountForDir(id: Long): Int = AppDatabase.get(this).dao().fileCountForDir(id)

    // ── Private: poll loop ────────────────────────────────────────────────────

    private fun startPollLoop(dirs: List<WatchedDirectory>) {
        if (dirs.isEmpty()) return
        val db = AppDatabase.get(this)
        val handler = Handler(Looper.getMainLooper())
        var cycle = 0

        val runnable = object : Runnable {
            override fun run() {
                if (!isWatching) return
                cycle++
                // heartbeat every ~1 min so you can see the loop is alive
                if (cycle % 12 == 0) dbLog("D", "Poll heartbeat #$cycle (${dirs.size} dir(s))")

                for (dir in dirs) pollDir(db, dir)
                handler.postDelayed(this, POLL_MS)
            }
        }

        pollHandler = handler
        pollRunnable = runnable
        handler.postDelayed(runnable, POLL_MS)
        isWatching = true
        dbLog("I", "Poll loop armed: ${dirs.map { "'${it.displayName}'" }}, interval=${POLL_MS}ms")
    }

    private fun pollDir(db: AppDatabase, dir: WatchedDirectory): Int {
        var newCount = 0
        try {
            when (dir.mode) {
                WATCH_MODE_SAF -> {
                    val seen = seenFiles[dir.id] ?: run {
                        dbLog("W", "pollDir SAF '${dir.displayName}': no seen-set, skipping")
                        return 0
                    }
                    val root = DocumentFile.fromTreeUri(this, Uri.parse(dir.uri))
                    if (root == null || !root.exists()) {
                        dbLog("W", "SAF '${dir.displayName}': inaccessible during poll (root=${root})")
                        return 0
                    }
                    val current = root.listFiles()
                    dbLog("D", "SAF poll '${dir.displayName}': ${current.size} item(s) listed")
                    for (doc in current) {
                        val uriStr = doc.uri.toString()
                        if (uriStr !in seen) {
                            seen.add(uriStr)
                            val name = doc.name ?: uriStr
                            dbLog("I", "NEW [SAF '${dir.displayName}']: $name")
                            emitAndStore(db, dir.id, name, uriStr)
                            newCount++
                        }
                    }
                }
                WATCH_MODE_DOWNLOADS -> {
                    val seen = seenFiles[dir.id] ?: run {
                        dbLog("W", "pollDir Downloads: no seen-set"); return 0
                    }
                    val dlDir = Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS
                    )
                    val current = dlDir.listFiles() ?: emptyArray()
                    dbLog("D", "Downloads poll: ${current.size} item(s) in ${dlDir.absolutePath}")
                    for (file in current) {
                        if (file.absolutePath !in seen) {
                            seen.add(file.absolutePath)
                            dbLog("I", "NEW [Downloads]: ${file.name}")
                            emitAndStore(db, dir.id, file.name, file.absolutePath)
                            newCount++
                        }
                    }
                }
            }
        } catch (e: Exception) {
            dbLog("E", "pollDir '${dir.displayName}': ${e.javaClass.simpleName}: ${e.message}")
        }
        return newCount
    }

    // ── Private: MediaStore observer (Downloads fallback) ─────────────────────

    private fun startMediaStoreObserver(dirId: Long) {
        mediaStoreDirId = dirId
        mediaStoreStartEpoch = System.currentTimeMillis() / 1000L
        mediaStoreObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                dbLog("D", "MediaStore.Downloads onChange, querying since epoch=$mediaStoreStartEpoch")
                queryNewDownloads()
            }
        }
        contentResolver.registerContentObserver(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI, true, mediaStoreObserver!!
        )
        dbLog("I", "MediaStore Downloads observer registered, dirId=$dirId")
    }

    private fun queryNewDownloads() {
        val db = AppDatabase.get(this)
        val proj = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.DATE_ADDED
        )
        contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI, proj,
            "${MediaStore.Downloads.DATE_ADDED} >= ?",
            arrayOf(mediaStoreStartEpoch.toString()),
            "${MediaStore.Downloads.DATE_ADDED} DESC"
        )?.use { c ->
            dbLog("D", "MediaStore query: ${c.count} row(s)")
            while (c.moveToNext()) {
                val id   = c.getLong(c.getColumnIndexOrThrow(MediaStore.Downloads._ID))
                val name = c.getString(c.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME))
                val uri  = ContentUris.withAppendedId(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, id
                )
                dbLog("I", "NEW [MediaStore]: $name")
                emitAndStore(db, mediaStoreDirId, name ?: uri.toString(), uri.toString())
            }
        }
    }

    // ── Private: emit + store ─────────────────────────────────────────────────

    private fun emitAndStore(db: AppDatabase, dirId: Long, displayName: String, uri: String) {
        // INSERT OR IGNORE — returns -1 if already stored (unique uri index)
        val rowId = db.dao().insertFile(
            DetectedFile(dirId = dirId, uri = uri, displayName = displayName)
        )
        if (rowId == -1L) return // duplicate, already emitted previously

        broadcast("[FILE_CREATED] $displayName")
        RustBridge.nativeOnFileEvent("FILE_CREATED", uri)
        val proposal = RustBridge.nativeClassify(uri)
        db.dao().updateFileProposal(rowId, proposal)
        LocalBroadcastManager.getInstance(this).sendBroadcast(
            Intent(ACTION_PROPOSAL).putExtra(EXTRA_PROPOSAL_JSON, proposal)
        )
    }

    // ── Private: logging / broadcast ─────────────────────────────────────────

    private fun dbLog(level: String, message: String) {
        val priority = when (level) {
            "E" -> Log.ERROR; "W" -> Log.WARN; "I" -> Log.INFO; else -> Log.DEBUG
        }
        Log.println(priority, TAG, message)

        try {
            val db = AppDatabase.get(this)
            db.dao().insertLog(DebugLogEntry(level = level, message = message))
            logInsertCount++
            if (logInsertCount % PRUNE_EVERY_N_LOGS == 0) db.dao().pruneDebugLog()
        } catch (e: Exception) {
            Log.e(TAG, "dbLog insert failed: ${e.message}")
        }

        // Broadcast I/W/E entries so the live event log in the UI reflects them
        if (level != "D") broadcast("[$level] $message")
    }

    private fun broadcast(msg: String) {
        LocalBroadcastManager.getInstance(this).sendBroadcast(
            Intent(ACTION_FILE_EVENT).putExtra(EXTRA_EVENT_LOG, msg)
        )
    }

    private fun autoRestartWatch() {
        if (!prefs().getBoolean(PREF_WAS_WATCHING, false)) return
        dbLog("I", "Auto-restart: resuming from last config")
        startWatchAll()
    }

    private fun prefs() = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle(getString(R.string.notification_title))
        .setContentText(getString(R.string.notification_text))
        .setSmallIcon(android.R.drawable.ic_menu_search)
        .setOngoing(true)
        .build()
}
