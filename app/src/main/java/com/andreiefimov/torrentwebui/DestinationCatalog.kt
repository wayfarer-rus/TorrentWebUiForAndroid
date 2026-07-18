package com.andreiefimov.torrentwebui

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Persistent catalog of approved destination paths and the latest-selected default.
 *
 * The canonical filesystem path is the sole identity — no labels, aliases, opaque IDs, or URIs.
 * Entries are persisted atomically; a path may only be removed when no queue entry references it.
 * The latest-selected path is updated on every successful add and persisted alongside the catalog.
 */
class DestinationCatalog(private val context: Context) {

    private val catalogFile = File(context.filesDir, "destination_catalog.txt")

    /** Returns all approved destination paths (canonical, sorted). */
    suspend fun listDestinations(): List<String> = withContext(Dispatchers.IO) {
        loadCatalog().keys.sorted()
    }

    /** Returns the latest-selected destination path, or null if none has been selected. */
    suspend fun getLatestSelected(): String? = withContext(Dispatchers.IO) {
        try {
            val prefs = context.getSharedPreferences("destination_catalog_prefs", Context.MODE_PRIVATE)
            prefs.getString(LATEST_SELECTED_KEY, null)
        } catch (_: Exception) { null }
    }

    /**
     * Adds a validated canonical path to the catalog and sets it as latest-selected.
     * @return true if added (or already present), false on I/O error.
     */
    suspend fun addDestination(canonicalPath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val catalog = loadCatalog()
            if (catalog.containsKey(canonicalPath)) return@withContext true // already present

            catalog[canonicalPath] = System.currentTimeMillis()
            writeCatalog(catalog)

            // Set as latest-selected.
            val prefs = context.getSharedPreferences("destination_catalog_prefs", Context.MODE_PRIVATE)
            prefs.edit().putString(LATEST_SELECTED_KEY, canonicalPath).apply()

            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Removes a destination from the catalog. Returns false if the path is not in the catalog
     * or if any queue entry still references it.
     */
    suspend fun removeDestination(canonicalPath: String, queueStore: QueueStore): Boolean = withContext(Dispatchers.IO) {
        try {
            // Check if any queue entry references this path.
            val queue = queueStore.loadQueueIntent()
            if (queue.any { it.destinationPath == canonicalPath }) {
                return@withContext false // cannot remove while referenced
            }

            val catalog = loadCatalog()
            if (!catalog.containsKey(canonicalPath)) return@withContext false

            catalog.remove(canonicalPath)
            writeCatalog(catalog)

            // If this was the latest-selected, clear it.
            val prefs = context.getSharedPreferences("destination_catalog_prefs", Context.MODE_PRIVATE)
            if (prefs.getString(LATEST_SELECTED_KEY, null) == canonicalPath) {
                prefs.edit().remove(LATEST_SELECTED_KEY).apply()
            }

            true
        } catch (_: Exception) {
            false
        }
    }

    /** Returns true if the given path is in the catalog. */
    suspend fun contains(canonicalPath: String): Boolean = withContext(Dispatchers.IO) {
        loadCatalog().containsKey(canonicalPath)
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
        tempFile.renameTo(catalogFile)
    }

    companion object {
        private const val LATEST_SELECTED_KEY = "latest_selected"
    }
}
