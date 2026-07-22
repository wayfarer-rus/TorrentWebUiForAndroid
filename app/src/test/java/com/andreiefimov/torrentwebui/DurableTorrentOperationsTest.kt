package com.andreiefimov.torrentwebui

import java.io.File
import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
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
    fun `safe add ignores unrelated siblings and resumes paused handle`() = runTest {
        val destination = createTempDirectory("m4-add-unrelated-").toFile()
        try {
            File(destination, "unrelated.txt").writeText("keep")
            val native = RecordingSessionOps(ownedData = TorrentOwnedDataState.None)
            val store = InMemoryQueueStore()
            val added = DurableTorrentOperations(native, store, QueueRuntimeBindings())
                .add("magnet:safe", TorrentDestination(destination.canonicalPath))

            assertEquals("ok", added.status)
            assertTrue(native.metadataOnly)
            assertEquals(listOf(1L), native.resumed)
            assertNull(store.loadQueueIntent().single().addCollisionState)
            assertEquals("keep", File(destination, "unrelated.txt").readText())
        } finally {
            destination.deleteRecursively()
        }
    }

    @Test
    fun `safe add reuses only verified complete owned data`() = runTest {
        val destination = createTempDirectory("m4-add-reuse-").toFile()
        try {
            File(destination, "payload.bin").writeText("verified")
            val native = RecordingSessionOps(ownedData = TorrentOwnedDataState.Present, progress = 1f)
            val store = InMemoryQueueStore()
            val added = DurableTorrentOperations(native, store, QueueRuntimeBindings())
                .add("magnet:reuse", TorrentDestination(destination.canonicalPath))

            assertEquals("ok", added.status)
            assertEquals(1, native.verifications)
            assertFalse(store.loadQueueIntent().single().storagePauseRequired)
        } finally {
            destination.deleteRecursively()
        }
    }

    @Test
    fun `safe add waits for verified progress snapshot before classifying reusable data`() = runTest {
        val destination = createTempDirectory("m4-add-delayed-status-").toFile()
        try {
            File(destination, "payload.bin").writeText("verified")
            val native = RecordingSessionOps(
                ownedData = TorrentOwnedDataState.Present,
                progressSnapshots = listOf(0f, 1f)
            )
            val store = InMemoryQueueStore()
            val added = DurableTorrentOperations(native, store, QueueRuntimeBindings())
                .add("magnet:delayed-status", TorrentDestination(destination.canonicalPath))

            assertEquals("ok", added.status)
            assertEquals(1, native.verifications)
            assertNull(store.loadQueueIntent().single().addCollisionState)
            assertEquals(listOf(1L), native.resumed)
        } finally {
            destination.deleteRecursively()
        }
    }

    @Test
    fun `safe add leaves corrupt owned data paused without overwriting bytes`() = runTest {
        val destination = createTempDirectory("m4-add-conflict-").toFile()
        val payload = File(destination, "payload.bin")
        try {
            payload.writeText("corrupt")
            val native = RecordingSessionOps(ownedData = TorrentOwnedDataState.Present, progress = 0f)
            val store = InMemoryQueueStore()
            val added = DurableTorrentOperations(native, store, QueueRuntimeBindings())
                .add("magnet:conflict", TorrentDestination(destination.canonicalPath))

            assertEquals("storage_conflict", added.status)
            assertEquals("storage_conflict", store.loadQueueIntent().single().addCollisionState)
            assertTrue(store.loadQueueIntent().single().storagePauseRequired)
            assertTrue(native.resumed.isEmpty())
            assertEquals("corrupt", payload.readText())
        } finally {
            destination.deleteRecursively()
        }
    }

    @Test
    fun `add conflict recovers initially paused until explicit revalidation`() {
        val request = recoveryAddRequest(
            QueueEntry(
                magnetUri = "magnet:conflict",
                destinationPath = "/storage/emulated/0/Download/conflict",
                addCollisionState = "storage_conflict"
            ),
            destinationPath = "/storage/emulated/0/Download/conflict",
            isDestinationAvailable = true
        )

        assertTrue(request.startPaused)
        assertFalse(request.metadataOnlyUntilVerified)
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

    private class RecordingSessionOps(
        private val ownedData: TorrentOwnedDataState = TorrentOwnedDataState.None,
        progress: Float = 1f,
        private val progressSnapshots: List<Float> = listOf(progress)
    ) : TorrentSessionOps {
        private var progressSnapshotIndex = 0
        val paused = mutableListOf<Long>()
        val resumed = mutableListOf<Long>()
        var startPaused = false
        var metadataOnly = false
        var verifications = 0
        override fun addMagnet(magnetUri: String): Long = 1L
        override fun addMagnet(magnetUri: String, request: TorrentAddRequest): Long {
            startPaused = request.startPaused
            metadataOnly = request.metadataOnlyUntilVerified
            return 1L
        }
        override fun inspectTorrentOwnedData(torrentId: Long): TorrentOwnedDataState = ownedData
        override suspend fun verifyTorrentData(torrentId: Long): Boolean {
            verifications++
            return true
        }
        override fun pauseTorrent(torrentId: Long): Boolean = paused.add(torrentId)
        override fun resumeTorrent(torrentId: Long): Boolean = resumed.add(torrentId)
        override fun removeTorrent(torrentId: Long, deleteFiles: Boolean): Boolean = true
        override fun moveStorage(torrentId: Long, targetPath: String): Boolean = true
        override fun getAllTorrentIds(): List<Long> = emptyList()
        override fun getTorrentStatus(torrentId: Long): TorrentStatus? = TorrentStatus(
            id = torrentId,
            name = "payload.bin",
            state = "paused",
            progress = progressSnapshots[progressSnapshotIndex.coerceAtMost(progressSnapshots.lastIndex)].also {
                progressSnapshotIndex++
            },
            downloadRate = 0,
            uploadRate = 0,
            peers = 0,
            savePath = ""
        )
        override fun popAlerts(): String = ""
        override val lastError: String? = null
    }
}
