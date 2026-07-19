package com.andreiefimov.torrentwebui

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Executes safe torrent data moves between approved destinations.
 *
 * Uses the typed native move_storage operation. Move initiation acknowledges
 * `moving` immediately after the durable move record exists and the native engine
 * accepts the request. Completion, failure, retry, and cancellation are asynchronous.
 *
 * Cancel is available only from `move_interrupted`: it clears the recovery record,
 * leaves both filesystem locations intact, and keeps the torrent paused.
 */
class MoveService(
    private val context: Context,
    private val queueStore: QueueStore,
    private val journal: MoveJournal,
    private val control: TorrentSessionOps,
    private val bindings: QueueRuntimeBindings
) {

    /**
     * Initiates a move of one torrent to [newDestination].
     *
     * Returns `ok` / `moving` only after the durable move record exists and the native
     * engine accepts the request. The HTTP caller acknowledges `moving` immediately;
     * completion is async.
     */
    suspend fun startMove(runtimeId: Long, newDestination: String): MoveResult = withContext(Dispatchers.IO) {
        // 1. Validate storage permission.
        if (!StoragePermissionChecker.isGranted(context)) {
            return@withContext MoveResult(status = "error", recoverableError = "Storage permission required")
        }

        // 2. Resolve queue identity.
        val queueId = bindings.queueIdFor(runtimeId) ?: run {
            return@withContext MoveResult(status = "error", recoverableError = "Torrent not found")
        }

        // 3. Check no other move is active for this torrent.
        val existingMove = journal.getMove(queueId)
        if (existingMove != null && existingMove.phase != MovePhase.Interrupted) {
            return@withContext MoveResult(status = "error", recoverableError = "Move already in progress for this torrent")
        }

        // 4. Get current queue entry to find source path.
        val queue = queueStore.loadQueueIntent()
        val entry = queue.find { it.queueId == queueId } ?: run {
            return@withContext MoveResult(status = "error", recoverableError = "Torrent not found in durable queue")
        }

        val sourcePath = entry.destinationPath ?: run {
            return@withContext MoveResult(status = "error", recoverableError = "Torrent has no destination path")
        }

        // 5. Validate new destination.
        val validation = DirectoryValidationService.validate(context, newDestination)
        if (!validation.isValid) {
            return@withContext MoveResult(status = "error", recoverableError = validation.rejectionReason ?: "Invalid destination")
        }

        val targetPath = validation.canonicalPath!!

        // 6. Check for existing target data — if non-empty, enter storage_conflict.
        val targetDir = File(targetPath)
        if (targetDir.exists() && targetDir.isDirectory && targetDir.listFiles()?.isNotEmpty() == true) {
            // Target contains data — pause and report conflict without overwriting.
            if (existingMove == null) {
                journal.createMove(queueId, sourcePath, targetPath)
            }
            journal.updatePhase(queueId, MovePhase.Interrupted)
            return@withContext MoveResult(
                status = "interrupted",
                recoverableError = "Target already contains data; move paused with storage conflict"
            )
        }

        // 7. Persist move journal (or resume interrupted).
        val journalOk = if (existingMove != null && existingMove.phase == MovePhase.Interrupted) {
            journal.updatePhase(queueId, MovePhase.JournalPersisted)
        } else {
            journal.createMove(queueId, sourcePath, targetPath)
        }
        if (!journalOk) {
            return@withContext MoveResult(status = "error", recoverableError = "Failed to persist move journal")
        }

        // 8. Pause the torrent.
        control.pauseTorrent(runtimeId)

        // 9. Request native async move.
        val nativeOk = control.moveStorage(runtimeId, targetPath)
        if (!nativeOk) {
            // Native rejected — mark as interrupted.
            journal.updatePhase(queueId, MovePhase.Interrupted)
            return@withContext MoveResult(
                status = "interrupted",
                recoverableError = control.lastError ?: "Native move rejected"
            )
        }

        // Accepted by native engine. Mark as copying; daemon alert handler will transition further.
        journal.updatePhase(queueId, MovePhase.Copying)

        MoveResult(status = "ok")
    }

    /**
     * Cancels a move. Available only from `move_interrupted`.
     * Clears the recovery record, leaves both locations intact, keeps the torrent paused.
     */
    suspend fun cancelMove(runtimeId: Long): Boolean = withContext(Dispatchers.IO) {
        val queueId = bindings.queueIdFor(runtimeId) ?: return@withContext false
        val move = journal.getMove(queueId) ?: return@withContext false
        if (move.phase != MovePhase.Interrupted) return@withContext false

        journal.removeMove(queueId)
        true
    }

    /** Retries an interrupted move. */
    suspend fun retryMove(runtimeId: Long, newDestination: String): MoveResult = withContext(Dispatchers.IO) {
        val queueId = bindings.queueIdFor(runtimeId) ?: return@withContext MoveResult(
            status = "error", recoverableError = "Torrent not found"
        )
        val move = journal.getMove(queueId)
        if (move == null || move.phase != MovePhase.Interrupted) {
            return@withContext MoveResult(
                status = "error", recoverableError = "No interrupted move found for this torrent"
            )
        }

        // Start a new move to the specified destination.
        startMove(runtimeId, newDestination)
    }
}
