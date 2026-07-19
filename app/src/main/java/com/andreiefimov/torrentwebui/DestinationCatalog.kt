package com.andreiefimov.torrentwebui

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Persistent catalog of approved destination paths and the latest-selected default.
 *
 * The canonical filesystem path is the sole identity — no labels, aliases, opaque IDs, or URIs.
 * Entries are persisted atomically; a path may only be removed when no queue entry references it.
 * The latest-selected path is updated on every successful add and persisted alongside the catalog.
 */
class DestinationCatalog(private val context: Context) {

    private val catalogFile = File(context.filesDir, "destination_catalog.txt")
    private val catalogMutex = Mutex()

    /** Returns all approved destination paths (canonical, sorted). */
    suspend fun listDestinations(): List<String> = withContext(Dispatchers.IO) {
        catalogMutex.withLock { loadCatalog().keys.sorted() }
    }

    /** Returns the latest-selected destination path, or null if none has been selected. */
    suspend fun getLatestSelected(): String? = withContext(Dispatchers.IO) {
        catalogMutex.withLock {
            try {
                val prefs = context.getSharedPreferences("destination_catalog_prefs", Context.MODE_PRIVATE)
                prefs.getString(LATEST_SELECTED_KEY, null)
            } catch (_: Exception) { null }
        }
    }

    /**
     * Adds a validated canonical path to the catalog and sets it as latest-selected.
     * @return true if added (or already present), false on I/O error.
     */
    suspend fun addDestination(canonicalPath: String): Boolean = withContext(Dispatchers.IO) {
        catalogMutex.withLock {
            try {
                val catalog = loadCatalog()
                if (!catalog.containsKey(canonicalPath)) {
                    catalog[canonicalPath] = System.currentTimeMillis()
                    writeCatalog(catalog)
                }

                // Selecting an existing approved path must also durably update the default.
                val prefs = context.getSharedPreferences("destination_catalog_prefs", Context.MODE_PRIVATE)
                prefs.edit().putString(LATEST_SELECTED_KEY, canonicalPath).commit()
            } catch (_: Exception) {
                false
            }
        }
    }

    /**
     * Removes a destination from the catalog. Returns false if the path is not in the catalog
     * or if any queue entry or move journal still references it.
     */
    suspend fun removeDestination(
        canonicalPath: String,
        queueStore: QueueStore,
        moveJournal: MoveJournal? = null
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
                    val entries = journal.getActiveMoves() + journal.getInterruptedMoves()
                    if (entries.any { it.sourcePath == canonicalPath || it.targetPath == canonicalPath }) {
                        return@withLock false
                    }
                }

                val catalog = loadCatalog()
                if (!catalog.containsKey(canonicalPath)) return@withLock false

                catalog.remove(canonicalPath)
                writeCatalog(catalog)

                // If this was the latest-selected, durably clear it.
                val prefs = context.getSharedPreferences("destination_catalog_prefs", Context.MODE_PRIVATE)
                if (prefs.getString(LATEST_SELECTED_KEY, null) == canonicalPath &&
                    !prefs.edit().remove(LATEST_SELECTED_KEY).commit()
                ) {
                    return@withLock false
                }

                true
            } catch (_: Exception) {
                false
            }
        }
    }

    /** Returns true if the given path is in the catalog. */
    suspend fun contains(canonicalPath: String): Boolean = withContext(Dispatchers.IO) {
        catalogMutex.withLock { loadCatalog().containsKey(canonicalPath) }
    }

    // ---- Internal persistence ----

    private fun loadCatalog(): MutableMap<String, Long> {
        if (!catalogFile.exists()) return mutableMapOf()
        return try {
            catalogFile.readText().lines()
                .mapNotNull { line ->
                    val parts = line.split("|", limit = 2)
                    if (parts.size == 2) {
                        val timestamp = parts[1].toLongOrNull() ?: return@mapNotNull null
                        parts[0] to timestamp
                    } else null
                }
                .toMap()
                .toMutableMap()
        } catch (_: Exception) {
            mutableMapOf()
        }
    }

    private fun writeCatalog(catalog: Map<String, Long>) {
        val json = catalog.entries.joinToString("\n") { "${it.key}|${it.value}" }
        val tempFile = File(catalogFile.parentFile, "${catalogFile.name}.tmp")
        tempFile.writeText(json)
        if (!tempFile.renameTo(catalogFile)) {
            tempFile.delete()
            throw IOException("Unable to replace destination catalog")
        }
    }

    companion object {
        private const val LATEST_SELECTED_KEY = "latest_selected"
    }
}
