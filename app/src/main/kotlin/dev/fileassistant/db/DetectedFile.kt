package dev.fileassistant.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A file detected during polling.
 * uri has a unique index → O(log n) dedup; INSERT OR IGNORE returns -1 if
 * the file was already seen, so we skip re-broadcasting duplicates.
 */
@Entity(
    tableName = "detected_files",
    indices = [
        Index(value = ["uri"], unique = true),
        Index(value = ["dirId"])
    ]
)
data class DetectedFile(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dirId: Long,
    /** Content URI or absolute path — the unique key */
    val uri: String,
    val displayName: String,
    val detectedAt: Long = System.currentTimeMillis(),
    val proposal: String? = null
)
