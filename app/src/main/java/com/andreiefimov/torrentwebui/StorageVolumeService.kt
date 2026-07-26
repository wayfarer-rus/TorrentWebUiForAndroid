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

            val discovered = if (storageManager != null && sdkInt >= android.os.Build.VERSION_CODES.R) {
                discoverVolumes(context, storageManager)
            } else {
                emptyList()
            }
            if (discovered.isNotEmpty()) discovered else listOfNotNull(primaryVolume())
        } catch (_: Exception) {
            // Fallback: use Android's reported primary shared-storage root.
            listOfNotNull(primaryVolume())
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

    @Suppress("DEPRECATION")
    private fun primaryStorageRoot(): File? = try {
        android.os.Environment.getExternalStorageDirectory()
    } catch (_: Exception) {
        null
    }

    private fun primaryVolume(): StorageVolume? {
        val root = primaryStorageRoot() ?: return null
        if (!root.isDirectory || !root.canRead()) return null
        return StorageVolume(path = root.canonicalPath, isPrimary = true, isMounted = true)
    }

    /** Discovers mounted, readable volume roots and their Android-reported metadata. */
    private fun discoverVolumes(
        context: Context,
        storageManager: android.os.storage.StorageManager
    ): List<StorageVolume> = storageManager.storageVolumes.mapNotNull { volume ->
        try {
            if (volume.state != android.os.Environment.MEDIA_MOUNTED &&
                volume.state != android.os.Environment.MEDIA_MOUNTED_READ_ONLY
            ) return@mapNotNull null
            val directory = volume.directory ?: return@mapNotNull null
            if (!directory.isDirectory || !directory.canRead()) return@mapNotNull null
            StorageVolume(
                path = directory.canonicalPath,
                description = volume.getDescription(context),
                isRemovable = volume.isRemovable,
                isPrimary = volume.isPrimary,
                isMounted = true
            )
        } catch (_: Exception) {
            null
        }
    }
}
