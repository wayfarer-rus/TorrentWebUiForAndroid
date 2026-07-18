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

    /**
     * Returns the global legacy save directory path.
     *
     * Entries with a null [QueueEntry.destinationPath] are treated as legacy entries
     * whose effective destination is this path. New torrents must always specify a
     * non-null, approved destination and cannot select a legacy path.
     */
    val globalLegacySavePath: String?

    /**
     * Marks all entries with a null destination as legacy by assigning the global save path.
     * Returns true if any entries were migrated (had null destination).
     *
     * This is called once on startup after the queue is loaded, so that legacy entries
     * can be exposed in the WebUI as read-only destinations while new torrents are rejected.
     */
    suspend fun migrateLegacyEntries(): Boolean
}

/**
 * A single entry in the persistent queue.
 *
 * @param magnetUri The magnet URI identifying this torrent.
 * @param isPaused Whether the torrent should start in paused state.
 * @param destinationPath Optional per-torrent canonical filesystem path for downloads.
 *   When null, the torrent uses the global legacy save directory. New torrents must
 *   always specify a destination; existing imports may have null until migrated.
 */
data class QueueEntry(
    val magnetUri: String,
    val isPaused: Boolean = false,
    val destinationPath: String? = null
)

/**
 * In-memory [QueueStore] implementation for testing.
 */
class InMemoryQueueStore(
    override val globalLegacySavePath: String? = null
) : QueueStore {

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

    /** Returns true if every entry in the current queue has a non-null destination. */
    fun allEntriesHaveDestination(): Boolean = queueIntent.get().all { it.destinationPath != null }

    override suspend fun migrateLegacyEntries(): Boolean {
        val path = globalLegacySavePath ?: return false
        val current = queueIntent.get()
        val hasNulls = current.any { it.destinationPath == null }
        if (hasNulls) {
            val migrated = current.map { entry ->
                if (entry.destinationPath == null) {
                    entry.copy(destinationPath = path)
                } else entry
            }
            queueIntent.set(migrated)
        }
        return hasNulls
    }
}

/**
 * File-backed [QueueStore] implementation for production use.
 *
 * Uses atomic file replacement (write to temp, then rename) to ensure durability.
 */
class FileQueueStore(
    private val context: Context,
    override val globalLegacySavePath: String? = null
) : QueueStore {

    private val queueFile = File(context.filesDir, "queue_intent.json")
    private val resumeDir = File(context.filesDir, "resume_data")

    init {
        resumeDir.mkdirs()
    }

    override suspend fun saveQueueIntent(queue: List<QueueEntry>) = withContext(Dispatchers.IO) {
        val json = queue.joinToString("\n") { entry ->
            "${entry.magnetUri}|${entry.isPaused}|${entry.destinationPath ?: ""}"
        }
        writeAtomic(queueFile, json.toByteArray())
    }

    override suspend fun loadQueueIntent(): List<QueueEntry> = withContext(Dispatchers.IO) {
        if (!queueFile.exists()) return@withContext emptyList()
        val content = queueFile.readText()
        content.lines().mapNotNull { line ->
            val parts = line.split("|")
            when (parts.size) {
                2 -> QueueEntry(
                    magnetUri = parts[0],
                    isPaused = parts[1].toBoolean(),
                    destinationPath = null // legacy entry — no per-torrent path
                )
                3 -> QueueEntry(
                    magnetUri = parts[0],
                    isPaused = parts[1].toBoolean(),
                    destinationPath = parts[2].takeIf { it.isNotEmpty() }
                )
                else -> null // malformed line, skip
            }
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

    override suspend fun migrateLegacyEntries(): Boolean {
        val path = globalLegacySavePath ?: return false
        return withContext(Dispatchers.IO) {
            val current = if (!queueFile.exists()) emptyList() else loadFromFile()
            val hasNulls = current.any { it.destinationPath == null }
            if (hasNulls) {
                val migrated = current.map { entry ->
                    if (entry.destinationPath == null) {
                        entry.copy(destinationPath = path)
                    } else entry
                }
                val json = migrated.joinToString("\n") { entry ->
                    "${entry.magnetUri}|${entry.isPaused}|${entry.destinationPath ?: ""}"
                }
                writeAtomic(queueFile, json.toByteArray())
            }
            hasNulls
        }
    }

    private fun loadFromFile(): List<QueueEntry> {
        if (!queueFile.exists()) return emptyList()
        val content = queueFile.readText()
        return content.lines().mapNotNull { line ->
            val parts = line.split("|")
            when (parts.size) {
                2 -> QueueEntry(
                    magnetUri = parts[0],
                    isPaused = parts[1].toBoolean(),
                    destinationPath = null
                )
                3 -> QueueEntry(
                    magnetUri = parts[0],
                    isPaused = parts[1].toBoolean(),
                    destinationPath = parts[2].takeIf { it.isNotEmpty() }
                )
                else -> null
            }
        }
    }

    private fun writeAtomic(file: File, data: ByteArray) {
        val tempFile = File(file.parentFile, "${file.name}.tmp")
        tempFile.writeBytes(data)
        tempFile.renameTo(file)
    }
}
