package dev.fileassistant.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A directory (or virtual source) the service should poll.
 * uri is unique — prevents adding the same folder twice.
 */
@Entity(
    tableName = "watched_dirs",
    indices = [Index(value = ["uri"], unique = true)]
)
data class WatchedDirectory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** SAF tree URI string, or the absolute path for direct-access dirs */
    val uri: String,
    val displayName: String,
    /** WatcherService.WATCH_MODE_SAF or WATCH_MODE_DOWNLOADS */
    val mode: String,
    val active: Boolean = true,
    val addedAt: Long = System.currentTimeMillis()
)
