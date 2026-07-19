package com.andreiefimov.torrentwebui

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Represents the current phase of a torrent data move operation.
 */
enum class MovePhase {
    JournalPersisted,
    Copying,
    Verifying,
    QueueUpdated,
    SourceRemoved,
    Completed,
    Interrupted
}

/**
 * Persists move journal entries for recovery across process death.
 *
 * V2 format uses durable [QueueId] instead of ephemeral native runtime IDs.
 * On startup, v1 records are migrated: a QueueId is attached only when exactly
 * one queue entry matches the recorded source path. Ambiguous records stay
 * interrupted and unassociated.
 */
class MoveJournal(private val context: Context) {

    private val journalFile = File(context.filesDir, "move_journal_v2.json")
    private val v1JournalFile = File(context.filesDir, "move_journal.txt")
    private val journalMutex = Mutex()

    companion object {
        private const val JOURNAL_FORMAT_VERSION = 2
    }

    /** Creates a new move journal entry. */
    suspend fun createMove(queueId: QueueId, sourcePath: String, targetPath: String): Boolean = withContext(Dispatchers.IO) {
        journalMutex.withLock {
            try {
                val entry = PersistedMoveEntry(
                    queueId = queueId,
                    sourcePath = sourcePath,
                    targetPath = targetPath,
                    phase = MovePhase.JournalPersisted,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )
                val entries = loadEntries() + entry
                writeEntries(entries)
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    /** Updates the phase of an active move. */
    suspend fun updatePhase(queueId: QueueId, phase: MovePhase): Boolean = withContext(Dispatchers.IO) {
        journalMutex.withLock {
            try {
                val entries = loadEntries().toMutableList()
                val idx = entries.indexOfFirst { it.queueId == queueId }
                if (idx < 0) return@withLock false

                entries[idx] = entries[idx].copy(phase = phase, updatedAt = System.currentTimeMillis())
                writeEntries(entries)
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    /** Removes a completed or cancelled move entry. */
    suspend fun removeMove(queueId: QueueId): Boolean = withContext(Dispatchers.IO) {
        journalMutex.withLock {
            try {
                val entries = loadEntries()
                val filtered = entries.filter { it.queueId != queueId }
                if (filtered.size == entries.size) return@withLock false
                writeEntries(filtered)
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    /** Returns the current move entry for a torrent, or null if no active move. */
    suspend fun getMove(queueId: QueueId): PersistedMoveEntry? = withContext(Dispatchers.IO) {
        journalMutex.withLock { loadEntries().find { it.queueId == queueId } }
    }

    /** Returns all active (non-completed, non-interrupted) moves. */
    suspend fun getActiveMoves(): List<PersistedMoveEntry> = withContext(Dispatchers.IO) {
        journalMutex.withLock {
            loadEntries().filter { it.phase != MovePhase.Completed && it.phase != MovePhase.Interrupted }
        }
    }

    /** Returns all interrupted moves (need user action to retry or cancel). */
    suspend fun getInterruptedMoves(): List<PersistedMoveEntry> = withContext(Dispatchers.IO) {
        journalMutex.withLock { loadEntries().filter { it.phase == MovePhase.Interrupted } }
    }

    /**
     * Migrates a v1 journal (ephemeral native IDs) to v2 (QueueId-backed).
     *
     * A v1 entry gains a QueueId only when exactly one queue entry matches its source path.
     * Ambiguous entries remain interrupted and unassociated (queueId = null).
     */
    suspend fun migrateV1Journal(queueStore: QueueStore): Int = withContext(Dispatchers.IO) {
        if (!v1JournalFile.exists()) return@withContext 0

        journalMutex.withLock {
            val v1Entries = loadV1Entries()
            if (v1Entries.isEmpty()) {
                v1JournalFile.delete()
                return@withContext 0
            }

            val existing = loadEntries()
            val queue = queueStore.loadQueueIntent()

            val migrated = v1Entries.mapNotNull { v1 ->
                // Try to associate with a queue entry by source path.
                val matchingEntries = queue.filter { it.destinationPath == v1.sourcePath }
                val queueId = if (matchingEntries.size == 1) {
                    matchingEntries[0].queueId
                } else {
                    null // Ambiguous — leave unassociated
                }

                // Only migrate entries that have a clear association or are already interrupted.
                if (queueId != null) {
                    PersistedMoveEntry(
                        queueId = queueId,
                        sourcePath = v1.sourcePath,
                        targetPath = v1.targetPath,
                        phase = v1.phase,
                        createdAt = v1.createdAt,
                        updatedAt = v1.updatedAt
                    )
                } else if (v1.phase == MovePhase.Interrupted) {
                    // Keep as interrupted with a sentinel null-equivalent; mark as unassociated.
                    PersistedMoveEntry(
                        queueId = QueueId("unassociated_v1_${v1.torrentId}"),
                        sourcePath = v1.sourcePath,
                        targetPath = v1.targetPath,
                        phase = MovePhase.Interrupted,
                        createdAt = v1.createdAt,
                        updatedAt = v1.updatedAt
                    )
                } else {
                    // Non-interrupted v1 entry with ambiguous association:
                    // treat as interrupted to be safe.
                    PersistedMoveEntry(
                        queueId = QueueId("unassociated_v1_${v1.torrentId}"),
                        sourcePath = v1.sourcePath,
                        targetPath = v1.targetPath,
                        phase = MovePhase.Interrupted,
                        createdAt = v1.createdAt,
                        updatedAt = v1.updatedAt
                    )
                }
            }

            // Merge with any existing v2 entries (avoid duplicates).
            val merged = (existing + migrated).distinctBy { it.queueId }
            writeEntries(merged)

            // Remove v1 file after successful migration.
            v1JournalFile.delete()

            migrated.size
        }
    }

    // ---- V1 legacy format (pipe-delimited, native IDs) ----

    private fun loadV1Entries(): List<V1MoveEntry> {
        if (!v1JournalFile.exists()) return emptyList()
        return try {
            v1JournalFile.readText().lines()
                .mapNotNull { line ->
                    val parts = line.split("|", limit = 6)
                    if (parts.size == 6) {
                        V1MoveEntry(
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

    // ---- V2 format (JSON, QueueId-backed) ----

    private fun loadEntries(): List<PersistedMoveEntry> {
        if (!journalFile.exists()) return emptyList()
        return try {
            val content = journalFile.readText()
            if (content.isBlank()) return emptyList()
            val persisted = Json.decodeFromString(PersistedJournal.serializer(), content)
            require(persisted.version == JOURNAL_FORMAT_VERSION) {
                "Unsupported journal format version ${persisted.version}"
            }
            persisted.entries
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun writeEntries(entries: List<PersistedMoveEntry>) {
        val content = Json.encodeToString(
            PersistedJournal.serializer(),
            PersistedJournal(version = JOURNAL_FORMAT_VERSION, entries = entries)
        )
        val tempFile = File(journalFile.parentFile, "${journalFile.name}.tmp")
        FileOutputStream(tempFile).use { output ->
            output.write(content.toByteArray())
            output.fd.sync()
        }
        if (!tempFile.renameTo(journalFile)) {
            tempFile.delete()
            throw IOException("Unable to replace move journal")
        }
    }
}

/** V1 legacy entry (ephemeral native ID). Used only during migration. */
private data class V1MoveEntry(
    val torrentId: Long,
    val sourcePath: String,
    val targetPath: String,
    val phase: MovePhase,
    val createdAt: Long,
    val updatedAt: Long
)

/** V2 persisted entry (QueueId-backed). */
@Serializable
data class PersistedMoveEntry(
    val queueId: QueueId,
    val sourcePath: String,
    val targetPath: String,
    val phase: MovePhase,
    val createdAt: Long,
    val updatedAt: Long = System.currentTimeMillis()
)

@Serializable
private data class PersistedJournal(
    val version: Int,
    val entries: List<PersistedMoveEntry>
)

/**
 * Result of a move operation.
 */
data class MoveResult(
    val status: String, // "ok", "interrupted", "error"
    val recoverableError: String? = null
)
