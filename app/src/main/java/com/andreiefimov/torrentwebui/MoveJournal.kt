package com.andreiefimov.torrentwebui

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Represents the current phase of a torrent data move operation.
 */
enum class MovePhase {
    /** Move has been initiated but data movement hasn't started. */
    JournalPersisted,

    /** Data is being copied/moved from source to target. */
    Copying,

    /** Copy complete; verifying target data integrity. */
    Verifying,

    /** Verification passed; queue destination updated atomically. */
    QueueUpdated,

    /** Source data removed after successful queue update. */
    SourceRemoved,

    /** Move completed successfully. */
    Completed,

    /** Move failed or was cancelled; source and target retained. */
    Interrupted
}

/**
 * Persists move journal entries for recovery across process death.
 *
 * Each entry tracks a single torrent's move operation through its phases.
 * The journal is written atomically (temp file + rename) and survives crashes.
 * On startup, the daemon inspects the journal for interrupted moves and reports
 * `move_interrupted` state to the user.
 */
class MoveJournal(private val context: Context) {

    private val journalFile = File(context.filesDir, "move_journal.txt")

    /**
     * Creates a new move journal entry.
     * @return true if the entry was written successfully.
     */
    suspend fun createMove(torrentId: Long, sourcePath: String, targetPath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val entry = MoveJournalEntry(torrentId, sourcePath, targetPath, MovePhase.JournalPersisted, System.currentTimeMillis())
            val entries = loadEntries() + entry
            writeEntries(entries)
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Updates the phase of an active move. */
    suspend fun updatePhase(torrentId: Long, phase: MovePhase): Boolean = withContext(Dispatchers.IO) {
        try {
            val entries = loadEntries().toMutableList()
            val idx = entries.indexOfFirst { it.torrentId == torrentId }
            if (idx < 0) return@withContext false

            val updated = entries[idx].copy(phase = phase, updatedAt = System.currentTimeMillis())
            entries[idx] = updated
            writeEntries(entries)
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Removes a completed or cancelled move entry. */
    suspend fun removeMove(torrentId: Long): Boolean = withContext(Dispatchers.IO) {
        try {
            val entries = loadEntries()
            val filtered = entries.filter { it.torrentId != torrentId }
            if (filtered.size == entries.size) return@withContext false // not found

            writeEntries(filtered)
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Returns the current move entry for a torrent, or null if no active move. */
    suspend fun getMove(torrentId: Long): MoveJournalEntry? = withContext(Dispatchers.IO) {
        loadEntries().find { it.torrentId == torrentId }
    }

    /** Returns all active (non-completed, non-interrupted) moves. */
    suspend fun getActiveMoves(): List<MoveJournalEntry> = withContext(Dispatchers.IO) {
        loadEntries().filter { it.phase != MovePhase.Completed && it.phase != MovePhase.Interrupted }
    }

    /** Returns all interrupted moves (need user action to retry or cancel). */
    suspend fun getInterruptedMoves(): List<MoveJournalEntry> = withContext(Dispatchers.IO) {
        loadEntries().filter { it.phase == MovePhase.Interrupted }
    }

    // ---- Internal persistence ----

    private fun loadEntries(): List<MoveJournalEntry> {
        if (!journalFile.exists()) return emptyList()
        return try {
            journalFile.readText().lines()
                .mapNotNull { line ->
                    val parts = line.split("|", limit = 6)
                    if (parts.size == 6) {
                        MoveJournalEntry(
                            torrentId = parts[0].toLongOrNull() ?: return@mapNotNull null,
                            sourcePath = parts[1],
                            targetPath = parts[2],
                            phase = try { MovePhase.valueOf(parts[3]) } catch (_: Exception) { return@mapNotNull null },
                            createdAt = parts[4].toLongOrNull() ?: return@mapNotNull null,
                            updatedAt = parts[5].toLongOrNull() ?: System.currentTimeMillis()
                        )
                    } else null
                }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun writeEntries(entries: List<MoveJournalEntry>) {
        val text = entries.joinToString("\n") { entry ->
            "${entry.torrentId}|${entry.sourcePath}|${entry.targetPath}|${entry.phase.name}|${entry.createdAt}|${entry.updatedAt}"
        }
        val tempFile = File(journalFile.parentFile, "${journalFile.name}.tmp")
        tempFile.writeText(text)
        tempFile.renameTo(journalFile)
    }
}

/**
 * A single entry in the move journal.
 */
data class MoveJournalEntry(
    val torrentId: Long,
    val sourcePath: String,
    val targetPath: String,
    val phase: MovePhase,
    val createdAt: Long,
    val updatedAt: Long = System.currentTimeMillis()
)
