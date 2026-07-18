package com.andreiefimov.torrentwebui

import android.content.Context
import java.io.File

/**
 * Result of validating a candidate destination path.
 *
 * @param path The original path that was validated.
 * @param canonicalPath The resolved canonical filesystem path (null if validation failed).
 * @param isValid Whether the path passed all validation rules.
 * @param rejectionReason A human-readable explanation of why validation failed (null if valid).
 *   Only present in authenticated WebUI/API responses and explicit diagnostics — never routine logs.
 */
data class DirectoryValidationResult(
    val path: String,
    val canonicalPath: String?,
    val isValid: Boolean,
    val rejectionReason: String? = null
) {
    companion object {
        fun valid(path: String, canonicalPath: String): DirectoryValidationResult =
            DirectoryValidationResult(path, canonicalPath, true)

        fun rejected(path: String, reason: String): DirectoryValidationResult =
            DirectoryValidationResult(path, null, false, rejectionReason = reason)
    }
}

/**
 * Validates candidate destination paths against M4 storage rules.
 *
 * Rules enforced:
 * - Path must exist and be a directory.
 * - Directory must be writable.
 * - Canonical path must lie beneath an approved shared/external storage volume root.
 * - Must not be inside app-private storage (e.g., /data/data/<pkg>/...).
 * - Must not be inside system-owned paths (e.g., /system, /vendor, /proc).
 * - Symlink resolution must not escape the selected storage volume.
 * - SAF `content://` URIs are never accepted.
 */
object DirectoryValidationService {

    /** App-private path prefix to reject. */
    private const val DATA_PREFIX = "/data/data/"

    /** System-owned prefixes to reject. */
    private val SYSTEM_PREFIXES = listOf("/system/", "/vendor/", "/proc/", "/sys/", "/dev/")

    /**
     * Validates a candidate destination path.
     *
     * @param context Used to discover storage volume roots.
     * @param candidatePath The raw path string from the user (may be a content URI, absolute path, etc.).
     * @return A [DirectoryValidationResult] indicating validity and reason for rejection.
     */
    fun validate(context: Context, candidatePath: String): DirectoryValidationResult {
        // Reject SAF/content URIs immediately.
        if (candidatePath.startsWith("content://") || candidatePath.startsWith("file:///")) {
            return DirectoryValidationResult.rejected(candidatePath, "SAF and file URI destinations are not supported")
        }

        // Must be an absolute path.
        if (!candidatePath.startsWith("/")) {
            return DirectoryValidationResult.rejected(candidatePath, "Path must be absolute")
        }

        val file = File(candidatePath)

        // Must exist and be a directory.
        if (!file.exists()) {
            return DirectoryValidationResult.rejected(candidatePath, "Path does not exist")
        }
        if (!file.isDirectory) {
            return DirectoryValidationResult.rejected(candidatePath, "Path is not a directory")
        }

        // Must be writable.
        if (!file.canWrite()) {
            return DirectoryValidationResult.rejected(candidatePath, "Directory is not writable")
        }

        // Resolve canonical path to detect symlinks and normalize.
        val canonicalFile = try {
            file.canonicalFile
        } catch (_: Exception) {
            return DirectoryValidationResult.rejected(candidatePath, "Cannot resolve path")
        }
        val canonicalPath = canonicalFile.absolutePath

        // Must not be app-private storage.
        if (canonicalPath.startsWith(DATA_PREFIX)) {
            return DirectoryValidationResult.rejected(
                candidatePath, "App-private storage is not a valid destination"
            )
        }

        // Must not be system-owned.
        for (prefix in SYSTEM_PREFIXES) {
            if (canonicalPath.startsWith(prefix)) {
                return DirectoryValidationResult.rejected(
                    candidatePath, "System-owned paths are not valid destinations"
                )
            }
        }

        // Must lie beneath an approved storage volume root.
        if (!StorageVolumeService.isUnderVolumeRoot(canonicalPath, context)) {
            return DirectoryValidationResult.rejected(
                candidatePath, "Path is outside reported storage volumes"
            )
        }

        return DirectoryValidationResult.valid(candidatePath, canonicalPath)
    }

    /**
     * Lists immediate child directories of [parentPath] that are validated as destinations.
     * Only returns directories that pass all validation rules.
     */
    fun listValidChildren(context: Context, parentPath: String): List<String> {
        val parent = File(parentPath)
        if (!parent.isDirectory || !parent.canRead()) {
            return emptyList()
        }

        return try {
            parent.listFiles { file ->
                file.isDirectory && file.canRead()
            }?.mapNotNull { child ->
                val result = validate(context, child.absolutePath)
                if (result.isValid) result.canonicalPath else null
            }?.sorted() ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }
}
