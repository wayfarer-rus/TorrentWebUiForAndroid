package com.andreiefimov.torrentwebui

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * Durable queue store for persisting torrent queue state across process death.
 *
 * Queue mutations are made durable before reporting success to the initiating control surface.
 * Native resume data is checkpointed with atomic replacement no less frequently than once every 30 seconds.
 *
 * Recovery records live only in app-private storage and are never exposed through WebUI responses,
 * notifications, or logs.
 */
interface QueueStore {

    /** Saves the current queue intent (magnet URIs, pause state) to durable storage. */
    suspend fun saveQueueIntent(queue: List<QueueEntry>)

    /** Loads the saved queue intent, or returns an empty list if none exists. */
    suspend fun loadQueueIntent(): List<QueueEntry>

    /** Saves native resume data for a specific torrent. */
    suspend fun saveResumeData(torrentId: Long, resumeData: ByteArray)

    /** Loads native resume data for a specific torrent, or null if none exists. */
    suspend fun loadResumeData(torrentId: Long): ByteArray?

    /** Removes resume data for a specific torrent. */
    suspend fun removeResumeData(torrentId: Long)

    /** Clears all saved state (queue intent and resume data). */
    suspend fun clearAll()
}

/**
 * A single entry in the persistent queue.
 */
data class QueueEntry(
    val magnetUri: String,
    val isPaused: Boolean = false
)

/**
 * In-memory [QueueStore] implementation for testing.
 */
class InMemoryQueueStore : QueueStore {

    private val queueIntent = AtomicReference<List<QueueEntry>>(emptyList())
    private val resumeData = mutableMapOf<Long, ByteArray>()

    override suspend fun saveQueueIntent(queue: List<QueueEntry>) {
        queueIntent.set(queue)
    }

    override suspend fun loadQueueIntent(): List<QueueEntry> {
        return queueIntent.get()
    }

    override suspend fun saveResumeData(torrentId: Long, resumeData: ByteArray) {
        this.resumeData[torrentId] = resumeData.copyOf()
    }

    override suspend fun loadResumeData(torrentId: Long): ByteArray? {
        return this.resumeData[torrentId]?.copyOf()
    }

    override suspend fun removeResumeData(torrentId: Long) {
        this.resumeData.remove(torrentId)
    }

    override suspend fun clearAll() {
        queueIntent.set(emptyList())
        resumeData.clear()
    }
}

/**
 * File-backed [QueueStore] implementation for production use.
 *
 * Uses atomic file replacement (write to temp, then rename) to ensure durability.
 */
class FileQueueStore(private val context: Context) : QueueStore {

    private val queueFile = File(context.filesDir, "queue_intent.json")
    private val resumeDir = File(context.filesDir, "resume_data")

    init {
        resumeDir.mkdirs()
    }

    override suspend fun saveQueueIntent(queue: List<QueueEntry>) = withContext(Dispatchers.IO) {
        val json = queue.joinToString("\n") { "${it.magnetUri}|${it.isPaused}" }
        writeAtomic(queueFile, json.toByteArray())
    }

    override suspend fun loadQueueIntent(): List<QueueEntry> = withContext(Dispatchers.IO) {
        if (!queueFile.exists()) return@withContext emptyList()
        val content = queueFile.readText()
        content.lines().mapNotNull { line ->
            val parts = line.split("|")
            if (parts.size == 2) {
                QueueEntry(magnetUri = parts[0], isPaused = parts[1].toBoolean())
            } else null
        }
    }

    override suspend fun saveResumeData(torrentId: Long, resumeData: ByteArray) = withContext(Dispatchers.IO) {
        val file = File(resumeDir, "$torrentId.resume")
        writeAtomic(file, resumeData)
    }

    override suspend fun loadResumeData(torrentId: Long): ByteArray? = withContext(Dispatchers.IO) {
        val file = File(resumeDir, "$torrentId.resume")
        if (file.exists()) file.readBytes() else null
    }

    override suspend fun removeResumeData(torrentId: Long) = withContext(Dispatchers.IO) {
        val file = File(resumeDir, "$torrentId.resume")
        if (file.exists()) file.delete()
    }

    override suspend fun clearAll() {
        withContext(Dispatchers.IO) {
            if (queueFile.exists()) queueFile.delete()
            resumeDir.listFiles()?.forEach { it.delete() }
        }
    }

    private fun writeAtomic(file: File, data: ByteArray) {
        val tempFile = File(file.parentFile, "${file.name}.tmp")
        tempFile.writeBytes(data)
        tempFile.renameTo(file)
    }
}
