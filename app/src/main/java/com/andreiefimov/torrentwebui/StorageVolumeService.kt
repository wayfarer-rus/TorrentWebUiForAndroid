package com.andreiefimov.torrentwebui

import android.content.Context
import java.io.File

/**
 * Represents a storage volume reported by the Android system.
 *
 * Each [StorageVolume] exposes its root path as a canonical filesystem path.
 */
data class StorageVolume(
    /** Canonical root path of this volume (e.g., "/storage/emulated/0"). */
    val path: String,
    /** Human-readable description (e.g., "Internal storage"). */
    val description: String = "",
    /** Whether this volume is removable (SD card, USB OTG). */
    val isRemovable: Boolean = false,
    /** Whether this volume is the primary built-in storage. */
    val isPrimary: Boolean = false,
    /** Whether this volume is mounted and accessible. */
    val isMounted: Boolean = true
)

/**
 * Discovers and reports storage volumes available on the device.
 *
 * Uses Android's StorageManager (API 26+) to enumerate volumes when available,
 * falling back to the primary external storage directory on older versions.
 * Returns only mounted, accessible volumes whose root path is a real directory.
 */
object StorageVolumeService {

    private val sdkInt: Int = android.os.Build.VERSION.SDK_INT

    /** Returns all mounted storage volumes reported by the system. */
    fun listVolumes(context: Context): List<StorageVolume> {
        return try {
            val storageManager = context.getSystemService(Context.STORAGE_SERVICE)
                as? android.os.storage.StorageManager

            val volumeFiles: List<File> = if (storageManager != null && sdkInt >= android.os.Build.VERSION_CODES.N) {
                discoverVolumeRoots(storageManager)
            } else {
                // Fallback: primary external storage.
                val primary = context.getExternalFilesDir(null)?.parentFile?.parentFile
                listOfNotNull(primary)
            }

            volumeFiles.filter { it.isDirectory && it.canRead() }.map { file ->
                StorageVolume(
                    path = file.absolutePath,
                    description = "",
                    isRemovable = false,
                    isPrimary = file.absolutePath == context.getExternalFilesDir(null)?.parentFile?.parentFile?.absolutePath,
                    isMounted = true
                )
            }
        } catch (_: Exception) {
            // Fallback: use the primary external storage directory.
            val primary = context.getExternalFilesDir(null)?.parentFile?.parentFile
            if (primary != null) listOf(StorageVolume(primary.absolutePath, isPrimary = true))
            else emptyList()
        }
    }

    /** Returns the canonical paths of all mounted volumes. */
    fun listVolumeRoots(context: Context): List<String> {
        return listVolumes(context).map { it.path }
    }

    /** Returns true if the given path is directly under one of the reported volume roots. */
    fun isUnderVolumeRoot(path: String, context: Context): Boolean {
        val canonical = try { File(path).canonicalPath } catch (_: Exception) { return false }
        return listVolumeRoots(context).any { root ->
            canonical == root || canonical.startsWith("$root/")
        }
    }

    // ---- Internal helpers ----

    /**
     * Discovers volume roots using reflection to avoid compile-time dependency on StorageVolume API.
     * Works on Android N+ where StorageManager.getStorageVolumes() exists.
     */
    private fun discoverVolumeRoots(storageManager: android.os.storage.StorageManager): List<File> {
        return try {
            val getVolumesMethod = storageManager.javaClass.getMethod("getStorageVolumes")
            @Suppress("UNCHECKED_CAST")
            val volumes = getVolumesMethod.invoke(storageManager) as? List<Any> ?: emptyList()

            volumes.mapNotNull { vol ->
                try {
                    val getDirMethod = vol.javaClass.getMethod("getDirectory")
                    getDirMethod.invoke(vol) as? File
                } catch (_: Exception) { null }
            }.filterNotNull()
        } catch (_: Exception) {
            emptyList()
        }
    }
}
