package com.andreiefimov.torrentwebui

import android.content.ContextWrapper
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueIdentityPersistenceTest {
    @Test
    fun `legacy queue receives stable IDs and is rewritten as version 2`() = runTest {
        val dir = createTempDir(prefix = "queue-identity-")
        try {
            File(dir, "queue_intent.json").writeText(
                "magnet:?xt=urn:btih:first|false|/storage/emulated/0/Download\n" +
                    "magnet:?xt=urn:btih:first|true|/storage/emulated/0/Movies"
            )
            val ids = ArrayDeque(listOf(QueueId("queue-a"), QueueId("queue-b")))
            val context = object : ContextWrapper(null) {
                override fun getFilesDir(): File = dir
            }

            val firstOpen = FileQueueStore(context, idFactory = { ids.removeFirst() })
            val migrated = firstOpen.loadQueueIntent()
            val reopened = FileQueueStore(context, idFactory = { error("must not replace persisted IDs") })
                .loadQueueIntent()

            assertEquals(listOf(QueueId("queue-a"), QueueId("queue-b")), migrated.map { it.queueId })
            assertEquals(migrated, reopened)
            assertNotEquals(migrated[0].queueId, migrated[1].queueId)
            assertTrue(File(dir, "queue_intent.json").readText().contains("\"version\":2"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
