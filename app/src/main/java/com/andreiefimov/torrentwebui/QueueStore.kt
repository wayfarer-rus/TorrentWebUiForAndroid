package com.andreiefimov.torrentwebui

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
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

    /**
     * Atomically loads, transforms, and checkpoints queue intent.
     *
     * The transform is serialized with every other queue-intent read/write so an identity
     * checkpoint performed during safe stop cannot overwrite a concurrent acknowledged mutation.
     */
    suspend fun mutateQueueIntent(
        transform: suspend (List<QueueEntry>) -> List<QueueEntry>
    ): List<QueueEntry>

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
@Serializable
@JvmInline
value class QueueId(val value: String) {
    companion object {
        fun random(): QueueId = QueueId(UUID.randomUUID().toString())
    }
}

@Serializable
data class QueueEntry(
    val magnetUri: String,
    val isPaused: Boolean = false,
    val destinationPath: String? = null,
    val queueId: QueueId = QueueId.random()
)

@Serializable
private data class PersistedQueue(
    val version: Int,
    val entries: List<QueueEntry>
)

/**
 * In-memory [QueueStore] implementation for testing.
 */
class InMemoryQueueStore(
    override val globalLegacySavePath: String? = null
) : QueueStore {

    private val queueIntent = AtomicReference<List<QueueEntry>>(emptyList())
    private val queueMutex = Mutex()
    private val resumeData = mutableMapOf<Long, ByteArray>()

    override suspend fun saveQueueIntent(queue: List<QueueEntry>) {
        queueMutex.withLock { queueIntent.set(queue.toList()) }
    }

    override suspend fun loadQueueIntent(): List<QueueEntry> =
        queueMutex.withLock { queueIntent.get() }

    override suspend fun mutateQueueIntent(
        transform: suspend (List<QueueEntry>) -> List<QueueEntry>
    ): List<QueueEntry> = queueMutex.withLock {
        transform(queueIntent.get()).toList().also(queueIntent::set)
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
        queueMutex.withLock { queueIntent.set(emptyList()) }
        resumeData.clear()
    }

    /** Returns true if every entry in the current queue has a non-null destination. */
    fun allEntriesHaveDestination(): Boolean = queueIntent.get().all { it.destinationPath != null }

    override suspend fun migrateLegacyEntries(): Boolean {
        val path = globalLegacySavePath ?: return false
        return queueMutex.withLock {
            val current = queueIntent.get()
            val hasNulls = current.any { it.destinationPath == null }
            if (hasNulls) {
                queueIntent.set(current.map { entry ->
                    if (entry.destinationPath == null) entry.copy(destinationPath = path) else entry
                })
            }
            hasNulls
        }
    }
}

/**
 * File-backed [QueueStore] implementation for production use.
 *
 * Uses atomic file replacement (write to temp, then rename) to ensure durability.
 */
class FileQueueStore(
    private val context: Context,
    override val globalLegacySavePath: String? = null,
    private val idFactory: () -> QueueId = { QueueId.random() }
) : QueueStore {

    private val queueFile = File(context.filesDir, "queue_intent.json")
    private val resumeDir = File(context.filesDir, "resume_data")
    private val queueMutex = Mutex()

    init {
        resumeDir.mkdirs()
    }

    override suspend fun saveQueueIntent(queue: List<QueueEntry>) = withContext(Dispatchers.IO) {
        queueMutex.withLock { writeQueue(queue) }
    }

    override suspend fun loadQueueIntent(): List<QueueEntry> = withContext(Dispatchers.IO) {
        queueMutex.withLock { loadFromFile() }
    }

    override suspend fun mutateQueueIntent(
        transform: suspend (List<QueueEntry>) -> List<QueueEntry>
    ): List<QueueEntry> = withContext(Dispatchers.IO) {
        queueMutex.withLock {
            transform(loadFromFile()).toList().also(::writeQueue)
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
            queueMutex.withLock {
                if (queueFile.exists()) queueFile.delete()
                resumeDir.listFiles()?.forEach { it.delete() }
            }
        }
    }

    override suspend fun migrateLegacyEntries(): Boolean {
        val path = globalLegacySavePath ?: return false
        return withContext(Dispatchers.IO) {
            queueMutex.withLock {
                val current = loadFromFile()
                val hasNulls = current.any { it.destinationPath == null }
                if (hasNulls) {
                    writeQueue(current.map { entry ->
                        if (entry.destinationPath == null) entry.copy(destinationPath = path) else entry
                    })
                }
                hasNulls
            }
        }
    }

    private fun loadFromFile(): List<QueueEntry> {
        if (!queueFile.exists()) return emptyList()
        val content = queueFile.readText()
        if (content.isBlank()) return emptyList()
        if (content.trimStart().startsWith("{")) {
            val persisted = Json.decodeFromString(PersistedQueue.serializer(), content)
            require(persisted.version == QUEUE_FORMAT_VERSION) {
                "Unsupported queue format version ${persisted.version}"
            }
            return persisted.entries
        }

        // V1 was an unversioned pipe-delimited row format. Assign IDs once while holding the
        // queue lock, then immediately replace it with V2 so IDs remain stable across restarts.
        val migrated = content.lineSequence().filter { it.isNotBlank() }.map { line ->
            val parts = line.split("|", limit = 3)
            require(parts.size in 2..3) { "Malformed legacy queue entry" }
            QueueEntry(
                magnetUri = parts[0],
                isPaused = requireNotNull(parts[1].toBooleanStrictOrNull()) {
                    "Malformed legacy queue pause intent"
                },
                destinationPath = parts.getOrNull(2)?.takeIf { it.isNotEmpty() },
                queueId = idFactory()
            )
        }.toList()
        writeQueue(migrated)
        return migrated
    }

    private fun writeQueue(queue: List<QueueEntry>) {
        val content = Json.encodeToString(
            PersistedQueue.serializer(),
            PersistedQueue(version = QUEUE_FORMAT_VERSION, entries = queue)
        )
        writeAtomic(queueFile, content.toByteArray())
    }

    private companion object {
        const val QUEUE_FORMAT_VERSION = 2
    }

    private fun writeAtomic(file: File, data: ByteArray) {
        val tempFile = File(file.parentFile, "${file.name}.tmp")
        FileOutputStream(tempFile).use { output ->
            output.write(data)
            output.fd.sync()
        }
        if (!tempFile.renameTo(file)) {
            tempFile.delete()
            throw java.io.IOException("Unable to replace durable queue state")
        }
    }
}
