package com.andreiefimov.torrentwebui

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class WebSocketSnapshotTest {

    @After
    fun tearDown() {
        TorrentServer.queueStore = null
        TorrentServer.queueBindings = null
        TorrentServer.moveJournal = null
        TorrentServer.resetToDefaults()
    }

    @Test
    fun `snapshot uses durable canonical destination and never consumes native alerts`() = runTest {
        val queueId = QueueId.random()
        val durablePath = "/storage/emulated/0/Download/canonical"
        val session = SnapshotSessionOps(
            TorrentStatus(
                id = 7L,
                name = "fixture",
                state = "paused",
                progress = 0.5f,
                downloadRate = 0,
                uploadRate = 0,
                peers = 0,
                savePath = ""
            )
        )
        val store = InMemoryQueueStore().apply {
            saveQueueIntent(listOf(QueueEntry(
                magnetUri = "magnet:?xt=urn:btih:fixture",
                destinationPath = durablePath,
                queueId = queueId
            )))
        }
        TorrentServer.configureForTest(InMemoryAuthManager(), DaemonControlFactory.createForTest(session))
        TorrentServer.queueStore = store
        TorrentServer.queueBindings = QueueRuntimeBindings().apply { bind(queueId, 7L) }

        val document = Json.parseToJsonElement(TorrentServer.buildSnapshotJson()).jsonObject
        val torrent = document.getValue("data").jsonArray.single().jsonObject

        assertEquals("torrents", document.getValue("type").jsonPrimitive.content)
        assertEquals(durablePath, torrent.getValue("destinationPath").jsonPrimitive.content)
        assertEquals(0, session.popAlertsCalls)
    }

    private class SnapshotSessionOps(private val status: TorrentStatus) : TorrentSessionOps {
        var popAlertsCalls = 0

        override fun addMagnet(magnetUri: String): Long = status.id
        override fun moveStorage(torrentId: Long, targetPath: String): Boolean = true
        override fun pauseTorrent(torrentId: Long): Boolean = true
        override fun resumeTorrent(torrentId: Long): Boolean = true
        override fun removeTorrent(torrentId: Long, deleteFiles: Boolean): Boolean = true
        override fun getAllTorrentIds(): List<Long> = listOf(status.id)
        override fun getTorrentStatus(torrentId: Long): TorrentStatus = status
        override fun popAlerts(): String {
            popAlertsCalls += 1
            return "[{\"type\":\"storage_moved_failed_alert\"}]"
        }
        override val lastError: String? = null
    }
}
