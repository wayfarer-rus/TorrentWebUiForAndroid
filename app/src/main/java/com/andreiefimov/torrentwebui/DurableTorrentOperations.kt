package com.andreiefimov.torrentwebui

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes native queue mutations with their durable queue-intent checkpoint. */
class DurableTorrentOperations(
    private val control: TorrentSessionOps,
    private val store: QueueStore,
    private val bindings: QueueRuntimeBindings
) {
    private val mutationMutex = Mutex()

    data class AddedTorrent(val runtimeId: Long, val queueId: QueueId)

    suspend fun add(magnetUri: String, destination: TorrentDestination): AddedTorrent =
        mutationMutex.withLock {
            val runtimeId = control.addMagnet(magnetUri, destination)
            check(runtimeId > 0) { control.lastError ?: "Unable to add torrent" }
            val entry = QueueEntry(magnetUri = magnetUri, destinationPath = destination.path)
            try {
                store.mutateQueueIntent { it + entry }
            } catch (failure: Exception) {
                control.removeTorrent(runtimeId, deleteFiles = false)
                throw failure
            }
            bindings.bind(entry.queueId, runtimeId)
            AddedTorrent(runtimeId, entry.queueId)
        }

    suspend fun pause(runtimeId: Long): QueueId = mutationMutex.withLock {
        val queueId = requireBinding(runtimeId)
        check(control.pauseTorrent(runtimeId)) { control.lastError ?: "Torrent not found" }
        try {
            update(queueId) { it.copy(isPaused = true) }
        } catch (failure: Exception) {
            control.resumeTorrent(runtimeId)
            throw failure
        }
        queueId
    }

    suspend fun resume(runtimeId: Long): QueueId = mutationMutex.withLock {
        val queueId = requireBinding(runtimeId)
        check(control.resumeTorrent(runtimeId)) { control.lastError ?: "Torrent not found" }
        try {
            update(queueId) { it.copy(isPaused = false) }
        } catch (failure: Exception) {
            control.pauseTorrent(runtimeId)
            throw failure
        }
        queueId
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
