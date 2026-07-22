package com.andreiefimov.torrentwebui

import java.io.File

/** Resolves a torrent-owned child and rejects traversal or the storage root itself. */
internal fun resolveMoveOwnedChild(rootPath: String, childName: String): File? = try {
    val root = File(rootPath).canonicalFile
    File(root, childName).canonicalFile.takeIf { it.path.startsWith(root.path + File.separator) }
} catch (_: Exception) {
    null
}

/** Validates an already-absolute journal path against its canonical source root. */
internal fun resolveMoveOwnedPath(rootPath: String, candidatePath: String): File? = try {
    val root = File(rootPath).canonicalFile
    File(candidatePath).canonicalFile.takeIf { it.path.startsWith(root.path + File.separator) }
} catch (_: Exception) {
    null
}

/** Deletes only a canonical torrent-owned path. Invalid/out-of-bound paths fail closed. */
internal fun deleteMoveOwnedPath(rootPath: String, candidatePath: String): Boolean {
    val path = resolveMoveOwnedPath(rootPath, candidatePath) ?: return false
    return !path.exists() || path.deleteRecursively()
}

/**
 * Confirms that libtorrent verified at least as much valid target data as the source had.
 * The source and target may contain different valid piece subsets; byte-for-byte equality
 * would incorrectly reject safe partial-data reuse.
 */
internal fun verifiedTargetRetainsSourceProgress(
    preservedSource: File?,
    verifiedTarget: File?,
    sourceProgress: Float,
    targetProgress: Float
): Boolean = preservedSource?.exists() == true &&
    verifiedTarget?.exists() == true &&
    targetProgress.coerceIn(0f, 1f) + 0.0005f >= sourceProgress.coerceIn(0f, 1f)
