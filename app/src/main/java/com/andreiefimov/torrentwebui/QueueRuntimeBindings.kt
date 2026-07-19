package com.andreiefimov.torrentwebui

/** Process-local correlation between durable queue identity and ephemeral native identity. */
class QueueRuntimeBindings {
    private val runtimeByQueue = mutableMapOf<QueueId, Long>()
    private val queueByRuntime = mutableMapOf<Long, QueueId>()

    @Synchronized
    fun bind(queueId: QueueId, runtimeId: Long) {
        runtimeByQueue.remove(queueId)?.let(queueByRuntime::remove)
        queueByRuntime.remove(runtimeId)?.let(runtimeByQueue::remove)
        runtimeByQueue[queueId] = runtimeId
        queueByRuntime[runtimeId] = queueId
    }

    @Synchronized
    fun queueIdFor(runtimeId: Long): QueueId? = queueByRuntime[runtimeId]

    @Synchronized
    fun runtimeIdFor(queueId: QueueId): Long? = runtimeByQueue[queueId]

    @Synchronized
    fun unbind(queueId: QueueId) {
        runtimeByQueue.remove(queueId)?.let(queueByRuntime::remove)
    }

    @Synchronized
    fun clear() {
        runtimeByQueue.clear()
        queueByRuntime.clear()
    }
}
