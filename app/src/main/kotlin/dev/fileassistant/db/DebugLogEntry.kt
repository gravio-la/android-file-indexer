package dev.fileassistant.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** In-app debug log row — persisted so it survives app closes and can be read without ADB. */
@Entity(tableName = "debug_log")
data class DebugLogEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    /** D / I / W / E */
    val level: String,
    val message: String
)
