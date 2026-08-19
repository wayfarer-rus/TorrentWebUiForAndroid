package com.andreiefimov.torrentwebui

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Server dependency for approved canonical destinations. */
internal interface DestinationCatalogOperations {
    suspend fun listDestinations(): List<String>
    suspend fun getLatestSelected(): String?
    suspend fun addDestination(canonicalPath: String): Boolean
    suspend fun removeDestination(canonicalPath: String, queueStore: QueueStore, moveJournal: MoveJournal?): Boolean
    suspend fun contains(canonicalPath: String): Boolean
}

/**
 * Persistent catalog of approved destination paths and the latest-selected default.
 *
 * The canonical filesystem path is the sole identity — no labels, aliases, opaque IDs, or URIs.
 * Entries are persisted atomically; a path may only be removed when no queue entry references it.
 * The latest-selected path is updated on every successful add and persisted alongside the catalog.
 */
class DestinationCatalog(private val context: Context) : DestinationCatalogOperations {

    private val catalogFile = File(context.filesDir, "destination_catalog.txt")
    private val atomicCatalogFile = AtomicFile(catalogFile)
    private val catalogMutex = Mutex()

    /** Returns all approved destination paths (canonical, sorted). */
    override suspend fun listDestinations(): List<String> = withContext(Dispatchers.IO) {
        catalogMutex.withLock { loadState().entries.keys.sorted() }
    }

    /** Returns the latest-selected destination path, or null if none has been selected. */
    override suspend fun getLatestSelected(): String? = withContext(Dispatchers.IO) {
        catalogMutex.withLock { loadState().latestSelected }
    }

    /**
     * Adds a validated canonical path to the catalog and sets it as latest-selected.
     * @return true if added (or already present), false on I/O error.
     */
    override suspend fun addDestination(canonicalPath: String): Boolean = withContext(Dispatchers.IO) {
        catalogMutex.withLock {
            try {
                val state = loadState()
                if (!state.entries.containsKey(canonicalPath)) {
                    state.entries[canonicalPath] = System.currentTimeMillis()
                }
                state.latestSelected = canonicalPath
                writeState(state)
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    /**
     * Removes a destination from the catalog. Returns false if the path is not in the catalog
     * or if any queue entry or move journal still references it.
     */
    suspend fun removeDestination(canonicalPath: String, queueStore: QueueStore): Boolean =
        removeDestination(canonicalPath, queueStore, null)

    override suspend fun removeDestination(
        canonicalPath: String,
        queueStore: QueueStore,
        moveJournal: MoveJournal?
    ): Boolean = withContext(Dispatchers.IO) {
        catalogMutex.withLock {
            try {
                // Check queue references.
                val queue = queueStore.loadQueueIntent()
                if (queue.any { it.destinationPath == canonicalPath }) {
                    return@withLock false
                }

                // Check move journal references (source or target paths).
                moveJournal?.let { journal ->
                    val entries = journal.getPathLockMoves()
                    if (entries.any { it.sourcePath == canonicalPath || it.targetPath == canonicalPath }) {
                        return@withLock false
                    }
                }

                val state = loadState()
                if (!state.entries.containsKey(canonicalPath)) return@withLock false

                state.entries.remove(canonicalPath)
                if (state.latestSelected == canonicalPath) state.latestSelected = null
                writeState(state)
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    /** Returns true if the given path is in the catalog. */
    override suspend fun contains(canonicalPath: String): Boolean = withContext(Dispatchers.IO) {
        catalogMutex.withLock { loadState().entries.containsKey(canonicalPath) }
    }

    // ---- Internal persistence ----

    private data class CatalogState(
        val entries: MutableMap<String, Long>,
        var latestSelected: String?
    )

    private fun loadState(): CatalogState {
        if (!catalogFile.exists()) return CatalogState(mutableMapOf(), legacyLatestSelected())
        return try {
            var hasEmbeddedLatest = false
            var latestSelected: String? = null
            val entries = mutableMapOf<String, Long>()
            atomicCatalogFile.openRead().bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (line.startsWith(LATEST_SELECTED_PREFIX)) {
                        hasEmbeddedLatest = true
                        latestSelected = line.removePrefix(LATEST_SELECTED_PREFIX).ifEmpty { null }
                    } else {
                        val parts = line.split("|", limit = 2)
                        val timestamp = parts.getOrNull(1)?.toLongOrNull()
                        if (timestamp != null) entries[parts[0]] = timestamp
                    }
                }
            }
            CatalogState(
                entries,
                if (hasEmbeddedLatest) latestSelected else legacyLatestSelected()
            )
        } catch (_: Exception) {
            CatalogState(mutableMapOf(), null)
        }
    }

    private fun legacyLatestSelected(): String? = try {
        context.getSharedPreferences(LEGACY_PREFERENCES, Context.MODE_PRIVATE)
            .getString(LEGACY_LATEST_SELECTED_KEY, null)
    } catch (_: Exception) {
        null
    }

    private fun writeState(state: CatalogState) {
        val content = buildString {
            append(LATEST_SELECTED_PREFIX)
            append(state.latestSelected.orEmpty())
            state.entries.toSortedMap().forEach { (path, timestamp) ->
                append('\n')
                append(path)
                append('|')
                append(timestamp)
            }
        }
        val output = atomicCatalogFile.startWrite()
        try {
            output.write(content.toByteArray(Charsets.UTF_8))
            atomicCatalogFile.finishWrite(output)
        } catch (failure: Exception) {
            atomicCatalogFile.failWrite(output)
            throw IOException("Unable to replace destination catalog", failure)
        }
    }

    companion object {
        private const val LATEST_SELECTED_PREFIX = "@latest|"
        private const val LEGACY_PREFERENCES = "destination_catalog_prefs"
        private const val LEGACY_LATEST_SELECTED_KEY = "latest_selected"
    }
}
