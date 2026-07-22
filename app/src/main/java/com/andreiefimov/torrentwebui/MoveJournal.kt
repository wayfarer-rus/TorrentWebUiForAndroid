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
enum class MovePhase(val apiName: String) {
    JournalPersisted("journal-persisted"),
    Copying("copying"),
    Verifying("verifying"),
    QueueUpdated("queue-updated"),
    SourceRemoved("source-removed"),
    Completed("completed"),
    Cancelled("cancelled"),
    Interrupted("move-interrupted"),
    StorageConflict("storage-conflict");

    val requiresUserAction: Boolean
        get() = this == Interrupted || this == StorageConflict

    val isTerminal: Boolean
        get() = this == Completed || this == Cancelled
}

/** Canonical move state used by WebSocket snapshots and the WebUI. */
internal fun MovePhase.webSocketApiName(): String = apiName

/**
 * Persists move journal entries for recovery across process death.
 *
 * V2 format uses durable [QueueId] instead of ephemeral native runtime IDs.
 * On startup, v1 records are migrated: a QueueId is attached only when exactly
 * one queue entry matches the recorded source path. Ambiguous records stay
 * interrupted and unassociated.
 */
open class MoveJournal(private val context: Context) {

    private val journalFile = File(context.filesDir, "move_journal_v2.json")
    private val v1JournalFile = File(context.filesDir, "move_journal.txt")
    private val journalMutex = Mutex()

    companion object {
        private const val JOURNAL_FORMAT_VERSION = 2
    }

    /**
     * Atomically creates or resumes one move while holding the persistence lock.
     * A second request cannot replace an active record for the torrent or reuse an
     * active target owned by another torrent.
     */
    open suspend fun beginMove(
        queueId: QueueId,
        sourcePath: String,
        targetPath: String,
        verifyExistingData: Boolean = false,
        sourceBackupPath: String? = null,
        sourceVerifiedProgress: Float = 1f
    ): Boolean = withContext(Dispatchers.IO) {
        journalMutex.withLock {
            try {
                val entries = loadEntries()
                val currentIndex = entries.indexOfLast { it.queueId == queueId && !it.phase.isTerminal }
                val current = entries.getOrNull(currentIndex)
                if (current != null && (!current.phase.requiresUserAction || current.targetPath != targetPath)) {
                    return@withLock false
                }
                if (entries.any { it.queueId != queueId && !it.phase.isTerminal && it.targetPath == targetPath }) {
                    return@withLock false
                }
                val now = System.currentTimeMillis()
                val entry = if (current != null) {
                    current.copy(
                        phase = MovePhase.JournalPersisted,
                        verifyExistingData = verifyExistingData,
                        sourceBackupPath = sourceBackupPath ?: current.sourceBackupPath,
                        sourceVerifiedProgress = sourceVerifiedProgress,
                        updatedAt = now
                    )
                } else {
                    PersistedMoveEntry(
                        queueId = queueId,
                        sourcePath = sourcePath,
                        targetPath = targetPath,
                        phase = MovePhase.JournalPersisted,
                        verifyExistingData = verifyExistingData,
                        sourceBackupPath = sourceBackupPath,
                        sourceVerifiedProgress = sourceVerifiedProgress,
                        createdAt = now,
                        updatedAt = now
                    )
                }
                val updated = entries.toMutableList()
                if (currentIndex >= 0) updated[currentIndex] = entry else updated += entry
                writeEntries(updated)
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    /** Compatibility entry point used by existing direct journal tests. */
    open suspend fun createMove(
        queueId: QueueId,
        sourcePath: String,
        targetPath: String,
        verifyExistingData: Boolean = false,
        sourceBackupPath: String? = null,
        sourceVerifiedProgress: Float = 1f
    ): Boolean = beginMove(
        queueId,
        sourcePath,
        targetPath,
        verifyExistingData,
        sourceBackupPath,
        sourceVerifiedProgress
    )

    /** Updates the phase of an active move. */
    open suspend fun updatePhase(queueId: QueueId, phase: MovePhase): Boolean = withContext(Dispatchers.IO) {
        journalMutex.withLock {
            try {
                val entries = loadEntries().toMutableList()
                val idx = entries.indexOfLast { it.queueId == queueId && !it.phase.isTerminal }
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

    /** Returns the current non-terminal move entry, or null when no move needs action. */
    suspend fun getMove(queueId: QueueId): PersistedMoveEntry? = withContext(Dispatchers.IO) {
        journalMutex.withLock { loadEntries().lastOrNull { it.queueId == queueId && !it.phase.isTerminal } }
    }

    /** Returns the retained audit/reconciliation record, including terminal cancellation. */
    suspend fun getRetainedMove(queueId: QueueId): PersistedMoveEntry? = withContext(Dispatchers.IO) {
        journalMutex.withLock { loadEntries().lastOrNull { it.queueId == queueId } }
    }

    /** Returns all terminal audit records retained for a torrent. */
    suspend fun getAuditMoves(queueId: QueueId): List<PersistedMoveEntry> = withContext(Dispatchers.IO) {
        journalMutex.withLock { loadEntries().filter { it.queueId == queueId && it.phase.isTerminal } }
    }

    /**
     * Validates the current journal before native recovery starts.
     *
     * Parsing/version failures propagate without rewriting either journal file. A valid legacy
     * journal may be migrated before this check; migration itself is also fail-closed.
     */
    suspend fun validateForRecovery(queueStore: QueueStore) = withContext(Dispatchers.IO) {
        if (v1JournalFile.exists()) migrateV1Journal(queueStore)
        journalMutex.withLock { loadEntries() }
    }

    /** All records that still protect their source/target catalog paths. */
    suspend fun getPathLockMoves(): List<PersistedMoveEntry> = withContext(Dispatchers.IO) {
        journalMutex.withLock { loadEntries().filter { it.phase != MovePhase.Cancelled } }
    }

    /** Returns moves still progressing without waiting for user recovery action. */
    suspend fun getActiveMoves(): List<PersistedMoveEntry> = withContext(Dispatchers.IO) {
        journalMutex.withLock {
            loadEntries().filter { !it.phase.isTerminal && !it.phase.requiresUserAction }
        }
    }

    /** Returns moves requiring explicit retry or cancel, including storage conflicts. */
    suspend fun getInterruptedMoves(): List<PersistedMoveEntry> = withContext(Dispatchers.IO) {
        journalMutex.withLock { loadEntries().filter { it.phase.requiresUserAction } }
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

            // Preserve every terminal audit while selecting one deterministic non-terminal
            // recovery authority per queue identity and active target. Legacy files may contain
            // duplicate/out-of-order lines after repeated process crashes.
            val terminalAudits = migrated.filter { it.phase.isTerminal }
            val latestPerQueue = migrated
                .filterNot { it.phase.isTerminal }
                .groupBy { it.queueId }
                .values
                .map { records -> records.maxWith(compareBy<PersistedMoveEntry> { it.updatedAt }.thenBy { it.createdAt }) }
            val latestPerTarget = latestPerQueue
                .groupBy { it.targetPath }
                .values
                .map { records -> records.maxWith(compareBy<PersistedMoveEntry> { it.updatedAt }.thenBy { it.createdAt }) }
            val existingActiveQueues = existing.filterNot { it.phase.isTerminal }.mapTo(mutableSetOf()) { it.queueId }
            val existingActiveTargets = existing.filterNot { it.phase.isTerminal }.mapTo(mutableSetOf()) { it.targetPath }
            val selectedAuthorities = latestPerTarget
                .sortedWith(compareBy<PersistedMoveEntry> { it.createdAt }.thenBy { it.updatedAt })
                .filter { it.queueId !in existingActiveQueues && it.targetPath !in existingActiveTargets }
            val selected = terminalAudits + selectedAuthorities
            writeEntries(existing + selected)

            // Remove v1 file after successful migration.
            v1JournalFile.delete()

            selected.size
        }
    }

    // ---- V1 legacy format (pipe-delimited, native IDs) ----

    private fun loadV1Entries(): List<V1MoveEntry> {
        if (!v1JournalFile.exists()) return emptyList()
        val content = v1JournalFile.readText()
        require(content.isNotBlank()) { "Legacy move journal is empty" }
        return content.lineSequence().filter { it.isNotBlank() }.map { line ->
            val parts = line.split("|", limit = 6)
            require(parts.size == 6) { "Invalid legacy move journal entry" }
            V1MoveEntry(
                torrentId = requireNotNull(parts[0].toLongOrNull()) { "Invalid legacy torrent identity" },
                sourcePath = parts[1],
                targetPath = parts[2],
                phase = MovePhase.valueOf(parts[3]),
                createdAt = requireNotNull(parts[4].toLongOrNull()) { "Invalid legacy timestamp" },
                updatedAt = requireNotNull(parts[5].toLongOrNull()) { "Invalid legacy update timestamp" }
            )
        }.toList()
    }

    // ---- V2 format (JSON, QueueId-backed) ----

    private fun loadEntries(): List<PersistedMoveEntry> {
        if (!journalFile.exists()) return emptyList()
        val content = journalFile.readText()
        require(content.isNotBlank()) { "Move journal is empty" }
        val persisted = Json.decodeFromString(PersistedJournal.serializer(), content)
        require(persisted.version == JOURNAL_FORMAT_VERSION) { "Unsupported move journal format" }
        return persisted.entries
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
    val verifyExistingData: Boolean = false,
    val sourceBackupPath: String? = null,
    /** Verified source fraction that the replacement target must retain or exceed. */
    val sourceVerifiedProgress: Float = 1f,
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
    val status: String, // "ok", "interrupted", "storage_conflict", "error"
    val recoverableError: String? = null
)
