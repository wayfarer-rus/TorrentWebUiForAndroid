package com.andreiefimov.torrentwebui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class SafeStopQueuePersistenceTest {

    @Test
    fun `safe stop checkpoint cannot overwrite an acknowledged concurrent queue add`() = runBlocking {
        val store = InMemoryQueueStore()
        val original = QueueEntry(
            "magnet:?xt=urn:btih:one",
            destinationPath = "/storage/emulated/0/Download"
        )
        val concurrentAdd = QueueEntry(
            "magnet:?xt=urn:btih:two",
            destinationPath = "/storage/emulated/0/Movies"
        )
        store.saveQueueIntent(listOf(original))

        val checkpointHasLoadedQueue = CompletableDeferred<Unit>()
        val allowCheckpointToFinish = CompletableDeferred<Unit>()
        val checkpoint = async {
            store.mutateQueueIntent { current ->
                checkpointHasLoadedQueue.complete(Unit)
                allowCheckpointToFinish.await()
                current
            }
        }
        checkpointHasLoadedQueue.await()

        val add = async {
            store.mutateQueueIntent { current -> current + concurrentAdd }
        }
        allowCheckpointToFinish.complete(Unit)

        checkpoint.await()
        add.await()

        assertEquals(listOf(original, concurrentAdd), store.loadQueueIntent())
    }
}
