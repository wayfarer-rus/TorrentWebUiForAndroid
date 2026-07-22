package com.andreiefimov.torrentwebui

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Executes safe torrent data moves between approved destinations.
 *
 * Uses the typed native move_storage operation. Move initiation acknowledges
 * `moving` immediately after the durable move record exists and the native engine
 * accepts the request. Completion, failure, retry, and cancellation are asynchronous.
 *
 * Cancel is available only from a recoverable state: it retains a terminal audit record,
 * releases the target lock, leaves both filesystem locations intact, and keeps the torrent paused.
 */
class MoveService(
    private val context: Context,
    private val queueStore: QueueStore,
    private val journal: MoveJournal,
    private val control: TorrentSessionOps,
    private val bindings: QueueRuntimeBindings,
    private val executionGate: MoveExecutionGate = MoveExecutionGate.None
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
        if (existingMove != null && !existingMove.phase.requiresUserAction) {
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
        val canonicalSourcePath = try {
            File(sourcePath).canonicalPath
        } catch (_: Exception) {
            return@withContext MoveResult(status = "error", recoverableError = "Invalid source destination")
        }
        if (targetPath == canonicalSourcePath && existingMove == null) {
            return@withContext MoveResult(
                status = "error",
                recoverableError = "Torrent is already stored at this destination"
            )
        }
        if (existingMove?.phase?.requiresUserAction == true && targetPath != existingMove.targetPath) {
            return@withContext MoveResult(
                status = "error",
                recoverableError = "Retry must use the pending move destination"
            )
        }

        // 6. Ignore unrelated siblings. Only a path owned by this torrent is reusable data,
        // and it must pass libtorrent piece verification before the queue destination changes.
        val torrentStatus = control.getTorrentStatus(runtimeId)
        val torrentName = torrentStatus?.name?.takeIf { it.isNotBlank() }
        var sourceVerifiedProgress = torrentStatus?.progress?.coerceIn(0f, 1f) ?: 1f
        val sourceOwnedPath = torrentName?.let { resolveMoveOwnedChild(sourcePath, it) }
        val targetOwnedPath = torrentName?.let { resolveMoveOwnedChild(targetPath, it) }
        if (torrentName != null && (sourceOwnedPath == null || targetOwnedPath == null)) {
            return@withContext MoveResult(status = "error", recoverableError = "Unsafe torrent storage path")
        }
        val verifyExistingData = targetOwnedPath?.exists() == true
        if (verifyExistingData) {
            if (!control.verifyTorrentData(runtimeId)) {
                return@withContext MoveResult(
                    status = "error",
                    recoverableError = "Unable to verify source data before move"
                )
            }
            sourceVerifiedProgress = withTimeoutOrNull(5_000L) {
                var verifiedStatus: TorrentStatus?
                do {
                    verifiedStatus = control.getTorrentStatus(runtimeId)
                    if (verifiedStatus == null || verifiedStatus.state.startsWith("checking")) delay(100)
                } while (verifiedStatus == null || verifiedStatus.state.startsWith("checking"))
                verifiedStatus.progress.coerceIn(0f, 1f)
            } ?: return@withContext MoveResult(
                status = "error",
                recoverableError = "Unable to read verified source state before move"
            )
        }
        // Preserve only this torrent's owned file/directory. Ordinary moves copy it to a
        // hidden source-side backup; collision verification renames it because native
        // dont_replace must hash the existing target without replacing it.
        val sourceBackupPath = sourceOwnedPath?.takeIf { it.exists() }?.let {
            resolveMoveOwnedChild(sourcePath, ".torrentwebui-move-backup-${queueId.value}")
        }

        // 7. Atomically persist or resume the move. The journal lock owns both the
        // active check and write so concurrent requests cannot replace one another.
        val journalOk = journal.beginMove(
            queueId,
            sourcePath,
            targetPath,
            verifyExistingData,
            sourceBackupPath?.absolutePath,
            sourceVerifiedProgress
        )
        if (!journalOk) {
            return@withContext MoveResult(status = "error", recoverableError = "Failed to persist move journal")
        }

        // 8. Pause only the selected torrent before asking native code to move its storage.
        // A failed pause is a durable interruption: native move_storage must not start.
        if (!control.pauseTorrent(runtimeId)) {
            journal.updatePhase(queueId, MovePhase.Interrupted)
            return@withContext MoveResult(
                status = "interrupted",
                recoverableError = control.lastError ?: "Unable to pause torrent before move"
            )
        }

        if (sourceBackupPath != null && !sourceBackupPath.exists()) {
            val sourceToPreserve = checkNotNull(sourceOwnedPath)
            val preserved = if (verifyExistingData) {
                sourceToPreserve.renameTo(sourceBackupPath)
            } else {
                sourceToPreserve.copyRecursively(sourceBackupPath, overwrite = false)
            }
            if (!preserved) {
                journal.updatePhase(queueId, MovePhase.Interrupted)
                return@withContext MoveResult(
                    status = "interrupted",
                    recoverableError = "Unable to preserve source before target verification"
                )
            }
        }

        // A debug-only injected fixture can hold this exact protocol boundary so live tests
        // deterministically make the target unavailable before native move_storage begins.
        try {
            executionGate.awaitBeforeNativeMove(sourcePath, targetPath)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            restoreSourceBackup(sourceBackupPath, sourceOwnedPath)
            journal.updatePhase(queueId, MovePhase.Interrupted)
            return@withContext MoveResult(
                status = "interrupted",
                recoverableError = "Move fixture gate failed"
            )
        }

        // 9. Persist the native-in-flight phase before invoking move_storage. If this
        // checkpoint fails, native data movement must not start.
        if (!journal.updatePhase(queueId, MovePhase.Copying)) {
            restoreSourceBackup(sourceBackupPath, sourceOwnedPath)
            return@withContext MoveResult(status = "error", recoverableError = "Unable to persist move start")
        }

        val nativeOk = control.moveStorage(runtimeId, targetPath, verifyExistingData)
        if (!nativeOk) {
            val restored = restoreSourceBackup(sourceBackupPath, sourceOwnedPath)
            val interruptedPersisted = journal.updatePhase(queueId, MovePhase.Interrupted)
            return@withContext MoveResult(
                status = "interrupted",
                recoverableError = if (restored && interruptedPersisted) {
                    control.lastError ?: "Native move rejected"
                } else {
                    "Native move rejected and durable recovery requires restart"
                }
            )
        }

        MoveResult(status = "ok")
    }

    /**
     * Cancels a recoverable move. Retains a terminal reconciliation record while
     * releasing the active target lock; both locations remain intact and paused.
     */
    suspend fun cancelMove(runtimeId: Long): Boolean = withContext(Dispatchers.IO) {
        val queueId = bindings.queueIdFor(runtimeId) ?: return@withContext false
        val move = journal.getMove(queueId) ?: return@withContext false
        if (!move.phase.requiresUserAction) return@withContext false

        val original = control.getTorrentStatus(runtimeId)?.name?.takeIf { it.isNotBlank() }
            ?.let { resolveMoveOwnedChild(move.sourcePath, it) }
        val backup = move.sourceBackupPath?.let(::File)
        if (!restoreSourceBackup(backup, original)) return@withContext false
        val queueRestored = try {
            queueStore.mutateQueueIntent { queue ->
                queue.map { entry ->
                    if (entry.queueId == queueId) entry.copy(destinationPath = move.sourcePath) else entry
                }
            }.any { it.queueId == queueId && it.destinationPath == move.sourcePath }
        } catch (_: Exception) {
            false
        }
        if (!queueRestored) return@withContext false
        // Libtorrent owns the live handle. Do not expose cancellation as recoverable
        // until its storage binding has asynchronously returned to the durable source.
        if (!control.rollbackStorage(runtimeId, move.sourcePath)) return@withContext false
        journal.updatePhase(queueId, MovePhase.Cancelled)
    }

    private fun restoreSourceBackup(backup: File?, original: File?): Boolean {
        if (backup == null || !backup.exists()) return original == null || original.exists()
        if (original == null) return false
        if (original.exists()) return backup.deleteRecursively()
        return backup.renameTo(original)
    }

    /** Retries an interrupted move. */
    suspend fun retryMove(runtimeId: Long, newDestination: String): MoveResult = withContext(Dispatchers.IO) {
        val queueId = bindings.queueIdFor(runtimeId) ?: return@withContext MoveResult(
            status = "error", recoverableError = "Torrent not found"
        )
        val move = journal.getMove(queueId)
        if (move == null || !move.phase.requiresUserAction) {
            return@withContext MoveResult(
                status = "error", recoverableError = "No recoverable move found for this torrent"
            )
        }

        // Start a new move to the specified destination.
        startMove(runtimeId, newDestination)
    }
}
