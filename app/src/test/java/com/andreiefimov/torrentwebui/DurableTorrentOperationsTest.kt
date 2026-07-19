package com.andreiefimov.torrentwebui

import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DurableTorrentOperationsTest {
    @Test
    fun `pause follows stable queue identity after runtime ID changes`() = runTest {
        val entry = QueueEntry("magnet:same", queueId = QueueId("stable"))
        val store = InMemoryQueueStore().also { it.saveQueueIntent(listOf(entry)) }
        val native = RecordingSessionOps()
        val bindings = QueueRuntimeBindings().apply { bind(entry.queueId, 91L) }
        val operations = DurableTorrentOperations(native, store, bindings)

        operations.pause(91L)
        bindings.bind(entry.queueId, 307L)
        operations.resume(307L)

        assertEquals(listOf(91L), native.paused)
        assertEquals(listOf(307L), native.resumed)
        assertFalse(store.loadQueueIntent().single().isPaused)
    }

    @Test
    fun `pause compensates native state when durable update fails`() = runTest {
        val entry = QueueEntry("magnet:test", queueId = QueueId("stable"))
        val backing = InMemoryQueueStore().also { it.saveQueueIntent(listOf(entry)) }
        val failingStore = object : QueueStore by backing {
            override suspend fun mutateQueueIntent(
                transform: suspend (List<QueueEntry>) -> List<QueueEntry>
            ): List<QueueEntry> = throw IOException("disk full")
        }
        val native = RecordingSessionOps()
        val operations = DurableTorrentOperations(
            native,
            failingStore,
            QueueRuntimeBindings().apply { bind(entry.queueId, 8L) }
        )

        try {
            operations.pause(8L)
        } catch (_: IOException) {
            // expected
        }

        assertEquals(listOf(8L), native.paused)
        assertEquals(listOf(8L), native.resumed)
    }

    @Test
    fun `bindings keep duplicate magnets distinct and replace old runtime IDs`() {
        val bindings = QueueRuntimeBindings()
        val first = QueueId("first")
        val second = QueueId("second")

        bindings.bind(first, 1L)
        bindings.bind(second, 2L)
        bindings.bind(first, 3L)

        assertNull(bindings.queueIdFor(1L))
        assertEquals(first, bindings.queueIdFor(3L))
        assertEquals(2L, bindings.runtimeIdFor(second))
    }

    private class RecordingSessionOps : TorrentSessionOps {
        val paused = mutableListOf<Long>()
        val resumed = mutableListOf<Long>()
        override fun addMagnet(magnetUri: String): Long = 1L
        override fun pauseTorrent(torrentId: Long): Boolean = paused.add(torrentId)
        override fun resumeTorrent(torrentId: Long): Boolean = resumed.add(torrentId)
        override fun removeTorrent(torrentId: Long, deleteFiles: Boolean): Boolean = true
        override fun moveStorage(torrentId: Long, targetPath: String): Boolean = true
        override fun getAllTorrentIds(): List<Long> = emptyList()
        override fun getTorrentStatus(torrentId: Long): TorrentStatus? = null
        override fun popAlerts(): String = ""
        override val lastError: String? = null
    }
}
