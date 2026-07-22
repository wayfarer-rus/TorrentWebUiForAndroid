package com.andreiefimov.torrentwebui

import android.content.Context
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/** Serializes native queue mutations with their durable queue-intent checkpoint. */
class DurableTorrentOperations(
    private val control: TorrentSessionOps,
    private val store: QueueStore,
    private val bindings: QueueRuntimeBindings
) {
    private val mutationMutex = Mutex()

    data class AddedTorrent(val runtimeId: Long, val queueId: QueueId, val status: String = "ok")

    suspend fun add(magnetUri: String, destination: TorrentDestination): AddedTorrent =
        mutationMutex.withLock {
            val collisionGuardRequired = File(destination.path).list()?.isNotEmpty() == true
            val runtimeId = control.addMagnet(
                magnetUri,
                TorrentAddRequest(destination, metadataOnlyUntilVerified = collisionGuardRequired)
            )
            check(runtimeId > 0) { control.lastError ?: "Unable to add torrent" }
            val entry = QueueEntry(
                magnetUri = magnetUri,
                destinationPath = destination.path,
                storagePauseRequired = collisionGuardRequired,
                addCollisionState = if (collisionGuardRequired) "checking" else null
            )
            try {
                store.mutateQueueIntent { it + entry }
            } catch (failure: Exception) {
                control.removeTorrent(runtimeId, deleteFiles = false)
                throw failure
            }
            bindings.bind(entry.queueId, runtimeId)
            if (!collisionGuardRequired) return@withLock AddedTorrent(runtimeId, entry.queueId)

            val reusable = resolveAddCollisionLocked(runtimeId, entry.queueId)
            AddedTorrent(
                runtimeId,
                entry.queueId,
                status = if (reusable) "ok" else "storage_conflict"
            )
        }

    suspend fun pause(runtimeId: Long): QueueId = mutationMutex.withLock {
        val queueId = requireBinding(runtimeId)
        check(control.pauseTorrent(runtimeId)) { control.lastError ?: "Torrent not found" }
        try {
            // A user pause must not clear an independent storage-safety pause.
            update(queueId) { it.copy(isPaused = true) }
        } catch (failure: Exception) {
            control.resumeTorrent(runtimeId)
            throw failure
        }
        queueId
    }

    /**
     * Atomically records an unavailable-destination safety pause before pausing native work.
     * Returns true only when the durable destination is currently valid.
     */
    suspend fun ensureDestinationAvailable(runtimeId: Long, context: Context): Boolean = mutationMutex.withLock {
        val queueId = requireBinding(runtimeId)
        val available = ensureQueueDestinationAvailableLocked(queueId, context)
        if (!available) {
            // Durability is authoritative even if the native session has already disappeared.
            control.pauseTorrent(runtimeId)
        }
        available
    }

    /** Recovery variant used before a runtime torrent ID exists. */
    suspend fun ensureQueueDestinationAvailable(queueId: QueueId, context: Context): Boolean =
        mutationMutex.withLock { ensureQueueDestinationAvailableLocked(queueId, context) }

    private suspend fun ensureQueueDestinationAvailableLocked(queueId: QueueId, context: Context): Boolean {
        val entry = store.loadQueueIntent().singleOrNull { it.queueId == queueId }
            ?: throw IllegalArgumentException("Torrent not found in durable queue")
        val destination = entry.destinationPath ?: store.globalLegacySavePath
        val available = destination != null && DirectoryValidationService.validate(context, destination).isValid
        if (!available) update(queueId) { it.copy(storagePauseRequired = true) }
        return available
    }

    suspend fun resume(runtimeId: Long, context: Context? = null): QueueId = mutationMutex.withLock {
        val queueId = requireBinding(runtimeId)
        val entry = store.loadQueueIntent().singleOrNull { it.queueId == queueId }
            ?: throw IllegalArgumentException("Torrent not found in durable queue")
        val destination = entry.destinationPath ?: store.globalLegacySavePath
        if (context != null) {
            check(destination != null && DirectoryValidationService.validate(context, destination).isValid) {
                "Destination unavailable"
            }
        }
        if (entry.addCollisionState != null) {
            check(resolveAddCollisionLocked(runtimeId, queueId)) { "Storage conflict" }
        } else {
            check(control.resumeTorrent(runtimeId)) { control.lastError ?: "Torrent not found" }
            try {
                update(queueId) { it.copy(isPaused = false, storagePauseRequired = false) }
            } catch (failure: Exception) {
                control.pauseTorrent(runtimeId)
                throw failure
            }
        }
        queueId
    }

    private suspend fun resolveAddCollisionLocked(runtimeId: Long, queueId: QueueId): Boolean {
        val ownedState = withTimeoutOrNull(30_000L) {
            var state: TorrentOwnedDataState
            do {
                state = control.inspectTorrentOwnedData(runtimeId)
                if (state == TorrentOwnedDataState.MetadataPending) delay(100)
            } while (state == TorrentOwnedDataState.MetadataPending)
            state
        } ?: TorrentOwnedDataState.MetadataPending

        val reusable = when (ownedState) {
            TorrentOwnedDataState.None -> true
            TorrentOwnedDataState.Present -> verifyCompleteOwnedData(runtimeId)
            TorrentOwnedDataState.MetadataPending -> false
        }
        if (reusable && control.resumeTorrent(runtimeId)) {
            update(queueId) { it.copy(addCollisionState = null, storagePauseRequired = false) }
            return true
        }
        control.pauseTorrent(runtimeId)
        update(queueId) { it.copy(addCollisionState = "storage_conflict", storagePauseRequired = true) }
        return false
    }

    private suspend fun verifyCompleteOwnedData(runtimeId: Long): Boolean {
        if (!control.verifyTorrentData(runtimeId)) return false

        // torrent_checked is the authoritative verification completion, but the status
        // snapshot can lag that alert briefly. Do not turn valid reusable data into a
        // durable conflict merely because the first post-alert snapshot is stale.
        return withTimeoutOrNull(5_000L) {
            while ((control.getTorrentStatus(runtimeId)?.progress ?: 0f) < 0.999f) {
                delay(100)
            }
            true
        } == true
    }

    suspend fun delete(runtimeId: Long, deleteFiles: Boolean): QueueId = mutationMutex.withLock {
        val queueId = requireBinding(runtimeId)
        var removed: QueueEntry? = null
        store.mutateQueueIntent { queue ->
            removed = queue.singleOrNull { it.queueId == queueId }
                ?: throw IllegalArgumentException("Torrent not found in durable queue")
            queue.filterNot { it.queueId == queueId }
        }
        if (!control.removeTorrent(runtimeId, deleteFiles)) {
            removed?.let { entry -> store.mutateQueueIntent { queue -> queue + entry } }
            error(control.lastError ?: "Unable to remove torrent")
        }
        bindings.unbind(queueId)
        queueId
    }

    private suspend fun update(queueId: QueueId, transform: (QueueEntry) -> QueueEntry) {
        store.mutateQueueIntent { queue ->
            var found = false
            queue.map { entry ->
                if (entry.queueId == queueId) {
                    found = true
                    transform(entry)
                } else entry
            }.also { require(found) { "Torrent not found in durable queue" } }
        }
    }

    private fun requireBinding(runtimeId: Long): QueueId =
        requireNotNull(bindings.queueIdFor(runtimeId)) { "Torrent runtime identity is not bound" }
}
