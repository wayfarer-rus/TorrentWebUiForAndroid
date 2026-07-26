package com.andreiefimov.torrentwebui

import java.io.File

internal sealed interface RecommendedDestinationResult {
    data class Success(val path: String) : RecommendedDestinationResult
    data class Failure(val reason: String) : RecommendedDestinationResult
}

internal interface DestinationFileSystem {
    fun resolve(root: String, relative: String): String
    fun canonicalPath(path: String): String
    fun exists(path: String): Boolean
    fun isDirectory(path: String): Boolean
    fun createDirectory(path: String): Boolean
    fun parent(path: String): String?
    fun isEmptyDirectory(path: String): Boolean
    fun deleteDirectory(path: String): Boolean
}

internal object RealDestinationFileSystem : DestinationFileSystem {
    override fun resolve(root: String, relative: String): String = File(root, relative).path
    override fun canonicalPath(path: String): String = File(path).canonicalPath
    override fun exists(path: String): Boolean = File(path).exists()
    override fun isDirectory(path: String): Boolean = File(path).isDirectory
    override fun createDirectory(path: String): Boolean = File(path).mkdir()
    override fun parent(path: String): String? = File(path).parent
    override fun isEmptyDirectory(path: String): Boolean =
        File(path).let { it.isDirectory && it.list()?.isEmpty() == true }
    override fun deleteDirectory(path: String): Boolean = File(path).delete()
}

internal interface RecommendedDestinationOperations {
    fun proposal(): RecommendedDestinationResult
    suspend fun confirm(): RecommendedDestinationResult
}

/** Owns derivation and confirmed creation of the canonical Recommended Destination. */
internal class RecommendedDestinationService(
    private val volumes: () -> List<StorageVolume>,
    private val fileSystem: DestinationFileSystem,
    private val validate: (String) -> DirectoryValidationResult,
    private val approve: suspend (String) -> Boolean
) : RecommendedDestinationOperations {
    override fun proposal(): RecommendedDestinationResult {
        val primary = volumes().firstOrNull { it.isPrimary && it.isMounted }
            ?: return RecommendedDestinationResult.Failure("Primary storage is unavailable")
        return try {
            val root = fileSystem.canonicalPath(primary.path)
            val candidate = fileSystem.canonicalPath(
                fileSystem.resolve(root, RECOMMENDED_RELATIVE_PATH)
            )
            if (!isWithinRoot(candidate, root)) {
                RecommendedDestinationResult.Failure("Recommended folder is outside primary storage")
            } else {
                RecommendedDestinationResult.Success(candidate)
            }
        } catch (_: Exception) {
            RecommendedDestinationResult.Failure("Recommended folder could not be resolved")
        }
    }

    override suspend fun confirm(): RecommendedDestinationResult {
        val proposed = proposal()
        if (proposed !is RecommendedDestinationResult.Success) return proposed
        val target = proposed.path
        val created = mutableListOf<String>()

        try {
            val missing = missingDirectories(target)
                ?: return RecommendedDestinationResult.Failure("Recommended folder cannot be created")
            for (directory in missing.asReversed()) {
                if (!fileSystem.createDirectory(directory)) {
                    if (!fileSystem.exists(directory) || !fileSystem.isDirectory(directory)) {
                        rollback(created)
                        return RecommendedDestinationResult.Failure("Recommended folder cannot be created")
                    }
                } else {
                    created += directory
                }
            }

            val canonicalTarget = fileSystem.canonicalPath(target)
            val primaryRoot = primaryCanonicalRoot()
                ?: run {
                    rollback(created)
                    return RecommendedDestinationResult.Failure("Primary storage is unavailable")
                }
            if (!isWithinRoot(canonicalTarget, primaryRoot)) {
                rollback(created)
                return RecommendedDestinationResult.Failure("Recommended folder is outside primary storage")
            }

            val validation = validate(canonicalTarget)
            val validatedPath = validation.canonicalPath
            if (!validation.isValid || validatedPath == null || !isWithinRoot(validatedPath, primaryRoot)) {
                rollback(created)
                return RecommendedDestinationResult.Failure(
                    validation.rejectionReason ?: "Recommended folder is unavailable"
                )
            }
            if (!approve(validatedPath)) {
                rollback(created)
                return RecommendedDestinationResult.Failure("Recommended folder could not be saved")
            }
            return RecommendedDestinationResult.Success(validatedPath)
        } catch (_: Exception) {
            rollback(created)
            return RecommendedDestinationResult.Failure("Recommended folder setup failed")
        }
    }

    /** Missing directories from target upward; null means an existing ancestor is not a directory. */
    private fun missingDirectories(target: String): List<String>? {
        val missing = mutableListOf<String>()
        var cursor: String? = target
        while (cursor != null && !fileSystem.exists(cursor)) {
            missing += cursor
            cursor = fileSystem.parent(cursor)
        }
        return if (cursor == null || !fileSystem.isDirectory(cursor)) null else missing
    }

    /** Removes only directories created by this operation and still provably empty. */
    private fun rollback(created: List<String>) {
        for (directory in created.asReversed()) {
            if (!fileSystem.isEmptyDirectory(directory) || !fileSystem.deleteDirectory(directory)) break
        }
    }

    private fun primaryCanonicalRoot(): String? = try {
        volumes().firstOrNull { it.isPrimary && it.isMounted }
            ?.path
            ?.let(fileSystem::canonicalPath)
    } catch (_: Exception) {
        null
    }

    private fun isWithinRoot(path: String, root: String): Boolean =
        path == root || path.startsWith("$root${File.separator}")

    companion object {
        private const val RECOMMENDED_RELATIVE_PATH = "Download/Torrents"
    }
}
