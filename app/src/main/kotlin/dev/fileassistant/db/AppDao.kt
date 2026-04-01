package dev.fileassistant.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface AppDao {

    // ── Watched directories ───────────────────────────────────────────────────

    @Query("SELECT * FROM watched_dirs ORDER BY addedAt ASC")
    fun getAllDirs(): List<WatchedDirectory>

    @Query("SELECT * FROM watched_dirs WHERE active = 1 ORDER BY addedAt ASC")
    fun getActiveDirs(): List<WatchedDirectory>

    /** Returns the new row id, or -1 if the uri already exists (unique constraint). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertDir(dir: WatchedDirectory): Long

    @Query("DELETE FROM watched_dirs WHERE id = :id")
    fun deleteDir(id: Long)

    @Query("UPDATE watched_dirs SET active = :active WHERE id = :id")
    fun setDirActive(id: Long, active: Boolean)

    // ── Detected files ────────────────────────────────────────────────────────

    /**
     * Insert or ignore. Returns -1 when the uri already exists in the table,
     * which lets the caller skip re-broadcasting duplicate detections.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertFile(file: DetectedFile): Long

    @Query("UPDATE detected_files SET proposal = :proposal WHERE id = :id")
    fun updateFileProposal(id: Long, proposal: String)

    @Query("SELECT * FROM detected_files ORDER BY detectedAt DESC LIMIT :limit")
    fun getRecentFiles(limit: Int): List<DetectedFile>

    @Query("SELECT COUNT(*) FROM detected_files WHERE dirId = :dirId")
    fun fileCountForDir(dirId: Long): Int

    // ── Debug log ─────────────────────────────────────────────────────────────

    @Insert
    fun insertLog(entry: DebugLogEntry)

    @Query("SELECT * FROM debug_log ORDER BY id DESC LIMIT :limit")
    fun getRecentLogs(limit: Int): List<DebugLogEntry>

    /** Keep only the most recent 1 000 entries to prevent unbounded growth. */
    @Query("""
        DELETE FROM debug_log
        WHERE id NOT IN (
            SELECT id FROM debug_log ORDER BY id DESC LIMIT 1000
        )
    """)
    fun pruneDebugLog()
}
